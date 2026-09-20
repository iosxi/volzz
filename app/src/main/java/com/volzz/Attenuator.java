package com.volzz;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;

/**
 * ハード段の隙間を埋める「負ゲイン」の掛け手。全体のミックス（セッション 0）に掛ける。
 *
 * ひとつの約束を守ること: セッション 0 に載せるエフェクトは、同時にひとつだけ。
 * 実機で確かめたところ、LoudnessEnhancer をセッション 0 に載せたまま
 * DynamicsProcessing を作ろうとすると、構築が
 * UnsupportedOperationException: invalid parameter operation で必ず失敗する。
 * 効く手段を試せていないのに「この端末では無理」と誤解する元になる。
 */
abstract class Attenuator {

    /** @return 掴めたら true */
    abstract boolean init();

    /** @param db 0 以下。0 は素通し。 */
    abstract void setDb(float db);

    abstract void release();

    /** 設定画面に出す名前。 */
    abstract String label();

    /**
     * いま実際に入っているゲインを読み戻す。
     *
     * 頼んだ値がそのまま通っているかを確かめるために要る。大きな負ゲインを
     * 内側で頭打ちにする端末があり得るので、頼んだ値だけを見ていては分からない。
     * 読めなければ NaN。
     */
    abstract float readBackDb();

    /** 効き方が OEM 依存で当てにならない手段か。 */
    boolean bestEffort() {
        return false;
    }

    /**
     * 使える手段を順に試す。DynamicsProcessing が本命で、
     * 入力ゲイン段が周波数に依らない素直な dB スケーラなので音色が変わらない。
     * @return 掴めなかったら null
     */
    static Attenuator acquire() {
        Attenuator dp = new Dynamics();
        if (dp.init()) {
            Prefs.note("減衰の手段: " + dp.label());
            return dp;
        }
        Attenuator le = new Loudness();
        if (le.init()) {
            Prefs.note("減衰の手段: " + le.label() + "（控え・効き方は端末依存）");
            return le;
        }
        Prefs.note("この端末では全体のミックスに負ゲインを掛けられません");
        return null;
    }

    /** 本命。API 28 から。入力ゲイン段を負にして使う。 */
    private static final class Dynamics extends Attenuator {
        private DynamicsProcessing dp;

        @Override
        boolean init() {
            try {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        /* channelCount */ 2,
                        /* preEqInUse   */ false, /* preEqBandCount  */ 0,
                        /* mbcInUse     */ false, /* mbcBandCount    */ 0,
                        /* postEqInUse  */ false, /* postEqBandCount */ 0,
                        /* limiterInUse */ false).build();
                dp = new DynamicsProcessing(0, 0, cfg);
                dp.setEnabled(true);
                dp.setInputGainAllChannelsTo(0f);
                return true;
            } catch (RuntimeException e) {
                Prefs.note("DynamicsProcessing を掴めませんでした: " + e.getMessage());
                release();
                return false;
            }
        }

        @Override
        void setDb(float db) {
            DynamicsProcessing d = dp;
            if (d == null) return;
            try {
                d.setInputGainAllChannelsTo(db);
            } catch (RuntimeException e) {
                Prefs.note("負ゲインを設定できませんでした: " + e.getMessage());
            }
        }

        @Override
        void release() {
            DynamicsProcessing d = dp;
            dp = null;
            if (d == null) return;
            try {
                d.setInputGainAllChannelsTo(0f);
                d.setEnabled(false);
            } catch (RuntimeException ignored) {
                // 解放の途中で文句を言われても、release まで進めたい
            }
            d.release();
        }

        @Override
        float readBackDb() {
            DynamicsProcessing d = dp;
            if (d == null) return Float.NaN;
            try {
                return d.getInputGainByChannelIndex(0);
            } catch (RuntimeException e) {
                return Float.NaN;
            }
        }

        @Override
        String label() {
            return "DynamicsProcessing";
        }
    }

    /**
     * 控え。DynamicsProcessing を持たない端末向け。
     * 負ゲインを受け付けるかは OEM 次第で、Xperia 1 VII では
     * 値は通るのに音は変わらなかった。掴めても効くとは限らない。
     */
    private static final class Loudness extends Attenuator {
        private LoudnessEnhancer le;

        @Override
        boolean init() {
            try {
                le = new LoudnessEnhancer(0);
                le.setTargetGain(0);
                le.setEnabled(true);
                return true;
            } catch (RuntimeException e) {
                Prefs.note("LoudnessEnhancer を掴めませんでした: " + e.getMessage());
                release();
                return false;
            }
        }

        @Override
        void setDb(float db) {
            LoudnessEnhancer l = le;
            if (l == null) return;
            try {
                l.setTargetGain(Math.round(db * 100f));   // mB
            } catch (RuntimeException e) {
                Prefs.note("負ゲインを設定できませんでした: " + e.getMessage());
            }
        }

        @Override
        void release() {
            LoudnessEnhancer l = le;
            le = null;
            if (l == null) return;
            try {
                l.setTargetGain(0);
                l.setEnabled(false);
            } catch (RuntimeException ignored) {
                // 同上
            }
            l.release();
        }

        @Override
        float readBackDb() {
            LoudnessEnhancer l = le;
            if (l == null) return Float.NaN;
            try {
                return l.getTargetGain() / 100f;
            } catch (RuntimeException e) {
                return Float.NaN;
            }
        }

        @Override
        String label() {
            return "LoudnessEnhancer";
        }

        @Override
        boolean bestEffort() {
            return true;
        }
    }
}
