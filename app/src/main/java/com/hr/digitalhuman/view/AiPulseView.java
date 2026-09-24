package com.hr.digitalhuman.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * AI 动态交互光核：聆听 / 思考 / 播报 三种脉冲节奏。
 */
public class AiPulseView extends View {

    public static final int MODE_IDLE = 0;
    public static final int MODE_LISTEN = 1;
    public static final int MODE_THINK = 2;
    public static final int MODE_SPEAK = 3;

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pulse = 0f;
    private int mode = MODE_IDLE;
    private ValueAnimator animator;

    public AiPulseView(Context context) {
        super(context);
        init();
    }

    public AiPulseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AiPulseView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2f));
        startAnim(1800);
    }

    public void setMode(int mode) {
        if (this.mode == mode && animator != null && animator.isRunning()) {
            return;
        }
        this.mode = mode;
        long duration = 1800;
        if (mode == MODE_LISTEN) {
            duration = 1200;
        } else if (mode == MODE_THINK) {
            duration = 900;
        } else if (mode == MODE_SPEAK) {
            duration = 700;
        }
        startAnim(duration);
        invalidate();
    }

    private void startAnim(long duration) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float base = Math.min(cx, cy) * 0.42f;
        float scale = 1f + 0.12f * pulse;
        if (mode == MODE_THINK) {
            scale = 1f + 0.22f * pulse;
        } else if (mode == MODE_SPEAK) {
            scale = 1f + 0.28f * pulse;
        } else if (mode == MODE_LISTEN) {
            scale = 1f + 0.18f * pulse;
        }
        float r = base * scale;

        int c0 = 0xFF5B9BFF;
        int c1 = 0xFF1B6BDB;
        if (mode == MODE_THINK) {
            c0 = 0xFFF0B429;
            c1 = 0xFF1B6BDB;
        } else if (mode == MODE_SPEAK) {
            c0 = 0xFF3D8BFF;
            c1 = 0xFF1554B0;
        }

        corePaint.setShader(new RadialGradient(cx, cy, r,
                new int[]{0xFFFFFFFF, c0, c1, 0x001B6BDB},
                new float[]{0f, 0.25f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, corePaint);
        corePaint.setShader(null);

        ringPaint.setColor(c0);
        ringPaint.setAlpha(90 + (int) (80 * pulse));
        canvas.drawCircle(cx, cy, r * (1.25f + 0.15f * pulse), ringPaint);
        ringPaint.setAlpha(50 + (int) (40 * (1f - pulse)));
        canvas.drawCircle(cx, cy, r * (1.55f + 0.2f * pulse), ringPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
