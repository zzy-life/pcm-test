package com.hr.digitalhuman.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hr.digitalhuman.R;

import java.util.Locale;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;

/** 原生画像报告；外部数据仅作为文本或图形输入，不加载简历链接和远程资源。 */
final class ResumePortraitReportView extends LinearLayout {
    private final JsonObject profile;
    private final String markdown;
    private final LinearLayout pages;
    private final Button[] tabs = new Button[3];
    private int selectedTab;
    private static final int BLUE = 0xFF68ADFF;
    private static final String[] DIMENSIONS = {"专业力", "领导力", "创新力", "影响力", "成长力", "适应力"};

    ResumePortraitReportView(Context context, JsonObject profile, String content, int selectedTab) {
        super(context);
        this.profile = profile;
        this.markdown = content == null || content.trim().isEmpty() ? buildMarkdown() : content;
        setOrientation(VERTICAL);
        LinearLayout navigation = new LinearLayout(context);
        String[] names = {"概览", "能力仪表盘", "完整报告"};
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            tabs[i] = button(names[i]);
            tabs[i].setOnClickListener(v -> select(tab));
            navigation.addView(tabs[i], new LayoutParams(0, dp(36), 1));
        }
        addView(navigation);
        pages = column();
        addView(pages, new LayoutParams(-1, 0, 1));
        select(Math.max(0, Math.min(2, selectedTab)));
    }

    int getSelectedTab() { return selectedTab; }

    private void select(int tab) {
        selectedTab = tab;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == tab ? BLUE : UiDecor.color(getContext(), R.color.text));
            tabs[i].setSelected(i == tab);
        }
        pages.removeAllViews();
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout panel = column();
        panel.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        pages.addView(scroll, new LayoutParams(-1, -1));
        if (tab == 0) overview(panel);
        else if (tab == 1) ability(panel);
        else fullReport(panel);
    }

    private void overview(LinearLayout panel) {
        JsonObject basic = object(profile, "basic_info");
        LinearLayout identity = card(panel, "人才能力画像");
        LinearLayout row = new LinearLayout(getContext());
        LinearLayout info = column();
        text(info, str(basic, "name"), 24, true);
        text(info, str(basic, "current_position") + " · " + str(basic, "city")
                + " · " + numberOrMissing(basic, "years_of_experience", " 年经验"), 13, false);
        text(info, str(basic, "education") + " · " + str(basic, "school")
                + " · " + str(basic, "major"), 13, false);
        text(info, str(basic, "personnel_category") + " · " + str(basic, "current_industry"), 13, false);
        row.addView(info, new LayoutParams(0, -2, 1));
        TextView score = text(null, decimal(num(profile, "overall_weighted_score")) + " / 5.00\n综合加权分", 22, true);
        score.setTextColor(BLUE);
        score.setGravity(Gravity.CENTER);
        row.addView(score, new LayoutParams(dp(160), -1));
        identity.addView(row);
        LinearLayout summary = card(panel, "画像摘要");
        text(summary, str(profile, "summary"), 15, false);
        JsonObject overall = object(profile, "overall");
        text(summary, "综合定位：" + str(overall, "positioning") + "\n综合得分："
                + numberOrMissing(overall, "score", "") + " · 置信度：" + confidence(str(overall, "confidence")), 13, false);
        JsonArray dimensions = array(profile, "dimensions");
        int highest = 0, confident = 0;
        for (JsonElement item : dimensions) {
            JsonObject dimension = asObject(item);
            highest = Math.max(highest, level(str(dimension, "level")));
            if ("high".equals(str(dimension, "confidence"))) confident++;
        }
        LinearLayout stats = card(panel, "能力概况");
        text(stats, "从业经验  " + numberOrMissing(basic, "years_of_experience", " 年")
                + "     最高能力等级  " + (highest == 0 ? "未提供" : "L" + highest)
                + "     高置信维度  " + confident + " / " + dimensions.size(), 14, false);
        LinearLayout tags = card(panel, "人才标签");
        JsonObject tagData = object(profile, "tags");
        String[] keys = {"identity", "skill", "soft_skill", "achievement", "potential"};
        String[] names = {"身份定位", "专业技能", "软技能", "成就", "潜力"};
        for (int i = 0; i < keys.length; i++) text(tags, names[i] + "：" + join(array(tagData, keys[i])), 13, false);
        jobs(panel, false);
        LinearLayout task = card(panel, "任务信息");
        text(task, "生成时间：" + str(profile, "generated_at") + "    画像版本：" + str(profile, "profile_revision"), 12, false);
    }

    private void ability(LinearLayout panel) {
        LinearLayout dashboard = new LinearLayout(getContext());
        panel.addView(dashboard, UiDecor.cardLp(getContext(), 8));
        LinearLayout radar = column();
        UiDecor.styleCard(getContext(), radar);
        text(radar, "六维能力雷达", 16, true);
        text(radar, "能力等级 L1–L5", 12, false);
        radar.addView(new RadarView(getContext()), new LayoutParams(-1, dp(245)));
        dashboard.addView(radar, new LayoutParams(0, -2, 1));
        LinearLayout evidence = column();
        UiDecor.styleCard(getContext(), evidence);
        LayoutParams evidenceLp = new LayoutParams(0, -2, 1.4f);
        evidenceLp.leftMargin = dp(8);
        dashboard.addView(evidence, evidenceLp);
        text(evidence, "维度评分与事实依据", 16, true);
        for (JsonElement item : array(profile, "dimensions")) {
            JsonObject dimension = asObject(item);
            String name = str(dimension, "name"), abilityLevel = str(dimension, "level");
            TextView heading = text(evidence, name + "  " + abilityLevel + "  ·  "
                    + confidence(str(dimension, "confidence")) + "置信度  ▾", 14, true);
            bar(evidence, level(abilityLevel) * 20);
            text(evidence, "权重 " + percent(num(dimension, "weight")) + " · 加权分 "
                    + decimal(num(dimension, "weighted_score")), 12, false);
            TextView facts = text(evidence, bullets(array(dimension, "evidence")), 13, false);
            facts.setVisibility(GONE);
            heading.setContentDescription(name + "，点击展开或收起事实依据");
            heading.setOnClickListener(v -> facts.setVisibility(facts.getVisibility() == GONE ? VISIBLE : GONE));
        }
        LinearLayout capabilities = card(panel, "核心能力");
        JsonArray items = array(profile, "capabilities");
        if (items.size() == 0) text(capabilities, "暂无核心能力数据", 13, false);
        for (JsonElement item : items) {
            JsonObject value = asObject(item);
            text(capabilities, str(value, "name") + " · " + category(str(value, "category"))
                    + " · " + str(value, "level") + " · " + decimal(num(value, "score")), 14, true);
            text(capabilities, confidence(str(value, "confidence")) + "置信度 · "
                    + (bool(value, "verified") ? "已有证据验证" : "待验证"), 12, false);
            text(capabilities, bullets(array(value, "evidence")), 13, false);
        }
        jobs(panel, true);
    }

    private void jobs(LinearLayout panel, boolean details) {
        LinearLayout jobs = card(panel, "岗位适配方向");
        JsonArray list = array(profile, "fit_directions");
        if (list.size() == 0) text(jobs, "暂无岗位适配数据", 13, false);
        for (JsonElement item : list) {
            JsonObject job = asObject(item);
            text(jobs, str(job, "priority") + " · " + str(job, "position") + "    "
                    + percent(num(job, "fit_score")), 15, true);
            // 岗位适配度为 0–1，不能使用雷达图的 L1–L5 分数。
            bar(jobs, (int) Math.round(num(job, "fit_score") * 100));
            if (details) {
                text(jobs, "胜任依据\n" + bullets(array(job, "reasons")), 13, false);
                text(jobs, "差距与风险\n" + bullets(array(job, "gaps")), 13, false);
            }
        }
    }

    private void fullReport(LinearLayout panel) {
        LinearLayout card = card(panel, "完整报告");
        LinearLayout actions = new LinearLayout(getContext());
        Button reading = button("阅读"), source = button("原文"), copy = button("复制报告");
        actions.addView(reading, new LayoutParams(0, dp(36), 1));
        actions.addView(source, new LayoutParams(0, dp(36), 1));
        actions.addView(copy, new LayoutParams(0, dp(36), 1));
        card.addView(actions);
        TextView report = text(card, "", 14, false);
        report.setTextIsSelectable(true);
        Markwon markwon = Markwon.builder(getContext()).usePlugin(TablePlugin.create(getContext()))
                .usePlugin(StrikethroughPlugin.create()).build();
        reading.setOnClickListener(v -> {
            report.setTypeface(Typeface.DEFAULT);
            markwon.setMarkdown(report, markdown);
        });
        source.setOnClickListener(v -> {
            report.setTypeface(Typeface.MONOSPACE);
            report.setText(markdown);
        });
        copy.setOnClickListener(v -> {
            if (markdown.length() > 100000) {
                Toast.makeText(getContext(), "报告过长，不能复制", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("人才画像报告", markdown));
                Toast.makeText(getContext(), "报告已复制，请注意保护个人信息", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(getContext(), "复制失败，请重试", Toast.LENGTH_SHORT).show();
            }
        });
        markwon.setMarkdown(report, markdown);
    }

    private String buildMarkdown() {
        StringBuilder out = new StringBuilder("# 人才能力画像报告\n\n");
        section(out, "画像摘要", str(profile, "summary"));
        fields(out, "基本信息", object(profile, "basic_info"),
                new String[]{"name", "gender", "education", "school", "major", "years_of_experience", "current_position", "current_industry", "city", "personnel_category"},
                new String[]{"姓名", "性别", "学历", "毕业院校", "专业", "工作年限", "当前职位", "当前行业", "城市", "人员类别"});
        section(out, "综合评价", "综合加权分：" + decimal(num(profile, "overall_weighted_score")) + " / 5.00\n\n"
                + "综合得分：" + numberOrMissing(object(profile, "overall"), "score", "") + "\n\n"
                + "综合定位：" + str(object(profile, "overall"), "positioning") + "\n\n置信度："
                + confidence(str(object(profile, "overall"), "confidence")));
        out.append("## 六维能力评估\n\n");
        for (JsonElement item : array(profile, "dimensions")) {
            JsonObject dimension = asObject(item);
            out.append("### ").append(str(dimension, "name")).append(" · ").append(str(dimension, "level"))
                    .append("\n\n权重：").append(percent(num(dimension, "weight"))).append(" · 加权分：")
                    .append(decimal(num(dimension, "weighted_score"))).append(" · 置信度：")
                    .append(confidence(str(dimension, "confidence"))).append("\n\n")
                    .append(bullets(array(dimension, "evidence"))).append("\n\n");
        }
        out.append("## 核心能力\n\n");
        for (JsonElement item : array(profile, "capabilities")) {
            JsonObject value = asObject(item);
            section(out, str(value, "name"), category(str(value, "category")) + " · " + str(value, "level")
                    + " · 得分 " + decimal(num(value, "score")) + " · " + confidence(str(value, "confidence"))
                    + "置信度 · " + (bool(value, "verified") ? "已有证据验证" : "待验证")
                    + "\n\n" + bullets(array(value, "evidence")));
        }
        fields(out, "人才标签", object(profile, "tags"),
                new String[]{"identity", "skill", "soft_skill", "achievement", "potential"},
                new String[]{"身份定位", "专业技能", "软技能", "成就", "潜力"});
        out.append("## 岗位适配方向\n\n");
        for (JsonElement item : array(profile, "fit_directions")) {
            JsonObject job = asObject(item);
            section(out, str(job, "priority") + " · " + str(job, "position"), "适配度：" + percent(num(job, "fit_score"))
                    + "\n\n胜任依据\n" + bullets(array(job, "reasons")) + "\n\n差距与风险\n" + bullets(array(job, "gaps")));
        }
        listFields(out, "发展建议", array(object(profile, "development"), "weaknesses"),
                new String[]{"area", "current_status", "action", "severity"}, new String[]{"领域", "当前情况", "建议行动", "建议级别"});
        listFields(out, "数据缺口", array(profile, "data_gaps"),
                new String[]{"category", "impact", "confidence_adjustment"}, new String[]{"类别", "评估影响", "置信度调整"});
        JsonObject military = object(profile, "military_service");
        if (military.size() > 0) fields(out, "服役经历", military,
                new String[]{"branch", "rank", "service_years", "military_specialty", "awards", "discharge_type", "transferable_capabilities"},
                new String[]{"军种", "军衔", "服役年限", "军事专业", "奖励", "退役类型", "可迁移能力"});
        JsonObject training = object(profile, "training_map");
        listFields(out, "已完成培训", array(training, "completed"), new String[]{"name", "outcome"}, new String[]{"培训", "成果"});
        listFields(out, "培训规划", array(training, "planned"), new String[]{"name", "relevance"}, new String[]{"培训", "相关性"});
        fields(out, "职业稳定性", object(profile, "stability"),
                new String[]{"average_job_time", "average_job_type_time", "average_industry_time", "long_job_time_num", "short_job_time_num", "job_stability"},
                new String[]{"平均任职时长", "平均同类岗位时长", "平均行业时长", "长期任职次数", "短期任职次数", "职业稳定性"});
        listFields(out, "适配行业", array(profile, "industries"), new String[]{"name", "reason"}, new String[]{"行业", "理由"});
        section(out, "发展路径", translated(str(profile, "development_path")));
        out.append("画像版本：").append(str(profile, "profile_revision")).append("\n\n生成时间：").append(str(profile, "generated_at"));
        return out.toString();
    }

    private static void section(StringBuilder out, String title, String content) {
        out.append("## ").append(title).append("\n\n").append(content).append("\n\n");
    }

    private static void fields(StringBuilder out, String title, JsonObject value, String[] keys, String[] labels) {
        out.append("## ").append(title).append("\n\n");
        for (int i = 0; i < keys.length; i++) {
            JsonElement field = value.get(keys[i]);
            String text = field != null && field.isJsonArray() ? join(field.getAsJsonArray()) : str(value, keys[i]);
            out.append("- ").append(labels[i]).append("：").append(translated(text)).append('\n');
        }
        out.append('\n');
    }

    private static void listFields(StringBuilder out, String title, JsonArray list, String[] keys, String[] labels) {
        out.append("## ").append(title).append("\n\n");
        if (list.size() == 0) out.append("暂无相关数据\n\n");
        for (JsonElement item : list) fields(out, title, asObject(item), keys, labels);
    }

    private final class RadarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadarView(Context context) { super(context); setContentDescription("六维能力雷达图，详细等级见右侧维度列表"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.max(0, Math.min(cx - dp(38), cy - dp(25)));
            paint.setStrokeWidth(dp(1));
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xFF3D5675);
            for (int ring = 1; ring <= 5; ring++) canvas.drawPath(polygon(cx, cy, radius * ring / 5f, null), paint);
            for (int i = 0; i < 6; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 3;
                canvas.drawLine(cx, cy, cx + (float) Math.cos(angle) * radius,
                        cy + (float) Math.sin(angle) * radius, paint);
            }
            float[] values = new float[6];
            for (JsonElement item : array(profile, "dimensions")) {
                JsonObject dimension = asObject(item);
                for (int i = 0; i < DIMENSIONS.length; i++) {
                    // 雷达使用等级比例，不使用权重或加权得分。
                    if (DIMENSIONS[i].equals(str(dimension, "name"))) values[i] = level(str(dimension, "level")) / 5f;
                }
            }
            Path scores = polygon(cx, cy, radius, values);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x4468ADFF);
            canvas.drawPath(scores, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(BLUE);
            paint.setStrokeWidth(dp(2));
            canvas.drawPath(scores, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(UiDecor.color(getContext(), R.color.text));
            paint.setTextSize(dp(12));
            paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 6; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 3;
                canvas.drawText(DIMENSIONS[i], cx + (float) Math.cos(angle) * (radius + dp(23)),
                        cy + (float) Math.sin(angle) * (radius + dp(18)) + dp(4), paint);
            }
        }
        private Path polygon(float cx, float cy, float radius, float[] values) {
            Path path = new Path();
            for (int i = 0; i < 6; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 3;
                float r = radius * (values == null ? 1 : values[i]);
                float x = cx + (float) Math.cos(angle) * r, y = cy + (float) Math.sin(angle) * r;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            return path;
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(VERTICAL);
        return view;
    }
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = column();
        UiDecor.styleCard(getContext(), card);
        card.setPadding(dp(12), dp(8), dp(12), dp(10));
        text(card, title, 16, true);
        parent.addView(card, UiDecor.cardLp(getContext(), 8));
        return card;
    }
    private TextView text(LinearLayout parent, String value, int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(UiDecor.color(getContext(), R.color.text));
        view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(4));
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        if (parent != null) parent.addView(view, new LayoutParams(-1, -2));
        return view;
    }
    private Button button(String label) {
        Button button = UiDecor.button(getContext(), label, false);
        button.setTextSize(13);
        button.setMinimumHeight(dp(32));
        button.setMinHeight(dp(32));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }
    private void bar(LinearLayout parent, int percent) {
        ProgressBar view = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        view.setMax(100);
        view.setProgress(Math.max(0, Math.min(100, percent)));
        view.getProgressDrawable().mutate().setColorFilter(BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
        parent.addView(view, new LayoutParams(-1, dp(8)));
    }
    private int dp(int value) { return UiDecor.dp(getContext(), value); }
    private static JsonObject object(JsonObject value, String key) { return asObject(value.get(key)); }
    private static JsonObject asObject(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }
    private static JsonArray array(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field != null && field.isJsonArray() ? field.getAsJsonArray() : new JsonArray();
    }
    private static String str(JsonObject value, String key) {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive()) return "未提供";
        String text = field.getAsString().trim();
        return text.isEmpty() || "null".equals(text) ? "未提供" : text;
    }
    private static double num(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isNumber() ? field.getAsDouble() : 0;
    }
    private static boolean bool(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isBoolean() && field.getAsBoolean();
    }
    private static String numberOrMissing(JsonObject value, String key, String suffix) {
        String text = str(value, key);
        return "未提供".equals(text) ? text : text + suffix;
    }
    private static String join(JsonArray list) {
        StringBuilder out = new StringBuilder();
        for (JsonElement item : list) {
            if (!item.isJsonPrimitive()) continue;
            if (out.length() > 0) out.append("、");
            out.append(item.getAsString());
        }
        return out.length() == 0 ? "暂无" : out.toString();
    }
    private static String bullets(JsonArray list) {
        StringBuilder out = new StringBuilder();
        for (JsonElement item : list) {
            if (item.isJsonPrimitive()) out.append("- ").append(item.getAsString()).append('\n');
        }
        return out.length() == 0 ? "暂无相关数据" : out.toString().trim();
    }
    private static int level(String value) { return value.matches("L[1-5]") ? value.charAt(1) - '0' : 0; }
    private static String decimal(double value) { return String.format(Locale.CHINA, "%.2f", value); }
    private static String percent(double value) { return String.format(Locale.CHINA, "%.0f%%", value * 100); }
    private static String confidence(String value) {
        if ("high".equals(value)) return "高";
        if ("medium".equals(value)) return "中";
        if ("low".equals(value)) return "低";
        return "未提供";
    }
    private static String category(String value) {
        if ("hard".equals(value)) return "专业技能";
        if ("soft".equals(value)) return "软技能";
        if ("transferable".equals(value)) return "可迁移能力";
        return "未分类";
    }
    private static String translated(String value) {
        if ("expert".equals(value)) return "专家型";
        if ("composite".equals(value)) return "复合型";
        if ("management".equals(value)) return "管理型";
        if ("critical".equals(value)) return "优先提升";
        if ("suggested".equals(value)) return "建议提升";
        return value;
    }
}
