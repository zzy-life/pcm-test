package com.hr.digitalhuman.ui;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import com.hr.digitalhuman.model.RobotConfig;

/**
 * 猎户星空机器人屏常见 densityDpi=560（约 density=3.5），
 * 在 1920×1080 下可用宽度仅约 549dp，会导致布局拥挤错乱。
 * 按设计宽度重算 density，使横屏 UI 按约 960dp 宽度排版。
 */
public final class DisplayDensityHelper {

    /** 设备实际密度（用户确认） */
    public static final int DEVICE_DENSITY_DPI = 560;

    /** 默认设计宽度（dp），1920px / 2.0 ≈ 960dp */
    public static final float DEFAULT_DESIGN_WIDTH_DP = 960f;

    private DisplayDensityHelper() {
    }

    public static void apply(Context context) {
        apply(context, null);
    }

    public static void apply(Context context, RobotConfig.DisplayConfig display) {
        if (context == null) {
            return;
        }
        Resources res = context.getResources();
        if (res == null) {
            return;
        }
        DisplayMetrics dm = res.getDisplayMetrics();
        int widthPx = dm.widthPixels;
        if (widthPx <= 0 && display != null && display.width > 0) {
            widthPx = display.width;
        }
        if (widthPx <= 0) {
            widthPx = 1920;
        }

        float designWidthDp = DEFAULT_DESIGN_WIDTH_DP;
        if (display != null && display.designWidthDp > 0) {
            designWidthDp = display.designWidthDp;
        }

        float targetDensity = widthPx / designWidthDp;
        // 限制范围，避免极端值
        if (targetDensity < 1.0f) {
            targetDensity = 1.0f;
        } else if (targetDensity > 4.0f) {
            targetDensity = 4.0f;
        }
        int targetDpi = Math.round(160f * targetDensity);
        float targetScaled = targetDensity;

        dm.density = targetDensity;
        dm.scaledDensity = targetScaled;
        dm.densityDpi = targetDpi;

        // 同步 Application 资源，避免部分 Context 仍用系统 560
        Context app = context.getApplicationContext();
        if (app != null && app != context) {
            DisplayMetrics appDm = app.getResources().getDisplayMetrics();
            appDm.density = targetDensity;
            appDm.scaledDensity = targetScaled;
            appDm.densityDpi = targetDpi;
        }
    }
}
