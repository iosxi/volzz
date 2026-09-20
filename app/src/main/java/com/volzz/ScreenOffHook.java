package com.volzz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * 画面が消えている間の音量キーを受け取る。
 *
 * <p><b>なぜ別の道が必要なのか。</b> 画面が消えているあいだ、音量キーは
 * アクセシビリティサービスには一切届かない。AOSP の PhoneWindowManager は
 * interceptKeyBeforeQueueing() で「画面が消えていて、注入されたキーでもないなら
 * ACTION_PASS_TO_USER は立てない」と決めており、AccessibilityInputFilter は
 * FLAG_PASS_TO_USER が無いイベントをハンドラに回さずそのまま素通しする。
 * つまり onKeyEvent() は呼ばれない。volzz 側の作りをどう直しても届かない。
 *
 * <p>そのかわりシステムは、行き場のなくなった音量キーを
 * MediaSessionLegacyHelper#sendVolumeKeyEvent() でメディアセッションに配る。
 * 宛先は「再生中のセッションのうち、音量を自分で扱えるもの」
 * （MediaSessionStack#getDefaultVolumeSession）。だから画面が消えている間だけ、
 * 音量を自分で扱うと宣言した VolumeProvider 付きのセッションを立てて受け皿にする。
 * 届き方はこうなる。
 *
 * <ul>
 *   <li>押した → onAdjustVolume(+1 / -1)</li>
 *   <li>離した → onAdjustVolume(0)（UP は direction 0 に変換されて届く）</li>
 * </ul>
 *
 * これで押していた時間が測れるので、画面が点いているときと同じ
 * 「離すまで音量を動かさない」を守れる。長押しのときに音量は一切動かない。
 * 長押し・超長押しの割り当ても、画面が点いているときと同じものを使う。
 *
 * <p>画面が点いたら受け皿はすぐ片づける。点いている間は今までどおり
 * {@link VolzzService} がキーを横取りするので、音量パネルも普段の見え方のままになる。
 */
final class ScreenOffHook {

    /** VolumeProvider に持たせる見かけの音量。実際の音量とは関係ない。 */
    private static final int FAKE_MAX = 100;
    private static final int FAKE_CURRENT = 50;

    /** 離した合図を取りこぼした押下を、超長押しの時間からどれだけ待って諦めるか。 */
    private static final long STALE_MS = 1500L;

    /** 受け皿を優先順位の先頭に押し戻す間隔。 */
    private static final long KEEP_TOP_MS = 1500L;

    /** 曲送りの直後、プレイヤーが状態を報告し終えた頃を狙って押し戻す時刻。 */
    private static final long[] AFTER_SKIP_MS = {200L, 600L, 1200L};

    private final Context context;
    private final AudioManager audio;
    private final Prefs prefs;
    /** 細かい音量。画面が点いているときと同じものを使い回す（エフェクトは 1 つだけ）。 */
    private final FineVolume fine;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 画面が消えている間だけ存在する受け皿。 */
    private MediaSession session;

    /** まだ長押しの時間に達していない。離せば短押し。 */
    private static final int STAGE_SHORT = 0;
    /** 長押しの時間に達した。離せば長押しの動作（超長押しがあるので預かっている）。 */
    private static final int STAGE_LONG = 1;
    /** もう実行した。離しても何も起きない。 */
    private static final int STAGE_DONE = 2;

    /** 押されている向き（ADJUST_RAISE / ADJUST_LOWER）。0 は押されていない。 */
    private int heldDirection = 0;
    private long heldSince = 0L;
    /** この押下がどこまで進んだか。 */
    private int stage = STAGE_SHORT;
    /** この押下に割り当てられている動作。押し始めに読み、離すまで変えない。 */
    private int longAction = Action.NONE;
    private int superAction = Action.NONE;

    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            onLongPress();
        }
    };

    private final Runnable superPressTask = new Runnable() {
        @Override
        public void run() {
            onSuperPress();
        }
    };

    /** 単発の押し戻し（曲送りの直後に何度か撃つ）。 */
    private final Runnable pushTask = new Runnable() {
        @Override
        public void run() {
            pushToTop();
        }
    };

    /** 定期の押し戻し。曲が自然に変わったときも先頭を保つ。 */
    private final Runnable keepTopTask = new Runnable() {
        @Override
        public void run() {
            if (session == null) {
                return;
            }
            // 何も鳴っていなければ競争相手も居ないので、押し戻す必要はない。
            if (audio.isMusicActive()) {
                pushToTop();
            }
            handler.postDelayed(this, KEEP_TOP_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                arm();
            } else {
                disarm();
            }
        }
    };

    ScreenOffHook(Context context, AudioManager audio, Prefs prefs, FineVolume fine) {
        this.context = context;
        this.audio = audio;
        this.prefs = prefs;
        this.fine = fine;
    }

    void start() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // SCREEN_ON / SCREEN_OFF はシステム専用（protected）の放送なので、
        // Android 14 以降で要求される RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED は
        // 要らない（BroadcastController は onlyProtectedBroadcasts なら例外を投げない）。
        context.registerReceiver(screenReceiver, filter);

        // サービスの接続が画面消灯より後になることもある。
        final PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) {
            arm();
        }
    }

    void stop() {
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
            // 登録できていなかった場合。何もしない。
        }
        disarm();
    }

    // ------------------------------------------------------------------
    // 受け皿の出し入れ
    // ------------------------------------------------------------------

    private void arm() {
        if (session != null || !prefs.enabled()) {
            return;
        }
        try {
            final MediaSession s = new MediaSession(context, "volzz");
            // setCallback() は必須。MediaSession#postToCallbackDelayed() は
            // mCallback が null だとメッセージを捨てるので、コールバックを
            // 付けずに VolumeProvider だけ渡すと、システムは音量キーをこの
            // セッションに配るのに onAdjustVolume() が一度も呼ばれない。
            // キーだけ握り潰して何も起きない状態になる（v4 の不具合）。
            // mCallback は setCallback() でしか作られない。
            s.setCallback(new MediaSession.Callback() {
                // 中身は空でよい。メディアボタンの宛先は「直前に音を出した uid」で
                // 決まるので、音を鳴らさない volzz にはそもそも回ってこない。
            }, handler);
            s.setPlaybackToRemote(newVolumeProvider());
            s.setActive(true);
            session = s;
            // 「再生中」と申告したセッションが音量キーの宛先になる
            // （getDefaultVolumeSession は再生中のセッションだけを見る）。
            pushToTop();
            handler.postDelayed(keepTopTask, KEEP_TOP_MS);
            Prefs.screenOffArmed = true;
            Prefs.note("画面が消えた → 音量キーの受け皿を立てた（再生中: "
                    + (audio.isMusicActive() ? "はい" : "いいえ") + "）");
        } catch (Exception e) {
            Prefs.screenOffArmed = false;
            Prefs.note("受け皿を立てられませんでした: " + e.getMessage());
        }
    }

    /**
     * 受け皿を優先順位の先頭に押し戻す。
     *
     * 音量キーの宛先は「再生中のセッションのうち、いちばん最近先頭に来たもの」。
     * 曲送りを受けたプレイヤーが `STATE_SKIPPING_TO_NEXT` などを報告すると、
     * それは「常に優先」の状態（`MediaSessionRecord.ALWAYS_PRIORITY_STATES`）
     * なので無条件で先頭に来て、volzz の受け皿は押し下げられる。そのままだと
     * 1 回曲を送ったあと、次の長押しから音量が動いてしまう（v5 の不具合）。
     *
     * `STATE_SKIPPING_TO_NEXT` はこちらから申告しても同じ効果があるので、
     * それで押し戻してから `STATE_PLAYING` に戻す。どちらも「再生中」の扱い
     * なので、押し戻しの途中で受け皿が外れることはない。
     */
    private void pushToTop() {
        if (session == null) {
            return;
        }
        try {
            session.setPlaybackState(playbackState(PlaybackState.STATE_SKIPPING_TO_NEXT));
            session.setPlaybackState(playbackState(PlaybackState.STATE_PLAYING));
            Prefs.keepTopCount++;
        } catch (Exception e) {
            Prefs.note("先頭に押し戻せませんでした: " + e.getMessage());
        }
    }

    /** 曲送りの直後は、プレイヤーが状態を報告し終えた頃を狙って何度か押し戻す。 */
    private void pushToTopAfterSkip() {
        for (long delay : AFTER_SKIP_MS) {
            handler.postDelayed(pushTask, delay);
        }
    }

    private static PlaybackState playbackState(int state) {
        return new PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .build();
    }

    private void disarm() {
        cancelPending();
        handler.removeCallbacks(keepTopTask);
        handler.removeCallbacks(pushTask);
        reset();
        Prefs.screenOffArmed = false;
        if (session == null) {
            return;
        }
        try {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build());
            session.setActive(false);
            session.release();
        } catch (Exception ignored) {
            // 片づけの失敗でサービスを落とす価値はない。
        }
        session = null;
        Prefs.note("画面が点いた → 受け皿を片づけた");
    }

    private VolumeProvider newVolumeProvider() {
        return new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, FAKE_MAX, FAKE_CURRENT) {
            @Override
            public void onAdjustVolume(final int direction) {
                // setCallback() に main の Handler を渡しているので、ここは
                // すでにメインスレッド。
                onVolumeKey(direction);
            }
        };
    }

    // ------------------------------------------------------------------
    // 長押し判定
    // ------------------------------------------------------------------

    private void onVolumeKey(int direction) {
        Prefs.screenOffKeyCount++;

        if (direction == AudioManager.ADJUST_RAISE || direction == AudioManager.ADJUST_LOWER) {
            onPress(direction);
        } else if (direction == 0) {
            onRelease();
        }
        // ミュートなど、それ以外の指示には触らない。
    }

    private void onPress(int direction) {
        final long now = SystemClock.uptimeMillis();

        if (heldDirection == direction && now - heldSince < holdWindowMs()) {
            // 同じキーの自動リピート。押し始めの時刻は上書きしない。
            return;
        }

        if (prefs.onlyWhilePlaying() && !audio.isMusicActive()) {
            // 何も鳴っていないときは何もしない。画面が消えているときの端末本来の
            // 動きも「音量キーを捨てる」（STREAM_MUSIC が鳴っていなければ飛ばす）
            // なので、これで素通しと同じになる。
            cancelPending();
            reset();
            Prefs.note("素通し（再生中ではない・画面消灯中）");
            return;
        }

        cancelPending();
        final boolean up = (direction == AudioManager.ADJUST_RAISE);
        longAction = prefs.action(up, false);
        superAction = prefs.action(up, true);
        heldDirection = direction;
        heldSince = now;
        stage = STAGE_SHORT;
        if (longAction != Action.NONE || superAction != Action.NONE) {
            handler.postDelayed(longPressTask, prefs.thresholdMs());
        }
        // ここでは音量を動かさない。離して「短押しだった」と確定してから動かす。
    }

    private void onRelease() {
        if (heldDirection == 0) {
            return;
        }
        cancelPending();

        final int direction = heldDirection;
        final long heldMs = SystemClock.uptimeMillis() - heldSince;

        if (stage == STAGE_DONE) {
            reset();
            Prefs.note(label(direction) + " 離した（音量は動かさない・画面消灯中）");
            return;
        }

        // 端末が眠ってタイマーが遅れたときの取りこぼしを、ここで拾う。
        // 画面が消えているあいだは CPU が止まることがあり、postDelayed は
        // 時刻どおりに起きるとは限らない。離した時刻から測り直せば落とさない。
        if (superAction != Action.NONE && heldMs >= prefs.superThresholdMs()) {
            // 合図は返せなかった（超長押しに達したことに気づけていない）ので、
            // ここで初めて鳴らす。
            fire(direction, true, stage == STAGE_SHORT);
            reset();
            return;
        }
        if (longAction != Action.NONE
                && (stage == STAGE_LONG || heldMs >= prefs.thresholdMs())) {
            // 長押しに達した合図は返してある（STAGE_LONG のとき）。
            fire(direction, false, stage == STAGE_SHORT);
            reset();
            return;
        }

        reset();

        // 短押しと確定した。預かっていた分をここで初めて反映する。
        // 画面が消えているので音量パネルも操作音も、volzz 自身の表示も出さない。
        //
        // ここは Media.adjustMusic() と同じく必ずメディア音量に向かう経路なので、
        // 画面が点いているときのような「宛先がメディアかどうか」の判定は要らない。
        if (fine != null && fine.canHandle()) {
            int moved = fine.stepByKey(direction);
            Prefs.note(label(direction) + " 短押し → 音量を"
                    + (direction == AudioManager.ADJUST_RAISE ? "上げた" : "下げた")
                    + "（" + fine.level() + "/" + fine.steps() + " 段"
                    + (moved > 1 ? " ×" + moved : "") + "・画面消灯中）");
            return;
        }

        // 動かすのは Media.adjustMusic()。adjustSuggestedStreamVolume() だと
        // 自分の受け皿に戻ってきてしまう（Media.adjustMusic のコメント）。
        Media.adjustMusic(audio, direction);
        Prefs.note(label(direction) + " 短押し → 音量を"
                + (direction == AudioManager.ADJUST_RAISE ? "上げた" : "下げた")
                + "（ハード 1 段・画面消灯中）");
    }

    /** 長押しの時間に達した。 */
    private void onLongPress() {
        if (heldDirection == 0) {
            return;
        }
        if (superAction != Action.NONE) {
            // まだ実行しない。このまま押し続ければ超長押しになるので、ここでは
            // 「長押しに達した」ことだけ指に返し、実行は離すまで預かる
            // （{@link VolzzService} と同じ作り）。
            stage = STAGE_LONG;
            if (prefs.vibrate()) {
                Media.buzz(context, Action.checkpointPattern(longAction));
            }
            handler.postDelayed(superPressTask, superDelayMs());
            return;
        }
        fire(heldDirection, false, true);
    }

    /** 超長押しの時間に達した。ここまで来たら離すのを待たずに実行する。 */
    private void onSuperPress() {
        if (heldDirection == 0 || stage != STAGE_LONG) {
            return;
        }
        fire(heldDirection, true, true);
    }

    /**
     * 割り当てられた動作を実行する。
     *
     * @param buzz 手応えを返すか。長押しを預かった場合は、達した時点で返してあるので false。
     */
    private void fire(int direction, boolean superPress, boolean buzz) {
        final int action = superPress ? superAction : longAction;
        final String pkg = prefs.appPackage(direction == AudioManager.ADJUST_RAISE, superPress);
        stage = STAGE_DONE;
        if (action == Action.NONE) {
            return;
        }
        if (buzz && prefs.vibrate()) {
            Media.buzz(context, Action.pattern(action));
        }
        final String what = Action.run(context, audio, action, pkg);
        Prefs.note(label(direction) + (superPress ? " 超長押し → " : " 長押し → ")
                + what + "（画面消灯中）");
        if (Action.movesPlayer(action)) {
            // 動作を受けたプレイヤーが先頭に来るので、続けて長押しできるように押し戻す。
            pushToTopAfterSkip();
        }
    }

    /** 長押しに達してから超長押しに達するまでの残り時間。 */
    private long superDelayMs() {
        return Math.max(Prefs.SUPER_GAP_MIN, prefs.superThresholdMs() - prefs.thresholdMs());
    }

    /**
     * 同じ押下の自動リピートと見なす時間。
     *
     * 超長押しを待っている間もリピートは届き続けるので、超長押しの時間より
     * 長くとる。短いと、押しっぱなしの途中から「新しい押下」と数え直してしまい、
     * 超長押しに永遠に届かなくなる。
     */
    private long holdWindowMs() {
        return prefs.superThresholdMs() + STALE_MS;
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    private void cancelPending() {
        handler.removeCallbacks(longPressTask);
        handler.removeCallbacks(superPressTask);
    }

    private void reset() {
        heldDirection = 0;
        heldSince = 0L;
        stage = STAGE_SHORT;
        longAction = Action.NONE;
        superAction = Action.NONE;
    }

    private static String label(int direction) {
        return direction == AudioManager.ADJUST_RAISE ? "音量アップ" : "音量ダウン";
    }
}
