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
 * 音量キーの長押しを曲送り・曲戻しに割り当てる。
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
 * 長押しの曲送りはどちらでも変わらない。
 */
public class VolzzService extends AccessibilityService {

    /** 長押し成立後、押しっぱなしで曲送りを繰り返す間隔。0 なら繰り返さない。 */
    private static final long REPEAT_MS = 0L;

    /** 設定画面から状態を覗くための参照。サービスと画面は同一プロセス。 */
    static volatile VolzzService instance;

    private AudioManager audio;
    private Prefs prefs;
    private ScreenOffHook screenOff;
    private FineVolume fine;
    private LevelHud hud;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** いま押されている音量キー。0 は「押されていない」。 */
    private int heldKey = 0;
    /** この押下を volzz が見張っているか。DOWN で決め、UP まで変えない。 */
    private boolean armed = false;
    /** この押下で長押しが成立したか。 */
    private boolean longFired = false;

    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            onLongPress();
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

        if (prefs.onlyWhilePlaying() && !audio.isMusicActive()) {
            // 何も鳴っていないときは完全な素通し。通常の音量操作に影響を残さない。
            reset();
            Prefs.note("素通し（再生中ではない）");
            return false;
        }

        armed = true;
        heldKey = code;
        longFired = false;
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

        final boolean wasLongPress = longFired;
        reset();

        if (wasLongPress) {
            // 曲送りは済んでいる。音量には触らない。
            Prefs.note(label(code) + " 長押しから離した（音量は動かさない）");
        } else {
            // 短押しと確定した。預かっていた分をここで初めて反映する。
            Prefs.note(label(code) + " 短押し → " + adjustVolume(code));
        }

        // DOWN を握り潰しているので UP も握り潰す（キーの対を崩さない）。
        return true;
    }

    private void onLongPress() {
        if (!armed) {
            return;
        }
        longFired = true;
        final int code = heldKey;

        final boolean next = Media.isNext(directionOf(code), prefs.swap());
        Media.sendKey(audio, next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        if (prefs.vibrate()) {
            Media.buzz(this, next);
        }
        Prefs.note(label(code) + " 長押し → " + (next ? "次の曲へ" : "前の曲へ"));

        if (REPEAT_MS > 0) {
            handler.postDelayed(longPressTask, REPEAT_MS);
        }
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

        // adjustSuggestedStreamVolume が狙うのはメディアだけとは限らない。
        // 何も鳴っていないときは着信音量に向かうので、そこは触らずに従来どおりにする。
        final boolean mediaIsTarget = audio.isMusicActive();

        if (mediaIsTarget && fine != null && fine.canHandle()) {
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

    private static int directionOf(int code) {
        return (code == KeyEvent.KEYCODE_VOLUME_UP)
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
    }

    // ------------------------------------------------------------------

    private void cancelPending() {
        handler.removeCallbacks(longPressTask);
    }

    private void reset() {
        armed = false;
        heldKey = 0;
        longFired = false;
    }

    private static String label(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP ? "音量アップ" : "音量ダウン";
    }
}
