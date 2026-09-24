package com.hr.digitalhuman.ui.display;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.ui.UiDecor;

import java.util.List;

/** 同一种 kind 的多种外观，颜色都落在政务蓝 + 暖金点缀。 */
final class SectionLooks {

    private SectionLooks() {
    }

    static View text(Context ctx, DisplaySection s, int variant, DisplayStyle.Skin skin) {
        switch (variant % 3) {
            case 1:
                return textBanner(ctx, s, skin);
            case 2:
                return textQuote(ctx, s, DisplayStyle.Skin.WARM);
            default:
                return textRibbon(ctx, s, skin);
        }
    }

    static View list(Context ctx, DisplaySection s, int variant, DisplayStyle.Skin skin) {
        switch (variant % 4) {
            case 1:
                return listCheck(ctx, s, skin);
            case 2:
                return listChip(ctx, s, skin);
            case 3:
                return listTile(ctx, s, skin);
            default:
                return listIndex(ctx, s, skin);
        }
    }

    static View table(Context ctx, DisplaySection s, int variant, DisplayStyle.Skin skin) {
        switch (variant % 3) {
            case 1:
                return tableKv(ctx, s, skin);
            case 2:
                return tableCompact(ctx, s, DisplayStyle.Skin.WARM);
            default:
                return tableStripe(ctx, s, skin);
        }
    }

    static View flow(Context ctx, DisplaySection s, int variant, DisplayStyle.Skin skin) {
        switch (variant % 3) {
            case 1:
                return flowStepper(ctx, s, skin);
            case 2:
                return flowBlock(ctx, s, skin);
            default:
                return flowRail(ctx, s, skin);
        }
    }

    // ---------- text ----------

    private static View textRibbon(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(DisplayStyle.accentBar(ctx));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (notEmpty(s.title)) {
            col.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        if (notEmpty(s.text)) {
            col.addView(DisplayStyle.body(ctx, s.text, 16));
        }
        row.addView(col);
        card.addView(row);
        return card;
    }

    private static View textBanner(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin, false);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.bannerHeader(ctx, s.title));
        }
        if (notEmpty(s.text)) {
            TextView body = DisplayStyle.body(ctx, s.text, 16);
            int p = UiDecor.dp(ctx, 16);
            body.setPadding(p, p, p, p);
            card.addView(body);
        }
        return card;
    }

    private static View textQuote(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View bar = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiDecor.dp(ctx, 2));
        bg.setColor(DisplayStyle.WARM);
        bar.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                UiDecor.dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT);
        blp.rightMargin = UiDecor.dp(ctx, 12);
        bar.setLayoutParams(blp);
        row.addView(bar);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (notEmpty(s.title)) {
            col.addView(DisplayStyle.pillTitle(ctx, s.title));
        }
        if (notEmpty(s.text)) {
            col.addView(DisplayStyle.body(ctx, s.text, 16));
        }
        row.addView(col);
        card.addView(row);
        return card;
    }

    // ---------- list ----------

    private static View listIndex(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        int i = 1;
        for (String item : s.items) {
            if (blank(item)) {
                continue;
            }
            card.addView(indexRow(ctx, String.valueOf(i++), item.trim(), DisplayStyle.CARD_CYAN));
        }
        return card;
    }

    private static View listCheck(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        for (String item : s.items) {
            if (blank(item)) {
                continue;
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, UiDecor.dp(ctx, 6), 0, UiDecor.dp(ctx, 6));
            TextView mark = new TextView(ctx);
            mark.setText("✓");
            mark.setTextColor(DisplayStyle.INK);
            mark.setTextSize(12);
            mark.setTypeface(Typeface.DEFAULT_BOLD);
            mark.setGravity(Gravity.CENTER);
            int sz = UiDecor.dp(ctx, 22);
            mark.setBackground(DisplayStyle.circle(DisplayStyle.WARM, sz));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(sz, sz);
            mlp.rightMargin = UiDecor.dp(ctx, 10);
            mlp.topMargin = UiDecor.dp(ctx, 2);
            mark.setLayoutParams(mlp);
            TextView tv = DisplayStyle.body(ctx, item.trim(), 15);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(mark);
            row.addView(tv);
            card.addView(row);
        }
        return card;
    }

    private static View listChip(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.pillTitle(ctx, s.title));
        }
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int col = 0;
        for (String item : s.items) {
            if (blank(item)) {
                continue;
            }
            if (col % 2 == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = col == 0 ? 0 : UiDecor.dp(ctx, 8);
                row.setLayoutParams(rlp);
                wrap.addView(row);
            }
            TextView chip = new TextView(ctx);
            chip.setText(item.trim());
            chip.setTextColor(UiDecor.color(ctx, R.color.text));
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            int p = UiDecor.dp(ctx, 10);
            chip.setPadding(p, p, p, p);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(DisplayStyle.TILE_BG);
            bg.setCornerRadius(UiDecor.dp(ctx, 20));
            bg.setStroke(UiDecor.dp(ctx, 1), DisplayStyle.CARD_CYAN_SOFT);
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (col % 2 == 0) {
                lp.rightMargin = UiDecor.dp(ctx, 8);
            }
            chip.setLayoutParams(lp);
            row.addView(chip);
            col++;
        }
        card.addView(wrap);
        return card;
    }

    private static View listTile(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int i = 0;
        for (String item : s.items) {
            if (blank(item)) {
                continue;
            }
            if (i % 2 == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = i == 0 ? 0 : UiDecor.dp(ctx, 8);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            LinearLayout tile = DisplayStyle.tile(ctx);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 0) {
                tlp.rightMargin = UiDecor.dp(ctx, 8);
            }
            tile.setLayoutParams(tlp);
            TextView idx = new TextView(ctx);
            idx.setText(String.format("%02d", i + 1));
            idx.setTextColor(UiDecor.color(ctx, R.color.primary_bright));
            idx.setTextSize(12);
            idx.setTypeface(Typeface.DEFAULT_BOLD);
            tile.addView(idx);
            tile.addView(DisplayStyle.body(ctx, item.trim(), 14));
            row.addView(tile);
            i++;
        }
        card.addView(grid);
        return card;
    }

    private static LinearLayout indexRow(Context ctx, String index, String text, int badgeColor) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, UiDecor.dp(ctx, 6), 0, UiDecor.dp(ctx, 6));
        TextView badge = new TextView(ctx);
        badge.setText(index);
        badge.setTextColor(DisplayStyle.INK);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        int sz = UiDecor.dp(ctx, 22);
        badge.setBackground(DisplayStyle.circle(badgeColor, sz));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = UiDecor.dp(ctx, 10);
        ilp.topMargin = UiDecor.dp(ctx, 2);
        badge.setLayoutParams(ilp);
        TextView tv = DisplayStyle.body(ctx, text, 15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(badge);
        row.addView(tv);
        return row;
    }

    // ---------- table ----------

    private static View tableStripe(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        card.addView(stripeTable(ctx, s, false));
        return card;
    }

    private static View tableCompact(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.pillTitle(ctx, s.title));
        }
        card.addView(stripeTable(ctx, s, true));
        return card;
    }

    private static View stripeTable(Context ctx, DisplaySection s, boolean compact) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setFillViewport(true);
        hsv.setHorizontalScrollBarEnabled(false);
        TableLayout table = new TableLayout(ctx);
        table.setStretchAllColumns(true);
        if (s.columns != null && !s.columns.isEmpty()) {
            table.addView(tableRow(ctx, s.columns, true, 0, compact));
        }
        if (s.rows != null) {
            int r = 0;
            for (List<String> row : s.rows) {
                table.addView(tableRow(ctx, row, false, r++, compact));
            }
        }
        hsv.addView(table);
        return hsv;
    }

    private static View tableKv(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        List<String> cols = s.columns;
        if (s.rows == null) {
            return card;
        }
        int r = 0;
        for (List<String> row : s.rows) {
            if (row == null) {
                continue;
            }
            LinearLayout block = DisplayStyle.tile(ctx);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) {
                blp.topMargin = UiDecor.dp(ctx, 8);
            }
            block.setLayoutParams(blp);
            for (int c = 0; c < row.size(); c++) {
                String label = cols != null && c < cols.size() ? cols.get(c) : null;
                String value = row.get(c);
                if (label != null && !label.trim().isEmpty()) {
                    TextView lab = new TextView(ctx);
                    lab.setText(label);
                    lab.setTextColor(UiDecor.color(ctx, R.color.text_dim));
                    lab.setTextSize(12);
                    if (c > 0) {
                        lab.setPadding(0, UiDecor.dp(ctx, 6), 0, 0);
                    }
                    block.addView(lab);
                }
                block.addView(DisplayStyle.body(ctx, value == null ? "" : value, 15));
            }
            card.addView(block);
            r++;
        }
        return card;
    }

    private static TableRow tableRow(Context ctx, List<String> cells, boolean header, int rowIndex,
                                     boolean compact) {
        TableRow tr = new TableRow(ctx);
        GradientDrawable bg = new GradientDrawable();
        if (header) {
            bg.setColor(compact ? 0xCC1B6BDB : DisplayStyle.TABLE_HEAD);
        } else {
            bg.setColor(rowIndex % 2 == 0 ? DisplayStyle.TABLE_ROW_A : DisplayStyle.TABLE_ROW_B);
        }
        tr.setBackground(bg);
        int hPad = UiDecor.dp(ctx, compact ? 8 : 10);
        int vPad = UiDecor.dp(ctx, compact ? 6 : 8);
        if (cells == null) {
            return tr;
        }
        for (String cell : cells) {
            TextView tv = new TextView(ctx);
            tv.setText(cell == null ? "" : cell);
            tv.setTextSize(header ? 13 : 14);
            tv.setTextColor(header && compact ? 0xFFFFFFFF
                    : UiDecor.color(ctx, header ? R.color.primary_bright : R.color.text));
            if (header) {
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            }
            tv.setPadding(hPad, vPad, hPad, vPad);
            tv.setMinWidth(UiDecor.dp(ctx, compact ? 72 : 88));
            tr.addView(tv);
        }
        return tr;
    }

    // ---------- flow ----------

    private static View flowRail(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        int current = s.current == null ? -1 : s.current;
        List<String> steps = s.steps;
        if (steps == null) {
            return card;
        }
        for (int i = 0; i < steps.size(); i++) {
            boolean isCurrent = i == current;
            boolean done = current >= 0 && i < current;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, UiDecor.dp(ctx, 4), 0, UiDecor.dp(ctx, 4));

            LinearLayout rail = new LinearLayout(ctx);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            rail.setLayoutParams(new LinearLayout.LayoutParams(
                    UiDecor.dp(ctx, 28), ViewGroup.LayoutParams.WRAP_CONTENT));
            View dot = new View(ctx);
            int dotSize = UiDecor.dp(ctx, isCurrent ? 16 : 12);
            int color = done ? DisplayStyle.FLOW_DONE : (isCurrent ? DisplayStyle.FLOW_CURRENT : DisplayStyle.FLOW_TODO);
            dot.setBackground(DisplayStyle.circle(color, dotSize));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dlp.gravity = Gravity.CENTER_HORIZONTAL;
            dot.setLayoutParams(dlp);
            rail.addView(dot);
            if (i < steps.size() - 1) {
                View line = new View(ctx);
                GradientDrawable lineBg = new GradientDrawable();
                lineBg.setColor(done ? DisplayStyle.FLOW_DONE : 0x554A6070);
                line.setBackground(lineBg);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        UiDecor.dp(ctx, 2), UiDecor.dp(ctx, 18));
                llp.gravity = Gravity.CENTER_HORIZONTAL;
                llp.topMargin = UiDecor.dp(ctx, 2);
                line.setLayoutParams(llp);
                rail.addView(line);
            }

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView stepTv = DisplayStyle.body(ctx, (i + 1) + ".  " + nz(steps.get(i)), 15);
            if (isCurrent) {
                stepTv.setTextColor(UiDecor.color(ctx, R.color.primary_bright));
                stepTv.setTypeface(Typeface.DEFAULT_BOLD);
            } else if (done) {
                stepTv.setTextColor(UiDecor.color(ctx, R.color.accent));
            }
            textCol.addView(stepTv);
            if (isCurrent) {
                TextView badge = new TextView(ctx);
                badge.setText("当前步骤");
                badge.setTextColor(DisplayStyle.INK);
                badge.setTextSize(11);
                badge.setPadding(UiDecor.dp(ctx, 8), UiDecor.dp(ctx, 2), UiDecor.dp(ctx, 8), UiDecor.dp(ctx, 2));
                badge.setBackground(DisplayStyle.pill(DisplayStyle.CARD_CYAN, 0));
                LinearLayout.LayoutParams bap = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bap.topMargin = UiDecor.dp(ctx, 4);
                badge.setLayoutParams(bap);
                textCol.addView(badge);
            }
            row.addView(rail);
            row.addView(textCol);
            card.addView(row);
        }
        return card;
    }

    private static View flowStepper(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, s.title));
        }
        List<String> steps = s.steps;
        if (steps == null || steps.isEmpty()) {
            return card;
        }
        int current = s.current == null ? -1 : s.current;
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < steps.size(); i++) {
            boolean isCurrent = i == current;
            boolean done = current >= 0 && i < current;
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView num = new TextView(ctx);
            num.setText(String.valueOf(i + 1));
            num.setTextColor(isCurrent || done ? DisplayStyle.INK : UiDecor.color(ctx, R.color.text));
            num.setTextSize(13);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setGravity(Gravity.CENTER);
            int sz = UiDecor.dp(ctx, 28);
            int fill = done ? DisplayStyle.WARM : (isCurrent ? DisplayStyle.CARD_CYAN : 0xFF1A2A3A);
            num.setBackground(DisplayStyle.circle(fill, sz));
            num.setMinWidth(sz);
            num.setMinHeight(sz);
            cell.addView(num);
            TextView lab = DisplayStyle.body(ctx, nz(steps.get(i)), 12);
            lab.setGravity(Gravity.CENTER);
            lab.setPadding(0, UiDecor.dp(ctx, 6), 0, 0);
            if (isCurrent) {
                lab.setTextColor(UiDecor.color(ctx, R.color.primary_bright));
                lab.setTypeface(Typeface.DEFAULT_BOLD);
            }
            cell.addView(lab);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    UiDecor.dp(ctx, 96), ViewGroup.LayoutParams.WRAP_CONTENT);
            cell.setLayoutParams(clp);
            row.addView(cell);
            if (i < steps.size() - 1) {
                View line = new View(ctx);
                GradientDrawable lg = new GradientDrawable();
                lg.setColor(done ? DisplayStyle.WARM : 0x554A6070);
                line.setBackground(lg);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        UiDecor.dp(ctx, 20), UiDecor.dp(ctx, 2));
                llp.gravity = Gravity.CENTER_VERTICAL;
                llp.bottomMargin = UiDecor.dp(ctx, 22);
                line.setLayoutParams(llp);
                row.addView(line);
            }
        }
        hsv.addView(row);
        card.addView(hsv);
        return card;
    }

    private static View flowBlock(Context ctx, DisplaySection s, DisplayStyle.Skin skin) {
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(s.title)) {
            card.addView(DisplayStyle.pillTitle(ctx, s.title));
        }
        List<String> steps = s.steps;
        if (steps == null) {
            return card;
        }
        int current = s.current == null ? -1 : s.current;
        for (int i = 0; i < steps.size(); i++) {
            boolean isCurrent = i == current;
            boolean done = current >= 0 && i < current;
            LinearLayout tile = DisplayStyle.tile(ctx);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                tlp.topMargin = UiDecor.dp(ctx, 8);
            }
            tile.setLayoutParams(tlp);
            tile.setOrientation(LinearLayout.HORIZONTAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            TextView num = new TextView(ctx);
            num.setText(String.format("%02d", i + 1));
            num.setTextColor(isCurrent || done ? DisplayStyle.INK : UiDecor.color(ctx, R.color.primary_bright));
            num.setTextSize(16);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setGravity(Gravity.CENTER);
            int box = UiDecor.dp(ctx, 36);
            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setCornerRadius(UiDecor.dp(ctx, 10));
            boxBg.setColor(done ? DisplayStyle.WARM : (isCurrent ? DisplayStyle.CARD_CYAN : 0x331B6BDB));
            num.setBackground(boxBg);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(box, box);
            nlp.rightMargin = UiDecor.dp(ctx, 12);
            num.setLayoutParams(nlp);
            TextView lab = DisplayStyle.body(ctx, nz(steps.get(i)), 15);
            if (isCurrent) {
                lab.setTypeface(Typeface.DEFAULT_BOLD);
                lab.setTextColor(UiDecor.color(ctx, R.color.primary_bright));
            }
            lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tile.addView(num);
            tile.addView(lab);
            card.addView(tile);
        }
        return card;
    }

    static LinearLayout wrapFormOrQr(Context ctx, DisplayStyle.Skin skin, boolean banner, String title) {
        if (banner && notEmpty(title)) {
            LinearLayout card = DisplayStyle.card(ctx, skin, false);
            card.addView(DisplayStyle.bannerHeader(ctx, title));
            return card;
        }
        LinearLayout card = DisplayStyle.card(ctx, skin);
        if (notEmpty(title)) {
            card.addView(DisplayStyle.sectionTitle(ctx, title));
        }
        return card;
    }

    static void padBody(Context ctx, View child) {
        int p = UiDecor.dp(ctx, 16);
        child.setPadding(p, p, p, p);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
