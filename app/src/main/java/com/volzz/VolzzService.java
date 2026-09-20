package com.volzz;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 音量キーの長押し・超長押しに、割り当てられた動作を結びつける。
 *
 * アクセシビリティサービスはシステムが束縛して常駐させるので、常駐のための
 * フォアグラウンドサービスも通知も自動起動の権限も要らない。端末を再起動しても
 * システムが自動で繋ぎ直す。
 *
 * ここで扱えるのは画面が点いている間のキーだけ。画面が消えているとキーイベントは
 * アクセシビリティサービスに届かないので、そのあいだは {@link ScreenOffHook} が
 * 別の道（メディアセッション）で受け取る。
 *
 * 短押しで動かす量は {@link FineVolume} が決める。端末のハード段階より細かく刻める
 * 端末では 1 押し 1 細段になり、そうでない端末では今までどおり 1 押し 1 ハード段になる。
 * 長押し・超長押しの動作はどちらでも変わらない。
 *
 * <p><b>長押しの動作を離すまで実行しない場合がある。</b> 超長押しが割り当てられて
 * いるキーでは、長押しの時間に達した時点ではまだ「どちらになるか」が決まっていない。
 * そこで実行してしまうと、そのまま押し続けて超長押しになったときに 2 つの動作が
 * 続けて起きる。だから長押しの時間では合図の振動だけを返し、実行は離すまで預かる。
 * 超長押しが「なし」のキーは待つ理由が無いので、v11 までと同じくその場で実行する。
 */
public class VolzzService extends AccessibilityService {

    /** 長押し成立後、押しっぱなしで曲送りを繰り返す間隔。0 なら繰り返さない。 */
    private static final long REPEAT_MS = 0L;

    /** 曲送りをしない押下（何も鳴っていないとき）で、押しっぱなし中に音量を刻む間隔。 */
    private static final long VOLUME_REPEAT_MS = 110L;

    /** 設定画面から状態を覗くための参照。サービスと画面は同一プロセス。 */
    static volatile VolzzService instance;

    private AudioManager audio;
    private Prefs prefs;
    private ScreenOffHook screenOff;
    private FineVolume fine;
    private LevelHud hud;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** まだ長押しの時間に達していない。離せば短押し。 */
    private static final int STAGE_SHORT = 0;
    /** 長押しの時間に達した。離せば長押しの動作（超長押しがあるので預かっている）。 */
    private static final int STAGE_LONG = 1;
    /** もう実行した。離しても何も起きない。 */
    private static final int STAGE_DONE = 2;

    /** いま押されている音量キー。0 は「押されていない」。 */
    private int heldKey = 0;
    /** この押下を volzz が見張っているか。DOWN で決め、UP まで変えない。 */
    private boolean armed = false;
    /** この押下がどこまで進んだか。 */
    private int stage = STAGE_SHORT;
    /** この押下では動作を起こさず、音量だけを動かすか。DOWN で決め、UP まで変えない。 */
    private boolean volumeOnly = false;
    /** この押下に割り当てられている動作。DOWN で読み、UP まで変えない。 */
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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = new Prefs(this);

        // XML でも宣言しているが、キーイベントを確実に受け取るためコードでも立てる。
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.eventTypes = 0;              // 画面上のイベントは受け取らない
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        setServiceInfo(info);

        // 細かい音量。エフェクトはこのサービスが持つ。サービスが繋がっている間は
        // プロセスが落ちないので、常駐のためのフォアグラウンドサービスは要らない。
        if (fine == null) {
            fine = new FineVolume(this, audio, prefs);
            fine.start();
        }
        if (hud == null) {
            hud = new LevelHud(this);
        }

        if (screenOff == null) {
            screenOff = new ScreenOffHook(this, audio, prefs, fine);
            screenOff.start();
        }

        instance = this;
        Prefs.serviceConnected = true;
        Prefs.note("サービスに接続しました");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        Prefs.serviceConnected = false;
        cancelPending();
        reset();
        stopScreenOffHook();
        stopFine();
        Prefs.note("サービスが切断されました");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        Prefs.serviceConnected = false;
        cancelPending();
        stopScreenOffHook();
        stopFine();
        super.onDestroy();
    }

    private void stopFine() {
        if (hud != null) {
            hud.destroy();
            hud = null;
        }
        if (fine != null) {
            fine.stop();
            fine = null;
        }
    }

    /** 設定画面から覗く。 */
    FineVolume fine() {
        return fine;
    }

    private void stopScreenOffHook() {
        if (screenOff != null) {
            screenOff.stop();
            screenOff = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // eventTypes = 0 なので呼ばれない。
    }

    @Override
    public void onInterrupt() {
        cancelPending();
        reset();
    }

    // ------------------------------------------------------------------
    // キーの横取り
    // ------------------------------------------------------------------

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;                 // 音量キー以外には一切触れない
        }
        Prefs.keyEventCount++;

        if (prefs == null || audio == null || !prefs.enabled()) {
            return false;
        }

        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0) {
                    return handleDown(code);
                }
                // 念のため。DOWN を握り潰しているのでリピートは来ないはずだが、
                // 届いた場合も見張っている間は握り潰す。
                return armed && heldKey == code;

            case KeyEvent.ACTION_UP:
                return handleUp(code);

            default:
                return false;
        }
    }

    private boolean handleDown(int code) {
        cancelPending();

        final boolean up = (code == KeyEvent.KEYCODE_VOLUME_UP);
        longAction = prefs.action(up, false);
        superAction = prefs.action(up, true);

        // 「再生中だけ介入する」が意味するのは長押しの動作のほうだけにする。何も鳴って
        // いないときでも音量は細かく刻みたい（ここを素通しにすると、再生していない間
        // だけハード 1 段ずつに戻ってしまい、100 段にした意味がなくなる）。
        final boolean silent = prefs.onlyWhilePlaying() && !audio.isMusicActive();
        // 長押しにも超長押しにも何も割り当てていないキーは、押し続けても起きることが
        // 無い。それなら端末本来のように、押しっぱなしで音量が動き続けるほうがいい。
        final boolean nothingAssigned =
                (longAction == Action.NONE && superAction == Action.NONE);
        volumeOnly = silent || nothingAssigned;

        if (!mediaIsTarget()) {
            // 通話中や着信中。音量キーはメディア以外に向かうべきなので手を出さない。
            reset();
            Prefs.note("素通し（いま音量キーはメディアに向かわない）");
            return false;
        }
        if (volumeOnly && (fine == null || !fine.canHandle())) {
            // 細かく刻めないなら横取りする値打ちがない。素通しにしておけば
            // 端末本来の音量操作（自動リピートも音量パネルも）がそのまま働く。
            reset();
            Prefs.note("素通し（" + (silent ? "再生中ではない" : "長押しに何も割り当てていない")
                    + "・細かい音量は使えない）");
            return false;
        }

        armed = true;
        heldKey = code;
        stage = STAGE_SHORT;
        handler.postDelayed(longPressTask, prefs.thresholdMs());

        // ここでは音量を動かさない。
        //
        // OS は「この押下がこれから長押しになるか」を事前には教えてくれない。
        // 押した瞬間に動かしてしまうと、長押しだったと分かった時点で戻すしか
        // なく、音量が 1 段上がってから下がる、という見苦しい動きになる。
        // だから離される（＝短押しと確定する）まで待つ。短押しの体感遅れは
        // 判定時間ではなく「実際に押していた時間」なので、軽いタップなら
        // ほとんど分からない。
        //
        // DOWN を握り潰すこと自体も必須。通してしまうと以降の自動リピートは
        // システム側（InputDispatcher）が作るようになり、アクセシビリティの
        // フィルタを通らなくなるため、volzz には止める手段がなくなる。
        return true;
    }

    private boolean handleUp(int code) {
        if (!armed || heldKey != code) {
            reset();
            return false;
        }
        cancelPending();

        if (volumeOnly) {
            if (stage != STAGE_SHORT) {
                // 押しっぱなしの間ずっと音量を刻んでいた。刻むたびに記録すると
                // 直近の記録が埋まってしまうので、離したときに 1 行だけ残す。
                Prefs.note(label(code) + " 押しっぱなし → 音量を動かした"
                        + (fine == null ? "" : "（" + fine.level() + "/" + fine.steps() + " 段）"));
            } else {
                // 短押しと確定した。預かっていた分をここで初めて反映する。
                Prefs.note(label(code) + " 短押し → " + adjustVolume(code));
            }
        } else if (stage == STAGE_LONG) {
            // 長押しの時間は越えたが、超長押しには届かなかった。預かっていた
            // 動作をここで実行する。合図の振動は長押しに達した時点で返してある。
            fire(code, false, false);
        } else if (stage == STAGE_DONE) {
            // 長押しか超長押しで実行済み。音量には触らない。
            Prefs.note(label(code) + " 離した（音量は動かさない）");
        } else {
            // 短押しと確定した。預かっていた分をここで初めて反映する。
            Prefs.note(label(code) + " 短押し → " + adjustVolume(code));
        }
        reset();

        // DOWN を握り潰しているので UP も握り潰す（キーの対を崩さない）。
        return true;
    }

    /** 長押しの時間に達した。 */
    private void onLongPress() {
        if (!armed) {
            return;
        }
        final int code = heldKey;

        if (volumeOnly) {
            // 動作を起こさない押下なので、素通しをやめた代わりに、
            // 端末本来の「押しっぱなしで動き続ける」を自前で出す。
            stage = STAGE_LONG;
            adjustVolume(code);
            handler.postDelayed(longPressTask, VOLUME_REPEAT_MS);
            return;
        }

        if (superAction != Action.NONE) {
            // ここではまだ実行しない（クラス説明の「離すまで実行しない場合がある」）。
            stage = STAGE_LONG;
            if (prefs.vibrate()) {
                Media.buzz(this, Action.checkpointPattern(longAction));
            }
            handler.postDelayed(superPressTask, superDelayMs());
            Prefs.note(label(code) + " 長押しに達した（離せば "
                    + Action.label(this, longAction, appOf(code, false)) + "）");
            return;
        }

        // 超長押しが無いなら待つ理由が無い。その場で実行する。
        fire(code, false, true);

        if (REPEAT_MS > 0) {
            handler.postDelayed(longPressTask, REPEAT_MS);
        }
    }

    /** 超長押しの時間に達した。ここまで来たら離すのを待たずに実行する。 */
    private void onSuperPress() {
        if (!armed || stage != STAGE_LONG) {
            return;
        }
        fire(heldKey, true, true);
    }

    /**
     * 割り当てられた動作を実行する。
     *
     * @param buzz 手応えを返すか。長押しを預かった場合は、達した時点で返してあるので false。
     */
    private void fire(int code, boolean superPress, boolean buzz) {
        stage = STAGE_DONE;
        final int action = superPress ? superAction : longAction;
        if (action == Action.NONE) {
            return;
        }
        if (buzz && prefs.vibrate()) {
            Media.buzz(this, Action.pattern(action));
        }
        final String what = Action.run(this, audio, action, appOf(code, superPress));
        Prefs.note(label(code) + (superPress ? " 超長押し → " : " 長押し → ") + what);
    }

    /** このトリガーで起動するアプリ。 */
    private String appOf(int code, boolean superPress) {
        return prefs.appPackage(code == KeyEvent.KEYCODE_VOLUME_UP, superPress);
    }

    /** 長押しに達してから超長押しに達するまでの残り時間。 */
    private long superDelayMs() {
        return Math.max(Prefs.SUPER_GAP_MIN, prefs.superThresholdMs() - prefs.thresholdMs());
    }

    // ------------------------------------------------------------------
    // 音量
    // ------------------------------------------------------------------

    /**
     * 短押しと確定したときだけ呼ぶ。
     *
     * 細かい音量が使える端末では 1 細段だけ動かし、volzz 自身の表示を出す。
     * システムの音量パネルは出さない。パネルが示すのはハード段で、
     * 細段を動かしても数コマに 1 度しか動かないため、かえって分からなくなる。
     *
     * 使えない端末では今までどおり、端末本来の音量操作と同じ見え方にする。
     *
     * @return 何をしたかの説明（診断に出す）
     */
    private String adjustVolume(int code) {
        final int direction = directionOf(code);

        // 細かい音量は setStreamVolume(STREAM_MUSIC) で必ずメディアに向かうので、
        // 何か鳴っているかどうかを問わない。鳴っていないときだけハード 1 段に戻すと、
        // 「再生していないと 100 段にならない」ことになってしまう。
        if (fine != null && fine.canHandle()) {
            int moved = fine.stepByKey(direction);
            if (hud != null) {
                hud.show(fine.level(), fine.steps(), fine.currentDb());
            }
            return "音量を" + (direction == AudioManager.ADJUST_RAISE ? "上げた" : "下げた")
                    + "（" + fine.level() + "/" + fine.steps() + " 段"
                    + (moved > 1 ? " ×" + moved : "") + "）";
        }

        Media.adjust(audio, direction,
                AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
        return "音量を" + (direction == AudioManager.ADJUST_RAISE ? "上げた" : "下げた")
                + "（ハード 1 段）";
    }

    /**
     * いま音量キーがメディアに向かうべきか。
     *
     * 通話中・着信中はメディア以外（通話音量、着信音量）に向かう。そこを横取りすると
     * 通話の音量が変えられなくなるので、音の鳴り方が普通でない間は手を出さない。
     */
    private boolean mediaIsTarget() {
        return audio.getMode() == AudioManager.MODE_NORMAL;
    }

    private static int directionOf(int code) {
        return (code == KeyEvent.KEYCODE_VOLUME_UP)
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
    }

    // ------------------------------------------------------------------

    private void cancelPending() {
        handler.removeCallbacks(longPressTask);
        handler.removeCallbacks(superPressTask);
    }

    private void reset() {
        armed = false;
        heldKey = 0;
        stage = STAGE_SHORT;
        volumeOnly = false;
        longAction = Action.NONE;
        superAction = Action.NONE;
    }

    private static String label(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP ? "音量アップ" : "音量ダウン";
    }
}
