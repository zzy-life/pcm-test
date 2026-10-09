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

import com.hr.digitalhuman.agents.AgentApiClient;
import com.hr.digitalhuman.agents.AgentDefinition;


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
    private boolean invalidParameters;
    private static final int TEXT = 0, LOCAL = 1, COS = 2, HISTORY = 3;
    private static final String SAMPLE_JD = "【虚构示例 JD，请替换】招聘 Java 后端工程师，负责业务接口开发与数据库优化，要求熟悉 Java、SQL 和团队协作。";
    private static final String SAMPLE_RESUME = "【虚构示例简历，请替换】示例候选人：3 年 Java 后端开发经验，参与订单系统接口开发、SQL 优化及自动化测试。以上经历均为虚构。";

    private Spinner agent, source, careerType;
    private EditText jd, jobTitle, resume, cosKey;
    private TextView status, fileInfo, agentHint;
    private Button begin, choose;
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
        root.setBackgroundColor(0xFF0B1422);
        root.setPadding(dp(16), dp(12), dp(16), dp(88));
        TextView heading = UiDecor.title(this, "智能体 · 职业资料分析");
        heading.setTextSize(22);
        root.addView(heading);
        Button back = button("返回");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        ScrollView inputScroll = new ScrollView(this);
        inputScroll.setFillViewport(true);
        LinearLayout inputs = column();
        UiDecor.styleCard(this, inputs);
        inputScroll.addView(inputs);
        root.addView(inputScroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
                "点击开始或重试，即授权将所选简历及本次智能体所需输入发送至智能体服务。请勿提交未经授权的个人资料。"));
        begin = button("授权并开始");
        inputs.addView(begin);
        begin.setOnClickListener(v -> startRequest());
        status = UiDecor.subtitle(this, "确认资料后开始，将在独立页面显示 Markdown 分析结果。");
        inputs.addView(status);
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
        if (invalidParameters) return;
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
        try { new AgentApiClient(key); }
        catch (IllegalArgumentException e) { status.setText("密钥配置无效，请检查 " + definition.configurationName); return; }
        Intent intent = new Intent(this, AgentResultActivity.class);
        intent.putExtra(EXTRA_AGENT_ID, definition.id);
        intent.putExtra(EXTRA_JOB_INFO, jdText);
        intent.putExtra(EXTRA_JOB_TITLE, titleText);
        intent.putExtra(EXTRA_RESUME_CONTENT, mode == TEXT ? resumeText : "");
        intent.putExtra(EXTRA_COS_KEY, mode == COS ? explicitKey : "");
        intent.putExtra(EXTRA_DOWNLOAD_TOKEN, mode == HISTORY ? downloadToken : "");
        intent.putExtra(EXTRA_RESUME_NAME, resumeName);
        intent.putExtra(AgentResultActivity.EXTRA_SOURCE, mode);
        intent.putExtra(AgentResultActivity.EXTRA_TYPE, planningType);
        if (mode == LOCAL) {
            intent.setData(fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(AgentResultActivity.EXTRA_FILE_NAME, filename);
            intent.putExtra(AgentResultActivity.EXTRA_MIME, mime);
        }
        startActivity(intent);
    }

    private void updateButtons() {
        if (begin != null) begin.setEnabled(!invalidParameters);
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
        state.putString("status", status.getText().toString());
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
