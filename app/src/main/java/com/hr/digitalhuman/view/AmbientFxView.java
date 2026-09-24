package com.hr.digitalhuman.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Full-screen ambient FX: floating particles, stream lights, soft meteors.
 * Non-interactive overlay for non-face pages.
 */
public class AmbientFxView extends View {

    private static final int PARTICLE_COUNT = 48;
    private static final int STREAM_COUNT = 5;
    private static final int METEOR_COUNT = 3;

    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meteorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path streamPath = new Path();
    private final Random random = new Random();

    private Particle[] particles;
    private Stream[] streams;
    private Meteor[] meteors;
    private float orbX;
    private float orbY;
    private float orbPhase;
    private boolean running;
    private long lastFrameMs;
    private float densityScale = 1f;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.04f, (now - lastFrameMs) / 1000f);
            lastFrameMs = now;
            step(dt);
            invalidate();
            postOnAnimation(this);
        }
    };

    public AmbientFxView(Context context) {
        super(context);
        init();
    }

    public AmbientFxView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AmbientFxView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setEnabled(false);
        particlePaint.setStyle(Paint.Style.FILL);
        streamPaint.setStyle(Paint.Style.STROKE);
        streamPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.FILL);
        meteorPaint.setStyle(Paint.Style.STROKE);
        meteorPaint.setStrokeCap(Paint.Cap.ROUND);
        densityScale = 1f;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    /** 0.6 ~ 1.4，控制粒子/流光密度。 */
    public void setIntensity(float intensity) {
        densityScale = Math.max(0.5f, Math.min(1.6f, intensity));
        if (getWidth() > 0) {
            spawnAll(getWidth(), getHeight());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            spawnAll(w, h);
        }
    }

    private void spawnAll(int w, int h) {
        int pc = Math.round(PARTICLE_COUNT * densityScale);
        int sc = Math.max(3, Math.round(STREAM_COUNT * densityScale));
        int mc = Math.max(2, Math.round(METEOR_COUNT * densityScale));
        particles = new Particle[pc];
        for (int i = 0; i < pc; i++) {
            particles[i] = newParticle(w, h, true);
        }
        streams = new Stream[sc];
        for (int i = 0; i < sc; i++) {
            streams[i] = newStream(w, h, true);
        }
        meteors = new Meteor[mc];
        for (int i = 0; i < mc; i++) {
            meteors[i] = newMeteor(w, h, true);
        }
        orbX = w * 0.72f;
        orbY = h * 0.18f;
    }

    private Particle newParticle(int w, int h, boolean randomY) {
        Particle p = new Particle();
        p.x = random.nextFloat() * w;
        p.y = randomY ? random.nextFloat() * h : h + random.nextFloat() * 40f;
        p.size = (1.2f + random.nextFloat() * 2.8f) * densityScale;
        p.vx = (random.nextFloat() - 0.5f) * 18f;
        p.vy = -(8f + random.nextFloat() * 22f);
        p.alpha = 0.15f + random.nextFloat() * 0.55f;
        p.twinkle = random.nextFloat() * (float) Math.PI * 2f;
        p.twinkleSpeed = 1.2f + random.nextFloat() * 2.4f;
        int tone = random.nextInt(3);
        if (tone == 0) {
            p.color = 0xFF1B6BDB;
        } else if (tone == 1) {
            p.color = 0xFF5B9BFF;
        } else {
            p.color = 0xFF3D8BFF;
        }
        return p;
    }

    private Stream newStream(int w, int h, boolean randomPhase) {
        Stream s = new Stream();
        s.y = h * (0.12f + random.nextFloat() * 0.76f);
        s.length = w * (0.35f + random.nextFloat() * 0.45f);
        s.thickness = (2.5f + random.nextFloat() * 4f) * densityScale;
        s.speed = 0.12f + random.nextFloat() * 0.22f;
        s.phase = randomPhase ? random.nextFloat() : -random.nextFloat() * 0.4f;
        s.slant = -0.08f - random.nextFloat() * 0.12f;
        s.cyan = random.nextBoolean();
        return s;
    }

    private Meteor newMeteor(int w, int h, boolean randomPhase) {
        Meteor m = new Meteor();
        resetMeteor(m, w, h, randomPhase);
        return m;
    }

    private void resetMeteor(Meteor m, int w, int h, boolean randomPhase) {
        m.x = w * (0.2f + random.nextFloat() * 0.9f);
        m.y = -20f - random.nextFloat() * 80f;
        m.vx = -(90f + random.nextFloat() * 110f);
        m.vy = 140f + random.nextFloat() * 160f;
        m.len = 40f + random.nextFloat() * 70f;
        m.life = randomPhase ? random.nextFloat() : 0f;
        m.maxLife = 1.4f + random.nextFloat() * 1.8f;
        m.width = 2f + random.nextFloat() * 2.5f;
        m.wait = randomPhase ? random.nextFloat() * 2.5f : 0.4f + random.nextFloat() * 2f;
    }

    private void step(float dt) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || particles == null) {
            return;
        }
        orbPhase += dt * 0.7f;
        orbX = w * (0.68f + 0.08f * (float) Math.sin(orbPhase));
        orbY = h * (0.16f + 0.05f * (float) Math.cos(orbPhase * 0.85f));

        for (Particle p : particles) {
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.twinkle += p.twinkleSpeed * dt;
            if (p.y < -10 || p.x < -20 || p.x > w + 20) {
                Particle np = newParticle(w, h, false);
                p.x = np.x;
                p.y = np.y;
                p.size = np.size;
                p.vx = np.vx;
                p.vy = np.vy;
                p.alpha = np.alpha;
                p.color = np.color;
                p.twinkle = np.twinkle;
                p.twinkleSpeed = np.twinkleSpeed;
            }
        }
        for (Stream s : streams) {
            s.phase += s.speed * dt;
            if (s.phase > 1.35f) {
                Stream ns = newStream(w, h, false);
                s.y = ns.y;
                s.length = ns.length;
                s.thickness = ns.thickness;
                s.speed = ns.speed;
                s.phase = -0.15f;
                s.slant = ns.slant;
                s.cyan = ns.cyan;
            }
        }
        for (Meteor m : meteors) {
            if (m.wait > 0) {
                m.wait -= dt;
                continue;
            }
            m.life += dt;
            m.x += m.vx * dt;
            m.y += m.vy * dt;
            if (m.life > m.maxLife || m.y > h + 40 || m.x < -80) {
                resetMeteor(m, w, h, false);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (particles == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();

        // soft orbit glow
        float orbR = Math.min(w, h) * 0.28f;
        glowPaint.setShader(new RadialGradient(orbX, orbY, orbR,
                new int[]{0x441B6BDB, 0x221B6BDB, 0x001B6BDB},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(orbX, orbY, orbR, glowPaint);
        glowPaint.setShader(null);

        // stream lights
        for (Stream s : streams) {
            float travel = s.phase;
            if (travel < 0f) {
                continue;
            }
            float startX = -s.length * 0.2f + (w + s.length) * Math.min(1f, travel);
            float endX = startX + s.length;
            float y1 = s.y;
            float y2 = s.y + s.slant * s.length;
            streamPath.reset();
            streamPath.moveTo(startX, y1);
            streamPath.lineTo(endX, y2);
            float mid = (startX + endX) * 0.5f;
            int c0 = s.cyan ? 0x001B6BDB : 0x003D8BFF;
            int c1 = s.cyan ? 0xAA5B9BFF : 0xAA3D8BFF;
            int c2 = s.cyan ? 0x001B6BDB : 0x003D8BFF;
            streamPaint.setStrokeWidth(s.thickness);
            streamPaint.setShader(new LinearGradient(startX, y1, endX, y2,
                    new int[]{c0, c1, c2}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            float alphaBoost = travel < 0.15f ? travel / 0.15f : (travel > 0.85f ? (1.35f - travel) / 0.5f : 1f);
            streamPaint.setAlpha(Math.max(0, Math.min(255, (int) (180 * alphaBoost))));
            canvas.drawPath(streamPath, streamPaint);
            streamPaint.setShader(null);
            streamPaint.setAlpha(255);
        }

        // meteors
        for (Meteor m : meteors) {
            if (m.wait > 0) {
                continue;
            }
            float progress = m.life / m.maxLife;
            float fade = progress < 0.15f ? progress / 0.15f : (progress > 0.7f ? (1f - progress) / 0.3f : 1f);
            float dx = m.vx;
            float dy = m.vy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) {
                continue;
            }
            float nx = dx / len;
            float ny = dy / len;
            float x2 = m.x - nx * m.len;
            float y2 = m.y - ny * m.len;
            meteorPaint.setStrokeWidth(m.width);
            meteorPaint.setShader(new LinearGradient(x2, y2, m.x, m.y,
                    new int[]{0x001B6BDB, 0xBB5B9BFF, 0xFFFFFFFF},
                    new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            meteorPaint.setAlpha(Math.max(0, Math.min(255, (int) (220 * fade))));
            canvas.drawLine(x2, y2, m.x, m.y, meteorPaint);
            meteorPaint.setShader(null);
            // head glow
            glowPaint.setColor(0x88FFFFFF);
            glowPaint.setAlpha(Math.max(0, Math.min(255, (int) (180 * fade))));
            canvas.drawCircle(m.x, m.y, m.width * 1.6f, glowPaint);
            glowPaint.setAlpha(255);
        }

        // particles
        for (Particle p : particles) {
            float tw = 0.55f + 0.45f * (float) Math.sin(p.twinkle);
            int a = Math.max(0, Math.min(255, (int) (255 * p.alpha * tw)));
            particlePaint.setColor(p.color);
            particlePaint.setAlpha(a);
            canvas.drawCircle(p.x, p.y, p.size, particlePaint);
            // soft halo
            particlePaint.setAlpha(a / 3);
            canvas.drawCircle(p.x, p.y, p.size * 2.2f, particlePaint);
        }
        particlePaint.setAlpha(255);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameMs = 0;
        removeCallbacks(frame);
        postOnAnimation(frame);
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
            running = true;
            lastFrameMs = 0;
            removeCallbacks(frame);
            postOnAnimation(frame);
        } else {
            running = false;
            removeCallbacks(frame);
        }
    }

    private static class Particle {
        float x, y, vx, vy, size, alpha, twinkle, twinkleSpeed;
        int color;
    }

    private static class Stream {
        float y, length, thickness, speed, phase, slant;
        boolean cyan;
    }

    private static class Meteor {
        float x, y, vx, vy, len, life, maxLife, width, wait;
    }
}
