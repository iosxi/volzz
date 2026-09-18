package com.volzz;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 設定と、実機で動きを確かめるための状態表示。 */
public class MainActivity extends Activity {

    private static final long POLL_MS = 400L;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView diag;
    private Button openSettings;
    private Switch enabled;
    private Switch onlyWhilePlaying;
    private Switch swap;
    private SeekBar threshold;
    private TextView thresholdLabel;
    private RadioButton modeA;
    private RadioButton modeB;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        applySystemBarInsets();

        status = findViewById(R.id.status);
        diag = findViewById(R.id.diag);
        openSettings = findViewById(R.id.open_settings);
        enabled = findViewById(R.id.enabled);
        onlyWhilePlaying = findViewById(R.id.only_while_playing);
        swap = findViewById(R.id.swap);
        threshold = findViewById(R.id.threshold);
        thresholdLabel = findViewById(R.id.threshold_label);
        modeA = findViewById(R.id.mode_a);
        modeB = findViewById(R.id.mode_b);

        openSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        enabled.setChecked(prefs.enabled());
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setEnabled(checked);
            }
        });

        onlyWhilePlaying.setChecked(prefs.onlyWhilePlaying());
        onlyWhilePlaying.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setOnlyWhilePlaying(checked);
            }
        });

        swap.setChecked(prefs.swap());
        swap.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setSwap(checked);
            }
        });

        threshold.setMax(Prefs.THRESHOLD_MAX - Prefs.THRESHOLD_MIN);
        threshold.setProgress(prefs.thresholdMs() - Prefs.THRESHOLD_MIN);
        showThreshold(prefs.thresholdMs());
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showThreshold(valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.setThresholdMs(valueOf(bar.getProgress()));
            }

            private int valueOf(int progress) {
                int v = Prefs.THRESHOLD_MIN + progress;
                return (v / 50) * 50;         // 50ms 刻みに丸める
            }
        });

        modeA.setChecked(prefs.mode() == Prefs.MODE_IMMEDIATE);
        modeB.setChecked(prefs.mode() == Prefs.MODE_DEFER);
        CompoundButton.OnCheckedChangeListener modeListener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        if (!checked) {
                            return;
                        }
                        boolean isA = button == modeA;
                        prefs.setMode(isA ? Prefs.MODE_IMMEDIATE : Prefs.MODE_DEFER);
                        modeA.setChecked(isA);
                        modeB.setChecked(!isA);
                    }
                };
        modeA.setOnCheckedChangeListener(modeListener);
        modeB.setOnCheckedChangeListener(modeListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    /**
     * ステータスバーとナビゲーションバーの分だけ余白を空ける。
     *
     * targetSdk 35 以降のアプリは端から端まで描画する（edge-to-edge）のが既定に
     * なったため、何もしないと画面の上下がバーの裏に潜って読めなくなる。
     * getSystemWindowInset* は非推奨だが API 28 から 36 まで一本のコードで済み、
     * systemBars と同じ値を返す。
     */
    @SuppressWarnings("deprecation")
    private void applySystemBarInsets() {
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    private void showThreshold(int ms) {
        thresholdLabel.setText(getString(R.string.threshold_value, ms));
    }

    private void refresh() {
        final boolean granted = isServiceEnabledInSettings();
        final boolean connected = Prefs.serviceConnected;

        if (connected) {
            status.setText(R.string.status_running);
        } else if (granted) {
            status.setText(R.string.status_granted_not_connected);
        } else {
            status.setText(R.string.status_off);
        }
        openSettings.setText(granted ? R.string.open_settings_again : R.string.open_settings);

        final StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_count, Prefs.keyEventCount));
        if (!TextUtils.isEmpty(Prefs.lastNote)) {
            sb.append('\n').append(getString(R.string.diag_last, Prefs.lastNote));
        }
        diag.setText(sb.toString());
    }

    /** 設定画面でこのサービスが有効にされているか。権限は要らない。 */
    private boolean isServiceEnabledInSettings() {
        final String flat = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) {
            return false;
        }
        final String me = new ComponentName(this, VolzzService.class).flattenToString();
        final String meShort = new ComponentName(this, VolzzService.class).flattenToShortString();
        for (String entry : flat.split(":")) {
            if (me.equalsIgnoreCase(entry) || meShort.equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            status.setText(R.string.status_no_settings);
        }
    }
}
