package com.volzz;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.view.KeyEvent;

/**
 * 長押し・超長押しに割り当てられる動作。
 *
 * <p>保存するのは下の整数そのもの。並び順を変えても、項目を足しても、
 * すでに保存された割り当てが別の動作に化けることはない。
 *
 * <p>手応え（振動）は動作ごとに変える。volzz は画面を見ないで使う道具なので、
 * 「いま何が起きたか」「このまま離すと何が起きるか」は指で分かる必要がある。
 */
final class Action {

    static final int NONE = 0;
    static final int NEXT = 1;
    static final int PREVIOUS = 2;
    static final int PLAY_PAUSE = 3;
    static final int APP = 4;

    /** 設定画面に並べる順。 */
    static final int[] ALL = {NEXT, PREVIOUS, PLAY_PAUSE, APP, NONE};

    /** 次の曲。短く 1 回。 */
    private static final long[] P_NEXT = {0L, 40L};
    /** 前の曲。短く 2 回。ポケットの中でも向きが分かる。 */
    private static final long[] P_PREVIOUS = {0L, 25L, 70L, 25L};
    /** 再生／一時停止。長く 1 回。曲送りの「短く」と取り違えない。 */
    private static final long[] P_PLAY_PAUSE = {0L, 120L};
    /** アプリ起動。短く 3 回。 */
    private static final long[] P_APP = {0L, 25L, 70L, 25L, 70L, 25L};
    /** 何も割り当てていない長押しが、超長押しの手前に達した合図。ごく軽く 1 回。 */
    private static final long[] P_TICK = {0L, 15L};

    static String label(Context context, int action, String pkg) {
        switch (action) {
            case NEXT:
                return context.getString(R.string.action_next);
            case PREVIOUS:
                return context.getString(R.string.action_previous);
            case PLAY_PAUSE:
                return context.getString(R.string.action_play_pause);
            case APP:
                return context.getString(R.string.action_app_named, appLabel(context, pkg));
            default:
                return context.getString(R.string.action_none);
        }
    }

    /** 起きたこと（起きること）の手応え。 */
    static long[] pattern(int action) {
        switch (action) {
            case NEXT:
                return P_NEXT;
            case PREVIOUS:
                return P_PREVIOUS;
            case PLAY_PAUSE:
                return P_PLAY_PAUSE;
            case APP:
                return P_APP;
            default:
                return P_TICK;
        }
    }

    /**
     * 「長押しに達した」合図の手応え。
     *
     * 超長押しが割り当てられている間、長押しの動作は離すまで実行しない。
     * だからここで鳴らすのは予告で、離したときに起きる動作そのものの手応えを返す。
     * 何も割り当てていないときは、押し続ければ超長押しに届くことだけを軽く伝える。
     */
    static long[] checkpointPattern(int action) {
        return action == NONE ? P_TICK : pattern(action);
    }

    /** メディアの状態を動かす動作か。画面消灯中の受け皿を押し戻すかの判断に使う。 */
    static boolean movesPlayer(int action) {
        return action == NEXT || action == PREVIOUS || action == PLAY_PAUSE;
    }

    /**
     * 割り当てられた動作を実行する。
     *
     * @return 何をしたかの説明（診断に出す）
     */
    static String run(Context context, AudioManager audio, int action, String pkg) {
        switch (action) {
            case NEXT:
                Media.sendKey(audio, KeyEvent.KEYCODE_MEDIA_NEXT);
                return context.getString(R.string.action_next);
            case PREVIOUS:
                Media.sendKey(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                return context.getString(R.string.action_previous);
            case PLAY_PAUSE:
                Media.sendKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return context.getString(R.string.action_play_pause);
            case APP:
                return launch(context, pkg);
            default:
                return context.getString(R.string.action_none);
        }
    }

    // ------------------------------------------------------------------
    // アプリ起動
    // ------------------------------------------------------------------

    /**
     * 登録したアプリを起動する。
     *
     * <p>`FLAG_ACTIVITY_NEW_TASK` は必須（サービスには起動元のタスクが無い）。
     * すでに起動しているアプリを前に出すときに新しい画面を積まないよう、
     * ランチャーのアイコンを押したときと同じ `RESET_TASK_IF_NEEDED` も付ける。
     */
    static String launch(Context context, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return context.getString(R.string.action_app_unset);
        }
        final Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            return context.getString(R.string.action_app_missing, pkg);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            return context.getString(R.string.action_app_named, appLabel(context, pkg));
        } catch (Exception e) {
            // 背面からの画面起動が拒まれた場合もここに来る。理由を残す。
            return context.getString(R.string.action_app_failed, String.valueOf(e.getMessage()));
        }
    }

    /** アプリの表示名。消えていたり見えなかったりするときはパッケージ名で代える。 */
    static String appLabel(Context context, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return context.getString(R.string.action_app_unset);
        }
        try {
            final PackageManager pm = context.getPackageManager();
            final ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            final CharSequence label = pm.getApplicationLabel(info);
            return label == null || label.length() == 0 ? pkg : label.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private Action() {
    }
}
