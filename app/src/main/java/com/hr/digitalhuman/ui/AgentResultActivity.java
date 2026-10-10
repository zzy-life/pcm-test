package com.hr.digitalhuman.ui;

import android.content.Intent;
import android.content.Context;
import android.app.Activity;
import com.google.gson.JsonParser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.hr.digitalhuman.R;
import com.hr.digitalhuman.agents.AgentApiClient;

import java.io.IOException;
import java.util.ArrayList;
import com.google.gson.JsonArray;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 通用智能体聊天页：业务方传入请求参数及密钥，不解释 inputs 内部字段。
 * 原生网络与离线 WebView 渲染分离；旋转只恢复有限结果，不隐式重复请求。
 */
public final class AgentResultActivity extends AppCompatActivity {
    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_INPUTS = "inputs";
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_RESPONSE_MODE = "response_mode";
    public static final String EXTRA_INITIAL_MESSAGE = "initial_message";
    public static final String EXTRA_RETAIN_INPUTS = "retain_inputs";
    public static final String EXTRA_POLL_ATTEMPTS = "poll_attempts";
    public static final String EXTRA_POLL_INTERVAL_MS = "poll_interval_ms";
    public static final String EXTRA_POLL_TIMEOUT_MS = "poll_timeout_ms";
    private Button getResult;
    private boolean sseCompleted, polling;
    private int pollAttempts = 10, pollIntervalMs = 3000, pollTimeoutMs = 10000;
    private long reportGeneration;
    private AgentApiClient reportClient;
    private Future<?> reportTask;
    private final Runnable pollNext = this::queryReport;
    private int attempts;
    private String reportConversation = "";

    public static void start(Context context, String apiKey, String displayName, JsonObject inputs,
                             String query, String conversationId, String responseMode, String initialMessage,
                             boolean retainInputs,
                             int pollAttempts, int pollIntervalMs, int pollTimeoutMs) {
        Intent intent = new Intent(context, AgentResultActivity.class);
        intent.putExtra(EXTRA_API_KEY, apiKey);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        intent.putExtra(EXTRA_INPUTS, inputs == null ? "{}" : inputs.toString());
        intent.putExtra(EXTRA_QUERY, query);
        intent.putExtra(EXTRA_CONVERSATION_ID, conversationId);
        intent.putExtra(EXTRA_RESPONSE_MODE, responseMode);
        intent.putExtra(EXTRA_INITIAL_MESSAGE, initialMessage);
        intent.putExtra(EXTRA_RETAIN_INPUTS, retainInputs);
        intent.putExtra(EXTRA_POLL_ATTEMPTS, pollAttempts);
        intent.putExtra(EXTRA_POLL_INTERVAL_MS, pollIntervalMs);
        intent.putExtra(EXTRA_POLL_TIMEOUT_MS, pollTimeoutMs);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    private static final int MAX_INPUT = 50000, MAX_OUTPUT = 200000, MAX_SAVED = 16000;
    private static final int MAX_TURNS = 30, MAX_SESSION = 400000;
    private static final long RENDER_DELAY_MS = 50;
    private static final String NETWORK_ERROR =
            "请求失败或流中断，未确认完成。请检查网络、密钥权限和输入参数。";

    private final Handler main = new Handler(Looper.getMainLooper());
    // cancel 另起短生命周期线程，不能排在被阻塞的网络任务之后。
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    // 原文始终保留在原生层，WebView 仅负责显示。
    private final ArrayList<Message> messages = new ArrayList<>();
    private Message activeReply;
    private volatile String conversationId = "";
    private volatile int totalChars;
    private int turns;

    private static final class Message {
        final boolean user;
        final StringBuilder text = new StringBuilder();
        String state = "";
        Message(boolean user, String content) { this.user = user; text.append(content); }
    }
    private volatile long generation;
    private volatile boolean destroyed;
    private boolean running, invalid, dirty, renderPosted, savedTruncated;
    private AgentApiClient client;
    private Future<?> networkTask;
    private TextView status;
    private EditText composer;
    private Button stop, send, latest;
    private android.widget.ImageButton microphone;
    private boolean capturing, applyingVoice, resumed;
    private String voiceBase = "";
    private com.hr.digitalhuman.robot.RobotSdkBridge voiceBridge;
    private static final int AUDIO_PERMISSION = 6102;
    private final com.hr.digitalhuman.robot.RobotSdkBridge.SpeechTextListener voiceListener =
            new com.hr.digitalhuman.robot.RobotSdkBridge.SpeechTextListener() {
                public void onAsrPartial(String text) { applyVoice(text, false); }
                public void onAsrResult(String text) { applyVoice(text, true); }
            };
    private AgentResultWebView result;
    private JsonObject initialInputs = new JsonObject();
    private String apiKey = "", displayName = "智能体", initialQuery = "", initialMessage = "";
    private String responseMode = "streaming";
    private boolean retainInputs;
    private TextView heading;
    private final Runnable renderTick = this::parseLatest;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        createViews();
        readAndValidateIntent();
        if (savedInstanceState != null) {
            retainInputs = savedInstanceState.getBoolean("retain_inputs", retainInputs);
            sseCompleted = savedInstanceState.getBoolean("sse_completed", false);
            synchronized (lock) {
                ArrayList<String> texts = savedInstanceState.getStringArrayList("texts");
                ArrayList<String> states = savedInstanceState.getStringArrayList("states");
                if (texts != null && states != null) {
                    for (int i = 0; i < texts.size(); i++) {
                        Message message = new Message(i % 2 == 0, texts.get(i));
                        message.state = states.get(i);
                        messages.add(message);
                        totalChars += message.text.length();
                    }
                }
                conversationId = savedInstanceState.getString("conversation_id", "");
                turns = savedInstanceState.getInt("turns", 0);
                totalChars = savedInstanceState.getInt("total_chars", totalChars);
                dirty = true;
            }
            composer.setText(savedInstanceState.getString("draft", ""));
            result.restorePosition(savedInstanceState.getBoolean("following", true),
                    savedInstanceState.getInt("scroll_y", 0));
            if (!invalid) {
                String message = savedInstanceState.getBoolean("request_active", false)
                        ? "页面已重建，原请求已结束；保留已保存的回复。"
                        : savedInstanceState.getString("status", "已恢复结果。");
                savedTruncated = savedInstanceState.getBoolean("truncated", false);
                String notice = "恢复结果仅保留前 16000 字符，其余内容未保存。";
                status.setText(message + (savedTruncated && !message.contains(notice)
                        ? "\n" + notice : ""));
            }
            scheduleRender();
        } else if (!invalid && !initialQuery.trim().isEmpty()) {
            // 仅显式传入首条 query 时自动发送；重建不重复发送。
            startRequest(initialQuery);
        }
        updateButtons();
    }

    private void createViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1523);
        root.setPadding(dp(8), dp(6), dp(8), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heading = UiDecor.title(this, "智能体 · 对话");
        heading.setTextSize(18);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        // 横屏标题与操作组同线居中，保留标题空间。
        android.widget.HorizontalScrollView actionsScroll = new android.widget.HorizontalScrollView(this);
        actionsScroll.setFillViewport(true);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        Button back = button("返回");
        stop = button("停止");
        send = button("发送");
        latest = button("回到底部");
        getResult = button("获取结果");
        getResult.setOnClickListener(v -> startReportPolling());
        actions.addView(back);
        for (Button action : new Button[]{stop, latest, getResult}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            actions.addView(action, lp);
        }
        actionsScroll.addView(actions);
        top.addView(actionsScroll, new LinearLayout.LayoutParams(-2, dp(40)));
        root.addView(top);

        LinearLayout conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        conversation.setBackground(surface(0xFF121E2E, 16));
        conversation.setPadding(dp(4), dp(4), dp(4), dp(4));
        conversation.setClipToOutline(true);
        LinearLayout.LayoutParams conversationLp = new LinearLayout.LayoutParams(-1, 0, 1);
        conversationLp.topMargin = dp(8);
        root.addView(conversation, conversationLp);

        status = UiDecor.subtitle(this, "输入消息开始对话。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        result = new AgentResultWebView(this);
        result.setFailureListener(() -> {
            String notice = "结果渲染器加载或显示失败，请更新 Android System WebView 后重试。";
            if (!destroyed && !status.getText().toString().contains(notice)) {
                status.setText(status.getText() + "\n" + notice);
            }
        });
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        conversation.addView(result, scrollLp);
        root.addView(status);
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inputRow.setBackground(surface(0xFF0C1826, 24));
        inputRow.setPadding(dp(4), dp(3), dp(4), dp(3));
        microphone = new android.widget.ImageButton(this);
        microphone.setImageResource(R.drawable.ic_home_mic);
        microphone.setBackground(surface(0xFF0C1826, 20));
        microphone.setPadding(dp(10), dp(10), dp(10), dp(10));
        microphone.setContentDescription("开始语音输入");
        inputRow.addView(microphone, new LinearLayout.LayoutParams(dp(40), dp(40)));
        microphone.setOnClickListener(v -> toggleVoice());
        send.setBackground(surface(0xFF2F7CFF, 20));
        send.setTextColor(0xFFFFFFFF);
        composer = new EditText(this);
        composer.setSaveEnabled(false);
        composer.setHint("点此输入中文，或直接说话…");
        composer.setPadding(dp(8), dp(6), dp(8), dp(6));
        composer.setTextColor(UiDecor.color(this, R.color.text));
        composer.setHintTextColor(UiDecor.color(this, R.color.text_dim));
        composer.setTextSize(16);
        composer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.setMaxLines(4);
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MAX_INPUT)});
        inputRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        inputRow.addView(send, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(6);
        root.addView(inputRow, inputLp);
        composer.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (capturing && !applyingVoice) endVoice();
                updateButtons();
            }
            public void afterTextChanged(Editable s) {}
        });
        send.setOnClickListener(v -> startRequest(composer.getText().toString().trim()));
        latest.setOnClickListener(v -> result.returnToLatest());
        back.setOnClickListener(v -> finish());
        stop.setOnClickListener(v -> stopRequest("已停止，保留当前回复。"));
        setContentView(root);
    }

    private void toggleVoice() {
        if (capturing) { endVoice(); return; }
        if (!resumed || running || polling || invalid || destroyed) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        voiceBridge = com.hr.digitalhuman.app.DigitalHumanApp.getInstance().getRobotBridge();
        if (voiceBridge == null || !voiceBridge.beginVoiceCapture(voiceListener)) {
            status.setText("机器人语音暂不可用，请确认服务连接、非演示模式、未播报且有人在识别范围内。");
            return;
        }
        voiceBase = composer.getText().toString();
        capturing = true;
        microphone.setBackground(surface(0xFF163A6B, 20));
        microphone.setContentDescription("结束语音输入");
        status.setText("正在聆听，识别后请确认并发送；再次点击麦克风结束。");
    }

    private void applyVoice(String text, boolean complete) {
        if (!capturing || !resumed || destroyed || text == null || text.trim().isEmpty()) return;
        String value = voiceBase + (voiceBase.isEmpty() || voiceBase.endsWith("\n") ? "" : "\n") + text.trim();
        value = value.substring(0, safeEnd(value, MAX_INPUT));
        applyingVoice = true;
        composer.setText(value);
        composer.setSelection(composer.length());
        applyingVoice = false;
        if (complete) voiceBase = value;
    }

    private void endVoice() {
        boolean wasCapturing = capturing;
        capturing = false;
        if (wasCapturing && status != null) status.setText("语音输入已结束，请确认文字后发送。");
        if (voiceBridge != null) voiceBridge.endVoiceCapture(voiceListener);
        if (microphone != null) {
            microphone.setBackground(surface(0xFF0C1826, 20));
            microphone.setContentDescription("开始语音输入");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updateButtons();
    }
    @Override protected void onPause() { resumed = false; endVoice(); super.onPause(); }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO_PERMISSION) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleVoice();
            else status.setText("未授予麦克风权限，请使用文字输入。");
        }
    }

    private void readAndValidateIntent() {
        try {
            Intent intent = getIntent();
            retainInputs = intent.getBooleanExtra(EXTRA_RETAIN_INPUTS, false);
            pollAttempts = intent.getIntExtra(EXTRA_POLL_ATTEMPTS, 10);
            pollIntervalMs = intent.getIntExtra(EXTRA_POLL_INTERVAL_MS, 3000);
            pollTimeoutMs = intent.getIntExtra(EXTRA_POLL_TIMEOUT_MS, 10000);
            if (pollAttempts < 1 || pollAttempts > 100 || pollIntervalMs < 1000
                    || pollIntervalMs > 60000 || pollTimeoutMs < 1000 || pollTimeoutMs > 60000) {
                throw new IllegalArgumentException();
            }
            apiKey = input(intent, EXTRA_API_KEY);
            new AgentApiClient(apiKey); // 复用密钥校验，不启动网络，不向渲染层传递密钥。
            displayName = input(intent, EXTRA_DISPLAY_NAME).trim();
            if (displayName.isEmpty()) displayName = "智能体";
            if (displayName.length() > 120) throw new IllegalArgumentException();
            initialQuery = input(intent, EXTRA_QUERY);
            initialMessage = input(intent, EXTRA_INITIAL_MESSAGE);
            conversationId = input(intent, EXTRA_CONVERSATION_ID);
            if (conversationId.length() > 256) throw new IllegalArgumentException();
            for (int i = 0; i < conversationId.length(); i++) {
                if (Character.isISOControl(conversationId.charAt(i))) throw new IllegalArgumentException();
            }
            responseMode = input(intent, EXTRA_RESPONSE_MODE);
            if (responseMode.isEmpty()) responseMode = "streaming";
            if (!"streaming".equals(responseMode)) {
                invalid = true;
                status.setText("当前聊天页仅支持 response_mode=streaming，未发送请求。");
                return;
            }
            String json = intent.getStringExtra(EXTRA_INPUTS);
            if (json != null) {
                if (json.length() > 200000) throw new IllegalArgumentException();
                initialInputs = JsonParser.parseString(json).getAsJsonObject();
            }
            heading.setText(displayName + " · 对话");
        } catch (RuntimeException e) {
            invalid = true;
            status.setText("聊天参数无效，请检查密钥、inputs JSON 对象、消息长度及会话 ID；未发送请求。");
        }
    }

    private static String input(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        if (value == null) return "";
        if (value.length() > MAX_INPUT) throw new IllegalArgumentException();
        return value;
    }

    private void startRequest(String query) {
        if (running || polling || invalid || destroyed) return;
        endVoice();
        final boolean first = turns == 0;
        if (query.trim().isEmpty() || query.length() > MAX_INPUT
                || (!first && conversationId.isEmpty())) return;
        String visibleQuery = first && !initialQuery.trim().isEmpty() && !initialMessage.isEmpty()
                ? initialMessage : query;
        if (turns >= MAX_TURNS || totalChars + visibleQuery.length() >= MAX_SESSION) {
            status.setText("当前会话已达到轮次或内容上限，请返回开始新会话。");
            return;
        }
        final String currentConversation = conversationId;
        final AgentApiClient requestClient;
        try { requestClient = new AgentApiClient(apiKey); }
        catch (IllegalArgumentException e) {
            status.setText("智能体密钥无效，未发送请求。");
            return;
        }
        final long id;
        final Message reply = new Message(false, "");
        synchronized (lock) {
            id = ++generation;
            Message user = new Message(true, visibleQuery);
            messages.add(user);
            messages.add(reply);
            activeReply = reply;
            totalChars += user.text.length();
            turns++;
            reply.state = "正在回复…";
            dirty = true;
        }
        cancelReportPolling();
        sseCompleted = false;
        client = requestClient;
        running = true;
        composer.setText("");
        result.returnToLatest();
        status.setText("正在回复…");
        updateButtons();
        scheduleRender();
        final AtomicBoolean limit = new AtomicBoolean();
        networkTask = network.submit(() -> {
            try {
                if (!isCurrent(id)) return;
                JsonObject body = new JsonObject();
                // 面试每轮携带原设置和简历；其他智能体继续沿用服务端会话上下文。
                body.add("inputs", currentConversation.isEmpty() || retainInputs
                        ? initialInputs.deepCopy() : new JsonObject());
                body.addProperty("query", query);
                if (!currentConversation.isEmpty()) body.addProperty("conversation_id", currentConversation);
                body.addProperty("response_mode", responseMode);
                requestClient.stream(body, new AgentApiClient.Listener() {
                    @Override public void onAnswer(String chunk) {
                        synchronized (lock) {
                            if (!isCurrent(id)) return;
                            int room = Math.min(MAX_OUTPUT - reply.text.length(), MAX_SESSION - totalChars);
                            if (chunk.length() > room) {
                                int end = safeEnd(chunk, room);
                                reply.text.append(chunk, 0, end);
                                totalChars += end;
                                dirty = true;
                                limit.set(true);
                                scheduleRender();
                                throw new IllegalStateException();
                            }
                            reply.text.append(chunk);
                            totalChars += chunk.length();
                            dirty = true;
                        }
                        scheduleRender();
                    }
                    @Override public void onConversationId(String value) {
                        synchronized (lock) {
                            if (!isCurrent(id)) return;
                            if (!conversationId.isEmpty() && !conversationId.equals(value)) {
                                throw new IllegalStateException("会话 ID 不一致");
                            }
                            conversationId = value;
                        }
                    }
                    @Override public void onComplete() {
                        // 客户端验证 message_end / workflow_finished 后完成，普通 EOF 不算完成。
                        main.post(() -> {
                            if (!isCurrent(id)) return;
                            sseCompleted = true;
                            finishRequest(id, "回复完成");
                        });
                    }
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> finishRequest(id, limit.get()
                        ? "回复或会话内容已达到上限，已停止；保留部分回复，未确认完整结果。"
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
        synchronized (lock) {
            if (activeReply != null) activeReply.state = sseCompleted ? "" : message;
            dirty = true;
        }
        main.removeCallbacks(renderTick);
        parseLatest();
    }

    private void startReportPolling() {
        if (!sseCompleted || running || polling || invalid || destroyed
                || conversationId.isEmpty()) return;
        endVoice();
        polling = true;
        attempts = 0;
        reportConversation = conversationId;
        ++reportGeneration;
        updateButtons();
        queryReport();
    }

    private void queryReport() {
        if (!polling || destroyed) return;
        final long id = reportGeneration;
        final int attempt = ++attempts;
        final long expiresAt = android.os.SystemClock.elapsedRealtime() + pollTimeoutMs;
        final AgentApiClient request;
        try { request = new AgentApiClient(apiKey); }
        catch (RuntimeException e) { finishReport("报告密钥无效，可检查配置后重试。"); return; }
        reportClient = request;
        final String target = reportConversation;
        status.setText("正在获取结果（" + attempt + "/" + pollAttempts + "）…");
        // 整次查询的墙钟超时，不能只依赖 socket 的单次读取超时。
        final Runnable deadline = () -> {
            if (!isCurrentReport(id, attempt)) return;
            AgentApiClient old = reportClient;
            reportClient = null;
            if (reportTask != null) reportTask.cancel(true);
            reportTask = null;
            if (old != null) new Thread(old::cancel, "agent-report-timeout").start();
            finishReport("获取结果超时，可再次点击“获取结果”。");
        };
        main.postDelayed(deadline, pollTimeoutMs);
        reportTask = network.submit(() -> {
            try {
                String report = request.fetchReportResult(target, pollTimeoutMs);
                main.post(() -> {
                    if (!isCurrentReport(id, attempt)) return;
                    main.removeCallbacks(deadline);
                    if (android.os.SystemClock.elapsedRealtime() >= expiresAt) {
                        finishReport("获取结果超时，可再次点击“获取结果”。");
                        return;
                    }
                    reportClient = null;
                    reportTask = null;
                    if (report != null) {
                        try {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("智能体结果", report));
                            finishReport("结果已复制到剪贴板，可能包含个人信息，请谨慎粘贴。");
                        } catch (RuntimeException e) {
                            finishReport("结果复制失败，可再次点击“获取结果”。");
                        }
                    } else if (attempt >= pollAttempts) {
                        finishReport("已达到最大轮询次数，报告内容暂未生成，可再次点击“获取结果”。");
                    } else {
                        status.setText("报告内容暂未生成，等待下一次查询（" + attempt + "/" + pollAttempts + "）。");
                        main.postDelayed(pollNext, pollIntervalMs);
                    }
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    if (!isCurrentReport(id, attempt)) return;
                    main.removeCallbacks(deadline);
                    finishReport("获取结果失败，请检查网络、报告权限和 JSON 格式后再次点击。");
                });
            }
        });
    }

    private boolean isCurrentReport(long id, int attempt) {
        return !destroyed && polling && reportGeneration == id && attempts == attempt;
    }

    private void finishReport(String message) {
        cancelReportPolling();
        status.setText(message);
        updateButtons();
    }

    private void cancelReportPolling() {
        ++reportGeneration;
        polling = false;
        main.removeCallbacks(pollNext);
        AgentApiClient old = reportClient;
        reportClient = null;
        if (reportTask != null) reportTask.cancel(true);
        reportTask = null;
        if (old != null) new Thread(old::cancel, "agent-report-cancel").start();
    }

    /** 50ms 合并分片，WebView 内另有单飞与 React 提交确认。 */
    private void scheduleRender() {
        synchronized (lock) {
            if (destroyed || !dirty || renderPosted) return;
            renderPosted = true;
            main.postDelayed(renderTick, RENDER_DELAY_MS);
        }
    }

    private void parseLatest() {
        JsonArray snapshot = new JsonArray();
        synchronized (lock) {
            renderPosted = false;
            if (destroyed || !dirty) return;
            dirty = false;
            for (Message message : messages) {
                JsonObject value = new JsonObject();
                value.addProperty("user", message.user);
                value.addProperty("name", displayName);
                value.addProperty("text", message.text.toString());
                value.addProperty("status", message.state);
                value.addProperty("running", running && message == activeReply);
                snapshot.add(value);
            }
        }
        result.render(snapshot);
    }

    private void stopRequest(String message) {
        cancelNetwork();
        synchronized (lock) {
            if (activeReply != null) activeReply.state = message;
            dirty = true;
        }
        status.setText(message);
        updateButtons();
        main.removeCallbacks(renderTick);
        parseLatest();
    }

    private void cancelNetwork() {
        cancelReportPolling();
        endVoice();
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
        endVoice();
        // 保存快照不代表页面已销毁，切后台时继续接收 SSE 和查询结果。
        state.putBoolean("request_active", running || polling);
        super.onSaveInstanceState(state);
        state.putBoolean("sse_completed", sseCompleted);
        synchronized (lock) {
            ArrayList<String> texts = new ArrayList<>(), states = new ArrayList<>();
            int remaining = MAX_SAVED;
            boolean truncated = savedTruncated;
            for (Message message : messages) {
                int end = safeEnd(message.text, remaining);
                texts.add(message.text.substring(0, end));
                states.add(message.state + (end < message.text.length() ? "（显示内容已截断）" : ""));
                remaining -= end;
                truncated |= end < message.text.length();
            }
            state.putStringArrayList("texts", texts);
            state.putStringArrayList("states", states);
            state.putBoolean("truncated", truncated);
            state.putString("conversation_id", conversationId);
            state.putInt("turns", turns);
            state.putInt("total_chars", totalChars);
        }
        state.putBoolean("retain_inputs", retainInputs);
        state.putString("draft", composer.getText().toString());
        state.putString("status", status.getText().toString());
        state.putBoolean("following", result.isFollowing());
        state.putInt("scroll_y", result.readingPosition());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelNetwork();
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        result.dispose();
        // 不等待网络线程退出，迟到结果由 generation 隔离。
        super.onDestroy();
    }

    private void updateButtons() {
        stop.setEnabled(running);
        getResult.setEnabled(sseCompleted && !conversationId.isEmpty()
                && !running && !polling && !invalid && !destroyed);
        microphone.setEnabled(!running && !polling && !invalid && !destroyed);
        boolean available = !running && !polling && !invalid && !destroyed && (turns == 0 || !conversationId.isEmpty())
                && turns < MAX_TURNS && totalChars < MAX_SESSION;
        send.setEnabled(available && !composer.getText().toString().trim().isEmpty());
        if (!running && !invalid && !destroyed) {
            String notice = turns > 0 && conversationId.isEmpty()
                    ? "未获取到会话 ID，无法继续追问，请返回开始新会话。"
                    : (turns >= MAX_TURNS || totalChars >= MAX_SESSION
                    ? "当前会话已达到上限，请返回开始新会话。" : "");
            if (!notice.isEmpty() && !status.getText().toString().contains(notice)) {
                status.append("\n" + notice);
            }
        }
    }

    private static int safeEnd(CharSequence value, int max) {
        int end = Math.min(value.length(), Math.max(0, max));
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) --end;
        return end;
    }

    private int dp(int value) { return UiDecor.dp(this, value); }

    private android.graphics.drawable.GradientDrawable surface(int color, int radius) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0xFF2C4464);
        return bg;
    }

    private Button button(String text) {
        Button button = UiDecor.button(this, text, false);
        button.setTextSize(13);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(40));
        button.setMinHeight(dp(40));
        button.setBackground(surface(0xFF18283D, 20));
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }
}
