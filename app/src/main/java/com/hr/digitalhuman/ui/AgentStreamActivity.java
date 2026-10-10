package com.hr.digitalhuman.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.agents.AgentApiClient;
import com.hr.digitalhuman.agents.AgentDefinition;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


/** 独立原生页面：不依赖机器人连接，所有网络操作在后台执行。 */
public class AgentStreamActivity extends AppCompatActivity {
    public static final String EXTRA_AGENT_ID = "agent_id";
    public static final String EXTRA_JOB_INFO = "job_info";
    public static final String EXTRA_JOB_TITLE = "job_title";
    public static final String EXTRA_RESUME_CONTENT = "resume_content";
    public static final String EXTRA_FILE_URL = "file_url";
    public static final String EXTRA_DOWNLOAD_TOKEN = "download_token";
    public static final String EXTRA_RESUME_NAME = "resume_name";
    public static final String EXTRA_RECORD_ID = "record_id";
    private static final int PICK_FILE = 6101;
    private static final int MAX_INPUT_CHARS = 50000;
    private boolean invalidParameters;
    private static final int TEXT = 0, LOCAL = 1, URL_FILE = 2, HISTORY = 3, NONE = 4;
    private static final AgentDefinition[] AGENTS = {AgentDefinition.CAREER_AGENT,
            AgentDefinition.DIAGNOSIS_AGENT, AgentDefinition.OPTIMIZATION_AGENT, AgentDefinition.INTERVIEW_AGENT};
    private static final String SAMPLE_JD = "【虚构示例 JD，请替换】招聘 Java 后端工程师，负责业务接口开发与数据库优化，要求熟悉 Java、SQL 和团队协作。";
    private static final String SAMPLE_RESUME = "【虚构示例简历，请替换】示例候选人：3 年 Java 后端开发经验，参与订单系统接口开发、SQL 优化及自动化测试。以上经历均为虚构。";

    private Spinner agent, source, careerType;
    private EditText jd, jobTitle, resume, fileUrl, questionNumber;
    private Spinner interviewRole, referenceAnswer;
    private LinearLayout interviewSettings;
    private TextView status, fileInfo, agentHint;
    private Button begin, choose, cancel;
    private final ExecutorService preparationExecutor = Executors.newCachedThreadPool();
    private Future<?> preparationTask;
    private AgentApiClient preparationClient;
    // 仅主线程读写；每次取消先递增，已排队的成功/失败回调也会失效。
    private long generation;
    private boolean preparing;
    private Uri fileUri;
    private String filename = "resume", mime = "application/octet-stream";
    private String downloadToken = "", resumeName = "";
    private Long recordId;
    private boolean initializing = true;
    private int lastAgent, lastSource, lastType;

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
        root.setBackgroundResource(R.drawable.bg_page);
        root.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = button("返回");
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView heading = UiDecor.title(this, "智能体 · 职业资料分析");
        heading.setTextSize(18);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setGravity(android.view.Gravity.CENTER);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        // 横屏按“分析配置 / 简历资料”分栏，各自滚动，键盘出现时仍可编辑。
        LinearLayout panels = new LinearLayout(this);
        LinearLayout.LayoutParams panelsLp = new LinearLayout.LayoutParams(-1, 0, 1);
        panelsLp.topMargin = dp(12);
        root.addView(panels, panelsLp);
        LinearLayout settings = column();
        LinearLayout inputs = column();
        LinearLayout[] columns = {settings, inputs};
        for (int i = 0; i < columns.length; i++) {
            ScrollView inputScroll = new ScrollView(this);
            inputScroll.setFillViewport(true);
            columns[i].setBackgroundResource(R.drawable.bg_glass_panel);
            columns[i].setPadding(dp(16), dp(16), dp(16), dp(16));
            inputScroll.addView(columns[i], new ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, i == 0 ? 1f : 1.2f);
            if (i > 0) lp.leftMargin = dp(12);
            panels.addView(inputScroll, lp);
        }
        label(settings, "选择智能体");
        String[] names = new String[AGENTS.length];
        for (int i = 0; i < AGENTS.length; i++) names[i] = AGENTS[i].name;
        agent = spinner(settings, names);
        agentHint = UiDecor.subtitle(this, "");
        settings.addView(agentHint);
        careerType = spinner(settings, "晋升路径", "转型建议");
        jobTitle = editor(settings, "职位标题（可选，不等于 JD）", 1);
        jd = editor(settings, "职位 JD", 4);
        interviewSettings = column();
        settings.addView(interviewSettings);
        label(interviewSettings, "面试题数（默认 5）");
        questionNumber = editor(interviewSettings, "5–15 题，留空默认 5", 1);
        questionNumber.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        questionNumber.setText("5");
        label(interviewSettings, "面试类型");
        interviewRole = spinner(interviewSettings, "HR综合面试", "专业面试");
        label(interviewSettings, "参考答案");
        referenceAnswer = spinner(interviewSettings, "不生成参考答案", "生成参考答案");
        label(inputs, "简历来源（只发送所选来源）");
        source = spinner(inputs, "编辑简历文本", "本地文件", "公网URL文件", "历史简历下载凭据", "不提供简历（仅模拟面试）");
        resume = editor(inputs, "简历正文", 5);
        fileUrl = editor(inputs, "公网文件完整 URL（http/https，不是下载 token）", 2);
        choose = button("选择本地文件（最多 50MB）");
        inputs.addView(choose, UiDecor.cardLp(this, 8));
        choose.setOnClickListener(v -> openDocument());
        fileInfo = UiDecor.subtitle(this, "");
        inputs.addView(fileInfo);
        begin = UiDecor.button(this, "开始", true);
        LinearLayout.LayoutParams beginLp = UiDecor.cardLp(this, 0);
        beginLp.topMargin = dp(12);
        inputs.addView(begin, beginLp);
        begin.setOnClickListener(v -> startRequest());
        cancel = button("取消准备");
        inputs.addView(cancel, UiDecor.cardLp(this, 8));
        cancel.setOnClickListener(v -> {
            cancelPreparation();
            status.setText("已取消准备，可修改资料后重新开始。");
        });
        status = UiDecor.subtitle(this, "确认资料后开始，将在独立页面显示结果或进行面试问答。");
        inputs.addView(status);
        setContentView(root);
    }

    private void readIntent() {
        Intent i = getIntent();
        String id = i.getStringExtra(EXTRA_AGENT_ID);
        if (id != null) {
            try {
                AgentDefinition definition = AgentDefinition.fromId(id);
                for (int p = 0; p < AGENTS.length; p++) {
                    if (AGENTS[p] == definition) agent.setSelection(p);
                }
            } catch (IllegalArgumentException e) {
                invalidParameters = true;
                status.setText("不支持的智能体 ID，请返回并选择受支持的智能体。");
            }
        }
        for (String name : new String[]{EXTRA_RESUME_CONTENT, EXTRA_JOB_INFO, EXTRA_JOB_TITLE, EXTRA_FILE_URL}) {
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
        boolean real = i.hasExtra(EXTRA_RESUME_CONTENT) || i.hasExtra(EXTRA_FILE_URL)
                || i.hasExtra(EXTRA_DOWNLOAD_TOKEN) || i.hasExtra(EXTRA_RECORD_ID)
                || i.hasExtra(EXTRA_RESUME_NAME) || i.hasExtra(EXTRA_JOB_TITLE)
                || i.hasExtra(EXTRA_JOB_INFO);
        resume.setText(i.hasExtra(EXTRA_RESUME_CONTENT)
                ? text(i.getStringExtra(EXTRA_RESUME_CONTENT)) : (real ? "" : SAMPLE_RESUME));
        jd.setText(i.hasExtra(EXTRA_JOB_INFO)
                ? text(i.getStringExtra(EXTRA_JOB_INFO)) : (real ? "" : SAMPLE_JD));
        jobTitle.setText(text(i.getStringExtra(EXTRA_JOB_TITLE)));
        String suppliedUrl = i.getStringExtra(EXTRA_FILE_URL);
        // 不 trim URL，保留控制字符供开始时校验拒绝，不悄悄修正非法地址。
        fileUrl.setText(suppliedUrl == null ? "" : suppliedUrl);
        // 冲突规则固定且可见：正文 > file_url > 历史 token；仍允许用户手动切换。
        if (!resume.getText().toString().trim().isEmpty()) source.setSelection(TEXT);
        else if (!fileUrl.getText().toString().trim().isEmpty()) source.setSelection(URL_FILE);
        else if (!downloadToken.isEmpty()) source.setSelection(HISTORY);
        if ((i.hasExtra(EXTRA_RESUME_CONTENT) && i.hasExtra(EXTRA_FILE_URL))
                || (i.hasExtra(EXTRA_DOWNLOAD_TOKEN) && (i.hasExtra(EXTRA_RESUME_CONTENT)
                || i.hasExtra(EXTRA_FILE_URL)))) {
            status.setText("多种资料参数：优先正文，其次 file_url，最后历史凭据；只发送当前所选来源。");
        }
    }

    private void bindChanges() {
        lastAgent = agent.getSelectedItemPosition();
        lastSource = source.getSelectedItemPosition();
        lastType = careerType.getSelectedItemPosition();
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int p, long id) {
                if (initializing) return;
                // 忽略首次布局及相同选项回调，保留旋转恢复后的来源选择。
                if (parent == source) {
                    if (p == lastSource) return;
                    lastSource = p;
                    updateSource();
                } else if (parent == agent) {
                    if (p == lastAgent) return;
                    lastAgent = p;
                    updateAgent();
                } else {
                    if (p == lastType) return;
                    lastType = p;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        agent.setOnItemSelectedListener(listener);
        source.setOnItemSelectedListener(listener);
        careerType.setOnItemSelectedListener(listener);
    }

    private void updateAgent() {
        AgentDefinition definition = AGENTS[agent.getSelectedItemPosition()];
        boolean career = definition == AgentDefinition.CAREER_AGENT;
        boolean interview = definition == AgentDefinition.INTERVIEW_AGENT;
        careerType.setVisibility(career ? View.VISIBLE : View.GONE);
        interviewSettings.setVisibility(interview ? View.VISIBLE : View.GONE);
        jobTitle.setVisibility(definition == AgentDefinition.DIAGNOSIS_AGENT || interview ? View.VISIBLE : View.GONE);
        jd.setVisibility(career ? View.GONE : View.VISIBLE);
        if (career) agentHint.setText("职业规划不发送 JD 或职位标题；资料保留供切换智能体使用。");
        else if (definition == AgentDefinition.OPTIMIZATION_AGENT) {
            jd.setHint("职位 JD（可选，填写后针对岗位优化）");
            agentHint.setText("直接改写简历；不填 JD 为通用优化，填写后针对岗位优化。简历必填。");
        } else {
            jd.setHint("职位 JD（必填，职位标题不能替代）");
            agentHint.setText(interview ? "填写真实 JD 后开始面试，简历可选；在结果页回答问题，可使用语音转文字。"
                    : "简历诊断必须填写完整 JD；职位标题仅为可选补充。未知职位请勿使用示例替代。");
        }
    }

    private void updateSource() {
        int mode = source.getSelectedItemPosition();
        resume.setVisibility(mode == TEXT ? View.VISIBLE : View.GONE);
        fileUrl.setVisibility(mode == URL_FILE ? View.VISIBLE : View.GONE);
        choose.setVisibility(mode == LOCAL ? View.VISIBLE : View.GONE);
        if (mode == LOCAL) fileInfo.setText(fileUri == null ? "尚未选择本地文件" : "本地文件：" + filename);
        else if (mode == HISTORY) fileInfo.setText(downloadToken.isEmpty()
                ? "没有历史下载凭据，请改用文本或本地文件。"
                : "真实历史简历：" + (resumeName.isEmpty() ? "未命名" : resumeName)
                + (recordId == null ? "" : "（记录 " + recordId + "）")
                + "；开始后下载 PDF 并上传，不以虚构简历替代。");
        else if (mode == URL_FILE) fileInfo.setText("使用可公开访问的 http/https 完整文件 URL；不接受本机、私网或下载 token。");
        else if (mode == NONE) fileInfo.setText("仅模拟面试可不提供简历；只按职位 JD 和面试设置提问。");
        else fileInfo.setText("编辑正文；含“虚构示例”的内容仅用于演示，请替换为实际简历。");
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
        if (preparing || isFinishing() || isDestroyed() || requestCode != PICK_FILE
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
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
        status.setText("文件已选择；点击开始上传并分析。");
    }

    private void startRequest() {
        if (invalidParameters || preparing || isFinishing() || isDestroyed()) return;
        AgentDefinition definition = AGENTS[agent.getSelectedItemPosition()];
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
        final String explicitUrl = fileUrl.getText().toString();
        String suppliedCount = questionNumber.getText().toString().trim();
        final String count = suppliedCount.isEmpty() ? "5" : suppliedCount;
        final int role = interviewRole.getSelectedItemPosition() + 1;
        final int reference = referenceAnswer.getSelectedItemPosition();
        if (definition == AgentDefinition.INTERVIEW_AGENT && !count.matches("[5-9]|1[0-5]")) {
            status.setText("面试题数支持 5–15，留空默认 5。"); return;
        }
        if (mode == NONE && definition != AgentDefinition.INTERVIEW_AGENT) {
            status.setText("该智能体需要简历，请切换简历来源并补充资料。"); return;
        }
        if ((definition == AgentDefinition.DIAGNOSIS_AGENT || definition == AgentDefinition.INTERVIEW_AGENT)
                && jdText.trim().isEmpty()) {
            status.setText("请补充真实职位 JD，职位标题不能替代 JD。"); return;
        }
        if ((mode == TEXT && resumeText.trim().isEmpty()) || (mode == LOCAL && fileUri == null)
                || (mode == URL_FILE && explicitUrl.isEmpty()) || (mode == HISTORY && downloadToken.isEmpty())) {
            status.setText("当前来源没有可用简历，请补充资料或切换来源。"); return;
        }
        if (mode == URL_FILE && !isPublicFileUrl(explicitUrl)) {
            status.setText("请输入合法的公网 http/https 完整文件 URL，不含账号密码、控制字符或本机/私网地址。");
            return;
        }
        final AgentApiClient client;
        try { client = new AgentApiClient(key); }
        catch (IllegalArgumentException e) {
            status.setText("密钥配置无效，请检查 " + definition.configurationName); return;
        }
        // 冻结全部选择参数，工作线程不读取任何控件或可变的文件/历史记录字段。
        final Uri selectedUri = fileUri;
        final String selectedName = filename, selectedMime = mime, selectedToken = downloadToken;
        final String selectedHistoryName = resumeName;
        final String summary = initialSummary(definition, planningType, titleText, mode,
                resumeText, mode == LOCAL ? selectedName : selectedHistoryName)
                + (definition == AgentDefinition.INTERVIEW_AGENT ? "\n面试：" + count + " 题 · "
                + (role == 1 ? "HR综合面试" : "专业面试")
                + (reference == 1 ? " · 生成参考答案" : " · 不生成参考答案") : "");
        if (mode == TEXT || mode == URL_FILE || mode == NONE) {
            try {
                JsonObject body = definition.buildRequest(planningType, jdText, titleText,
                        mode == TEXT ? resumeText : "", mode == URL_FILE ? explicitUrl : "",
                        "", count, role, reference);
                openResult(definition, key, body, summary);
            } catch (RuntimeException e) {
                status.setText("资料准备失败，请检查输入后重试。");
            }
            return;
        }
        final long requestGeneration = ++generation;
        preparing = true;
        preparationClient = client;
        updateButtons();
        status.setText(mode == HISTORY ? "正在下载并上传历史简历，可取消准备。" : "正在上传文件，可取消准备。");
        preparationTask = preparationExecutor.submit(() -> {
            try {
                final String uploadedFile;
                if (mode == HISTORY) uploadedFile = client.uploadResume(selectedToken);
                else {
                    // openInputStream 可能不响应中断；返回后仍交给已取消的 client，确保关闭。
                    try (InputStream input = getContentResolver().openInputStream(selectedUri)) {
                        uploadedFile = client.upload(input, selectedName, selectedMime);
                    }
                }
                JsonObject body = definition.buildRequest(planningType, jdText, titleText, "", uploadedFile,
                        mode == LOCAL ? selectedName : selectedHistoryName, count, role, reference);
                runOnUiThread(() -> {
                    if (!isCurrentPreparation(requestGeneration)) return;
                    preparing = false;
                    preparationClient = null;
                    preparationTask = null;
                    updateButtons();
                    try { openResult(definition, key, body, summary); }
                    catch (RuntimeException e) { status.setText("无法打开结果页面，请重试。"); }
                });
            } catch (Exception e) {
                // 不透传异常 message、上传响应或 URI，以免泄露 token、key 和简历内容。
                runOnUiThread(() -> {
                    if (!isCurrentPreparation(requestGeneration)) return;
                    preparing = false;
                    preparationClient = null;
                    preparationTask = null;
                    updateButtons();
                    status.setText("文件准备失败，请检查文件或网络后重试。");
                });
            }
        });
    }

    private void openResult(AgentDefinition definition, String key, JsonObject body, String summary) {
        if (isFinishing() || isDestroyed()) return;
        AgentResultActivity.start(this, key, definition.name, body.getAsJsonObject("inputs"),
                body.get("query").getAsString(), "", body.get("response_mode").getAsString(), summary,
                definition == AgentDefinition.INTERVIEW_AGENT, 10, 3000, 10000);
    }

    private static String initialSummary(AgentDefinition definition, String type, String title,
                                         int mode, String resumeText, String name) {
        String business = definition.name + (definition == AgentDefinition.CAREER_AGENT ? " · " + type : "");
        if (definition == AgentDefinition.OPTIMIZATION_AGENT) business += " · 直接改写";
        if ((definition == AgentDefinition.DIAGNOSIS_AGENT || definition == AgentDefinition.INTERVIEW_AGENT)
                && !title.trim().isEmpty()) {
            business += "\n职位：" + title.trim();
        }
        if (mode == NONE) return business + "\n未提供简历，按职位 JD 面试。";
        if (mode == TEXT) {
            String content = resumeText.trim();
            int end = content.offsetByCodePoints(0, Math.min(120, content.codePointCount(0, content.length())));
            return business + "\n简历：" + content.substring(0, end);
        }
        // URL 的路径/查询可能携带凭据，不从 URL 提取文件名，不展示 URL 或下载 token。
        return business + "\n文件：" + (mode == URL_FILE ? "公网URL文件" : (name.isEmpty() ? "未命名" : name));
    }

    private boolean isCurrentPreparation(long expected) {
        return preparing && generation == expected && !isFinishing() && !isDestroyed();
    }

    private void cancelPreparation() {
        // 先使 UI 回调失效，再断开网络并中断工作线程；取消不保证服务端撤回已接收的文件。
        // 即使上传刚完成、成功回调已经排队，也不能在取消、退出或旋转后跳转。
        ++generation;
        preparing = false;
        AgentApiClient client = preparationClient;
        Future<?> task = preparationTask;
        preparationClient = null;
        preparationTask = null;
        if (task != null) task.cancel(true);
        // disconnect/close 可能阻塞；独立短生命周期线程不占用主线程，
        // 也不排在上传任务之后或依赖即将关闭的 preparationExecutor。
        if (client != null) new Thread(client::cancel, "agent-preparation-cancel").start();
        updateButtons();
    }

    @Override public void finish() {
        cancelPreparation();
        super.finish();
    }

    @Override protected void onDestroy() {
        cancelPreparation();
        preparationExecutor.shutdown();
        super.onDestroy();
    }

    private void updateButtons() {
        if (begin == null) return;
        begin.setEnabled(!invalidParameters && !preparing);
        for (View view : new View[]{agent, source, careerType, jd, jobTitle, resume, fileUrl, choose,
                questionNumber, interviewRole, referenceAnswer}) {
            view.setEnabled(!preparing);
        }
        cancel.setVisibility(preparing ? View.VISIBLE : View.GONE);
        cancel.setEnabled(preparing);
    }

    /** 纯 URI/字面量校验，不查询 DNS；域名实际解析和重定向的安全边界需由服务端保证。 */
    private static boolean isPublicFileUrl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i)) || Character.isWhitespace(value.charAt(i))) return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getPort() == 0 || uri.getPort() > 65535) return false;
            // 拒绝转义控制字符，避免 URL 解码后出现换行/NUL 等。
            String decoded = uri.getSchemeSpecificPart();
            for (int i = 0; i < decoded.length(); i++) {
                if (Character.isISOControl(decoded.charAt(i))) return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.startsWith("[")) {
                // 保守接受全球单播 IPv6（2000::/3），排除本机、ULA、链路本地及映射 IPv4。
                String literal = host.substring(1, host.length() - 1);
                return literal.matches("[23][0-9a-f]{3}:[0-9a-f:]+")
                        && !literal.startsWith("2001:db8:");
            }
            if (host.matches("[0-9.]+")) {
                String[] parts = host.split("\\.", -1);
                if (parts.length != 4) return false;
                int[] octets = new int[4];
                for (int i = 0; i < 4; i++) {
                    if (parts[i].isEmpty() || parts[i].length() > 3
                            || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
                    octets[i] = Integer.parseInt(parts[i]);
                    if (octets[i] > 255) return false;
                }
                int a = octets[0], b = octets[1];
                return a != 0 && a != 10 && a != 127 && a < 224
                        && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                        && !(a == 192 && (b == 168 || b == 0))
                        && !(a == 100 && b >= 64 && b <= 127) && !(a == 198 && (b == 18 || b == 19));
            }
            // 单标签、常见本地域名和非标准数字 IP 表示不当作公网地址。
            return host.contains(".") && !host.equals("localhost") && !host.endsWith(".localhost")
                    && !host.endsWith(".local") && !host.endsWith(".lan") && !host.endsWith(".internal")
                    && !host.endsWith(".home") && !host.endsWith(".home.arpa")
                    && !host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*");
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // 保存状态即取消准备，不恢复网络任务或自动重传，旋转后由用户再次确认开始。
        boolean wasPreparing = preparing;
        cancelPreparation();
        if (wasPreparing) status.setText("页面状态已保存，文件准备已取消；请重新开始。");
        super.onSaveInstanceState(state);
        state.putBoolean("invalid_parameters", invalidParameters);
        state.putInt("agent", agent.getSelectedItemPosition());
        state.putInt("source", source.getSelectedItemPosition());
        state.putInt("type", careerType.getSelectedItemPosition());
        state.putString("question_number", questionNumber.getText().toString());
        state.putInt("interview_role", interviewRole.getSelectedItemPosition());
        state.putInt("reference_answer", referenceAnswer.getSelectedItemPosition());
        state.putString("jd", jd.getText().toString());
        state.putString("title", jobTitle.getText().toString());
        state.putString("resume", resume.getText().toString());
        state.putString("file_url", fileUrl.getText().toString());
        state.putString("uri", fileUri == null ? "" : fileUri.toString());
        state.putString("filename", filename);
        state.putString("mime", mime);
        state.putString("token", downloadToken);
        state.putString("name", resumeName);
        if (recordId != null) state.putLong("record", recordId);
        state.putString("status", status.getText().toString());
    }

    private void restore(Bundle state) {
        invalidParameters = state.getBoolean("invalid_parameters", false);
        agent.setSelection(state.getInt("agent"));
        source.setSelection(state.getInt("source"));
        careerType.setSelection(state.getInt("type"));
        questionNumber.setText(state.getString("question_number", "5"));
        interviewRole.setSelection(state.getInt("interview_role", 0));
        referenceAnswer.setSelection(state.getInt("reference_answer", 0));
        jd.setText(state.getString("jd", ""));
        jobTitle.setText(state.getString("title", ""));
        resume.setText(state.getString("resume", ""));
        fileUrl.setText(state.getString("file_url", ""));
        String uri = state.getString("uri", "");
        if (!uri.isEmpty()) fileUri = Uri.parse(uri);
        filename = state.getString("filename", "resume");
        mime = state.getString("mime", "application/octet-stream");
        downloadToken = state.getString("token", "");
        resumeName = state.getString("name", "");
        if (state.containsKey("record")) recordId = state.getLong("record");
        status.setText(state.getString("status", "就绪"));
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
        return UiDecor.button(this, title, false);
    }
    private Spinner spinner(LinearLayout parent, String... options) {
        Spinner spinner = new androidx.appcompat.widget.AppCompatSpinner(this, Spinner.MODE_DROPDOWN);
        androidx.core.view.ViewCompat.setBackgroundTintList(spinner, null);
        spinner.setBackgroundResource(R.drawable.bg_input);
        ((androidx.appcompat.widget.AppCompatSpinner) spinner)
                .setPopupBackgroundResource(R.drawable.bg_input);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, options) {
            @Override public View getView(int position, View convertView, ViewGroup group) {
                TextView view = (TextView) super.getView(position, convertView, group);
                view.setTextColor(UiDecor.color(AgentStreamActivity.this, R.color.text));
                view.setTextSize(14);
                view.setText(getItem(position) + "  ▾");
                view.setSingleLine(true);
                view.setEllipsize(android.text.TextUtils.TruncateAt.END);
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup group) {
                TextView view = (TextView) super.getDropDownView(position, convertView, group);
                view.setTextColor(UiDecor.color(AgentStreamActivity.this, R.color.text));
                view.setTextSize(14);
                view.setMinHeight(dp(48));
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        LinearLayout.LayoutParams lp = UiDecor.cardLp(this, 8);
        lp.topMargin = dp(6);
        parent.addView(spinner, lp);
        return spinner;
    }
    private EditText editor(LinearLayout parent, String hint, int lines) {
        EditText field = new EditText(this);
        field.setHint(hint);
        androidx.core.view.ViewCompat.setBackgroundTintList(field, null);
        field.setBackgroundResource(R.drawable.bg_input);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setMinimumHeight(dp(48));
        field.setTextColor(UiDecor.color(this, R.color.text));
        field.setHintTextColor(UiDecor.color(this, R.color.text_dim));
        field.setTextSize(14);
        // 输入会随页面状态保存，限制长度以免超出 Android 状态事务大小。
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MAX_INPUT_CHARS)});
        field.setMinLines(lines);
        field.setMaxLines(Math.max(lines, 8));
        field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | (lines > 1 ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        LinearLayout.LayoutParams lp = UiDecor.cardLp(this, 8);
        lp.topMargin = dp(6);
        parent.addView(field, lp);
        return field;
    }
}
