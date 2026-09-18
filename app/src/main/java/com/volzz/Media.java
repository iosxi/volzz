package com.volzz;

import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * 音量操作とメディアキー送出。
 *
 * 画面が点いているとき（VolzzService）と消えているとき（ScreenOffHook）で
 * キーの受け取り方は違うが、受け取ったあとにやることは同じなのでここに置く。
 */
final class Media {

    /** 短押しと確定したときだけ呼ぶ。端末本来の音量操作と同じ見え方にする。 */
    static void adjust(AudioManager audio, int direction, int flags) {
        try {
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE,
                    flags);
        } catch (SecurityException e) {
            // マナーモードや通知制御の絡みで拒否されることがある。
            Prefs.note("音量を変更できませんでした: " + e.getMessage());
        }
    }

    /**
     * いま再生中のプレイヤー（メディアボタンの宛先）にメディアキーを送る。
     *
     * 宛先は「直前に実際に音を出した uid のメディアセッション」で決まる
     * （MediaSessionStack#updateMediaButtonSessionIfNeeded）。volzz は音を
     * 鳴らさないので、画面消灯中に volzz 自身がメディアセッションを持っていても
     * 宛先を横取りしてしまうことはない。
     */
    static void sendKey(AudioManager audio, int keyCode) {
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

    /** 長押しの向きを、設定の入れ替えを踏まえて「次の曲か」に変える。 */
    static boolean isNext(int direction, boolean swap) {
        return (direction == AudioManager.ADJUST_RAISE) != swap;
    }

    private Media() {
    }
}
