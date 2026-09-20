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

    /**
     * 画面が消えている間の音量操作。
     *
     * ここで {@link #adjust} は使えない。`adjustSuggestedStreamVolume()` は
     * `MediaSessionLegacyHelper#sendAdjustVolumeBy()` を通る、つまり
     * **いまの音量キーの宛先に配られる**仕組みで、画面が消えている間その宛先は
     * volzz 自身（{@link ScreenOffHook} の受け皿）になっている。呼ぶと自分の
     * `onAdjustVolume()` に戻ってきて、音量は動かないまま押下と数えられ、
     * 離した合図が来ないので長押しが成立してしまう（v7 の不具合）。
     *
     * `adjustStreamVolume()` は AudioService に直接行くので回らない。
     */
    static void adjustMusic(AudioManager audio, int direction) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (SecurityException e) {
            Prefs.note("音量を変更できませんでした: " + e.getMessage());
        }
    }

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

    // ------------------------------------------------------------------
    // 手応え
    // ------------------------------------------------------------------

    /**
     * 合図として短く振動させる。振り方は {@link Action} が動作ごとに決める。
     *
     * ここで分かるのは「メディアキーを送れた」ことまでで、プレイヤーが実際に
     * 曲を変えたかどうかは分からない。dispatchMediaKeyEvent() は結果を返さず、
     * プレイヤーの状態を覗くには通知へのアクセス権が要る（volzz は取らない）。
     */
    static void buzz(Context context, long[] pattern) {
        final Vibrator vibrator = vibrator(context);
        if (vibrator == null || !vibrator.hasVibrator() || pattern == null) {
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
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
