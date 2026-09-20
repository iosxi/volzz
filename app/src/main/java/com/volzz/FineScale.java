package com.volzz;

/**
 * 細かい段階（15〜150）と、実際に端末へ出す「ハード段 + エフェクトの負ゲイン」の対応。
 *
 * 目標の音量を dB で決め、その目標以上でいちばん近いハード段を選び、
 * 足りない分（必ず 0 以下）をエフェクトの負ゲインで埋める。
 *
 * 目標以上の段を選ぶことに意味がある。こうすると埋める量が
 * その段のすぐ下との間隔以内に必ず収まるので、
 * もしエフェクトが何かの理由で効かなくなっても、音は「ハード 1 段分」しか跳ねない。
 * これは物理キーを 1 回押したときと同じ跳ね幅で、事故にならない。
 */
final class FineScale {

    static final int MIN_STEPS = 15;
    static final int MAX_STEPS = 150;
    static final int DEFAULT_STEPS = 100;

    /** 端末に実際に出す値。level 0 は消音（ハード段 0）。 */
    static final class Target {
        final int hwIndex;
        /** エフェクトに入れる負ゲイン (dB, <= 0)。 */
        final float gainDb;
        /** 最大音量を 0 としたときの、この段の狙いの dB。 */
        final float totalDb;

        Target(int hwIndex, float gainDb, float totalDb) {
            this.hwIndex = hwIndex;
            this.gainDb = gainDb;
            this.totalDb = totalDb;
        }
    }

    static int clampSteps(int steps) {
        if (steps < MIN_STEPS) return MIN_STEPS;
        if (steps > MAX_STEPS) return MAX_STEPS;
        return steps;
    }

    static int clampLevel(int level, int steps) {
        if (level < 0) return 0;
        if (level > steps) return steps;
        return level;
    }

    /** level（0〜steps）を、端末に出す値へ。 */
    static Target targetFor(VolumeCurve c, int steps, int level) {
        steps = clampSteps(steps);
        level = clampLevel(level, steps);
        if (level <= 0) {
            return new Target(0, 0f, Float.NEGATIVE_INFINITY);   // 消音
        }

        float targetDb = targetDbFor(c, steps, level);

        // 目標以上でいちばん小さいハード段
        int h = c.maxIndex;
        for (int i = c.minAudibleIndex; i <= c.maxIndex; i++) {
            if (c.relDb[i] >= targetDb - 1e-4f) {
                h = i;
                break;
            }
        }
        float gain = targetDb - c.relDb[h];
        if (gain > 0f) gain = 0f;            // 念のため。増幅は決してしない。
        return new Target(h, gain, targetDb);
    }

    /** level に対応する狙いの dB。level=1 が端末の下限、level=steps が最大。 */
    static float targetDbFor(VolumeCurve c, int steps, int level) {
        steps = clampSteps(steps);
        level = clampLevel(level, steps);
        if (level <= 0) return Float.NEGATIVE_INFINITY;
        float floorDb = c.floorDb();
        if (steps <= 1) return 0f;
        float t = (float) (level - 1) / (float) (steps - 1);
        return floorDb + (0f - floorDb) * t;
    }

    /**
     * ハード段だけが外から変えられていたとき（他アプリ、システム、voom を切っていた間など）に、
     * その音量にいちばん近い level を求めて話を合わせる。
     */
    static int levelForHwIndex(VolumeCurve c, int steps, int hwIndex) {
        steps = clampSteps(steps);
        if (hwIndex <= 0) return 0;
        if (hwIndex > c.maxIndex) hwIndex = c.maxIndex;
        if (hwIndex < c.minAudibleIndex) hwIndex = c.minAudibleIndex;
        float floorDb = c.floorDb();
        if (floorDb >= 0f) return steps;
        float t = (c.relDb[hwIndex] - floorDb) / (0f - floorDb);
        int level = 1 + Math.round(t * (steps - 1));
        return clampLevel(level, steps);
    }

    /** 1 段あたり平均何 dB になるか。設定画面で細かさを示すのに使う。 */
    static float dbPerStep(VolumeCurve c, int steps) {
        steps = clampSteps(steps);
        if (steps <= 1) return 0f;
        return -c.floorDb() / (float) (steps - 1);
    }
}
