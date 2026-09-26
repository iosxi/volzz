package com.volzz;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

/**
 * 端末のハードウェア段階より細かい音量。
 *
 * メディア音量の段階数は起動時に読まれる読み取り専用プロパティ
 * {@code ro.config.media_vol_steps} で決まり、変える公開 API は無い
 * （Xperia 1 VII は 30 段、AQUOS R8 は端末の申告どおり）。
 * そこで段階を増やすのではなく、段と段の隙間を負ゲインで埋める。
 *
 * <ol>
 *   <li>{@link VolumeCurve} で各ハード段が実際に何 dB なのかを読む</li>
 *   <li>狙いの音量を dB で決める</li>
 *   <li>その狙い以上でいちばん近いハード段を選ぶ</li>
 *   <li>足りない分（必ず 0 以下）を {@link Attenuator} の負ゲインで埋める</li>
 * </ol>
 *
 * 「狙い以上の段を選ぶ」ことに意味がある。埋める量がその段のすぐ下との間隔以内に
 * 必ず収まるので、エフェクトが効かなくなっても音はハード 1 段分しか跳ねない。
 * 物理キーを 1 回押したときと同じ跳ね幅で、事故にならない。
 *
 * 下限（いちばん小さい音）はハードの最下段とは限らない。最下段が浅い端末では
 * {@link FineScale#FLOOR_DB} まで負ゲインで伸ばす。頼んだ負ゲインが本当に
 * 入っているかは {@link #verifyGain} で読み戻して確かめる。
 */
final class FineVolume {

    /** この間隔以内に続けて押されたら「素早く連打している」と見る。 */
    private static final long RAPID_MS = 400L;

    private final Context ctx;
    private final AudioManager am;
    private final Prefs prefs;

    private Attenuator att;
    private VolumeCurve curve;
    private int steps;
    private int level;

    /** volzz が最後に自分で設定したハード段。外から変えられたかの判定に使う。 */
    private int hwSetByUs = -1;
    /** いま実際に入っている負ゲイン。 */
    private float gainNow = 0f;
    /** 最後に読み戻した負ゲイン。読めなければ NaN。 */
    private float gainReadBack = Float.NaN;
    /** 同じ頭打ちを何度も言わないための覚え。 */
    private float complainedFor = Float.NaN;

    /** 連打の加速。volzz では長押しが曲送りなので、連打で稼ぐしかない。 */
    private long lastKeyAt = 0L;
    private int lastKeyDirection = 0;
    private int consecutive = 0;

    FineVolume(Context ctx, AudioManager am, Prefs prefs) {
        this.ctx = ctx.getApplicationContext();
        this.am = am;
        this.prefs = prefs;
    }

    /** エフェクトを掴み、いまの音量から level を引き直す。 */
    void start() {
        steps = prefs.fineSteps();
        att = Attenuator.acquire();
        probeDepth();
        refreshCurve();
        level = FineScale.levelForHwIndex(curve, steps, hwIndex());
        int saved = prefs.fineLevel();
        if (saved >= 0 && hwIndex() == prefs.fineLastHwIndex()) {
            // 前回 volzz が置いた状態のままなら、細かい位置まで復元する
            // （保存したときと段階数が違えば、同じ音量になる段へ移す）
            level = FineScale.rescaleLevel(saved, prefs.fineLevelSteps(), steps);
        }
        apply(level);
    }

    void stop() {
        Attenuator a = att;
        att = null;
        if (a != null) a.release();
        gainNow = 0f;
    }

    boolean hasEffect() {
        return att != null;
    }

    String effectLabel() {
        return att == null ? "なし" : att.label();
    }

    boolean effectBestEffort() {
        return att != null && att.bestEffort();
    }

    int steps() {
        return steps;
    }

    int level() {
        return level;
    }

    VolumeCurve curve() {
        return curve;
    }

    float currentDb() {
        return curve == null ? 0f : FineScale.targetDbFor(curve, steps, level);
    }

    /**
     * 細かい音量で音量キーを担えるか。
     * 担えないときは呼び出し側が volzz 本来の 1 段操作に落とす。
     */
    boolean canHandle() {
        return att != null && prefs.fineEnabled();
    }

    /** 設定画面から段階数が変わったとき。聞こえている音量はできるだけ保つ。 */
    void setSteps(int newSteps) {
        newSteps = FineScale.clampSteps(newSteps);
        if (newSteps == steps) return;
        float keepDb = currentDb();
        steps = newSteps;
        prefs.setFineSteps(steps);
        level = Float.isInfinite(keepDb) ? 0 : levelNearestDb(keepDb);
        apply(level);
    }

    /** 細かい音量をやめるとき。負ゲインを抜いて素通しに戻す。 */
    void neutralize() {
        if (att == null) return;
        att.setDb(0f);
        gainNow = 0f;
    }

    /**
     * 音量キー 1 回分。
     *
     * @param direction {@link AudioManager#ADJUST_RAISE} か {@link AudioManager#ADJUST_LOWER}
     * @return 実際に動かした細段の数（連打の加速でまとめて動くことがある）
     */
    int stepByKey(int direction) {
        int delta = (direction == AudioManager.ADJUST_RAISE) ? 1 : -1;
        int magnitude = accelerate(direction);
        resyncIfChangedOutside();
        apply(FineScale.clampLevel(level + delta * magnitude, steps));
        return magnitude;
    }

    /**
     * 連打したときだけ大股にする。
     *
     * volzz では長押しが曲送りなので、voom 単体のときのような「押しっぱなしで加速」は
     * 使えない。70 段のとき端から端まで 1 段ずつでは苦行なので、
     * 続けて押している間だけ歩幅を広げる。押すのをやめれば 1 段刻みに戻る。
     */
    private int accelerate(int direction) {
        long now = SystemClock.uptimeMillis();
        if (direction == lastKeyDirection && now - lastKeyAt <= RAPID_MS) {
            consecutive++;
        } else {
            consecutive = 0;
        }
        lastKeyAt = now;
        lastKeyDirection = direction;

        // 段階数が少ないときに大股にしても意味がないので、段階数に応じて抑える。
        int cap = Math.max(1, steps / 25);
        int magnitude;
        if (consecutive >= 12) magnitude = 8;
        else if (consecutive >= 8) magnitude = 4;
        else if (consecutive >= 4) magnitude = 2;
        else magnitude = 1;
        return Math.min(magnitude, Math.max(1, cap));
    }

    /**
     * 出力先が変わっていたらカーブを読み直し、
     * ハード段が volzz の知らないところで動いていたら level を引き直す。
     */
    private void resyncIfChangedOutside() {
        int dev = VolumeCurve.currentOutputDeviceType(am);
        if (curve == null || curve.deviceType != dev) {
            refreshCurve();
            level = FineScale.levelForHwIndex(curve, steps, hwIndex());
            gainNow = 0f;
            if (att != null) att.setDb(0f);
            return;
        }
        int hw = hwIndex();
        if (hwSetByUs >= 0 && hw != hwSetByUs) {
            // 誰かが物理キー以外の道で音量を動かした。その音量から続ける。
            level = FineScale.levelForHwIndex(curve, steps, hw);
            gainNow = 0f;
            if (att != null) att.setDb(0f);
        }
    }

    private void refreshCurve() {
        // 負ゲインを掛けられる端末では、ハードの最下段より下まで下限を伸ばす。
        // 掛けられないなら伸ばしようがないので、ハードの最下段を下限にする。
        curve = VolumeCurve.read(am, VolumeCurve.currentOutputDeviceType(am),
                att != null ? FineScale.FLOOR_DB : 0f);
    }

    /**
     * 掴んだ直後に一度だけ、深い負ゲインが本当に通るかを測る。
     *
     * ハードの最下段が浅い端末では、いちばん小さい音を作るのに 10 dB を超える減衰を
     * エフェクトに頼むことになる。端末が内側で頭打ちにしていないかを、使う前に
     * 記録に残しておく（設定画面の「直近の動き」と logcat に出る）。
     *
     * 分かるのは「値が通るか」まで。値が通っても音が変わらない端末はあり得るので
     * （Xperia 1 VII の LoudnessEnhancer がそうだった）、そこは耳で確かめるしかない。
     *
     * 何か鳴っている最中には測らない。一瞬とはいえ音が落ちるため。
     */
    private void probeDepth() {
        if (att == null || am.isMusicActive()) return;
        att.setDb(FineScale.FLOOR_DB);
        float back = att.readBackDb();
        att.setDb(0f);
        gainNow = 0f;
        if (Float.isNaN(back)) {
            Prefs.note("負ゲインの深さ: 読み戻せない端末です");
        } else {
            Prefs.note(String.format("負ゲインの深さ: 頼み %.0f dB → 実際 %.1f dB",
                    FineScale.FLOOR_DB, back));
        }
    }

    /**
     * 頼んだ負ゲインがそのまま入ったか確かめる。
     *
     * ハードの最下段より下を作るときは、エフェクトに 10 dB を超える減衰を頼むことになる。
     * 端末が内側で頭打ちにしていると「下げたのに小さくならない」という症状になるが、
     * 頼んだ値だけを見ていては気づけない。読み戻して食い違いを残しておく。
     */
    private void verifyGain(float wanted) {
        if (att == null) {
            gainReadBack = Float.NaN;
            return;
        }
        gainReadBack = att.readBackDb();
        if (Float.isNaN(gainReadBack)) return;
        if (Math.abs(gainReadBack - wanted) <= 0.5f) {
            complainedFor = Float.NaN;
            return;
        }
        if (wanted == complainedFor) return;      // 同じ値で何度も言わない
        complainedFor = wanted;
        Prefs.note(String.format("負ゲインが頭打ちです: 頼み %.1f dB → 実際 %.1f dB",
                wanted, gainReadBack));
    }

    /**
     * level を端末に反映する。
     *
     * 音が一瞬跳ねないように、必ず「静かになる側」から動かす。
     * 古いゲインと新しいゲインの小さいほうを先に入れ、ハード段を変え、最後に狙いのゲインにする。
     * こうすると途中のどの瞬間も、変更前と変更後のどちらよりも大きくならない。
     */
    private void apply(int newLevel) {
        if (curve == null) refreshCurve();
        level = newLevel;
        FineScale.Target t = FineScale.targetFor(curve, steps, level);

        float safeGain = Math.min(gainNow, t.gainDb);
        if (att != null && safeGain != gainNow) {
            att.setDb(safeGain);
            gainNow = safeGain;
        }
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, t.hwIndex, 0);
            hwSetByUs = t.hwIndex;
        } catch (SecurityException e) {
            // 「サイレント / 通知を邪魔しない」の制限に当たることがある
            Prefs.note("音量を設定できませんでした: " + e.getMessage());
        }
        if (att != null && t.gainDb != gainNow) {
            att.setDb(t.gainDb);
            gainNow = t.gainDb;
        }

        verifyGain(t.gainDb);

        prefs.setFineLevel(level, steps);
        prefs.setFineLastHwIndex(t.hwIndex);
        Prefs.fineLastDetail = String.format("%d/%d 段 hw=%d gain=%.2fdB%s 合計=%.2fdB",
                level, steps, t.hwIndex, t.gainDb,
                Float.isNaN(gainReadBack) ? "" : String.format("(実 %.2f)", gainReadBack),
                t.totalDb);
    }

    private int hwIndex() {
        return am.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private int levelNearestDb(float db) {
        int best = 1;
        float bestDiff = Float.MAX_VALUE;
        for (int l = 1; l <= steps; l++) {
            float diff = Math.abs(FineScale.targetDbFor(curve, steps, l) - db);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = l;
            }
        }
        return best;
    }
}
