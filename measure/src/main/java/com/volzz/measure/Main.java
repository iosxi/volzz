package com.volzz.measure;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * volzz の「細かい音量」が本当に音に効いているかを、端末ごとに測る道具。
 * スピーカーから 1 kHz を流し、本体マイクで 1 kHz 成分の大きさを測る。
 */
public class Main extends Activity {
    static final String T = "volzz-measure";
    static final int SR = 48000;
    static final double F = 1000.0;

    AudioManager am;
    TextView out;
    Button start, copy;
    final StringBuilder log = new StringBuilder();
    volatile boolean playing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = getSystemService(AudioManager.class);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 96, 32, 32);
        TextView info = new TextView(this);
        info.setText("volzz 音量測定\n\n"
                + "1. 先に volzz のユーザー補助をオフにしてください\n"
                + "2. イヤホン・Bluetooth を外し、本体スピーカーで鳴る状態に\n"
                + "3. 静かな場所で、端末を机に置いて「測定開始」\n"
                + "   （約 2 分、ピー音が鳴ります。最後は大きめの音が出ます）\n"
                + "4. 終わったら「結果をコピー」して送ってください\n");
        start = new Button(this);
        start.setText("測定開始");
        copy = new Button(this);
        copy.setText("結果をコピー");
        copy.setEnabled(false);
        out = new TextView(this);
        out.setTextIsSelectable(true);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextSize(11);
        root.addView(info);
        root.addView(start);
        root.addView(copy);
        root.addView(out);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        start.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                return;
            }
            go();
        });
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("volzz 測定", log.toString()));
            copy.setText("コピーしました");
        });
        if (getIntent().getBooleanExtra("auto", false)) go();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) go();
        else say("マイクの許可が無いと測れません");
    }

    void go() {
        start.setEnabled(false);
        log.setLength(0);
        new Thread(() -> {
            try {
                run();
            } catch (Throwable t) {
                say("失敗: " + Log.getStackTraceString(t));
            }
            say("DONE");
            runOnUiThread(() -> copy.setEnabled(true));
        }).start();
    }

    void say(String s) {
        Log.i(T, s);
        synchronized (log) { log.append(s).append('\n'); }
        runOnUiThread(() -> out.setText(log.toString()));
    }

    void run() throws Exception {
        say(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        int saved = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int dev = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        float[] rel = new float[max + 1];
        float ref = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, max, dev);
        for (int i = 1; i <= max; i++)
            rel[i] = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, dev) - ref;
        say("ハード段 " + max + " 段 / 最下段 " + String.format("%.1f", rel[1]) + " dB");

        String dpErr = null;
        DynamicsProcessing dp = null;
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    false, 0, false, 0, false, 0, false).build();
            dp = new DynamicsProcessing(0, 0, cfg);
            dp.setEnabled(true);
            dp.setInputGainAllChannelsTo(0f);
        } catch (Throwable t) {
            dpErr = t.toString();
            dp = null;
        }
        say("DynamicsProcessing: " + (dp != null ? "掴めた" : "掴めない " + dpErr));

        AudioRecord rec = openRec();
        playing = true;
        Thread player = startTone(0.3f);
        Thread.sleep(800);

        // 1) 負ゲインが効くか。音が中くらいになるハード段で測る
        int mid = 1;
        for (int i = 1; i <= max; i++) if (rel[i] <= -20f) mid = i;
        if (dp != null) {
            say("\n[1] 負ゲインだけを変える（ハード段 " + mid + " に固定）");
            measure(rec, mid, 0f, dp);
            double base = measure(rec, mid, 0f, dp);
            for (float g : new float[]{-3, -6, -10, -20}) {
                double m = measure(rec, mid, g, dp);
                say(String.format("  頼み %5.1f dB → 実測 %6.2f dB  (読み戻し %.1f)", g, m - base,
                        dp.getInputGainByChannelIndex(0)));
            }
        }
        say("\n[1b] LoudnessEnhancer の負ゲイン（ハード段 " + mid + "）");
        if (dp != null) { dp.setInputGainAllChannelsTo(0f); dp.setEnabled(false); dp.release(); dp = null; }
        try {
            LoudnessEnhancer le = new LoudnessEnhancer(0);
            le.setTargetGain(0);
            le.setEnabled(true);
            measure(rec, mid, 0f, null);
            double base = measure(rec, mid, 0f, null);
            le.setTargetGain(-1000);
            double m = measure(rec, mid, 0f, null);
            say(String.format("  頼み -10.0 dB → 実測 %6.2f dB", m - base));
            le.setTargetGain(0);
            le.setEnabled(false);
            le.release();
        } catch (Throwable t) {
            say("  掴めない " + t);
        }

        // 2) ハード段の端末申告と実測
        say("\n[2] ハード段 申告dB / 実測dB（実測は最大段比、差が大きいほど申告と食い違う）");
        double[] meas = new double[max + 1];
        for (int h = 1; h <= max; h++) meas[h] = measure(rec, h, 0f, null);
        playing = false;
        player.join();
        // 上のほうはマイクが飽和するので、小さいトーンで測り直してつなぐ
        playing = true;
        player = startTone(0.02f);
        Thread.sleep(500);
        measure(rec, 1, 0f, null);
        double[] meas2 = new double[max + 1];
        double prev = 0;
        for (int h = 1; h <= max; h++) meas2[h] = measure(rec, h, 0f, null);
        playing = false;
        player.join();
        // 上半分は小さいトーン、下半分は大きいトーンを mid でつなぐ
        for (int h = 1; h <= max; h++) {
            double v = (h >= mid) ? meas2[h] - meas2[max]
                    : meas[h] - meas[mid] + meas2[mid] - meas2[max];
            double step = h > 1 ? v - prev : 0;
            say(String.format("  %2d: 申告 %6.1f  実測 %6.1f  (前の段から %+5.1f)", h, rel[h], v, step));
            prev = v;
        }
        rec.stop();
        rec.release();
        am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
    }

    double measure(AudioRecord rec, int hw, float gain, DynamicsProcessing dp) throws Exception {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, hw, 0);
        if (dp != null) dp.setInputGainAllChannelsTo(gain);
        Thread.sleep(500);
        drain(rec);
        double[] v = new double[3];
        for (int k = 0; k < 3; k++) v[k] = goertzelDb(rec, SR / 5);
        java.util.Arrays.sort(v);
        return v[1];
    }

    void drain(AudioRecord rec) {
        short[] buf = new short[SR / 10];
        for (int i = 0; i < 3; i++) rec.read(buf, 0, buf.length);
    }

    static double goertzelDb(AudioRecord rec, int n) {
        short[] buf = new short[n];
        int got = 0;
        while (got < n) got += rec.read(buf, got, n - got);
        double w = 2 * Math.PI * F / SR, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            double s = buf[i] / 32768.0 * hann + c * s1 - s2;
            s2 = s1; s1 = s;
        }
        double p = s1 * s1 + s2 * s2 - c * s1 * s2;
        return 10 * Math.log10(p + 1e-20);
    }

    AudioRecord openRec() {
        int src = MediaRecorder.AudioSource.UNPROCESSED;
        if (!"true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)))
            src = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int bs = Math.max(AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), SR);
        AudioRecord r = new AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bs * 2);
        r.startRecording();
        return r;
    }

    Thread startTone(float amp) {
        AudioTrack tr = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(SR * 2)
                .build();
        tr.play();
        Thread t = new Thread(() -> {
            short[] buf = new short[4800 * 2];
            long n = 0;
            while (playing) {
                for (int i = 0; i < 4800; i++, n++) {
                    short v = (short) (amp * 32767 * Math.sin(2 * Math.PI * F * n / SR));
                    buf[2 * i] = v; buf[2 * i + 1] = v;
                }
                tr.write(buf, 0, buf.length);
            }
            tr.stop();
            tr.release();
        });
        t.start();
        return t;
    }
}
