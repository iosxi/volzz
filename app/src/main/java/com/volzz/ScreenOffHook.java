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
import android.view.KeyEvent;

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
 * 「離すまで音量を動かさない」を守れる。曲送りのときに音量は一切動かない。
 *
 * <p>画面が点いたら受け皿はすぐ片づける。点いている間は今までどおり
 * {@link VolzzService} がキーを横取りするので、音量パネルも普段の見え方のままになる。
 */
final class ScreenOffHook {

    /** VolumeProvider に持たせる見かけの音量。実際の音量とは関係ない。 */
    private static final int FAKE_MAX = 100;
    private static final int FAKE_CURRENT = 50;

    /** 離した合図を取りこぼした押下を、いつ諦めるか。 */
    private static final long STALE_MS = 1500L;

    private final Context context;
    private final AudioManager audio;
    private final Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 画面が消えている間だけ存在する受け皿。 */
    private MediaSession session;

    /** 押されている向き（ADJUST_RAISE / ADJUST_LOWER）。0 は押されていない。 */
    private int heldDirection = 0;
    private long heldSince = 0L;
    private boolean longFired = false;

    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            onLongPress();
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

    ScreenOffHook(Context context, AudioManager audio, Prefs prefs) {
        this.context = context;
        this.audio = audio;
        this.prefs = prefs;
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
            // 「再生中」と申告したセッションが音量キーの宛先になる
            // （getDefaultVolumeSession は再生中のセッションだけを見る）。
            s.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .build());
            session = s;
            Prefs.screenOffArmed = true;
            Prefs.note("画面が消えた → 音量キーの受け皿を立てた（再生中: "
                    + (audio.isMusicActive() ? "はい" : "いいえ") + "）");
        } catch (Exception e) {
            Prefs.screenOffArmed = false;
            Prefs.note("受け皿を立てられませんでした: " + e.getMessage());
        }
    }

    private void disarm() {
        cancelPending();
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

        if (heldDirection == direction && now - heldSince < prefs.thresholdMs() + STALE_MS) {
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
        heldDirection = direction;
        heldSince = now;
        longFired = false;
        handler.postDelayed(longPressTask, prefs.thresholdMs());
        // ここでは音量を動かさない。離して「短押しだった」と確定してから動かす。
    }

    private void onRelease() {
        if (heldDirection == 0) {
            return;
        }
        cancelPending();

        final int direction = heldDirection;
        final long heldMs = SystemClock.uptimeMillis() - heldSince;
        final boolean wasLongPress = longFired;
        reset();

        if (wasLongPress) {
            Prefs.note(label(direction) + " 長押しから離した（音量は動かさない・画面消灯中）");
            return;
        }

        if (heldMs >= prefs.thresholdMs()) {
            // 端末が眠ってタイマーが遅れたときの取りこぼしを、ここで拾う。
            // 画面が消えているあいだは CPU が止まることがあり、postDelayed は
            // 時刻どおりに起きるとは限らない。離した時刻から測り直せば落とさない。
            skip(direction);
            return;
        }

        // 短押しと確定した。預かっていた分をここで初めて反映する。
        // 画面が消えているので音量パネルも操作音も出さない。
        Media.adjust(audio, direction, 0);
        Prefs.note(label(direction) + " 短押し → 音量を"
                + (direction == AudioManager.ADJUST_RAISE ? "上げた" : "下げた")
                + "（画面消灯中）");
    }

    private void onLongPress() {
        if (heldDirection == 0) {
            return;
        }
        longFired = true;
        skip(heldDirection);
    }

    private void skip(int direction) {
        final boolean next = Media.isNext(direction, prefs.swap());
        Media.sendKey(audio, next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        Prefs.note(label(direction) + " 長押し → " + (next ? "次の曲へ" : "前の曲へ")
                + "（画面消灯中）");
    }

    // ------------------------------------------------------------------

    private void cancelPending() {
        handler.removeCallbacks(longPressTask);
    }

    private void reset() {
        heldDirection = 0;
        heldSince = 0L;
        longFired = false;
    }

    private static String label(int direction) {
        return direction == AudioManager.ADJUST_RAISE ? "音量アップ" : "音量ダウン";
    }
}
