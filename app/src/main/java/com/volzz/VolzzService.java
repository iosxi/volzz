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
 */
public class VolzzService extends AccessibilityService {

    /** 長押し成立後、押しっぱなしで曲送りを繰り返す間隔。0 なら繰り返さない。 */
    private static final long REPEAT_MS = 0L;

    private AudioManager audio;
    private Prefs prefs;
    private ScreenOffHook screenOff;
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

        if (screenOff == null) {
            screenOff = new ScreenOffHook(this, audio, prefs);
            screenOff.start();
        }

        Prefs.serviceConnected = true;
        Prefs.note("サービスに接続しました");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Prefs.serviceConnected = false;
        cancelPending();
        reset();
        stopScreenOffHook();
        Prefs.note("サービスが切断されました");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Prefs.serviceConnected = false;
        cancelPending();
        stopScreenOffHook();
        super.onDestroy();
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
            adjustVolume(code);
            Prefs.note(label(code) + " 短押し → 音量を"
                    + (code == KeyEvent.KEYCODE_VOLUME_UP ? "上げた" : "下げた"));
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
        Prefs.note(label(code) + " 長押し → " + (next ? "次の曲へ" : "前の曲へ"));

        if (REPEAT_MS > 0) {
            handler.postDelayed(longPressTask, REPEAT_MS);
        }
    }

    // ------------------------------------------------------------------
    // 音量
    // ------------------------------------------------------------------

    /** 短押しと確定したときだけ呼ぶ。端末本来の音量操作と同じ見え方にする。 */
    private void adjustVolume(int code) {
        Media.adjust(audio, directionOf(code),
                AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
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
