package com.hr.digitalhuman.ui.display;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.ui.UiDecor;

/** 展示画布统一视觉：卡片、标题、脚注。皮肤都落在政务蓝 + 暖金点缀上。 */
public final class DisplayStyle {

    public static final int CARD_CYAN = 0xFF1B6BDB;
    public static final int CARD_CYAN_SOFT = 0x551B6BDB;
    public static final int CARD_BG_TOP = 0xE6163A6B;
    public static final int CARD_BG_BOT = 0xE60F2A4D;
    public static final int CARD_GLOW_TOP = 0xF01B4A8A;
    public static final int CARD_GLASS_TOP = 0xFF1A2C44;
    public static final int CARD_GLASS_MID = 0xFF152536;
    public static final int CARD_GLASS_BOT = 0xFF121E2E;
    public static final int ACCENT = 0xFF3D8BFF;
    public static final int WARM = 0xFFF0B429;
    public static final int WARM_SOFT = 0x66F0B429;
    public static final int INK = 0xFF071018;
    public static final int TABLE_HEAD = 0xCC123056;
    public static final int TABLE_ROW_A = 0x33163A6B;
    public static final int TABLE_ROW_B = 0x22122B4A;
    public static final int FLOW_DONE = 0xFF3D8BFF;
    public static final int FLOW_CURRENT = 0xFF1B6BDB;
    public static final int FLOW_TODO = 0xFF4A6070;
    public static final int TILE_BG = 0x331B6BDB;

    public enum Skin {
        STANDARD, GLOW, GLASS, WARM
    }

    private DisplayStyle() {
    }

    public static LinearLayout card(Context ctx) {
        return card(ctx, Skin.STANDARD, true);
    }

    public static LinearLayout card(Context ctx, Skin skin) {
        return card(ctx, skin, true);
    }

    public static LinearLayout card(Context ctx, Skin skin, boolean padded) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground(ctx, skin));
        if (padded) {
            int p = UiDecor.dp(ctx, 16);
            card.setPadding(p, p, p, p);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiDecor.dp(ctx, 12);
        card.setLayoutParams(lp);
        return card;
    }

    public static GradientDrawable cardBackground(Context ctx, Skin skin) {
        Skin s = skin == null ? Skin.STANDARD : skin;
        int[] colors;
        int stroke;
        int radius = UiDecor.dp(ctx, 16);
        switch (s) {
            case GLOW:
                colors = new int[]{CARD_GLOW_TOP, CARD_BG_BOT};
                stroke = 0x553D8BFF;
                break;
            case GLASS:
                colors = new int[]{CARD_GLASS_TOP, CARD_GLASS_BOT};
                stroke = 0xFF2C4464;
                break;
            case WARM:
                colors = new int[]{0xFF2A2414, CARD_BG_BOT};
                stroke = 0x66F0B429;
                break;
            default:
                colors = new int[]{0xFF1A2C44, 0xFF121E2E};
                stroke = 0xFF2C4464;
                break;
        }
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        bg.setCornerRadius(radius);
        bg.setStroke(UiDecor.dp(ctx, 1), stroke);
        return bg;
    }

    public static LinearLayout bannerHeader(Context ctx, String title) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF1B6BDB, 0xFF123056});
        bg.setCornerRadii(new float[]{
                UiDecor.dp(ctx, 16), UiDecor.dp(ctx, 16),
                UiDecor.dp(ctx, 16), UiDecor.dp(ctx, 16),
                0, 0, 0, 0});
        bar.setBackground(bg);
        int p = UiDecor.dp(ctx, 14);
        bar.setPadding(p, UiDecor.dp(ctx, 12), p, UiDecor.dp(ctx, 12));
        TextView tv = new TextView(ctx);
        tv.setText(title == null ? "" : title);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(tv);
        return bar;
    }

    public static TextView pillTitle(Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title == null ? "" : title);
        tv.setTextColor(INK);
        tv.setTextSize(13);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(UiDecor.dp(ctx, 12), UiDecor.dp(ctx, 4), UiDecor.dp(ctx, 12), UiDecor.dp(ctx, 4));
        tv.setBackground(pill(CARD_CYAN, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiDecor.dp(ctx, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static LinearLayout tile(Context ctx) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TILE_BG);
        bg.setCornerRadius(UiDecor.dp(ctx, 12));
        bg.setStroke(UiDecor.dp(ctx, 1), CARD_CYAN_SOFT);
        tile.setBackground(bg);
        int p = UiDecor.dp(ctx, 12);
        tile.setPadding(p, p, p, p);
        return tile;
    }

    public static View diamond(Context ctx, int color) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(UiDecor.dp(ctx, 2));
        v.setBackground(d);
        int sz = UiDecor.dp(ctx, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.rightMargin = UiDecor.dp(ctx, 10);
        lp.topMargin = UiDecor.dp(ctx, 7);
        v.setLayoutParams(lp);
        v.setRotation(45f);
        return v;
    }

    public static TextView sectionTitle(Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title == null ? "" : title);
        tv.setTextColor(UiDecor.color(ctx, R.color.primary_bright));
        tv.setTextSize(15);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 0, UiDecor.dp(ctx, 10));
        return tv;
    }

    public static TextView body(Context ctx, String text, int sp) {
        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(UiDecor.color(ctx, R.color.text));
        tv.setTextSize(sp);
        tv.setLineSpacing(UiDecor.dp(ctx, 4), 1f);
        return tv;
    }

    public static View accentBar(Context ctx) {
        View bar = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiDecor.dp(ctx, 2));
        bg.setColor(CARD_CYAN);
        bar.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiDecor.dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = UiDecor.dp(ctx, 12);
        bar.setLayoutParams(lp);
        return bar;
    }

    public static GradientDrawable circle(int color, int sizePx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(sizePx, sizePx);
        return d;
    }

    public static GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(48f);
        if (stroke != 0) {
            d.setStroke(2, stroke);
        }
        return d;
    }

    public static TextView footnote(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(UiDecor.color(ctx, R.color.text_dim));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, UiDecor.dp(ctx, 8), 0, UiDecor.dp(ctx, 4));
        return tv;
    }
}
