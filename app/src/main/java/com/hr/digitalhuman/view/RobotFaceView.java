package com.hr.digitalhuman.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Glowing robot face: irregular oval eyes, large cyan iris, drifting highlight, blink.
 */
public class RobotFaceView extends View {

    private static final int COLOR_IRIS = Color.parseColor("#4C8DFF");
    private static final int COLOR_GLOW = Color.parseColor("#994C8DFF");

    /** 0 = open, 1 = closed. */
    private float blinkProgress = 0f;

    /** Highlight drift from rest (0,0); returns home after each glance. */
    private float highlightOx;
    private float highlightOy;
    private float highlightTargetOx;
    private float highlightTargetOy;

    /** Mouth open amount, smoothly chasing speakTarget. */
    private float speakAmount = 0.2f;
    private float speakTarget = 0.2f;

    private final Paint glowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint irisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coreStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path leftClosedEye = new Path();
    private final Path rightClosedEye = new Path();
    private final Path speakMouth = new Path();
    private final Path eyeOutline = new Path();
    private final Path eyeHole = new Path();
    private final Path eyeRing = new Path();
    private final Path lashPath = new Path();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueAnimator blinkAnimator;
    private ValueAnimator idleAnimator;
    private boolean autoBlinkEnabled = true;
    private boolean speakingEnabled = true;
    private boolean playfulEyeEnabled = true;
    private boolean highlightGoingHome = false;
    private int playfulEyeIntervalMinMs = 3000;
    private int playfulEyeIntervalMaxMs = 8000;

    private final Runnable scheduleNextBlink = new Runnable() {
        @Override
        public void run() {
            if (!autoBlinkEnabled || !isAttachedToWindow()) {
                return;
            }
            playBlink();
            handler.postDelayed(this, 2200L + (long) (Math.random() * 2000L));
        }
    };

    private final Runnable scheduleHighlightGlance = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow() || !playfulEyeEnabled) {
                return;
            }
            // Pick a small offset, then later return home
            highlightGoingHome = false;
            highlightTargetOx = (float) ((Math.random() * 2.0 - 1.0) * 0.55);
            highlightTargetOy = (float) ((Math.random() * 2.0 - 1.0) * 0.4);
            handler.postDelayed(() -> {
                highlightGoingHome = true;
                highlightTargetOx = 0f;
                highlightTargetOy = 0f;
            }, 900L + (long) (Math.random() * 600L));
            // Rest at home a while, then glance again (playful eyes)
            int min = Math.max(1000, playfulEyeIntervalMinMs);
            int max = Math.max(min + 500, playfulEyeIntervalMaxMs);
            handler.postDelayed(this, min + (long) (Math.random() * (max - min)));
        }
    };

    private boolean speakOpenNext = true;

    private final Runnable scheduleSpeakTarget = new Runnable() {
        @Override
        public void run() {
            if (!speakingEnabled || !isAttachedToWindow()) {
                return;
            }
            // Alternate silkworm 鈫?teddy nose; hold a bit so transition can finish
            if (speakOpenNext) {
                speakTarget = 1f;
            } else {
                speakTarget = 0f;
            }
            speakOpenNext = !speakOpenNext;
            handler.postDelayed(this, 420L + (long) (Math.random() * 280L));
        }
    };

    public RobotFaceView(Context context) {
        super(context);
        init();
    }

    public RobotFaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RobotFaceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setOnClickListener(v -> playBlink());

        glowFill.setStyle(Paint.Style.FILL);
        glowFill.setColor(Color.WHITE);
        glowFill.setShadowLayer(36f, 0f, 0f, COLOR_GLOW);

        glowStroke.setStyle(Paint.Style.STROKE);
        glowStroke.setStrokeCap(Paint.Cap.ROUND);
        glowStroke.setStrokeJoin(Paint.Join.ROUND);
        glowStroke.setColor(Color.WHITE);
        glowStroke.setShadowLayer(28f, 0f, 0f, COLOR_GLOW);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);
        fillPaint.setShadowLayer(18f, 0f, 0f, COLOR_GLOW);

        irisPaint.setStyle(Paint.Style.FILL);
        irisPaint.setColor(COLOR_IRIS);

        coreStroke.setStyle(Paint.Style.STROKE);
        coreStroke.setStrokeCap(Paint.Cap.ROUND);
        coreStroke.setStrokeJoin(Paint.Join.ROUND);
        coreStroke.setColor(Color.WHITE);

        haloPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startIdleLoop();
        if (playfulEyeEnabled) {
            handler.postDelayed(scheduleHighlightGlance, 1200L);
        }
        if (speakingEnabled) {
            handler.post(scheduleSpeakTarget);
        }
        if (autoBlinkEnabled) {
            handler.postDelayed(scheduleNextBlink, 1000L);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
            blinkAnimator = null;
        }
        if (idleAnimator != null) {
            idleAnimator.cancel();
            idleAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    public void setAutoBlinkEnabled(boolean enabled) {
        autoBlinkEnabled = enabled;
        handler.removeCallbacks(scheduleNextBlink);
        if (enabled && isAttachedToWindow()) {
            handler.postDelayed(scheduleNextBlink, 2000L);
        }
    }

    /** Enable/disable talking mouth animation. */
    public void setSpeakingEnabled(boolean enabled) {
        speakingEnabled = enabled;
        handler.removeCallbacks(scheduleSpeakTarget);
        if (enabled) {
            if (isAttachedToWindow()) {
                handler.post(scheduleSpeakTarget);
            }
        } else {
            speakTarget = 0f;
            speakAmount = 0f;
            invalidate();
        }
    }

    /** Standby playful eye glances (mouth stays still when speaking disabled). */
    public void setPlayfulEyeEnabled(boolean enabled) {
        playfulEyeEnabled = enabled;
        handler.removeCallbacks(scheduleHighlightGlance);
        if (enabled && isAttachedToWindow()) {
            handler.postDelayed(scheduleHighlightGlance, 1200L);
        }
    }

    public void setPlayfulEyeIntervalSec(int minSec, int maxSec) {
        playfulEyeIntervalMinMs = Math.max(1, minSec) * 1000;
        playfulEyeIntervalMaxMs = Math.max(minSec + 1, maxSec) * 1000;
    }

    public void setExpression(String expression) {
        if (expression == null) {
            return;
        }
        String e = expression.trim().toLowerCase();
        switch (e) {
            case "smile":
            case "happy":
            case "greeting":
            case "welcome":
                setSpeakingEnabled(false);
                speakTarget = 0.35f;
                speakAmount = 0.35f;
                playBlink();
                break;
            case "nod":
                playBlink();
                break;
            case "dance":
                setSpeakingEnabled(true);
                playfulEyeEnabled = true;
                playBlink();
                break;
            default:
                break;
        }
        invalidate();
    }

    /** Apply standby mode: mouth still + playful eyes + blink. */
    public void applyStandbyMode(boolean playfulEye, int intervalMinSec, int intervalMaxSec) {
        setSpeakingEnabled(false);
        setAutoBlinkEnabled(true);
        setPlayfulEyeIntervalSec(intervalMinSec, intervalMaxSec);
        setPlayfulEyeEnabled(playfulEye);
    }

    /** Apply welcome/speaking mode. */
    public void applySpeakingMode() {
        setPlayfulEyeEnabled(true);
        setAutoBlinkEnabled(true);
        setSpeakingEnabled(true);
    }

    /** Smooth chase of highlight + mouth every frame. */
    private void startIdleLoop() {
        if (idleAnimator != null && idleAnimator.isRunning()) {
            return;
        }
        idleAnimator = ValueAnimator.ofFloat(0f, 1f);
        idleAnimator.setDuration(1000L);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.setInterpolator(new LinearInterpolator());
        idleAnimator.addUpdateListener(a -> {
            // Soft follow: highlight returns home when target is 0
            float hSmooth = highlightGoingHome ? 0.06f : 0.045f;
            highlightOx += (highlightTargetOx - highlightOx) * hSmooth;
            highlightOy += (highlightTargetOy - highlightOy) * hSmooth;
            if (Math.abs(highlightOx) < 0.002f) {
                highlightOx = 0f;
            }
            if (Math.abs(highlightOy) < 0.002f) {
                highlightOy = 0f;
            }

            // Mouth: smooth chase 鈥?one shape only, no layered crossfade
            float mouthSmooth = 0.08f;
            speakAmount += (speakTarget - speakAmount) * mouthSmooth;

            if (blinkProgress < 0.85f) {
                invalidate();
            }
        });
        idleAnimator.start();
    }

    public void playBlink() {
        if (blinkAnimator != null && blinkAnimator.isRunning()) {
            return;
        }

        ValueAnimator close = ValueAnimator.ofFloat(0f, 1f);
        close.setDuration(100);
        close.setInterpolator(new AccelerateInterpolator());
        close.addUpdateListener(a -> {
            blinkProgress = (float) a.getAnimatedValue();
            invalidate();
        });

        ValueAnimator hold = ValueAnimator.ofFloat(1f, 1f);
        hold.setDuration(140);

        ValueAnimator open = ValueAnimator.ofFloat(1f, 0f);
        open.setDuration(180);
        open.setInterpolator(new DecelerateInterpolator());
        open.addUpdateListener(a -> {
            blinkProgress = (float) a.getAnimatedValue();
            invalidate();
        });

        close.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                blinkAnimator = hold;
                hold.start();
            }
        });
        hold.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                blinkAnimator = open;
                open.start();
            }
        });
        open.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                blinkProgress = 0f;
                blinkAnimator = null;
                invalidate();
            }
        });

        blinkAnimator = close;
        close.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.45f;
        float eyeRx = Math.min(w, h) * 0.145f;
        float eyeRy = eyeRx * 0.92f;
        float gap = eyeRx * 1.35f;
        float leftCx = cx - eyeRx - gap * 0.5f;
        float rightCx = cx + eyeRx + gap * 0.5f;

        float openA = 1f - blinkProgress;
        float closedA = blinkProgress;

        if (openA > 0.02f) {
            float scaleY = Math.max(0.06f, 1f - blinkProgress * 0.94f);

            canvas.save();
            canvas.scale(1f, scaleY, leftCx, cy);
            drawOpenEye(canvas, leftCx, cy, eyeRx, eyeRy, true, openA);
            canvas.restore();

            canvas.save();
            canvas.scale(1f, scaleY, rightCx, cy);
            drawOpenEye(canvas, rightCx, cy, eyeRx, eyeRy, false, openA);
            canvas.restore();
        }

        if (closedA > 0.02f) {
            buildClosedEyes(leftCx, rightCx, cy, eyeRx);
            drawClosedEyesOnly(canvas, eyeRx, closedA);
        }

        // Mouth is independent of blink 鈥?no sudden swap when eyes close
        drawTalkingMouth(canvas, cx, cy + eyeRy * 1.55f, eyeRx, 1f);
    }

    private void drawOpenEye(Canvas canvas, float cx, float cy, float rx, float ry,
                             boolean left, float alpha) {
        float outRx = rx * 1.05f;
        float outRy = ry * 1.02f;
        // Inner edge of white rim 鈥?blue fills flush to this edge
        float inRx = rx * 0.78f;
        float inRy = ry * 0.76f;

        // Halo flush against the white rim 鈥?no gap between orbit and glow
        float eyeR = Math.max(outRx, outRy);
        float haloR = eyeR * 1.55f;
        // Start bright right at the outer rim, fade outward
        float rimStart = (eyeR * 0.78f) / haloR;  // inside rim 鈫?already lit
        float rimPeak = (eyeR * 0.98f) / haloR;   // peak on the white orbit edge
        float rimMid = (eyeR * 1.18f) / haloR;    // thick band just outside
        float rimFade = (eyeR * 1.42f) / haloR;
        rimStart = Math.max(0.05f, Math.min(0.7f, rimStart));
        rimPeak = Math.min(0.88f, Math.max(rimStart + 0.05f, rimPeak));
        rimMid = Math.min(0.94f, Math.max(rimPeak + 0.04f, rimMid));
        rimFade = Math.min(0.99f, Math.max(rimMid + 0.04f, rimFade));
        haloPaint.setShader(new RadialGradient(
                cx, cy, haloR,
                new int[]{
                        Color.TRANSPARENT,
                        Color.argb((int) (55 * alpha), 150, 225, 255),
                        Color.argb((int) (120 * alpha), 165, 235, 255),
                        Color.argb((int) (70 * alpha), 130, 215, 255),
                        Color.TRANSPARENT
                },
                new float[]{0f, rimStart, rimPeak, rimMid, rimFade},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, haloPaint);
        haloPaint.setShader(null);

        // Blue iris first: same organic oval as the rim hole, slightly oversized to seal AA gaps
        float irisRx = inRx * 1.04f;
        float irisRy = inRy * 1.04f;
        eyeHole.reset();
        buildOrganicOval(eyeHole, cx, cy, irisRx, irisRy, left, false);
        irisPaint.setAlpha((int) (255 * alpha));
        canvas.drawPath(eyeHole, irisPaint);

        // White rim + eyelash 鈥?moderate local glow only
        buildIrregularEyeRing(cx, cy, rx, ry, left, outRx, outRy, inRx, inRy);
        glowFill.setShadowLayer(28f, 0f, 0f, COLOR_GLOW);
        glowFill.setAlpha((int) (255 * alpha));
        canvas.drawPath(eyeRing, glowFill);
        glowFill.setShadowLayer(36f, 0f, 0f, COLOR_GLOW);

        // Large white highlight (~half iris) 鈥?upper-inner, drifts gently
        float irisR = Math.min(irisRx, irisRy);
        float highlightR = irisR * 0.58f;
        float baseHx = left ? 0.12f : -0.12f;
        float baseHy = -0.12f;
        float hx = cx + (baseHx + highlightOx * 0.28f) * irisR;
        float hy = cy + (baseHy + highlightOy * 0.28f) * irisR;
        float dx = hx - cx;
        float dy = hy - cy;
        float lim = Math.max(0.01f, irisR - highlightR * 0.92f);
        float dist = (float) Math.hypot(dx, dy);
        if (dist > lim) {
            float s = lim / dist;
            hx = cx + dx * s;
            hy = cy + dy * s;
        }

        fillPaint.setAlpha((int) (255 * alpha));
        canvas.drawCircle(hx, hy, highlightR, fillPaint);

        glowFill.setAlpha(255);
        irisPaint.setAlpha(255);
        fillPaint.setAlpha(255);
    }

    /**
     * Irregular oval white outline with a sharp outer-top eyelash flick.
     * Built as even-odd: outer organic oval minus inner oval, union eyelash.
     */
    private void buildIrregularEyeRing(float cx, float cy, float rx, float ry, boolean left,
                                       float outRx, float outRy, float inRx, float inRy) {
        eyeOutline.reset();
        buildOrganicOval(eyeOutline, cx, cy, outRx, outRy, left, true);

        eyeHole.reset();
        buildOrganicOval(eyeHole, cx, cy, inRx, inRy, left, false);

        eyeRing.reset();
        eyeRing.setFillType(Path.FillType.EVEN_ODD);
        eyeRing.addPath(eyeOutline);
        eyeRing.addPath(eyeHole);

        // Eyelash: thicker root, short outward, tip soft inward curl
        lashPath.reset();
        float sign = left ? -1f : 1f;
        float baseOuterX = cx + sign * outRx * 0.86f;
        float baseOuterY = cy - outRy * 0.42f;
        float baseInnerX = cx + sign * outRx * 0.58f;
        float baseInnerY = cy - outRy * 0.62f;
        float midX = cx + sign * outRx * 0.92f;
        float midY = cy - outRy * 0.74f;
        float tipX = cx + sign * outRx * 0.78f;
        float tipY = cy - outRy * 0.88f;

        lashPath.moveTo(baseOuterX, baseOuterY);
        lashPath.quadTo(midX, midY, tipX, tipY);
        lashPath.quadTo(cx + sign * outRx * 0.68f, cy - outRy * 0.76f,
                baseInnerX, baseInnerY);
        lashPath.close();

        eyeRing.op(lashPath, Path.Op.UNION);
    }

    private void buildOrganicOval(Path path, float cx, float cy, float rx, float ry,
                                  boolean left, boolean outer) {
        // Smooth oval; only slight outer widen 鈥?no inner corner spike
        float outerPull = outer ? 0.05f : 0.02f;

        float leftX = cx - rx;
        float rightX = cx + rx;
        float topY = cy - ry;
        float bottomY = cy + ry * 0.98f;

        if (left) {
            leftX -= rx * outerPull;
        } else {
            rightX += rx * outerPull;
        }

        float k = 0.5522847f;
        float cpx = rx * k;
        float cpy = ry * k;

        path.moveTo(cx, topY);
        path.cubicTo(cx + cpx, topY, rightX, cy - cpy, rightX, cy);
        path.cubicTo(rightX, cy + cpy, cx + cpx, bottomY, cx, bottomY);
        path.cubicTo(cx - cpx, bottomY, leftX, cy + cpy, leftX, cy);
        path.cubicTo(leftX, cy - cpy, cx - cpx, topY, cx, topY);
        path.close();
    }

    /**
     * Single filled path morph (no stroke/fill crossfade 鈫?no flicker):
     * open鈮? 鈫?杈冪獎銆佺瓑绮椼€佷笅鍑瑰集铓?     * open鈮? 鈫?娉拌开鐔婇蓟瀛?     */
    private void drawTalkingMouth(Canvas canvas, float cx, float cy, float eyeRx, float alpha) {
        float open = Math.max(0f, Math.min(1f, speakAmount));
        float t = open * open * (3f - 2f * open);

        buildMouthMorphPath(cx, cy, eyeRx, t);
        glowFill.setAlpha((int) (255 * alpha));
        canvas.drawPath(speakMouth, glowFill);
        glowFill.setAlpha(255);
    }

    private void buildMouthMorphPath(float cx, float cy, float eyeRx, float t) {
        // Closed silkworm: narrower + constant thickness ribbon
        float cHalfW = eyeRx * 0.34f;
        float cThick = eyeRx * 0.10f;
        float cCurve = eyeRx * 0.30f;
        float cEndY = cy - cCurve * 0.20f;
        float cMidY = cy + cCurve * 0.50f;
        float cTopMid = cMidY - cThick * 0.5f;
        float cBotMid = cMidY + cThick * 0.5f;
        float cTopEnd = cEndY - cThick * 0.5f;
        float cBotEnd = cEndY + cThick * 0.5f;

        // Open teddy nose
        float nHalfW = eyeRx * 0.36f;
        float nTop = cy - eyeRx * 0.02f;
        float nBot = cy + eyeRx * 0.50f;
        float nCornerY = nTop + eyeRx * 0.02f;
        float nBotHalfW = nHalfW * 0.08f;
        float nSideY = nTop * 0.35f + nBot * 0.65f;

        float halfW = lerp(cHalfW, nHalfW, t);
        float topMid = lerp(cTopMid, nTop, t);
        float botMid = lerp(cBotMid, nBot, t);
        float topEnd = lerp(cTopEnd, nCornerY, t);
        float botEnd = lerp(cBotEnd, nCornerY, t);
        float botHalfW = lerp(cHalfW * 0.95f, nBotHalfW, t);
        float sideY = lerp((cTopEnd + cBotEnd) * 0.5f, nSideY, t);

        speakMouth.reset();
        // Top: left end 鈫?mid (涓嬪嚬) 鈫?right end
        speakMouth.moveTo(cx - halfW, topEnd);
        speakMouth.quadTo(cx, topMid, cx + halfW, topEnd);
        // Right side down to bottom tip / belly
        speakMouth.cubicTo(
                cx + halfW * lerp(1.02f, 0.7f, t), sideY,
                cx + botHalfW, botMid,
                cx, botMid);
        // Left side back up
        speakMouth.cubicTo(
                cx - botHalfW, botMid,
                cx - halfW * lerp(1.02f, 0.7f, t), sideY,
                cx - halfW, botEnd);
        // Left end cap (keeps closed thickness even)
        speakMouth.quadTo(
                cx - halfW - cThick * 0.08f * (1f - t), (topEnd + botEnd) * 0.5f,
                cx - halfW, topEnd);
        speakMouth.close();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void buildClosedEyes(float leftCx, float rightCx, float cy, float eyeR) {
        float w = eyeR * 1.9f;
        float lift = eyeR * 0.42f;

        leftClosedEye.reset();
        leftClosedEye.moveTo(leftCx + w * 0.4f, cy + lift * 0.2f);
        leftClosedEye.cubicTo(
                leftCx + w * 0.1f, cy - lift * 0.95f,
                leftCx - w * 0.25f, cy - lift * 0.55f,
                leftCx - w * 0.52f, cy + lift * 0.05f);
        leftClosedEye.quadTo(
                leftCx - w * 0.62f, cy - lift * 0.55f,
                leftCx - w * 0.78f, cy - lift * 1.1f);

        rightClosedEye.reset();
        rightClosedEye.moveTo(rightCx - w * 0.4f, cy + lift * 0.2f);
        rightClosedEye.cubicTo(
                rightCx - w * 0.1f, cy - lift * 0.95f,
                rightCx + w * 0.25f, cy - lift * 0.55f,
                rightCx + w * 0.52f, cy + lift * 0.05f);
        rightClosedEye.quadTo(
                rightCx + w * 0.62f, cy - lift * 0.55f,
                rightCx + w * 0.78f, cy - lift * 1.1f);
    }

    private void drawClosedEyesOnly(Canvas canvas, float eyeR, float alpha) {
        int a = (int) (255 * alpha);

        glowStroke.setAlpha(a);
        glowStroke.setStrokeWidth(eyeR * 0.24f);
        canvas.drawPath(leftClosedEye, glowStroke);
        canvas.drawPath(rightClosedEye, glowStroke);

        coreStroke.setAlpha(a);
        coreStroke.setStrokeWidth(eyeR * 0.13f);
        canvas.drawPath(leftClosedEye, coreStroke);
        canvas.drawPath(rightClosedEye, coreStroke);

        glowStroke.setAlpha(255);
        coreStroke.setAlpha(255);
    }
}
