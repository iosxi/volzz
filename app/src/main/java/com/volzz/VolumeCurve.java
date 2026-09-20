package com.volzz;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * 端末が実際に持っているメディア音量の dB カーブ。
 *
 * ハードウェアの音量段階は OEM が決めていて、しかも等間隔ではない。
 * 手元の Xperia 1 VII (Android 16) では 30 段だが、下のほうは 1 段で 4.8 dB も飛び、
 * 上のほうは 1 段 1 dB しかない。細かい段階を作るには「各段が実際に何 dB なのか」を
 * 知らなければならない。それを {@link AudioManager#getStreamVolumeDb} (API 28) で読む。
 *
 * dB はすべて最大段を 0 とした相対値で持つ。AudioFlinger が返す絶対値は
 * 利用者にとって意味がなく、必要なのは段と段の距離だけ。
 */
final class VolumeCurve {

    /** 相対 dB。添字はハード段。relDb[maxIndex] は必ず 0。単調増加。 */
    final float[] relDb;
    /** ハード段の最大値（この端末の「粗さ」の正体）。 */
    final int maxIndex;
    /** 音が出る最も小さいハード段。ここが端末の下限。 */
    final int minAudibleIndex;
    /** このカーブを読んだ出力先（スピーカー / BT / 有線 …）。 */
    final int deviceType;
    /** getStreamVolumeDb が使えず、仮のカーブで代用しているか。 */
    final boolean synthetic;
    /** 細かい段階を並べる下限（負の値）。ハードの最下段より下のこともある。 */
    private final float floorDb;

    private VolumeCurve(float[] relDb, int maxIndex, int minAudibleIndex,
                        int deviceType, boolean synthetic, float floorDb) {
        this.relDb = relDb;
        this.maxIndex = maxIndex;
        this.minAudibleIndex = minAudibleIndex;
        this.deviceType = deviceType;
        this.synthetic = synthetic;
        this.floorDb = floorDb;
    }

    /**
     * 細かい段階を並べる下限が、最大段より何 dB 下か（負の値）。
     *
     * ふつうはハードの最下段そのもの。ただし最下段がどれだけ下かは OEM 次第で、
     * 実測では Xperia 1 VII が -66.8 dB、AQUOS R8 は -51 dB しかない。同じ「1 段目」でも
     * AQUOS のほうがはっきり大きい音になってしまう。そこで負ゲインを掛けられる端末では、
     * 最下段よりさらに下まで下限を伸ばす（{@link #read} の deepestFloorDb）。
     */
    float floorDb() {
        return floorDb;
    }

    /** ハードの最下段が最大段より何 dB 下か。負ゲインで伸ばす前の、素の下限。 */
    float hardwareFloorDb() {
        return relDb[minAudibleIndex];
    }

    /** 段 index の直下の段との間隔（dB）。エフェクトが死んだときの跳ね上がり量の上限。 */
    float gapBelow(int index) {
        if (index <= minAudibleIndex) return 0f;
        return relDb[index] - relDb[index - 1];
    }

    /**
     * @param deepestFloorDb 負ゲインで伸ばしてよい下限（0 以下）。ハードの最下段が
     *                       これより上なら、その差はエフェクトの負ゲインが埋める。
     *                       伸ばす手段が無いときは 0 を渡す（＝ハードの最下段が下限）。
     */
    static VolumeCurve read(AudioManager am, int deviceType, float deepestFloorDb) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max < 1) max = 1;
        float[] rel = new float[max + 1];
        boolean synthetic = false;

        float ref;
        try {
            ref = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, max, deviceType);
        } catch (RuntimeException e) {
            ref = 0f;
            synthetic = true;
        }
        if (Float.isNaN(ref) || Float.isInfinite(ref)) synthetic = true;

        if (!synthetic) {
            for (int i = 0; i <= max; i++) {
                float db;
                try {
                    db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, deviceType);
                } catch (RuntimeException e) {
                    synthetic = true;
                    break;
                }
                rel[i] = (Float.isInfinite(db) || Float.isNaN(db))
                        ? Float.NEGATIVE_INFINITY : db - ref;
            }
        }

        if (synthetic) {
            // 読めない端末向けの当て。等間隔 dB として扱う。細かさは落ちるが破綻はしない。
            rel[0] = Float.NEGATIVE_INFINITY;
            for (int i = 1; i <= max; i++) {
                rel[i] = -60f * (float) (max - i) / (float) max;
            }
        }

        // 音が出る最小段。stream の下限と、dB が有限になる最小段の大きいほう。
        int streamMin;
        try {
            streamMin = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException e) {
            streamMin = 0;
        }
        int minAudible = -1;
        for (int i = Math.max(streamMin, 0); i <= max; i++) {
            if (!Float.isInfinite(rel[i]) && !Float.isNaN(rel[i])) {
                minAudible = i;
                break;
            }
        }
        if (minAudible < 0) minAudible = Math.min(1, max);

        // 単調増加を保証する。壊れた値を返す OEM があるので、ここで均しておかないと
        // 「上げたのに小さくなる」という事故になる。
        for (int i = minAudible + 1; i <= max; i++) {
            if (Float.isInfinite(rel[i]) || Float.isNaN(rel[i]) || rel[i] < rel[i - 1]) {
                rel[i] = rel[i - 1];
            }
        }
        // 最大段は定義上 0。読み取り誤差があっても 0 に合わせる。
        rel[max] = 0f;
        if (rel[minAudible] >= 0f) rel[minAudible] = -60f;   // 全段が同値という異常への保険

        // ハードの最下段が浅い端末では、そこからさらに下を負ゲインで作る。
        float floor = rel[minAudible];
        if (deepestFloorDb < floor) floor = deepestFloorDb;

        return new VolumeCurve(rel, max, minAudible, deviceType, synthetic, floor);
    }

    /** いま音が出ている先を推定する。出力先が変わると dB カーブも変わるので要る。 */
    static int currentOutputDeviceType(AudioManager am) {
        AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int best = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        int bestRank = -1;
        for (AudioDeviceInfo d : outs) {
            int rank = rankOf(d.getType());
            if (rank > bestRank) {
                bestRank = rank;
                best = d.getType();
            }
        }
        return best;
    }

    /** 実際に音が向かう先ほど大きい値。挿してあれば内蔵スピーカーより優先される。 */
    private static int rankOf(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return 50;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return 40;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_HDMI:
                return 30;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 10;
            default:
                return 0;
        }
    }

    static String deviceLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有線イヤホン";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "スピーカー";
            default: return "出力先 " + type;
        }
    }
}
