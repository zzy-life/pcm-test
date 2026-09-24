package com.hr.digitalhuman.ui.display;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.ui.UiDecor;

/**
 * 思考中：轨道上循环「政策咨询 / 业务导办 / 接待引路」，下面一行「名字正在努力思考中」。
 */
public class ThinkingScene extends LinearLayout {

    public ThinkingScene(Context context, String line) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        int pad = UiDecor.dp(context, 12);
        setPadding(pad, pad, pad, UiDecor.dp(context, 16));
        setBackground(DisplayStyle.cardBackground(context, DisplayStyle.Skin.GLASS));
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiDecor.dp(context, 12);
        setLayoutParams(lp);

        addView(new OrbitStage(context), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiDecor.dp(context, 168)));

        TextView phrase = new TextView(context);
        phrase.setText(line == null || line.trim().isEmpty() ? "正在努力思考中" : line.trim());
        phrase.setTextColor(context.getResources().getColor(R.color.text));
        phrase.setTextSize(18);
        phrase.setGravity(Gravity.CENTER);
        LayoutParams textLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = UiDecor.dp(context, 4);
        addView(phrase, textLp);
    }

    /** 光效在底层，三个文字控件在上层绕圈，避免画笔画不出汉字。 */
    private static final class OrbitStage extends FrameLayout {

        private static final String[] WORDS = {"政策咨询", "业务导办", "接待引路"};

        private final TextView[] labels = new TextView[WORDS.length];
        private ValueAnimator animator;
        private float phase;

        OrbitStage(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            addView(new Glow(context), new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            for (int i = 0; i < WORDS.length; i++) {
                TextView tv = new TextView(context);
                tv.setText(WORDS[i]);
                tv.setTextColor(0xFFF4F7FB);
                tv.setTextSize(15);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                int hp = UiDecor.dp(context, 10);
                int vp = UiDecor.dp(context, 4);
                tv.setPadding(hp, vp, hp, vp);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF0121E2E);
                bg.setCornerRadius(UiDecor.dp(context, 14));
                bg.setStroke(UiDecor.dp(context, 1), 0xFFF0B429);
                tv.setBackground(bg);
                addView(tv, new LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                labels[i] = tv;
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setInterpolator(new LinearInterpolator());
            animator.setDuration(12000);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                place(phase);
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            place(phase);
        }

        private void place(float turn) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int edge = UiDecor.dp(getContext(), 2);
            for (int i = 0; i < labels.length; i++) {
                TextView tv = labels[i];
                int tw = tv.getWidth();
                int th = tv.getHeight();
                if (tw <= 0 || th <= 0) {
                    continue;
                }
                float rx = Math.max(0f, w / 2f - tw / 2f - edge);
                float ry = Math.max(0f, h / 2f - th / 2f - edge);
                rx = Math.min(rx, UiDecor.dp(getContext(), 168));
                ry = Math.min(ry, UiDecor.dp(getContext(), 46));
                double ang = (turn + i / (float) labels.length) * Math.PI * 2;
                float x = w / 2f + (float) Math.cos(ang) * rx - tw / 2f;
                float y = h / 2f + (float) Math.sin(ang) * ry - th / 2f;
                tv.setX(x);
                tv.setY(y);
            }
        }
    }

    private static final class Glow extends View {

        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator animator;
        private float phase;

        Glow(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(UiDecor.dp(context, 1));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(4200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float unit = UiDecor.dp(getContext(), 1);
            float breathe = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2);

            float haloR = 72 * unit * (0.92f + 0.08f * breathe);
            if (haloR < 1f) {
                haloR = 1f;
            }
            halo.setShader(new RadialGradient(cx, cy, haloR,
                    new int[]{0x66F0B429, 0x331B6BDB, 0x00122B4A},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, haloR, halo);
            halo.setShader(null);

            ring.setColor(0xFF5B9BFF);
            ring.setAlpha(70 + (int) (50 * breathe));
            canvas.drawCircle(cx, cy, 46 * unit, ring);
            ring.setColor(0xFFF0B429);
            ring.setAlpha(40 + (int) (40 * (1f - breathe)));
            canvas.drawCircle(cx, cy, 60 * unit, ring);

            float core = (7 + 2 * breathe) * unit;
            dot.setColor(0xFFFFFFFF);
            dot.setAlpha(230);
            canvas.drawCircle(cx, cy, core * 0.45f, dot);
            dot.setColor(0xFFF0B429);
            dot.setAlpha(180);
            canvas.drawCircle(cx, cy, core, dot);
        }
    }
}
