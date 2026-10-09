package com.hr.digitalhuman.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.hr.digitalhuman.agents.AgentApiClient;
import com.hr.digitalhuman.agents.AgentDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 独立原生页面：不依赖机器人连接，所有网络操作在后台执行。 */
public class AgentStreamActivity extends AppCompatActivity {
    public static final String EXTRA_AGENT_ID = "agent_id";
    public static final String EXTRA_JOB_INFO = "job_info";
    public static final String EXTRA_JOB_TITLE = "job_title";
    public static final String EXTRA_RESUME_CONTENT = "resume_content";
    public static final String EXTRA_COS_KEY = "cos_key";
    public static final String EXTRA_DOWNLOAD_TOKEN = "download_token";
    public static final String EXTRA_RESUME_NAME = "resume_name";
    public static final String EXTRA_RECORD_ID = "record_id";
    private static final int PICK_FILE = 6101;
    private static final int MAX_INPUT_CHARS = 50000;
    private static final int MAX_ANSWER_CHARS = 200000;
    private boolean invalidParameters;
    private static final int TEXT = 0, LOCAL = 1, COS = 2, HISTORY = 3;
    private static final String SAMPLE_JD = "【虚构示例 JD，请替换】招聘 Java 后端工程师，负责业务接口开发与数据库优化，要求熟悉 Java、SQL 和团队协作。";
    private static final String SAMPLE_RESUME = "【虚构示例简历，请替换】示例候选人：3 年 Java 后端开发经验，参与订单系统接口开发、SQL 优化及自动化测试。以上经历均为虚构。";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final Object answerLock = new Object();
    private final StringBuilder answer = new StringBuilder();
    private Spinner agent, source, careerType;
    private EditText jd, jobTitle, resume, cosKey;
    private TextView status, result, fileInfo, agentHint;
    private Button begin, retry, stop, choose;
    private Uri fileUri;
    private String filename = "resume", mime = "application/octet-stream";
    private String downloadToken = "", resumeName = "", cachedCosKey = "";
    private Long recordId;
    private volatile long requestId;
    private boolean running, initializing = true;
    private volatile boolean destroyed;
    private int lastAgent, lastSource, lastType;
    private AgentApiClient client;
    private Future<?> task;
    private Runnable pendingRender;

    public static void start(Context context) {
        launch(context, new Intent(context, AgentStreamActivity.class));
    }

    public static void startWithResume(Context context, String downloadToken, String resumeName,
                                       String targetPosition, Long recordId) {
        Intent intent = new Intent(context, AgentStreamActivity.class);
        intent.putExtra(EXTRA_AGENT_ID, AgentDefinition.RESUME_DIAGNOSIS);
        intent.putExtra(EXTRA_DOWNLOAD_TOKEN, downloadToken);
        intent.putExtra(EXTRA_RESUME_NAME, resumeName);
        // 职位标题不是 JD，真实历史记录必须由用户补充完整 JD。
        intent.putExtra(EXTRA_JOB_TITLE, targetPosition);
        if (recordId != null) intent.putExtra(EXTRA_RECORD_ID, recordId.longValue());
        launch(context, intent);
    }

    private static void launch(Context context, Intent intent) {
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        createViews();
        if (savedInstanceState != null) restore(savedInstanceState);
        else readIntent();
        bindChanges();
        initializing = false;
        updateSource();
        updateAgent();
        updateButtons();
    }

    private void createViews() {
        LinearLayout root = column();
        root.setBackgroundColor(0xFF0B1422);
        root.setPadding(dp(16), dp(12), dp(16), dp(88));
        TextView heading = UiDecor.title(this, "智能体 · 职业资料分析");
        heading.setTextSize(22);
        root.addView(heading);
        Button back = button("返回");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        LinearLayout panels = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        panels.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView inputScroll = new ScrollView(this);
        inputScroll.setFillViewport(true);
        LinearLayout inputs = column();
        UiDecor.styleCard(this, inputs);
        inputScroll.addView(inputs);
        LinearLayout output = column();
        UiDecor.styleCard(this, output);
        if (wide) {
            panels.addView(inputScroll, new LinearLayout.LayoutParams(0, -1, 0.44f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 0.56f);
            lp.setMargins(dp(12), 0, 0, 0);
            panels.addView(output, lp);
        } else {
            panels.addView(inputScroll, new LinearLayout.LayoutParams(-1, 0, 1.15f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
            lp.setMargins(0, dp(10), 0, 0);
            panels.addView(output, lp);
        }
        label(inputs, "选择智能体");
        agent = spinner(inputs, "职业规划", "简历诊断");
        agentHint = UiDecor.subtitle(this, "");
        inputs.addView(agentHint);
        careerType = spinner(inputs, "晋升路径", "转型建议");
        jobTitle = editor(inputs, "职位标题（可选，不等于 JD）", 1);
        jd = editor(inputs, "职位 JD（简历诊断必填）", 4);
        label(inputs, "简历来源（只发送所选来源）");
        source = spinner(inputs, "编辑简历文本", "本地文件", "已有 cos_key", "历史简历下载凭据");
        resume = editor(inputs, "简历正文", 5);
        cosKey = editor(inputs, "已上传文件的 cos_key（不是 URL 或 token）", 2);
        choose = button("选择本地文件（最多 50MB）");
        inputs.addView(choose);
        choose.setOnClickListener(v -> openDocument());
        fileInfo = UiDecor.subtitle(this, "");
        inputs.addView(fileInfo);
        inputs.addView(UiDecor.subtitle(this,
                "点击开始或重试，即授权将所选简历及本次智能体所需输入发送至品才猫第三方智能体服务。请勿提交未经授权的个人资料。"));
        begin = button("授权并开始");
        retry = button("授权并重试");
        stop = button("停止");
        inputs.addView(begin);
        inputs.addView(retry);
        inputs.addView(stop);
        begin.setOnClickListener(v -> startRequest());
        retry.setOnClickListener(v -> { cancelRequest(false); startRequest(); });
        stop.setOnClickListener(v -> cancelRequest(true));
        output.addView(UiDecor.title(this, "分析结果"));
        status = UiDecor.subtitle(this, "就绪：确认资料后开始");
        output.addView(status);
        ScrollView resultScroll = new ScrollView(this);
        result = UiDecor.title(this, "结果将逐步显示在这里。可随时停止，编辑后重试。");
        result.setTextSize(15);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(12), 0, dp(88));
        resultScroll.addView(result);
        output.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void readIntent() {
        Intent i = getIntent();
        String id = i.getStringExtra(EXTRA_AGENT_ID);
        if (AgentDefinition.RESUME_DIAGNOSIS.equals(id)) agent.setSelection(1);
        else if (id != null && !AgentDefinition.CAREER.equals(id)) {
            invalidParameters = true;
            status.setText("不支持的智能体 ID，请返回并传入 career 或 resume_diagnosis。");
        }
        for (String name : new String[]{EXTRA_RESUME_CONTENT, EXTRA_JOB_INFO, EXTRA_JOB_TITLE, EXTRA_COS_KEY}) {
            String value = i.getStringExtra(name);
            if (value != null && value.length() > MAX_INPUT_CHARS) {
                invalidParameters = true;
                status.setText("传入资料超过文本长度上限，请返回缩短内容或改用文件；不会发送截断资料。");
                return;
            }
        }
        downloadToken = text(i.getStringExtra(EXTRA_DOWNLOAD_TOKEN));
        resumeName = text(i.getStringExtra(EXTRA_RESUME_NAME));
        if (i.hasExtra(EXTRA_RECORD_ID)) recordId = i.getLongExtra(EXTRA_RECORD_ID, 0);
        // 只有完全没有真实资料/记录参数时才提供虚构示例。
        boolean real = i.hasExtra(EXTRA_RESUME_CONTENT) || i.hasExtra(EXTRA_COS_KEY)
                || i.hasExtra(EXTRA_DOWNLOAD_TOKEN) || i.hasExtra(EXTRA_RECORD_ID)
                || i.hasExtra(EXTRA_RESUME_NAME) || i.hasExtra(EXTRA_JOB_TITLE)
                || i.hasExtra(EXTRA_JOB_INFO);
        resume.setText(i.hasExtra(EXTRA_RESUME_CONTENT)
                ? text(i.getStringExtra(EXTRA_RESUME_CONTENT)) : (real ? "" : SAMPLE_RESUME));
        jd.setText(i.hasExtra(EXTRA_JOB_INFO)
                ? text(i.getStringExtra(EXTRA_JOB_INFO)) : (real ? "" : SAMPLE_JD));
        jobTitle.setText(text(i.getStringExtra(EXTRA_JOB_TITLE)));
        cosKey.setText(text(i.getStringExtra(EXTRA_COS_KEY)));
        // 冲突规则固定且可见：正文 > cos_key > 历史 token；仍允许用户手动切换。
        if (!resume.getText().toString().trim().isEmpty()) source.setSelection(TEXT);
        else if (!cosKey.getText().toString().trim().isEmpty()) source.setSelection(COS);
        else if (!downloadToken.isEmpty()) source.setSelection(HISTORY);
        if ((i.hasExtra(EXTRA_RESUME_CONTENT) && i.hasExtra(EXTRA_COS_KEY))
                || (i.hasExtra(EXTRA_DOWNLOAD_TOKEN) && (i.hasExtra(EXTRA_RESUME_CONTENT)
                || i.hasExtra(EXTRA_COS_KEY)))) {
            status.setText("多种资料参数：优先正文，其次 cos_key，最后历史凭据；只发送当前所选来源。");
        }
    }

    private void bindChanges() {
        lastAgent = agent.getSelectedItemPosition();
        lastSource = source.getSelectedItemPosition();
        lastType = careerType.getSelectedItemPosition();
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int p, long id) {
                if (initializing) return;
                // 忽略首次布局及相同选项回调，避免清除旋转恢复的上传缓存。
                if (parent == source) {
                    if (p == lastSource) return;
                    lastSource = p;
                    cachedCosKey = "";
                    updateSource();
                } else if (parent == agent) {
                    if (p == lastAgent) return;
                    lastAgent = p;
                    cachedCosKey = ""; // 不跨智能体密钥复用上传结果。
                    updateAgent();
                } else {
                    if (p == lastType) return;
                    lastType = p;
                }
                if (running) cancelRequest(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        agent.setOnItemSelectedListener(listener);
        source.setOnItemSelectedListener(listener);
        careerType.setOnItemSelectedListener(listener);
        for (EditText input : new EditText[]{resume, cosKey, jd, jobTitle}) {
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (initializing) return;
                    if (input == resume || input == cosKey) cachedCosKey = "";
                    if (running) cancelRequest(true);
                }
                @Override public void afterTextChanged(Editable s) { }
            });
        }
    }

    private void updateAgent() {
        boolean career = agent.getSelectedItemPosition() == 0;
        careerType.setVisibility(career ? View.VISIBLE : View.GONE);
        agentHint.setText(career ? "职业规划不发送 JD 或职位标题；JD 仅保留供切换诊断使用。"
                : "简历诊断必须填写完整 JD；职位标题仅为可选补充。未知职位请勿使用示例替代。");
    }

    private void updateSource() {
        int mode = source.getSelectedItemPosition();
        resume.setVisibility(mode == TEXT ? View.VISIBLE : View.GONE);
        cosKey.setVisibility(mode == COS ? View.VISIBLE : View.GONE);
        choose.setVisibility(mode == LOCAL ? View.VISIBLE : View.GONE);
        if (mode == LOCAL) fileInfo.setText(fileUri == null ? "尚未选择本地文件" : "本地文件：" + filename);
        else if (mode == HISTORY) fileInfo.setText(downloadToken.isEmpty()
                ? "没有历史下载凭据，请改用文本或本地文件。"
                : "真实历史简历：" + (resumeName.isEmpty() ? "未命名" : resumeName)
                + (recordId == null ? "" : "（记录 " + recordId + "）")
                + "；开始后下载 PDF 并上传，不以虚构简历替代。");
        else if (mode == COS) fileInfo.setText("仅接受已上传的 cos_key；不要填写下载 token 或公网 URL。");
        else fileInfo.setText("编辑正文；含“虚构示例”的内容仅用于演示，请替换为授权资料。");
    }

    private void openDocument() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, PICK_FILE); }
        catch (RuntimeException e) { status.setText("系统文件选择器不可用，请使用简历文本。"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        cancelRequest(false);
        cachedCosKey = "";
        fileUri = data.getData();
        filename = "resume";
        mime = "application/octet-stream";
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) getContentResolver().takePersistableUriPermission(fileUri, flags);
        } catch (RuntimeException ignored) { /* 临时读权限仍可用于本次会话。 */ }
        try (Cursor cursor = getContentResolver().query(fileUri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) filename = name;
            }
            String type = getContentResolver().getType(fileUri);
            if (type != null) mime = type;
        } catch (RuntimeException ignored) { /* 不输出包含 URI 的系统异常。 */ }
        source.setSelection(LOCAL);
        updateSource();
        status.setText("文件已选择；点击开始授权上传。");
    }

    private void startRequest() {
        if (running || destroyed || invalidParameters) return;
        AgentDefinition definition = agent.getSelectedItemPosition() == 0
                ? AgentDefinition.CAREER_AGENT : AgentDefinition.DIAGNOSIS_AGENT;
        String key = definition.apiKey();
        if (key == null || key.trim().isEmpty()) {
            status.setText("请在 local.properties 配置 " + definition.configurationName
                    + "，由对应 BuildConfig 密钥字段读取。");
            return;
        }
        final int mode = source.getSelectedItemPosition();
        final String resumeText = resume.getText().toString();
        final String jdText = jd.getText().toString();
        final String titleText = jobTitle.getText().toString();
        final String planningType = careerType.getSelectedItem().toString();
        final String explicitKey = cosKey.getText().toString().trim();
        if (definition == AgentDefinition.DIAGNOSIS_AGENT && jdText.trim().isEmpty()) {
            status.setText("请补充真实职位 JD，职位标题不能替代 JD。"); return;
        }
        if ((mode == TEXT && resumeText.trim().isEmpty()) || (mode == LOCAL && fileUri == null)
                || (mode == COS && explicitKey.isEmpty()) || (mode == HISTORY && downloadToken.isEmpty())) {
            status.setText("当前来源没有可用简历，请补充资料或切换来源。"); return;
        }
        if (mode == COS && (explicitKey.contains("://") || explicitKey.equals(downloadToken))) {
            status.setText("请输入已上传的 cos_key，不要使用 URL 或历史下载 token。"); return;
        }
        final AgentApiClient requestClient;
        try { requestClient = new AgentApiClient(key); }
        catch (IllegalArgumentException e) { status.setText("密钥配置无效，请检查 " + definition.configurationName); return; }
        final Uri selectedUri = fileUri;
        final String selectedName = filename, selectedMime = mime, selectedToken = downloadToken;
        final String reusableKey = cachedCosKey;
        final long id = ++requestId;
        final java.util.concurrent.atomic.AtomicBoolean outputLimitReached = new java.util.concurrent.atomic.AtomicBoolean();
        client = requestClient;
        running = true;
        synchronized (answerLock) { answer.setLength(0); }
        result.setText("");
        status.setText(mode == LOCAL || mode == HISTORY ? "准备简历并上传…" : "正在分析…");
        updateButtons();
        task = worker.submit(() -> {
            try {
                String uploaded = mode == COS ? explicitKey : reusableKey;
                if ((mode == LOCAL || mode == HISTORY) && uploaded.isEmpty()) {
                    if (mode == LOCAL) {
                        try (InputStream input = getContentResolver().openInputStream(selectedUri)) {
                            if (input == null) throw new IOException();
                            uploaded = requestClient.upload(input, selectedName, selectedMime);
                        }
                    } else uploaded = requestClient.uploadResume(selectedToken);
                    final String cache = uploaded;
                    main.post(() -> { if (isCurrent(id)) cachedCosKey = cache; });
                }
                if (!isCurrent(id)) return;
                JsonObject body = definition.buildRequest(planningType, jdText, titleText,
                        mode == TEXT ? resumeText : "", mode == TEXT ? "" : uploaded);
                main.post(() -> { if (isCurrent(id)) status.setText("正在分析，结果持续更新…"); });
                requestClient.stream(body, new AgentApiClient.Listener() {
                    @Override public void onAnswer(String chunk) {
                        synchronized (answerLock) {
                            if (!isCurrent(id)) return;
                            if ((long) answer.length() + chunk.length() > MAX_ANSWER_CHARS) {
                                outputLimitReached.set(true);
                                throw new IllegalStateException("结果超过显示上限");
                            }
                            answer.append(chunk);
                        }
                        scheduleRender(id);
                    }
                    @Override public void onComplete() {
                        main.post(() -> finishRequest(id, "分析完成"));
                    }
                });
            } catch (IOException | RuntimeException e) {
                // 不显示原始异常、服务端正文、下载凭据或密钥。
                main.post(() -> finishRequest(id, outputLimitReached.get()
                        ? "结果超过显示上限，已停止接收；请缩小分析范围后重试。"
                        : "请求失败或流中断。请检查网络、密钥权限和文件（最多 50MB），确认资料后重试。"));
            }
        });
    }

    private boolean isCurrent(long id) { return !destroyed && requestId == id; }

    /** 每个请求最多登记一个 100ms 更新任务，不为每个分片向主线程排队。 */
    private void scheduleRender(long id) {
        synchronized (answerLock) {
            if (!isCurrent(id) || pendingRender != null) return;
            pendingRender = () -> {
                synchronized (answerLock) {
                    pendingRender = null;
                    if (isCurrent(id)) result.setText(answer.toString());
                }
            };
            main.postDelayed(pendingRender, 100);
        }
    }

    private void finishRequest(long id, String message) {
        if (!isCurrent(id)) return;
        synchronized (answerLock) {
            if (pendingRender != null) main.removeCallbacks(pendingRender);
            pendingRender = null;
            result.setText(answer.toString());
        }
        running = false;
        client = null;
        task = null;
        status.setText(message);
        updateButtons();
    }

    private void cancelRequest(boolean showStatus) {
        ++requestId; // 先使旧回调失效，再中断任务和连接。
        AgentApiClient oldClient = client;
        client = null;
        if (task != null) task.cancel(true);
        task = null;
        if (oldClient != null) {
            // disconnect/close 可能涉及底层 I/O，不阻塞主线程。
            worker.execute(oldClient::cancel);
        }
        synchronized (answerLock) {
            if (pendingRender != null) main.removeCallbacks(pendingRender);
            pendingRender = null;
            if (result != null) result.setText(answer.toString());
        }
        running = false;
        if (showStatus && status != null) status.setText("已停止；保留当前结果，可编辑资料后重试。");
        updateButtons();
    }

    private void updateButtons() {
        if (begin == null) return;
        begin.setEnabled(!running && !invalidParameters);
        retry.setEnabled(!running && !invalidParameters);
        stop.setEnabled(running);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("invalid_parameters", invalidParameters);
        state.putInt("agent", agent.getSelectedItemPosition());
        state.putInt("source", source.getSelectedItemPosition());
        state.putInt("type", careerType.getSelectedItemPosition());
        state.putString("jd", jd.getText().toString());
        state.putString("title", jobTitle.getText().toString());
        state.putString("resume", resume.getText().toString());
        state.putString("cos", cosKey.getText().toString());
        state.putString("uri", fileUri == null ? "" : fileUri.toString());
        state.putString("filename", filename);
        state.putString("mime", mime);
        state.putString("token", downloadToken);
        state.putString("name", resumeName);
        if (recordId != null) state.putLong("record", recordId);
        state.putString("cache", cachedCosKey);
        // 输入完整保存；大段结果不放入 Bundle，避免 Binder 事务过大。
        synchronized (answerLock) {
            state.putString("answer", answer.substring(0, Math.min(answer.length(), 16000)));
        }
        state.putString("status", running ? "页面已重建，原请求已停止；请确认资料后重试。" : status.getText().toString());
    }

    private void restore(Bundle state) {
        invalidParameters = state.getBoolean("invalid_parameters", false);
        agent.setSelection(state.getInt("agent"));
        source.setSelection(state.getInt("source"));
        careerType.setSelection(state.getInt("type"));
        jd.setText(state.getString("jd", ""));
        jobTitle.setText(state.getString("title", ""));
        resume.setText(state.getString("resume", ""));
        cosKey.setText(state.getString("cos", ""));
        String uri = state.getString("uri", "");
        if (!uri.isEmpty()) fileUri = Uri.parse(uri);
        filename = state.getString("filename", "resume");
        mime = state.getString("mime", "application/octet-stream");
        downloadToken = state.getString("token", "");
        resumeName = state.getString("name", "");
        if (state.containsKey("record")) recordId = state.getLong("record");
        cachedCosKey = state.getString("cache", "");
        answer.append(state.getString("answer", ""));
        result.setText(answer.toString());
        status.setText(state.getString("status", "就绪"));
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelRequest(false);
        main.removeCallbacksAndMessages(null);
        // 已排队的 cancel 仍执行；不立即 shutdownNow 丢弃取消任务。
        worker.shutdown();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private int dp(int value) { return UiDecor.dp(this, value); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private void label(LinearLayout parent, String value) { parent.addView(UiDecor.subtitle(this, value)); }
    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(0xFFEAF2FF);
        androidx.core.view.ViewCompat.setBackgroundTintList(button,
                android.content.res.ColorStateList.valueOf(0xFF264C76));
        button.setMinHeight(dp(48));
        return button;
    }
    private Spinner spinner(LinearLayout parent, String... options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, options) {
            @Override public View getView(int position, View convertView, ViewGroup group) {
                TextView view = (TextView) super.getView(position, convertView, group);
                view.setTextColor(0xFFEAF2FF);
                view.setPadding(dp(8), dp(12), dp(8), dp(12));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
        return spinner;
    }
    private EditText editor(LinearLayout parent, String hint, int lines) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(0xFFEAF2FF);
        field.setHintTextColor(0xFF9FB1C9);
        field.setTextSize(14);
        // 输入会随页面状态保存，限制长度以免超出 Android 状态事务大小。
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MAX_INPUT_CHARS)});
        field.setMinLines(lines);
        field.setMaxLines(Math.max(lines, 8));
        field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | (lines > 1 ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }
}
