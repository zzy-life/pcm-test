package com.hr.digitalhuman.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hr.digitalhuman.R;
import com.hr.digitalhuman.agents.AgentApiClient;
import com.hr.digitalhuman.agents.AgentDefinition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 单次画像任务：流式生成后自动取报告，重建只恢复报告查询，不重复提交简历。 */
public class ResumePortraitActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private AgentApiClient client;
    private File cache;
    private JsonObject state;
    private LinearLayout body;
    private TextView status;
    private ProgressBar progress;
    private ResumePortraitReportView reportView;
    private boolean destroyed, running;
    private int generation, attempts;
    private android.app.Dialog generationDialog;
    private ProgressBar generationProgress;
    private final TextView[] stepTitles = new TextView[4];
    private final TextView[] stepDescriptions = new TextView[4];
    private final TextView[] stepMarkers = new TextView[4];
    private final LinearLayout[] stepRows = new LinearLayout[4];
    private long generationStartedAt;
    private int[] stepDurations;
    private boolean completing;
    private static final String[] STEP_TITLES = {"解析简历结构", "提取六维能力特征",
            "测算能力匹配度", "构建标签与发展路径"};
    private static final String[] STEP_DESCRIPTIONS = {"识别教育、经历、项目与成果", "从经历证据中建立能力坐标",
            "对照能力模型评估适配方向", "生成优势标签与提升建议"};

    private final Runnable updateGeneration = new Runnable() {
        @Override public void run() {
            if (destroyed || generationDialog == null || !generationDialog.isShowing() || completing) return;
            int elapsed = (int) Math.min(60, Math.max(0,
                    (System.currentTimeMillis() - generationStartedAt) / 1000));
            renderGeneration(elapsed, false);
            handler.postDelayed(this, 1000);
        }
    };

    public static void start(Context context, JsonObject request) {
        File file = null;
        try {
            file = File.createTempFile("resume_portrait_", ".json", context.getCacheDir());
            JsonObject state = new JsonObject();
            state.add("request", request);
            write(file, state);
            Intent intent = new Intent(context, ResumePortraitActivity.class);
            intent.putExtra("cache", file.getName());
            ((android.app.Activity) context).startActivityForResult(intent, 6103);
        } catch (Exception e) {
            if (file != null) file.delete();
            throw new IllegalStateException("无法准备画像任务");
        }
    }

    @Override protected void attachBaseContext(Context context) {
        com.hr.digitalhuman.model.RobotConfig config = com.hr.digitalhuman.app.DigitalHumanApp
                .getInstance().getSessionStore().getConfig();
        super.attachBaseContext(DisplayDensityHelper.createPageContext(context,
                config == null ? null : config.display));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(6), dp(10), dp(6));
        root.setBackgroundResource(R.drawable.bg_page);
        LinearLayout header = new LinearLayout(this);
        Button back = UiDecor.button(this, "返回", false);
        back.setMinimumHeight(dp(28));
        back.setMinHeight(dp(28));
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(-2, dp(28)));
        TextView title = UiDecor.title(this, "智能体 · 简历画像");
        title.setGravity(android.view.Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        status = UiDecor.subtitle(this, "正在准备画像任务");
        root.addView(status);
        progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(32)));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        try {
            String name = getIntent().getStringExtra("cache");
            if (name == null || !name.matches("resume_portrait_[A-Za-z0-9_-]+\\.json")) {
                throw new IllegalArgumentException();
            }
            cache = new File(getCacheDir(), name);
            try (InputStreamReader reader = new InputStreamReader(new android.util.AtomicFile(cache).openRead(), "UTF-8")) {
                state = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (state.has("profile")) showReport();
            else if (state.has("conversation_id")) startPolling();
            else if (saved == null && !state.has("started")) generate();
            else fail("生成已中断，请返回输入页重新开始", false);
        } catch (Exception e) {
            fail("任务资料已失效，请返回重新开始", false);
        }
    }

    private void showGeneration() {
        if (generationDialog != null && generationDialog.isShowing()) return;
        completing = false;
        if (state.has("generation_started_at")) {
            generationStartedAt = state.get("generation_started_at").getAsLong();
            JsonArray durations = state.getAsJsonArray("step_durations");
            stepDurations = new int[4];
            for (int i = 0; i < 4; i++) stepDurations[i] = durations.get(i).getAsInt();
        } else {
            // 与参考页面一致：第一阶段15秒，其余随机分配，合计60秒；不代表真实节点进度。
            java.util.Random random = new java.util.Random();
            int second = 10 + random.nextInt(11);
            int remaining = 45 - second;
            int third = 10 + random.nextInt(remaining - 14);
            stepDurations = new int[]{15, second, third, remaining - third};
            generationStartedAt = System.currentTimeMillis();
            state.addProperty("generation_started_at", generationStartedAt);
            JsonArray durations = new JsonArray();
            for (int duration : stepDurations) durations.add(duration);
            state.add("step_durations", durations);
        }
        generationDialog = new android.app.Dialog(this);
        generationDialog.setCancelable(false);
        generationDialog.setCanceledOnTouchOutside(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(16), dp(24), dp(16));
        panel.setBackgroundResource(R.drawable.bg_glass_panel);
        TextView title = UiDecor.title(this, "AI 正在构建人才画像");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);
        TextView description = UiDecor.subtitle(this, "正在深度理解简历中的能力证据，请稍候");
        description.setGravity(android.view.Gravity.CENTER);
        panel.addView(description);
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            stepRows[i] = row;
            stepMarkers[i] = UiDecor.title(this, String.valueOf(i + 1));
            stepMarkers[i].setGravity(android.view.Gravity.CENTER);
            stepMarkers[i].setTextSize(11);
            row.addView(stepMarkers[i], new LinearLayout.LayoutParams(dp(48), -1));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            stepTitles[i] = UiDecor.title(this, STEP_TITLES[i]);
            stepTitles[i].setTextSize(13);
            labels.addView(stepTitles[i]);
            stepDescriptions[i] = UiDecor.subtitle(this, STEP_DESCRIPTIONS[i]);
            stepDescriptions[i].setTextSize(11);
            labels.addView(stepDescriptions[i]);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            panel.addView(row, lp);
        }
        generationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        generationProgress.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(6));
        barLp.topMargin = dp(12);
        panel.addView(generationProgress, barLp);
        generationDialog.setContentView(panel);
        generationDialog.show();
        android.view.Window window = generationDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(540), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
        }
        progress.setVisibility(View.GONE);
        handler.removeCallbacks(updateGeneration);
        updateGeneration.run();
    }

    private void renderGeneration(int elapsed, boolean finished) {
        int end = 0, active = 3;
        for (int i = 0; i < 4; i++) {
            end += stepDurations[i];
            if (elapsed < end) { active = i; break; }
        }
        for (int i = 0; i < 4; i++) {
            boolean done = finished || i < active;
            boolean current = !finished && i == active;
            stepMarkers[i].setText(done ? "完成" : current ? "处理中" : String.valueOf(i + 1));
            stepMarkers[i].setTextColor(done ? 0xFF69CB91 : current ? 0xFF68ADFF : 0xFF718096);
            stepRows[i].setBackgroundColor(current ? 0xFF203E60 : android.graphics.Color.TRANSPARENT);
            stepTitles[i].setAlpha(done || current ? 1f : 0.45f);
            stepDescriptions[i].setAlpha(done || current ? 1f : 0.45f);
        }
        generationProgress.setProgress(elapsed * 100 / 60);
        // 60秒只是模拟展示上限，最终阶段仍等待真实报告，不自动宣告成功。
    }

    private void dismissGeneration() {
        handler.removeCallbacks(updateGeneration);
        if (generationDialog != null) {
            generationDialog.dismiss();
            generationDialog = null;
        }
    }

    private void generate() throws Exception {
        showGeneration();
        state.addProperty("started", true);
        persist();
        final JsonObject request = state.getAsJsonObject("request");
        client = new AgentApiClient(AgentDefinition.PORTRAIT_AGENT.apiKey());
        final AgentApiClient active = client;
        final int token = ++generation;
        running = true;
        network.execute(() -> {
            try {
                active.stream(request, new AgentApiClient.Listener() {
                    public void onAnswer(String chunk) { /* 画像以结构化报告为准，不展示中间文本。 */ }
                    public void onComplete() {}
                    public void onConversationId(String id) {
                        handler.post(() -> {
                            if (!current(token)) return;
                            if (state.has("conversation_id")
                                    && id.equals(state.get("conversation_id").getAsString())) return;
                            state.addProperty("conversation_id", id);
                            state.remove("request");
                            try { persist(); }
                            catch (Exception e) { fail("无法保存任务，请返回重试", false); }
                        });
                    }
                });
                handler.post(() -> {
                    if (!current(token)) return;
                    running = false;
                    client = null;
                    state.remove("request");
                    if (state.has("conversation_id")) startPolling();
                    else fail("未获取到画像任务，请返回重新开始", false);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (current(token)) fail("画像生成中断，请重新开始",
                            state.has("conversation_id"));
                });
            }
        });
    }

    private void startPolling() {
        if (destroyed || running) return;
        try {
            client = new AgentApiClient(AgentDefinition.PORTRAIT_AGENT.apiKey());
            running = true;
            attempts = 0;
            showGeneration();
            poll(++generation);
        } catch (Exception e) { fail("服务尚未配置，请联系管理员", false); }
    }

    private void poll(int token) {
        if (!current(token)) return;
        ++attempts;
        status.setText("正在获取画像报告（" + attempts + "/10）");
        AgentApiClient active = client;
        String conversation = state.get("conversation_id").getAsString();
        network.execute(() -> {
            try {
                AgentApiClient.ReportData report = active.fetchReportData(conversation, 10000);
                JsonObject profile = report == null || report.json == null ? null
                        : extract(JsonParser.parseString(report.json), 0);
                if (report != null && report.json != null && profile == null) {
                    throw new IllegalArgumentException("画像结构无效");
                }
                handler.post(() -> {
                    if (!current(token)) return;
                    if (profile != null) {
                        state.add("profile", profile);
                        state.addProperty("content", report.content == null ? "" : report.content);
                        state.remove("request");
                        try {
                            persist();
                            completing = true;
                            handler.removeCallbacks(updateGeneration);
                            renderGeneration(60, true);
                            handler.postDelayed(() -> {
                                if (!current(token)) return;
                                running = false;
                                client = null;
                                showReport();
                            }, 300);
                        } catch (Exception e) { fail("报告保存失败，请重新获取", true); }
                    } else if (attempts < 10) {
                        handler.postDelayed(() -> poll(token), 1500);
                    } else fail("报告暂未生成，请稍后重新获取", true);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (current(token)) fail("报告查询或解析失败，请重新获取", true);
                });
            }
        });
    }

    /** 对齐网页解析器：结构化数据可能包在对象、数组或 JSON 字符串内。 */
    private static JsonObject extract(JsonElement value, int depth) {
        if (value == null || value.isJsonNull() || depth > 12) return null;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("profile_revision") && object.has("generated_at")
                    && object.has("basic_info") && object.get("basic_info").isJsonObject()
                    && object.getAsJsonObject("basic_info").has("name")
                    && object.has("dimensions") && object.get("dimensions").isJsonArray()
                    && object.has("capabilities") && object.get("capabilities").isJsonArray()
                    && object.has("fit_directions") && object.get("fit_directions").isJsonArray()
                    && object.has("overall_weighted_score")
                    && object.get("overall_weighted_score").isJsonPrimitive()
                    && object.get("overall_weighted_score").getAsJsonPrimitive().isNumber()
                    && object.has("summary")) return object;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonObject found = extract(entry.getValue(), depth + 1);
                if (found != null) return found;
            }
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                JsonObject found = extract(item, depth + 1);
                if (found != null) return found;
            }
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString().trim();
            int start = text.indexOf('{'), end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try { return extract(JsonParser.parseString(text.substring(start, end + 1)), depth + 1); }
                catch (RuntimeException ignored) { /* 普通字符串不是结构化画像。 */ }
            }
        }
        return null;
    }

    private void showReport() {
        dismissGeneration();
        progress.setVisibility(View.GONE);
        status.setText("画像已生成");
        body.removeAllViews();
        reportView = new ResumePortraitReportView(this, state.getAsJsonObject("profile"),
                state.has("content") ? state.get("content").getAsString() : "",
                state.has("tab") ? state.get("tab").getAsInt() : 0);
        body.addView(reportView, new LinearLayout.LayoutParams(-1, -1));
    }

    private boolean current(int token) { return !destroyed && generation == token && running; }

    private void fail(String message, boolean canRetry) {
        dismissGeneration();
        running = false;
        ++generation;
        AgentApiClient old = client;
        client = null;
        if (old != null) new Thread(old::cancel, "portrait-cancel").start();
        progress.setVisibility(View.GONE);
        status.setText(message);
        // 与网页一致：失败恢复输入页，输入控件仍由上一页保留，不自动重新生成。
        Intent result = new Intent();
        result.putExtra("portrait_error", message);
        setResult(RESULT_CANCELED, result);
        finish();
    }

    private void persist() throws Exception { write(cache, state); }

    private static void write(File file, JsonObject value) throws Exception {
        // AtomicFile 防止页面重建或进程终止时留下半份任务状态。
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try {
            OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
            writer.write(value.toString());
            writer.flush();
            atomic.finishWrite(out);
        } catch (Exception e) {
            atomic.failWrite(out);
            throw e;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (reportView != null) {
            state.addProperty("tab", reportView.getSelectedTab());
            try { persist(); } catch (Exception ignored) { /* 不影响当前报告阅读。 */ }
        }
        super.onSaveInstanceState(out);
    }

    @Override public void finish() {
        // 立即使排队回调失效，避免点击返回后迟到的结果继续写缓存或刷新页面。
        destroyed = true;
        ++generation;
        handler.removeCallbacksAndMessages(null);
        dismissGeneration();
        super.finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ++generation;
        handler.removeCallbacksAndMessages(null);
        dismissGeneration();
        AgentApiClient old = client;
        if (old != null) new Thread(old::cancel, "portrait-destroy").start();
        network.shutdownNow();
        // 任务仅在当前页面生命周期内使用，不长期保存含个人信息的报告。
        if (isFinishing() && cache != null) new android.util.AtomicFile(cache).delete();
        super.onDestroy();
    }

    private int dp(int value) { return UiDecor.dp(this, value); }
}
