package com.hr.digitalhuman.ui.display;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.ui.UiDecor;

/**
 * 首页空闲：居中问候，不放写死的业务入口。快捷服务在底部，由配置下发。
 */
public class WelcomeScene extends LinearLayout {

    public WelcomeScene(Context context, String robotName, String slogan) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = UiDecor.dp(context, 8);
        setPadding(pad, pad, pad, pad);

        String name = robotName == null || robotName.isEmpty() ? "人社小助手" : robotName;
        String line = slogan == null || slogan.isEmpty() ? "智慧人社 · 贴心服务" : slogan;

        addView(new Spacer(context), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ListenMark mark = new ListenMark(context);
        addView(mark, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiDecor.dp(context, 160)));

        TextView hello = new TextView(context);
        hello.setText("您好，我是" + name);
        hello.setTextColor(UiDecor.color(context, R.color.text));
        hello.setTextSize(26);
        hello.setGravity(Gravity.CENTER);
        hello.setTypeface(hello.getTypeface(), android.graphics.Typeface.BOLD);
        LayoutParams helloLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helloLp.topMargin = UiDecor.dp(context, 6);
        addView(hello, helloLp);

        TextView sub = new TextView(context);
        sub.setText(line);
        sub.setTextColor(UiDecor.color(context, R.color.primary_bright));
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        LayoutParams subLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = UiDecor.dp(context, 6);
        addView(sub, subLp);

        TextView hint = new TextView(context);
        hint.setText("请直接说话");
        hint.setTextColor(UiDecor.color(context, R.color.text_dim));
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        LayoutParams hintLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = UiDecor.dp(context, 10);
        addView(hint, hintLp);

        addView(new Spacer(context), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f));
    }

    private static final class Spacer extends View {
        Spacer(Context context) {
            super(context);
        }
    }

    /** 慢呼吸光环，表示正在等您开口，不做成业务菜单。 */
    private static final class ListenMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator animator;
        private float phase;

        ListenMark(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(3600);
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
            float haloR = 58 * unit * (0.94f + 0.06f * breathe);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, haloR,
                    new int[]{0x553D8BFF, 0x221B6BDB, 0x0010182C},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, haloR, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * unit);
            paint.setColor(0xFF5B9BFF);
            paint.setAlpha(90 + (int) (80 * breathe));
            canvas.drawCircle(cx, cy, 28 * unit, paint);
            paint.setAlpha(50 + (int) (40 * (1f - breathe)));
            canvas.drawCircle(cx, cy, 42 * unit, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5B9BFF);
            paint.setAlpha(220);
            canvas.drawCircle(cx, cy, (6 + breathe) * unit, paint);
        }
    }
}
