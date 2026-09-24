package com.hr.digitalhuman.ui.display;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hr.digitalhuman.R;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.ui.UiDecor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 kind 渲染智能体展示区块。模型只给展示意图，SDK 在此落地成组件。
 */
public final class SectionFactory {

    public interface FormListener {
        void onSubmit(String summary, Map<String, String> values);
    }

    /** 区块操作（如二维码旁「查看简历」） */
    public interface ActionListener {
        void onAction(DisplaySection section);
    }

    private SectionFactory() {
    }

    public static View create(Context ctx, DisplaySection section, FormListener formListener) {
        return create(ctx, section, formListener, null, new SectionStylePicker());
    }

    public static View create(Context ctx, DisplaySection section, FormListener formListener,
                              SectionStylePicker picker) {
        return create(ctx, section, formListener, null, picker);
    }

    public static View create(Context ctx, DisplaySection section, FormListener formListener,
                              ActionListener actionListener, SectionStylePicker picker) {
        if (section == null) {
            return null;
        }
        SectionStylePicker p = picker == null ? new SectionStylePicker() : picker;
        String kind = section.kind == null ? DisplaySection.TEXT : section.kind;
        DisplayStyle.Skin skin = p.skin();
        switch (kind) {
            case DisplaySection.LIST:
                return SectionLooks.list(ctx, section, p.next(kind, 4), skin);
            case DisplaySection.TABLE:
                return SectionLooks.table(ctx, section, p.next(kind, 3), skin);
            case DisplaySection.FLOW:
                return SectionLooks.flow(ctx, section, p.next(kind, 3), skin);
            case DisplaySection.FORM:
                return formCard(ctx, section, formListener, p.next(kind, 2) == 1, skin);
            case DisplaySection.QRCODE:
                return qrCard(ctx, section, p.next(kind, 2) == 1, skin, actionListener);
            case DisplaySection.TEXT:
            default:
                return SectionLooks.text(ctx, section, p.next(DisplaySection.TEXT, 3), skin);
        }
    }

    private static View formCard(Context ctx, DisplaySection section, FormListener listener,
                                 boolean banner, DisplayStyle.Skin skin) {
        JsonObject schema = section.schema;
        String title = section.title;
        if (title == null && schema != null) {
            title = str(schema, "title");
        }
        LinearLayout card = SectionLooks.wrapFormOrQr(ctx, skin, banner, title);
        LinearLayout body = card;
        if (banner && notEmpty(title)) {
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            SectionLooks.padBody(ctx, body);
            card.addView(body);
        }
        if (schema == null) {
            body.addView(DisplayStyle.body(ctx, "表单配置缺失", 14));
            return card;
        }

        JsonObject properties = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        List<String> required = new ArrayList<>();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement el : schema.getAsJsonArray("required")) {
                required.add(el.getAsString());
            }
        }

        final Map<String, FieldHolder> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : properties.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject prop = e.getValue().getAsJsonObject();
            String key = e.getKey();
            String label = firstNonEmpty(str(prop, "title"), key);
            boolean req = required.contains(key);
            TextView lab = new TextView(ctx);
            lab.setText(label + (req ? " *" : ""));
            lab.setTextColor(UiDecor.color(ctx, R.color.text_dim));
            lab.setTextSize(13);
            lab.setPadding(0, UiDecor.dp(ctx, 8), 0, UiDecor.dp(ctx, 6));
            body.addView(lab);

            FieldHolder holder = new FieldHolder();
            holder.key = key;
            holder.label = label;
            holder.required = req;
            List<String> enums = enumValues(prop);
            if (!enums.isEmpty()) {
                holder.enumValues = enums;
                holder.chipRow = new LinearLayout(ctx);
                holder.chipRow.setOrientation(LinearLayout.HORIZONTAL);
                HorizontalScrollView hsv = new HorizontalScrollView(ctx);
                hsv.setHorizontalScrollBarEnabled(false);
                for (String opt : enums) {
                    TextView chip = UiDecor.chip(ctx, opt);
                    chip.setOnClickListener(v -> {
                        holder.value = opt;
                        tintChips(ctx, holder.chipRow, opt);
                        // 单选枚举：点选项即提交；多字段时（如简历名称+确认）也汇总全部字段后提交
                        if (listener == null) {
                            return;
                        }
                        if (fields.size() == 1 || "answer".equals(holder.key)) {
                            Map<String, String> values = new LinkedHashMap<>();
                            StringBuilder summary = new StringBuilder();
                            for (FieldHolder h : fields.values()) {
                                String val = h == holder ? opt : h.read();
                                if (h.required && (val == null || val.trim().isEmpty())) {
                                    android.widget.Toast.makeText(ctx,
                                            "请先填写「" + h.label + "」",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    if (h.edit != null) {
                                        h.edit.requestFocus();
                                    }
                                    return;
                                }
                                if (val != null && !val.trim().isEmpty()) {
                                    values.put(h.key, val.trim());
                                    if (summary.length() > 0) {
                                        summary.append("，");
                                    }
                                    summary.append(h.label).append("：").append(val.trim());
                                }
                            }
                            listener.onSubmit(summary.toString(), values);
                        }
                    });
                    holder.chipRow.addView(chip);
                }
                hsv.addView(holder.chipRow);
                body.addView(hsv);
            } else if ("boolean".equals(str(prop, "type"))) {
                holder.enumValues = new ArrayList<>();
                holder.enumValues.add("是");
                holder.enumValues.add("否");
                holder.chipRow = new LinearLayout(ctx);
                holder.chipRow.setOrientation(LinearLayout.HORIZONTAL);
                for (String opt : holder.enumValues) {
                    TextView chip = UiDecor.chip(ctx, opt);
                    chip.setOnClickListener(v -> {
                        holder.value = opt;
                        tintChips(ctx, holder.chipRow, opt);
                    });
                    holder.chipRow.addView(chip);
                }
                body.addView(holder.chipRow);
            } else {
                EditText et = new EditText(ctx);
                et.setHint(firstNonEmpty(str(prop, "description"), "请输入"));
                et.setTextColor(UiDecor.color(ctx, R.color.text));
                et.setHintTextColor(UiDecor.color(ctx, R.color.text_dim));
                et.setTextSize(15);
                et.setSingleLine(true);
                et.setFocusable(true);
                et.setFocusableInTouchMode(true);
                if ("number".equals(str(prop, "type")) || "integer".equals(str(prop, "type"))) {
                    et.setInputType(InputType.TYPE_CLASS_NUMBER);
                } else {
                    et.setInputType(InputType.TYPE_CLASS_TEXT);
                }
                GradientDrawable fieldBg = new GradientDrawable();
                fieldBg.setColor(0x3312202E);
                fieldBg.setCornerRadius(UiDecor.dp(ctx, 10));
                fieldBg.setStroke(UiDecor.dp(ctx, 1), 0x551B6BDB);
                et.setBackground(fieldBg);
                int p = UiDecor.dp(ctx, 10);
                et.setPadding(p, p, p, p);
                String def = firstNonEmpty(str(prop, "default"), str(prop, "defaultValue"));
                if (notEmpty(def)) {
                    et.setText(def);
                    holder.value = def;
                }
                holder.edit = et;
                body.addView(et);
            }
            fields.put(key, holder);
        }

        TextView submit = new TextView(ctx);
        submit.setText("确认发送");
        submit.setTextColor(0xFFFFFFFF);
        submit.setTextSize(15);
        submit.setTypeface(Typeface.DEFAULT_BOLD);
        submit.setGravity(Gravity.CENTER);
        submit.setBackgroundResource(R.drawable.bg_btn_primary);
        int vp = UiDecor.dp(ctx, 12);
        submit.setPadding(UiDecor.dp(ctx, 24), vp, UiDecor.dp(ctx, 24), vp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiDecor.dp(ctx, 16);
        slp.gravity = Gravity.END;
        submit.setLayoutParams(slp);
        submit.setClickable(true);
        submit.setFocusable(true);
        submit.setOnClickListener(v -> {
            Map<String, String> values = new LinkedHashMap<>();
            StringBuilder summary = new StringBuilder();
            for (FieldHolder h : fields.values()) {
                String val = h.read();
                if (h.required && (val == null || val.trim().isEmpty())) {
                    android.widget.Toast.makeText(ctx,
                            "请先填写「" + h.label + "」", android.widget.Toast.LENGTH_SHORT).show();
                    if (h.edit != null) {
                        h.edit.requestFocus();
                    }
                    return;
                }
                if (val != null && !val.trim().isEmpty()) {
                    values.put(h.key, val.trim());
                    if (summary.length() > 0) {
                        summary.append("，");
                    }
                    summary.append(h.label).append("：").append(val.trim());
                }
            }
            if (values.isEmpty()) {
                android.widget.Toast.makeText(ctx, "请先填写后再发送", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (listener != null) {
                listener.onSubmit(summary.toString(), values);
            }
        });
        body.addView(submit);
        return card;
    }

    private static View qrCard(Context ctx, DisplaySection section, boolean banner, DisplayStyle.Skin skin,
                               ActionListener actionListener) {
        LinearLayout card = SectionLooks.wrapFormOrQr(ctx, skin, banner, section.title);
        LinearLayout body = card;
        if (banner && notEmpty(section.title)) {
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setGravity(Gravity.CENTER_HORIZONTAL);
            SectionLooks.padBody(ctx, body);
            card.addView(body);
        } else {
            card.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        // 二维码 + 旁侧操作按钮（查看简历）
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowLp);

        int size = UiDecor.dp(ctx, 180);
        android.graphics.Bitmap bmp = QrBitmaps.encode(section.text, size);
        ImageView iv = new ImageView(ctx);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
        iv.setLayoutParams(ilp);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            iv.setBackgroundResource(R.drawable.bg_qr_box);
            int pad = UiDecor.dp(ctx, 8);
            iv.setPadding(pad, pad, pad, pad);
        } else {
            iv.setBackgroundResource(R.drawable.bg_qr_box);
        }
        row.addView(iv);

        if (notEmpty(section.actionLabel)) {
            LinearLayout side = new LinearLayout(ctx);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sideLp.leftMargin = UiDecor.dp(ctx, 16);
            side.setLayoutParams(sideLp);

            TextView hint = DisplayStyle.body(ctx, "本机查看 PDF 简历", 13);
            hint.setTextColor(UiDecor.color(ctx, R.color.text_dim));
            hint.setGravity(Gravity.CENTER);
            side.addView(hint);

            TextView btn = new TextView(ctx);
            btn.setText(section.actionLabel.trim());
            btn.setTextColor(UiDecor.color(ctx, R.color.white));
            btn.setTextSize(16);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.bg_btn_primary);
            int hp = UiDecor.dp(ctx, 18);
            int vp = UiDecor.dp(ctx, 14);
            btn.setPadding(hp, vp, hp, vp);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = UiDecor.dp(ctx, 12);
            btn.setLayoutParams(btnLp);
            btn.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onAction(section);
                }
            });
            side.addView(btn);

            TextView sub = DisplayStyle.body(ctx, "手机可扫左侧二维码下载", 12);
            sub.setTextColor(UiDecor.color(ctx, R.color.text_dim));
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, UiDecor.dp(ctx, 10), 0, 0);
            side.addView(sub);

            row.addView(side);
        }

        body.addView(row);

        String cap = firstNonEmpty(section.caption, notEmpty(section.actionLabel) ? null : section.text);
        if (notEmpty(cap)) {
            TextView tv = DisplayStyle.body(ctx, cap, 13);
            tv.setTextColor(UiDecor.color(ctx, R.color.text_dim));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, UiDecor.dp(ctx, 10), 0, 0);
            body.addView(tv);
        }
        return card;
    }

    private static void tintChips(Context ctx, LinearLayout row, String selected) {
        if (row == null) {
            return;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) {
                continue;
            }
            TextView chip = (TextView) child;
            boolean on = selected != null && selected.equals(chip.getText().toString());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiDecor.dp(ctx, 20));
            if (on) {
                bg.setColor(DisplayStyle.CARD_CYAN);
                chip.setTextColor(0xFF071018);
            } else {
                bg.setColor(0xFF1A2A3A);
                bg.setStroke(UiDecor.dp(ctx, 1), 0x551B6BDB);
                chip.setTextColor(UiDecor.color(ctx, R.color.text));
            }
            chip.setBackground(bg);
        }
    }

    private static List<String> enumValues(JsonObject prop) {
        List<String> list = new ArrayList<>();
        if (prop == null || !prop.has("enum") || !prop.get("enum").isJsonArray()) {
            return list;
        }
        JsonArray arr = prop.getAsJsonArray("enum");
        for (JsonElement el : arr) {
            if (el != null && !el.isJsonNull()) {
                list.add(el.getAsString());
            }
        }
        return list;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        return b;
    }

    private static class FieldHolder {
        String key;
        String label;
        boolean required;
        String value;
        List<String> enumValues;
        LinearLayout chipRow;
        EditText edit;

        String read() {
            if (edit != null && edit.getText() != null) {
                return edit.getText().toString();
            }
            return value;
        }
    }
}
