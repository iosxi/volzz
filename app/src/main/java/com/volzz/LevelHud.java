package com.volzz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * キーを押したときに出る小さなレベル表示。
 *
 * voom がキーを食ってしまうので、システムの音量パネルは出ない。
 * 代わりにこれを出さないと、いま何段目なのかが分からなくなる。
 *
 * 窓の種別は TYPE_ACCESSIBILITY_OVERLAY。アクセシビリティサービスから出す限り
 * 「他のアプリの上に表示」の権限は要らない。
 */
final class LevelHud {

    private static final long VISIBLE_MS = 1400L;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private TextView label;
    private Bar bar;
    private boolean added;

    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            hideNow();
        }
    };

    LevelHud(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(int level, int steps, float db) {
        if (root == null) build();
        String right = Float.isInfinite(db) ? "消音" : String.format("%.1f dB", db);
        label.setText(level + " / " + steps + "     " + right);
        bar.setRatio(steps <= 0 ? 0f : (float) level / (float) steps);
        if (!added) {
            try {
                wm.addView(root, params());
                added = true;
            } catch (RuntimeException e) {
                added = false;
                return;
            }
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, VISIBLE_MS);
    }

    void destroy() {
        handler.removeCallbacks(hide);
        hideNow();
        root = null;
    }

    private void hideNow() {
        if (added && root != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // すでに外れていることがある
            }
        }
        added = false;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    private void build() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101014);
        bg.setCornerRadius(dp(18));
        root.setBackground(bg);

        label = new TextView(ctx);
        label.setTextColor(0xFFF2F2F5);
        label.setTextSize(15f);
        label.setGravity(Gravity.CENTER);
        root.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new Bar(ctx);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(220), dp(6));
        bp.topMargin = dp(10);
        root.addView(bar, bp);
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(96);
        return p;
    }

    /** 細い横棒。段数が多いので数字だけだと位置が掴みにくい。 */
    private final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float ratio;

        Bar(Context c) {
            super(c);
        }

        void setRatio(float r) {
            ratio = Math.max(0f, Math.min(1f, r));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float r = h / 2f;
            paint.setColor(0x33FFFFFF);
            rect.set(0f, 0f, getWidth(), h);
            canvas.drawRoundRect(rect, r, r, paint);
            if (ratio > 0f) {
                paint.setColor(Color.parseColor("#FF35D48A"));
                rect.set(0f, 0f, Math.max(h, getWidth() * ratio), h);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }
}
