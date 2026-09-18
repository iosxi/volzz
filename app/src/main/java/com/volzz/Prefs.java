package com.volzz;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定の保存と、動作確認用の診断情報。 */
public final class Prefs {

    private static final String FILE = "volzz";
    private static final String K_ENABLED = "enabled";
    private static final String K_THRESHOLD = "threshold_ms";
    private static final String K_ONLY_PLAYING = "only_while_playing";
    private static final String K_SWAP = "swap";

    public static final int THRESHOLD_MIN = 250;
    public static final int THRESHOLD_MAX = 1000;
    public static final int THRESHOLD_DEFAULT = 450;

    /** 診断用。サービスと画面は同一プロセスなので static で共有できる。 */
    public static volatile String lastNote = "";
    public static volatile long lastNoteAt = 0L;
    public static volatile int keyEventCount = 0;
    public static volatile boolean serviceConnected = false;

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

    /** 画面に出す「いま何が起きたか」。ファイルには書かないので押下のたびに呼んでよい。 */
    public static void note(String message) {
        lastNote = message;
        lastNoteAt = System.currentTimeMillis();
    }
}
