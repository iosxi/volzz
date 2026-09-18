package com.volzz;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 音量キーの長押しを曲送り・曲戻しに割り当てる。
 *
 * アクセシビリティサービスはシステムが束縛して常駐させるので、常駐のための
 * フォアグラウンドサービスも通知も自動起動の権限も要らない。端末を再起動しても
 * システムが自動で繋ぎ直す。
 */
public class VolzzService extends AccessibilityService {

    /** 長押し成立後、押しっぱなしで曲送りを繰り返す間隔。0 なら繰り返さない。 */
    private static final long REPEAT_MS = 0L;

    private AudioManager audio;
    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** いま押されている音量キー。0 は「押されていない」。 */
    private int heldKey = 0;
    /** この押下を volzz が見張っているか。DOWN で決め、UP まで変えない。 */
    private boolean armed = false;
    /** この押下で長押しが成立したか。 */
    private boolean longFired = false;
    /** この押下がモード A（押した瞬間に音量を動かす）か。 */
    private boolean immediate = false;
    /** モード A で戻すための、押す直前の音量。-1 は「分からない」。 */
    private int snapshotStream = -1;
    private int snapshotVolume = -1;

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

        Prefs.serviceConnected = true;
        Prefs.note("サービスに接続しました");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Prefs.serviceConnected = false;
        cancelPending();
        reset();
        Prefs.note("サービスが切断されました");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Prefs.serviceConnected = false;
        cancelPending();
        super.onDestroy();
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

        final boolean musicActive = audio.isMusicActive();
        if (prefs.onlyWhilePlaying() && !musicActive) {
            // 何も鳴っていないときは完全な素通し。通常の音量操作に影響を残さない。
            reset();
            Prefs.note("素通し（再生中ではない）");
            return false;
        }

        armed = true;
        heldKey = code;
        longFired = false;
        immediate = (prefs.mode() == Prefs.MODE_IMMEDIATE);
        snapshotStream = -1;
        snapshotVolume = -1;

        if (immediate) {
            // 遅延なしで音量を動かす。長押しになったら戻せるよう控えてから動かす。
            if (musicActive) {
                snapshotStream = AudioManager.STREAM_MUSIC;
                snapshotVolume = safeGetVolume(AudioManager.STREAM_MUSIC);
            }
            adjustVolume(code, true);
        }

        handler.postDelayed(longPressTask, prefs.thresholdMs());

        // DOWN は必ず握り潰す。ここで通してしまうと、以降の自動リピートは
        // システム側（InputDispatcher）が作るようになり、アクセシビリティの
        // フィルタを通らなくなる。そうなると曲送りのあとも音量が上がり続け、
        // volzz には止める手段がなくなる。
        return true;
    }

    private boolean handleUp(int code) {
        if (!armed || heldKey != code) {
            reset();
            return false;
        }
        cancelPending();

        final boolean wasLongPress = longFired;
        final boolean wasImmediate = immediate;
        reset();

        if (wasLongPress) {
            // 曲送りは済んでいる。離すまでに音量を動かしてはいけない。
            Prefs.note(label(code) + " 長押しから離した（音量は動かさない）");
        } else if (wasImmediate) {
            // DOWN の時点で動かしてある。ここでは何もしない。
            Prefs.note(label(code) + " 短押し → 音量を"
                    + (code == KeyEvent.KEYCODE_VOLUME_UP ? "上げた" : "下げた"));
        } else {
            // モード B の短押し。預かったままなので、ここで初めて動かす。
            adjustVolume(code, true);
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

        if (immediate) {
            restoreVolume(code);          // 押した瞬間に動かした 1 段を戻す
        }

        final boolean up = (code == KeyEvent.KEYCODE_VOLUME_UP) != prefs.swap();
        sendMediaKey(up ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        Prefs.note(label(code) + " 長押し → " + (up ? "次の曲へ" : "前の曲へ"));

        if (REPEAT_MS > 0) {
            handler.postDelayed(longPressTask, REPEAT_MS);
        }
    }

    // ------------------------------------------------------------------
    // 音量とメディアキー
    // ------------------------------------------------------------------

    private void adjustVolume(int code, boolean showUi) {
        final int direction = (code == KeyEvent.KEYCODE_VOLUME_UP)
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        final int flags = showUi
                ? (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND)
                : 0;
        try {
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, flags);
        } catch (SecurityException e) {
            // マナーモードや通知制御の絡みで拒否されることがある。
            Prefs.note("音量を変更できませんでした: " + e.getMessage());
        }
    }

    /** 長押しと判定される前に 1 段動いてしまった音量を、元の値に戻す。 */
    private void restoreVolume(int code) {
        try {
            if (snapshotStream >= 0 && snapshotVolume >= 0) {
                if (safeGetVolume(snapshotStream) != snapshotVolume) {
                    audio.setStreamVolume(snapshotStream, snapshotVolume, 0);
                }
            } else {
                final int back = (code == KeyEvent.KEYCODE_VOLUME_UP)
                        ? AudioManager.ADJUST_LOWER
                        : AudioManager.ADJUST_RAISE;
                audio.adjustSuggestedStreamVolume(back, AudioManager.USE_DEFAULT_STREAM_TYPE, 0);
            }
        } catch (SecurityException e) {
            // 戻せなくても曲送りは続ける。
        }
    }

    private int safeGetVolume(int stream) {
        try {
            return audio.getStreamVolume(stream);
        } catch (Exception e) {
            return -1;
        }
    }

    /** いま再生中のプレイヤー（メディアセッションの持ち主）にメディアキーを送る。 */
    private void sendMediaKey(int keyCode) {
        final long now = SystemClock.uptimeMillis();
        final KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        final KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        try {
            audio.dispatchMediaKeyEvent(down);
            audio.dispatchMediaKeyEvent(up);
        } catch (Exception e) {
            Prefs.note("曲送りに失敗しました: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private void cancelPending() {
        handler.removeCallbacks(longPressTask);
    }

    private void reset() {
        armed = false;
        heldKey = 0;
        longFired = false;
        immediate = false;
        snapshotStream = -1;
        snapshotVolume = -1;
    }

    private static String label(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP ? "音量アップ" : "音量ダウン";
    }
}
