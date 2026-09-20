package com.volzz;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private Switch vibrate;
    private SeekBar threshold;
    private TextView thresholdLabel;
    private SeekBar superThreshold;
    private TextView superThresholdLabel;
    private TextView superThresholdDetail;
    private TextView fineStatus;
    private Switch fineEnabled;
    private SeekBar fineSteps;
    private TextView fineStepsLabel;
    private TextView fineStepsDetail;

    private int pendingFineSteps;

    /** 割り当ての 4 つのトリガー。並びは画面のボタンと揃えてある。 */
    private static final int[] ASSIGN_ID = {
            R.id.assign_up_long, R.id.assign_down_long,
            R.id.assign_up_super, R.id.assign_down_super};
    private static final int[] ASSIGN_NAME = {
            R.string.assign_up_long, R.string.assign_down_long,
            R.string.assign_up_super, R.string.assign_down_super};
    private static final boolean[] ASSIGN_UP = {true, false, true, false};
    private static final boolean[] ASSIGN_SUPER = {false, false, true, true};

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
        vibrate = findViewById(R.id.vibrate);
        threshold = findViewById(R.id.threshold);
        thresholdLabel = findViewById(R.id.threshold_label);
        superThreshold = findViewById(R.id.super_threshold);
        superThresholdLabel = findViewById(R.id.super_threshold_label);
        superThresholdDetail = findViewById(R.id.super_threshold_detail);
        ((TextView) findViewById(R.id.super_threshold_desc)).setText(
                getString(R.string.super_threshold_desc, Prefs.SUPER_GAP_MIN));
        fineStatus = findViewById(R.id.fine_status);
        fineEnabled = findViewById(R.id.fine_enabled);
        fineSteps = findViewById(R.id.fine_steps);
        fineStepsLabel = findViewById(R.id.fine_steps_label);
        fineStepsDetail = findViewById(R.id.fine_steps_detail);

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

        vibrate.setChecked(prefs.vibrate());
        vibrate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setVibrate(checked);
                if (checked) {
                    // どんな手応えか確かめられる
                    Media.buzz(MainActivity.this, Action.pattern(Action.NEXT));
                }
            }
        });

        for (int i = 0; i < ASSIGN_ID.length; i++) {
            setUpAssign(i);
        }

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
                // 超長押しは長押しから必ず離れるので、そちらの表示も見直す。
                showSuperThreshold();
            }

            private int valueOf(int progress) {
                int v = Prefs.THRESHOLD_MIN + progress;
                return (v / 50) * 50;         // 50ms 刻みに丸める
            }
        });

        superThreshold.setMax(Prefs.SUPER_MAX - Prefs.SUPER_MIN);
        superThreshold.setProgress(prefs.superThresholdMs() - Prefs.SUPER_MIN);
        showSuperThreshold();
        superThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showSuperThreshold(valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.setSuperThresholdMs(valueOf(bar.getProgress()));
                showSuperThreshold();
            }

            private int valueOf(int progress) {
                int v = Prefs.SUPER_MIN + progress;
                return (v / 50) * 50;         // 50ms 刻みに丸める
            }
        });

        pendingFineSteps = prefs.fineSteps();
        fineEnabled.setChecked(prefs.fineEnabled());
        fineEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setFineEnabled(checked);
                final FineVolume fv = fineVolume();
                if (fv != null && !checked) {
                    // 切ったら負ゲインを抜いて素通しに戻す。
                    // 抜くぶん、音がハード 1 段分まで大きくなることがある。
                    fv.neutralize();
                }
                showFineSteps();
            }
        });

        fineSteps.setMax(FineScale.MAX_STEPS - FineScale.MIN_STEPS);
        fineSteps.setProgress(pendingFineSteps - FineScale.MIN_STEPS);
        fineSteps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                pendingFineSteps = FineScale.clampSteps(progress + FineScale.MIN_STEPS);
                showFineSteps();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.setFineSteps(pendingFineSteps);
                final FineVolume fv = fineVolume();
                if (fv != null) {
                    fv.setSteps(pendingFineSteps);
                }
                showFineSteps();
            }
        });
        showFineSteps();
    }

    // ------------------------------------------------------------------
    // 長押し・超長押しに割り当てる動作
    // ------------------------------------------------------------------

    private void setUpAssign(final int index) {
        final Button button = findViewById(ASSIGN_ID[index]);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAction(index);
            }
        });
        showAssign(index);
    }

    private void showAssign(int index) {
        final boolean up = ASSIGN_UP[index];
        final boolean sup = ASSIGN_SUPER[index];
        final Button button = findViewById(ASSIGN_ID[index]);
        button.setText(getString(R.string.assign_row, getString(ASSIGN_NAME[index]),
                Action.label(this, prefs.action(up, sup), prefs.appPackage(up, sup))));
    }

    private void pickAction(final int index) {
        final CharSequence[] items = new CharSequence[Action.ALL.length];
        for (int i = 0; i < Action.ALL.length; i++) {
            // アプリだけは「どれを起動するか」がこの後に来るので、名前ではなく誘いを出す。
            items[i] = (Action.ALL[i] == Action.APP)
                    ? getString(R.string.action_app)
                    : Action.label(this, Action.ALL[i], "");
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.assign_pick_action, getString(ASSIGN_NAME[index])))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final int action = Action.ALL[which];
                        if (action == Action.APP) {
                            pickApp(index);
                            return;
                        }
                        prefs.setAction(ASSIGN_UP[index], ASSIGN_SUPER[index], action);
                        showAssign(index);
                    }
                })
                .show();
    }

    /**
     * 起動するアプリを選ぶ。
     *
     * 一覧作りは端末に入っているアプリの数だけ時間がかかる（表示名の読み出しが
     * それぞれディスクに触る）ので、別のスレッドで作ってから画面に出す。
     */
    private void pickApp(final int index) {
        Toast.makeText(this, R.string.assign_loading, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String[]> apps = launchableApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        showAppDialog(index, apps);
                    }
                });
            }
        }).start();
    }

    private void showAppDialog(final int index, final List<String[]> apps) {
        final CharSequence[] items = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            items[i] = apps.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.assign_pick_app)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setAppPackage(ASSIGN_UP[index], ASSIGN_SUPER[index],
                                apps.get(which)[1]);
                        prefs.setAction(ASSIGN_UP[index], ASSIGN_SUPER[index], Action.APP);
                        showAssign(index);
                    }
                })
                .show();
    }

    /**
     * ランチャーから起動できるアプリ。{表示名, パッケージ名} の組を名前順に返す。
     *
     * マニフェストの &lt;queries&gt; で宣言した範囲しか見えない。すべてのアプリを
     * 見る権限は取っていないので、ホーム画面に出ないアプリはここにも出ない。
     */
    private List<String[]> launchableApps() {
        final List<String[]> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found;
        try {
            found = getPackageManager().queryIntentActivities(main, 0);
        } catch (RuntimeException e) {
            return out;
        }
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) {
                continue;
            }
            final String pkg = info.activityInfo.packageName;
            // 入口がいくつもあるアプリ（複数のランチャーアイコン）は 1 つにまとめる。
            if (pkg == null || !seen.add(pkg)) {
                continue;
            }
            final CharSequence label = info.loadLabel(getPackageManager());
            out.add(new String[]{label == null ? pkg : label.toString(), pkg});
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return collator.compare(a[0], b[0]);
            }
        });
        return out;
    }

    private FineVolume fineVolume() {
        final VolzzService service = VolzzService.instance;
        return service == null ? null : service.fine();
    }

    /** 段階数の表示。サービスが経がっていなくても端末のカーブは読めるので出す。 */
    private void showFineSteps() {
        fineStepsLabel.setText(getString(R.string.fine_steps_value, pendingFineSteps));

        final VolumeCurve curve = currentCurve();
        final float mine = FineScale.dbPerStep(curve, pendingFineSteps);
        final float theirs = FineScale.dbPerStep(curve, curve.maxIndex + 1);
        fineStepsDetail.setText(getString(R.string.fine_steps_detail,
                mine, curve.maxIndex, theirs));
    }

    private VolumeCurve currentCurve() {
        final FineVolume fv = fineVolume();
        if (fv != null && fv.curve() != null) {
            return fv.curve();
        }
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // サービスがまだ繋がっていないときの代用。負ゲインが使える前提の下限で出す。
        return VolumeCurve.read(am, VolumeCurve.currentOutputDeviceType(am),
                FineScale.FLOOR_DB);
    }

    /**
     * volzz 以外に、音量キーを受け取るアクセシビリティサービスが有効になっていないか。
     *
     * Android はキーフィルタを要求している「すべての」サービスにキーを配る。
     * 2 つの音量アプリが同時に有効だと、1 回の押しに両方が反応して二重に動く。
     * AQUOS R8 での試験で実際にこれを踏んだ（上げ方向だけ 2 段進む症状になる）。
     */
    private List<String> otherKeyFilteringServices() {
        final List<String> names = new ArrayList<>();
        final AccessibilityManager manager =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return names;
        }
        List<AccessibilityServiceInfo> list;
        try {
            list = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        } catch (RuntimeException e) {
            return names;
        }
        if (list == null) {
            return names;
        }
        for (AccessibilityServiceInfo info : list) {
            final String id = info.getId();
            if (id == null || id.startsWith(getPackageName() + "/")) {
                continue;
            }
            if ((info.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) {
                continue;
            }
            final CharSequence label = (info.getResolveInfo() == null ? null
                    : info.getResolveInfo().loadLabel(getPackageManager()));
            names.add(label == null || label.length() == 0
                    ? id.substring(0, Math.max(0, id.indexOf('/')))
                    : label.toString());
        }
        return names;
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

    private void showSuperThreshold() {
        showSuperThreshold(prefs.superThresholdMs());
    }

    /**
     * 超長押しの時間。長押しに近すぎるときは押し下げた後の値を出す。
     *
     * スライダーの目盛りより大きい値が出ることになるが、嘘を書くよりいい。
     * 実際に効くのはこちらの値なので。
     */
    private void showSuperThreshold(int ms) {
        final int longMs = prefs.thresholdMs();
        final int effective = Math.max(ms, longMs + Prefs.SUPER_GAP_MIN);
        superThresholdLabel.setText(getString(R.string.super_threshold_value, effective));
        superThresholdDetail.setText(getString(R.string.super_threshold_detail,
                longMs, effective - longMs));
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

        refreshFineStatus();
        for (int i = 0; i < ASSIGN_ID.length; i++) {
            showAssign(i);
        }

        final StringBuilder sb = new StringBuilder();
        if (prefs.vibrate() && Prefs.systemVibrationOff(this)) {
            // 端末側で切られていると volzz の振動も鳴らない。原因がここだと分かるように。
            sb.append(getString(R.string.vibrate_off_warning)).append('\n');
        }
        sb.append(getString(R.string.diag_count, Prefs.keyEventCount));
        sb.append('\n').append(getString(R.string.diag_count_dark, Prefs.screenOffKeyCount));
        sb.append('\n').append(getString(R.string.diag_count_keep_top, Prefs.keepTopCount));

        if (!TextUtils.isEmpty(Prefs.fineLastDetail)) {
            sb.append('\n').append("細かい音量: ").append(Prefs.fineLastDetail);
        }

        final String[] notes = Prefs.recentNotes();
        if (notes.length > 0) {
            sb.append('\n').append(getString(R.string.diag_log_title));
            for (String note : notes) {
                sb.append('\n').append(note);
            }
        } else if (!TextUtils.isEmpty(Prefs.lastNote)) {
            sb.append('\n').append(getString(R.string.diag_last, Prefs.lastNote));
        }
        diag.setText(sb.toString());
    }

    private void refreshFineStatus() {
        final FineVolume fv = fineVolume();
        final VolumeCurve curve = currentCurve();
        final StringBuilder sb = new StringBuilder();

        if (fv == null) {
            sb.append(getString(R.string.fine_st_waiting));
        } else if (!fv.hasEffect()) {
            sb.append(getString(R.string.fine_st_effect_none));
        } else if (fv.effectBestEffort()) {
            sb.append(getString(R.string.fine_st_effect_weak, fv.effectLabel()));
        } else {
            sb.append(getString(R.string.fine_st_effect, fv.effectLabel()));
        }

        sb.append('\n').append(getString(R.string.fine_st_device,
                VolumeCurve.deviceLabel(curve.deviceType), curve.maxIndex, curve.floorDb()));

        if (fv != null && fv.hasEffect()) {
            sb.append('\n').append(getString(R.string.fine_st_level,
                    fv.level(), fv.steps(), fv.currentDb()));
        }

        final List<String> rivals = otherKeyFilteringServices();
        if (!rivals.isEmpty()) {
            sb.append('\n').append(getString(R.string.fine_st_conflict,
                    TextUtils.join("、", rivals)));
        }

        fineStatus.setText(sb.toString());
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
