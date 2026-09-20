package com.volzz;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** 設定の保存と、動作確認用の診断情報。 */
public final class Prefs {

    /** logcat のタグ。`adb logcat -s volzz` で動きを追える。 */
    public static final String TAG = "volzz";

    private static final String FILE = "volzz";
    private static final String K_ENABLED = "enabled";
    private static final String K_THRESHOLD = "threshold_ms";
    private static final String K_ONLY_PLAYING = "only_while_playing";
    private static final String K_SWAP = "swap";
    private static final String K_VIBRATE = "vibrate";
    private static final String K_FINE = "fine_enabled";
    private static final String K_FINE_STEPS = "fine_steps";
    private static final String K_FINE_LEVEL = "fine_level";
    private static final String K_FINE_LAST_HW = "fine_last_hw";

    public static final int THRESHOLD_MIN = 250;
    public static final int THRESHOLD_MAX = 1000;
    public static final int THRESHOLD_DEFAULT = 450;

    /** 診断用。サービスと画面は同一プロセスなので static で共有できる。 */
    public static volatile String lastNote = "";
    public static volatile long lastNoteAt = 0L;
    public static volatile int keyEventCount = 0;
    public static volatile boolean serviceConnected = false;
    /** 画面が消えている間に受け取った音量キーの回数（メディアセッション経由）。 */
    public static volatile int screenOffKeyCount = 0;
    /** 画面が消えている間の受け皿が立っているか。 */
    public static volatile boolean screenOffArmed = false;
    /** 受け皿を優先順位の先頭に押し戻した回数。 */
    public static volatile int keepTopCount = 0;
    /** 細かい音量で最後に何をしたか。設定画面の診断に出す。 */
    public static volatile String fineLastDetail = "";

    /** 直近の動きを何件残すか。画面が消えている間の分をあとから読むために要る。 */
    private static final int LOG_SIZE = 14;
    private static final String[] recent = new String[LOG_SIZE];
    private static int recentAt = 0;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return sp.getBoolean(K_ENABLED, true);
    }

    public void setEnabled(boolean value) {
        sp.edit().putBoolean(K_ENABLED, value).apply();
    }

    public int thresholdMs() {
        int v = sp.getInt(K_THRESHOLD, THRESHOLD_DEFAULT);
        if (v < THRESHOLD_MIN) return THRESHOLD_MIN;
        if (v > THRESHOLD_MAX) return THRESHOLD_MAX;
        return v;
    }

    public void setThresholdMs(int value) {
        sp.edit().putInt(K_THRESHOLD, value).apply();
    }

    public boolean onlyWhilePlaying() {
        return sp.getBoolean(K_ONLY_PLAYING, true);
    }

    public void setOnlyWhilePlaying(boolean value) {
        sp.edit().putBoolean(K_ONLY_PLAYING, value).apply();
    }

    public boolean swap() {
        return sp.getBoolean(K_SWAP, false);
    }

    public void setSwap(boolean value) {
        sp.edit().putBoolean(K_SWAP, value).apply();
    }

    public boolean vibrate() {
        return sp.getBoolean(K_VIBRATE, true);
    }

    /**
     * 端末全体のバイブレーションが切られているか。
     *
     * 切られていると、アプリが何を頼んでも `VibratorManagerService` が
     * `ignored_for_settings` で捨てる（volzz に限らず、着信もアラームも全部）。
     * 振動が鳴らない原因のほとんどはこれなので、画面に出して気づけるようにする。
     * 読めなければ「オフではない」と見なす。
     */
    public static boolean systemVibrationOff(Context context) {
        try {
            return android.provider.Settings.System.getInt(
                    context.getContentResolver(), "vibrate_on", 1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 細かい音量（ハード段階より細かく刻む）
    // ------------------------------------------------------------------

    /** 細かい音量を使うか。切ると volzz 本来の 1 段ずつの操作になる。 */
    public boolean fineEnabled() {
        return sp.getBoolean(K_FINE, true);
    }

    public void setFineEnabled(boolean value) {
        sp.edit().putBoolean(K_FINE, value).apply();
    }

    public int fineSteps() {
        return FineScale.clampSteps(sp.getInt(K_FINE_STEPS, FineScale.DEFAULT_STEPS));
    }

    public void setFineSteps(int value) {
        sp.edit().putInt(K_FINE_STEPS, FineScale.clampSteps(value)).apply();
    }

    /** 前回どの段にいたか。-1 は記録なし。 */
    public int fineLevel() {
        return sp.getInt(K_FINE_LEVEL, -1);
    }

    public void setFineLevel(int value) {
        sp.edit().putInt(K_FINE_LEVEL, value).apply();
    }

    /** 前回 volzz が置いたハード段。復元してよいかの判定に使う。 */
    public int fineLastHwIndex() {
        return sp.getInt(K_FINE_LAST_HW, -1);
    }

    public void setFineLastHwIndex(int value) {
        sp.edit().putInt(K_FINE_LAST_HW, value).apply();
    }

    public void setVibrate(boolean value) {
        sp.edit().putBoolean(K_VIBRATE, value).apply();
    }

    /**
     * 画面に出す「いま何が起きたか」。ファイルには書かないので押下のたびに呼んでよい。
     *
     * 画面が消えている間の動きは、その場では誰も見られない。あとから読めるように
     * 直近ぶんを残し、logcat にも同じ行を出す（`adb logcat -s volzz`）。
     */
    public static synchronized void note(String message) {
        lastNote = message;
        lastNoteAt = System.currentTimeMillis();
        Log.i(TAG, message);
        recent[recentAt % LOG_SIZE] = String.format("%tT", lastNoteAt) + "  " + message;
        recentAt++;
    }

    /** 直近の動き。新しいものから順に。 */
    public static synchronized String[] recentNotes() {
        final String[] out = new String[Math.min(recentAt, LOG_SIZE)];
        for (int i = 0; i < out.length; i++) {
            out[i] = recent[(recentAt - 1 - i + LOG_SIZE) % LOG_SIZE];
        }
        return out;
    }
}
