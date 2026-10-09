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

    public static void start(Context context, String apiKey, String displayName, JsonObject inputs,
                             String query, String conversationId, String responseMode, String initialMessage) {
        Intent intent = new Intent(context, AgentResultActivity.class);
        intent.putExtra(EXTRA_API_KEY, apiKey);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        intent.putExtra(EXTRA_INPUTS, inputs == null ? "{}" : inputs.toString());
        intent.putExtra(EXTRA_QUERY, query);
        intent.putExtra(EXTRA_CONVERSATION_ID, conversationId);
        intent.putExtra(EXTRA_RESPONSE_MODE, responseMode);
        intent.putExtra(EXTRA_INITIAL_MESSAGE, initialMessage);
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
    private AgentResultWebView result;
    private JsonObject initialInputs = new JsonObject();
    private String apiKey = "", displayName = "智能体", initialQuery = "", initialMessage = "";
    private String responseMode = "streaming";
    private TextView heading;
    private final Runnable renderTick = this::parseLatest;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        createViews();
        readAndValidateIntent();
        if (savedInstanceState != null) {
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
                String message = savedInstanceState.getString("status", "已恢复结果。");
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
        root.setBackgroundResource(R.drawable.bg_page);
        root.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(android.view.Gravity.RIGHT);
        heading = UiDecor.title(this, "智能体 · 对话");
        heading.setTextSize(18);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(heading);
        // 标题和操作组分行右对齐，窄屏时不会与居中标题争抢宽度。
        android.widget.HorizontalScrollView actionsScroll = new android.widget.HorizontalScrollView(this);
        actionsScroll.setFillViewport(true);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        Button back = button("返回");
        stop = button("停止");
        send = button("发送");
        latest = button("回到底部");
        actions.addView(back);
        for (Button action : new Button[]{stop, latest}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            actions.addView(action, lp);
        }
        actionsScroll.addView(actions);
        top.addView(actionsScroll, new LinearLayout.LayoutParams(-1, -2));
        root.addView(top);

        LinearLayout conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        UiDecor.styleCard(this, conversation);
        conversation.setPadding(dp(8), dp(8), dp(8), dp(8));
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
        UiDecor.styleCard(this, inputRow);
        inputRow.setPadding(dp(12), dp(4), dp(8), dp(4));
        composer = new EditText(this);
        composer.setSaveEnabled(false);
        composer.setHint("输入追问…");
        composer.setTextColor(UiDecor.color(this, R.color.text));
        composer.setHintTextColor(UiDecor.color(this, R.color.text_dim));
        composer.setTextSize(16);
        composer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.setMaxLines(4);
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MAX_INPUT)});
        inputRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        inputRow.addView(send);
        root.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));
        composer.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { updateButtons(); }
            public void afterTextChanged(Editable s) {}
        });
        send.setOnClickListener(v -> startRequest(composer.getText().toString().trim()));
        latest.setOnClickListener(v -> result.returnToLatest());
        back.setOnClickListener(v -> finish());
        stop.setOnClickListener(v -> stopRequest("已停止，保留当前回复。"));
        setContentView(root);
    }

    private void readAndValidateIntent() {
        try {
            Intent intent = getIntent();
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
        if (running || invalid || destroyed) return;
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
                body.add("inputs", currentConversation.isEmpty() ? initialInputs.deepCopy() : new JsonObject());
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
                        main.post(() -> finishRequest(id, "回复完成"));
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
            if (activeReply != null) activeReply.state = message;
            dirty = true;
        }
        main.removeCallbacks(renderTick);
        parseLatest();
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
        if (running) stopRequest("页面已重建，原请求已中止；保留部分回复。");
        super.onSaveInstanceState(state);
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
        boolean available = !running && !invalid && !destroyed && (turns == 0 || !conversationId.isEmpty())
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

    private Button button(String text) {
        Button button = UiDecor.button(this, text, false);
        button.setTextSize(14);
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }
}
