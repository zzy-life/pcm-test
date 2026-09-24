package com.hr.digitalhuman.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Animated flowing-light border for glass panels / AI dialog frames.
 * Must only be used inside a parent with a defined size (not wrap_content).
 */
public class FlowBorderView extends View {

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float phase;
    private boolean running;
    private float radiusDp = 20f;
    private long lastMs;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = lastMs == 0 ? 0.016f : Math.min(0.04f, (now - lastMs) / 1000f);
            lastMs = now;
            phase += dt * 0.55f;
            if (phase > 1f) {
                phase -= 1f;
            }
            invalidate();
            postOnAnimation(this);
        }
    };

    public FlowBorderView(Context context) {
        super(context);
        init();
    }

    public FlowBorderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FlowBorderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setEnabled(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.2f));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(1.4f));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }

    public void setCornerRadiusDp(float radiusDp) {
        this.radiusDp = radiusDp;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(3f);
        rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
        float r = dp(radiusDp);

        // base subtle border
        strokePaint.setShader(null);
        strokePaint.setColor(0x55344568);
        canvas.drawRoundRect(rect, r, r, strokePaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            running = false;
            removeCallbacks(frame);
            invalidate();
        } else {
            running = false;
            removeCallbacks(frame);
        }
    }
}
