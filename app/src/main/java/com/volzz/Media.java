package com.volzz;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
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

    // ------------------------------------------------------------------
    // 手応え
    // ------------------------------------------------------------------

    /** 次の曲。短く 1 回。 */
    private static final long[] NEXT_PATTERN = {0L, 40L};
    /** 前の曲。短く 2 回。ポケットの中でも向きが分かる。 */
    private static final long[] PREVIOUS_PATTERN = {0L, 25L, 70L, 25L};

    /**
     * 曲送りを送った合図として短く振動させる。
     *
     * ここで分かるのは「メディアキーを送れた」ことまでで、プレイヤーが実際に
     * 曲を変えたかどうかは分からない。dispatchMediaKeyEvent() は結果を返さず、
     * プレイヤーの状態を覗くには通知へのアクセス権が要る（volzz は取らない）。
     */
    static void buzz(Context context, boolean next) {
        final Vibrator vibrator = vibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(
                    next ? NEXT_PATTERN : PREVIOUS_PATTERN, -1));
        } catch (Exception e) {
            Prefs.note("振動できませんでした: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        // API 31 未満。VIBRATOR_SERVICE は 31 で非推奨になったが、そこまでは
        // これしかない。
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private Media() {
    }
}
