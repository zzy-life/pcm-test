package com.hr.digitalhuman.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.hr.digitalhuman.R;

/** Shared visual helpers for non-face screens. */
public final class UiDecor {

    private UiDecor() {
    }

    public static int color(Context ctx, int resId) {
        return ContextCompat.getColor(ctx, resId);
    }

    public static int dp(Context ctx, int v) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    public static LinearLayout.LayoutParams cardLp(Context ctx, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, bottomMarginDp));
        return lp;
    }

    /** 纯代码绘制卡片背景，避免 XML drawable 在部分机型/旧包上 NotFoundException */
    public static void styleCard(Context ctx, View card) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A2C44, 0xFF121E2E});
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 16));
        bg.setStroke(dp(ctx, 1), 0xFF2C4464);
        card.setBackground(bg);
        int p = dp(ctx, 16);
        card.setPadding(p, p, p, p);
    }

    /** 两个智能体页面复用项目按钮样式，清除主题 tint，避免覆盖圆角背景。 */
    public static android.widget.Button button(Context ctx, String text, boolean primary) {
        android.content.Context themed = new androidx.appcompat.view.ContextThemeWrapper(
                ctx, primary ? R.style.BtnPrimary : R.style.BtnSecondary);
        androidx.appcompat.widget.AppCompatButton button =
                new androidx.appcompat.widget.AppCompatButton(themed, null, 0);
        button.setSupportBackgroundTintList(null);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinimumHeight(dp(ctx, 48));
        // 禁用态保持与后台任务状态一致，不能看起来仍可点击。
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{color(ctx, R.color.text_dim),
                        color(ctx, primary ? R.color.white : R.color.text)}));
        return button;
    }

    public static TextView title(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color(ctx, R.color.text));
        tv.setTextSize(16);
        return tv;
    }

    public static TextView subtitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color(ctx, R.color.text_dim));
        tv.setTextSize(13);
        tv.setPadding(0, dp(ctx, 6), 0, 0);
        return tv;
    }

    public static TextView chip(Context ctx, String label) {
        TextView chip = new TextView(ctx);
        chip.setText(label);
        chip.setTextColor(color(ctx, R.color.text));
        chip.setTextSize(13);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(0xFF16263C);
        bg.setCornerRadius(dp(ctx, 16));
        bg.setStroke(dp(ctx, 1), 0xFF2C4464);
        chip.setBackground(bg);
        chip.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(ctx, 10), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    public static void playEnter(View view, int delayMs) {
        if (view == null) {
            return;
        }
        view.setTranslationY(12f);
        view.animate()
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public static void pulseDot(View dot) {
        if (dot == null) {
            return;
        }
        ObjectAnimator sx = ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.55f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 1.55f, 1f);
        ObjectAnimator a = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.45f, 1f);
        sx.setRepeatCount(ObjectAnimator.INFINITE);
        sy.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy, a);
        set.setDuration(1200);
        set.start();
    }
}
