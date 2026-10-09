package com.hr.digitalhuman.ui;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.google.gson.JsonObject;
import com.hr.digitalhuman.agents.AgentApiClient;
import com.hr.digitalhuman.agents.AgentDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * 输入页开始分析后的独立结果页。密钥仅由 AgentDefinition / BuildConfig 获取。
 * 网络、Markdown 解析分离；旋转只恢复有限结果，不隐式重复请求。
 */
public final class AgentResultActivity extends AppCompatActivity {
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_FILE_URI = "file_uri";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_MIME = "mime";
    private static final int TEXT = 0, LOCAL = 1, COS = 2, HISTORY = 3;
    private static final int MAX_INPUT = 50000, MAX_OUTPUT = 200000, MAX_SAVED = 16000;
    private static final long RENDER_DELAY_MS = 250;
    private static final String NETWORK_ERROR =
            "请求失败或流中断，未确认完成。请检查网络、密钥权限和文件（最多 50MB），重试。";

    private final Handler main = new Handler(Looper.getMainLooper());
    // cancel 另起短生命周期线程，不能排在被阻塞的网络任务之后。
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService parser = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    // 单飞跨越后台解析与主线程应用整个周期，同一 Markwon 实例不会并发调用。
    private final StringBuilder answer = new StringBuilder();
    private volatile long generation;
    private volatile boolean destroyed;
    private boolean running, invalid, dirty, parseInFlight, renderPosted, savedTruncated;
    private AgentApiClient client;
    private Future<?> networkTask;
    private Markwon markwon;
    private TextView status, result;
    private Button stop, retry, latest;
    private FollowingScrollView scroll;
    private AgentDefinition definition;
    private int source;
    private String type = "", resume = "", jd = "", title = "", cos = "", token = "";
    private String fileName = "resume", mime = "application/octet-stream", cacheIdentity = "";
    private String cachedCosKey = "";
    private Uri fileUri;
    private int restoreScrollY = -1;
    private final Runnable renderTick = this::parseLatest;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        createViews();
        // 无 HTML、图片或网络加载插件；链接完全禁用，比仅过滤 scheme 更保守。
        // toMarkdown 在单线程后台调用，setParsedMarkdown 仅在主线程调用。
        // 表格插件后台解析的线程亲和性未经官方源码核验；不把串行等同于线程安全保证。
        markwon = Markwon.builder(this)
                .usePlugin(TablePlugin.create(this))
                .usePlugin(StrikethroughPlugin.create())
                .build();
        readAndValidateIntent();
        if (savedInstanceState != null) {
            synchronized (lock) {
                String saved = savedInstanceState.getString("answer", "");
                answer.append(saved, 0, safeEnd(saved, MAX_SAVED));
                if (!invalid && cacheIdentity.equals(savedInstanceState.getString("cache_identity", ""))) {
                    cachedCosKey = savedInstanceState.getString("cache", "");
                }
                dirty = true;
            }
            scroll.restoreFollowing(savedInstanceState.getBoolean("following", true));
            restoreScrollY = savedInstanceState.getInt("scroll_y", 0);
            if (!invalid) {
                String message = savedInstanceState.getString("status", "已恢复结果。");
                savedTruncated = savedInstanceState.getBoolean("truncated", false);
                status.setText(message + (savedTruncated
                        ? "\n恢复结果仅保留前 16000 字符，其余内容未保存。" : ""));
            }
            scheduleRender();
        } else if (!invalid) {
            // 首次进入自动启动；重建页面不重复发送请求。
            startRequest();
        }
        updateButtons();
    }

    private void createViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1422);
        root.setPadding(dp(12), dp(8), dp(12), dp(88));
        LinearLayout top = new LinearLayout(this);
        Button back = button("返回编辑");
        stop = button("停止");
        retry = button("重试");
        Button copy = button("复制 Markdown");
        copy.setOnClickListener(v -> copyMarkdown());
        top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1.4f));
        top.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(retry, new LinearLayout.LayoutParams(0, -2, 1.4f));
        root.addView(top);
        root.addView(UiDecor.title(this, "智能体 · Markdown 分析结果"));
        status = UiDecor.subtitle(this, "准备分析…");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        scroll = new FollowingScrollView(this);
        UiDecor.styleCard(this, scroll);
        result = UiDecor.title(this, "");
        result.setTextSize(16);
        result.setLineSpacing(dp(3), 1f);
        result.setTextIsSelectable(true);
        result.setAutoLinkMask(0);
        result.setLinksClickable(false);
        // 不让框架另存一份大文本；Bundle 只保存手动限制的 Markdown。
        result.setSaveEnabled(false);
        scroll.setSaveEnabled(false);
        scroll.addView(result, new android.widget.ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        latest = button("回到最新");
        root.addView(latest, new LinearLayout.LayoutParams(-1, -2));
        scroll.setFollowListener(following -> latest.setVisibility(following ? View.GONE : View.VISIBLE));
        latest.setOnClickListener(v -> scroll.returnToLatest());
        back.setOnClickListener(v -> finish());
        stop.setOnClickListener(v -> stopRequest("已停止；保留当前 Markdown 结果，可返回编辑或重试。"));
        retry.setOnClickListener(v -> startRequest());
        setContentView(root);
    }

    private void readAndValidateIntent() {
        try {
            Intent intent = getIntent();
            definition = AgentDefinition.fromId(intent.getStringExtra(AgentStreamActivity.EXTRA_AGENT_ID));
            source = intent.getIntExtra(EXTRA_SOURCE, -1);
            if (source < TEXT || source > HISTORY) throw new IllegalArgumentException();
            type = input(intent, EXTRA_TYPE);
            resume = input(intent, AgentStreamActivity.EXTRA_RESUME_CONTENT);
            jd = input(intent, AgentStreamActivity.EXTRA_JOB_INFO);
            title = input(intent, AgentStreamActivity.EXTRA_JOB_TITLE);
            cos = input(intent, AgentStreamActivity.EXTRA_COS_KEY);
            token = input(intent, AgentStreamActivity.EXTRA_DOWNLOAD_TOKEN);
            input(intent, AgentStreamActivity.EXTRA_RESUME_NAME); // 只验证，不替换真实简历。
            String uriString = input(intent, EXTRA_FILE_URI);
            if (source == LOCAL) {
                fileUri = intent.getData();
                if (fileUri == null || !"content".equalsIgnoreCase(fileUri.getScheme())
                        || (!uriString.isEmpty() && !fileUri.toString().equals(uriString))) {
                    throw new IllegalArgumentException();
                }
            }
            String name = input(intent, EXTRA_FILE_NAME);
            String suppliedMime = input(intent, EXTRA_MIME);
            if (!name.isEmpty()) fileName = name;
            if (!suppliedMime.isEmpty()) mime = suppliedMime;
            if ((source == TEXT && resume.isEmpty()) || (source == COS && cos.isEmpty())
                    || (source == HISTORY && token.isEmpty())) throw new IllegalArgumentException();
            if (source == COS && (cos.contains("://") || cos.equals(token))) throw new IllegalArgumentException();
            // 用已确认协议在上传前验证 type/JD；占位 key 从不用于网络请求。
            definition.buildRequest(type, jd, title, source == TEXT ? resume : "",
                    source == TEXT ? "" : "validation-only");
            cacheIdentity = identity(definition.id + "\n" + source + "\n" + fileUri
                    + "\n" + token + "\n" + fileName + "\n" + mime);
        } catch (RuntimeException e) {
            invalid = true;
            status.setText("输入参数无效：请返回检查智能体、简历来源、文件读权限、分析类型及真实 JD。文本不得超过 50000 字符；未发送任何资料。");
        }
    }

    private static String input(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        if (value == null) return "";
        if (value.length() > MAX_INPUT) throw new IllegalArgumentException();
        return value.trim();
    }

    private void startRequest() {
        if (running || invalid || destroyed) return;
        final AgentApiClient requestClient;
        try { requestClient = new AgentApiClient(definition.apiKey()); }
        catch (IllegalArgumentException e) {
            status.setText("智能体密钥未配置或无效，请检查 " + definition.configurationName + "。未发送资料。");
            return;
        }
        final long id;
        final String reusable;
        synchronized (lock) {
            id = ++generation;
            answer.setLength(0);
            dirty = true;
            reusable = cachedCosKey;
        }
        savedTruncated = false;
        restoreScrollY = -1;
        result.setText("");
        scroll.returnToLatest();
        client = requestClient;
        running = true;
        status.setText(source == LOCAL || source == HISTORY ? "准备资料并上传…" : "正在分析…");
        updateButtons();
        scheduleRender();
        final AtomicBoolean limit = new AtomicBoolean();
        networkTask = network.submit(() -> {
            try {
                String uploaded = source == COS ? cos : reusable;
                if ((source == LOCAL || source == HISTORY) && uploaded.isEmpty()) {
                    if (!isCurrent(id)) return;
                    if (source == LOCAL) {
                        try (InputStream stream = getContentResolver().openInputStream(fileUri)) {
                            if (stream == null) throw new IOException();
                            uploaded = requestClient.upload(stream, fileName, mime);
                        }
                    } else uploaded = requestClient.uploadResume(token);
                    synchronized (lock) {
                        if (!isCurrent(id)) return;
                        cachedCosKey = uploaded; // 同页面不可变资料、同 agent；可用于重试。
                    }
                }
                if (!isCurrent(id)) return;
                JsonObject body = definition.buildRequest(type, jd, title,
                        source == TEXT ? resume : "", source == TEXT ? "" : uploaded);
                main.post(() -> { if (isCurrent(id) && running) status.setText("正在分析，结果持续更新…"); });
                requestClient.stream(body, new AgentApiClient.Listener() {
                    @Override public void onAnswer(String chunk) {
                        synchronized (lock) {
                            if (!isCurrent(id)) return;
                            int room = MAX_OUTPUT - answer.length();
                            if (chunk.length() > room) {
                                answer.append(chunk, 0, safeEnd(chunk, room));
                                dirty = true;
                                limit.set(true);
                                scheduleRender();
                                throw new IllegalStateException();
                            }
                            answer.append(chunk);
                            dirty = true;
                        }
                        scheduleRender();
                    }
                    @Override public void onComplete() {
                        // 客户端验证 message_end / workflow_finished 后完成，普通 EOF 不算完成。
                        main.post(() -> finishRequest(id, "分析完成"));
                    }
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> finishRequest(id, limit.get()
                        ? "结果已达到 200000 字符上限，已停止；仅保留上限内内容，未确认完整结果。请缩小范围后重试。"
                        : NETWORK_ERROR));
            }
        });
    }

    private boolean isCurrent(long id) { return !destroyed && generation == id; }

    private void finishRequest(long id, String message) {
        if (!isCurrent(id)) return;
        running = false;
        client = null;
        networkTask = null;
        status.setText(message);
        updateButtons();
        scheduleRender();
    }

    /** 所有分片共用一个 250ms 定时任务；飞行中的解析只记录 dirty。 */
    private void scheduleRender() {
        synchronized (lock) {
            if (destroyed || !dirty || parseInFlight || renderPosted) return;
            renderPosted = true;
            main.postDelayed(renderTick, RENDER_DELAY_MS);
        }
    }

    private void parseLatest() {
        final String markdown;
        final long id;
        synchronized (lock) {
            renderPosted = false;
            if (destroyed || parseInFlight || !dirty) return;
            parseInFlight = true;
            dirty = false;
            id = generation;
            markdown = answer.toString();
        }
        parser.execute(() -> {
            Spanned parsed = null;
            try {
                SpannableStringBuilder safe = new SpannableStringBuilder(markwon.toMarkdown(markdown));
                // Markwon LinkSpan 不一定继承 URLSpan，移除全部 ClickableSpan 才能彻底禁链接。
                for (ClickableSpan span : safe.getSpans(0, safe.length(), ClickableSpan.class)) safe.removeSpan(span);
                parsed = safe;
            } catch (RuntimeException ignored) { /* 固定提示，不暴露响应或解析异常。 */ }
            final Spanned ready = parsed;
            if (destroyed) return;
            main.post(() -> {
                try {
                    if (isCurrent(id)) {
                        int previousY = scroll.getScrollY();
                        boolean displayFailed = ready == null;
                        if (ready != null) {
                            try { markwon.setParsedMarkdown(result, ready); }
                            catch (RuntimeException ignored) { displayFailed = true; }
                        }
                        if (displayFailed) {
                            result.setText(markdown);
                            String notice = "Markdown 显示失败，本次更新已保留为纯文本。";
                            if (!status.getText().toString().contains(notice)) {
                                status.setText(status.getText() + "\n" + notice);
                            }
                        }
                        result.setLinksClickable(false);
                        final int preservedY = restoreScrollY >= 0 ? restoreScrollY : previousY;
                        restoreScrollY = -1;
                        if (scroll.isFollowing()) scroll.onContentRendered();
                        else result.post(() -> {
                            if (isCurrent(id) && !scroll.isFollowing()) scroll.preserveReadingPosition(preservedY);
                        });
                    }
                } finally {
                    synchronized (lock) { parseInFlight = false; }
                    // 旧 generation 完成也释放单飞标记；应用完成后才允许下次解析。
                    scheduleRender();
                }
            });
        });
    }

    private void stopRequest(String message) {
        cancelNetwork();
        synchronized (lock) { dirty = true; }
        status.setText(message);
        updateButtons();
        scheduleRender();
    }

    private void cancelNetwork() {
        synchronized (lock) { ++generation; }
        AgentApiClient old = client;
        client = null;
        Future<?> oldTask = networkTask;
        networkTask = null;
        running = false;
        if (oldTask != null) oldTask.cancel(true);
        if (old != null) {
            new Thread(old::cancel, "agent-result-cancel").start();
        }
    }

    @Override public void finish() {
        // 返回输入页只 finish，不能再启动一份输入页；先隔离迟到回调。
        cancelNetwork();
        super.finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // 立即冻结当前结果，避免旋转保存之后仍接收但未保存的分片。
        if (running) stopRequest("页面已重建，原请求已中止；保留部分结果，请确认重试。");
        super.onSaveInstanceState(state);
        synchronized (lock) {
            state.putString("answer", answer.substring(0, safeEnd(answer, MAX_SAVED)));
            state.putBoolean("truncated", savedTruncated || answer.length() > MAX_SAVED);
            state.putString("cache", cachedCosKey.length() <= MAX_INPUT ? cachedCosKey : "");
            state.putString("cache_identity", cacheIdentity);
        }
        state.putString("status", status.getText().toString());
        state.putBoolean("following", scroll.isFollowing());
        state.putInt("scroll_y", scroll.getScrollY());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelNetwork();
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        parser.shutdownNow();
        // 不等待线程退出、不在主线程 disconnect/close；解析迟到结果由 generation 隔离。
        super.onDestroy();
    }

    private void copyMarkdown() {
        final String markdown;
        synchronized (lock) { markdown = answer.toString(); }
        if (markdown.isEmpty()) {
            Toast.makeText(this, "暂无可复制的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            // 复制 SSE 累积的原始正文，不取渲染后 TextView 的文本。
            clipboard.setPrimaryClip(ClipData.newPlainText("Markdown", markdown));
            Toast.makeText(this, "已复制 Markdown 原文", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "复制失败，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButtons() {
        stop.setEnabled(running);
        retry.setEnabled(!running && !invalid && !destroyed);
    }

    private static int safeEnd(CharSequence value, int max) {
        int end = Math.min(value.length(), Math.max(0, max));
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) --end;
        return end;
    }

    private static String identity(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(Charset.forName("UTF-8")));
            StringBuilder encoded = new StringBuilder();
            for (byte b : digest) encoded.append(Integer.toHexString((b & 255) | 256).substring(1));
            return encoded.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException();
        }
    }

    private int dp(int value) { return UiDecor.dp(this, value); }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(0xFFEAF2FF);
        button.setMinHeight(dp(48));
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(0xFF264C76));
        return button;
    }
}
