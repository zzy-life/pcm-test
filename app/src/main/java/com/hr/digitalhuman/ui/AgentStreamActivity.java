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
    private static final int PICK_FILE = 6101;
    private static final int MAX_INPUT_CHARS = 50000;
    private boolean invalidParameters;
    private static final int TEXT = 0, LOCAL = 1, URL_FILE = 2;
    private static final AgentDefinition[] AGENTS = {AgentDefinition.CAREER_AGENT,
            AgentDefinition.DIAGNOSIS_AGENT, AgentDefinition.OPTIMIZATION_AGENT, AgentDefinition.INTERVIEW_AGENT};
    private static final String SAMPLE_JD = "高级销售专员/ 销售主管\n"
            + "岗位亮点\n行业领先的平台，完善的晋升通道和培训体系\n"
            + "参与核心业务拓展，发挥个人及团队影响力\n丰厚的绩效激励与多元成长机会\n"
            + "岗位职责\n负责公司重点产品的市场开拓与销售，挖掘并维护核心客户资源；\n"
            + "独立完成销售目标，推动大客户签约与合作落地；\n"
            + "参与销售策略制定，根据市场变化及时调整业务方向；\n"
            + "指导和培训新同事，提升团队整体业绩与专业能力；\n"
            + "定期分析市场和客户需求，输出数据报告，推动业务优化；\n"
            + "协同市场、产品等多部门，推动项目高效落地。\n"
            + "任职要求\n大专及以上学历，市场营销、管理等相关专业优先；\n"
            + "3年以上销售相关工作经验，有B端客户开发经验者优先；\n"
            + "具备独立开发客户、谈判签约及项目跟进能力；\n"
            + "具备较强的市场洞察力与数据分析能力；\n"
            + "沟通表达能力强，具备团队协作与带教经验者优先；\n"
            + "目标感强，抗压能力好，具备自我驱动和持续学习能力。\n"
            + "加分项\n有互联网、科技、快消等行业销售经验；\n"
            + "主导或参与过大客户项目及团队管理经历；\n熟练使用CRM系统及办公软件。\n"
            + "薪酬福利\n具有市场竞争力的底薪+高额绩效奖金\n"
            + "五险一金、年度体检、节日福利\n完善的培训体系与多元晋升空间";
    private static final String SAMPLE_RESUME = "个人简历\n"
            + "我叫赵小晴，目前居住在上海市，联系电话为138-XXXX-XXXX，电子邮箱为zhaoxiaoqing@example.com，目前正在寻求销售主管或高级销售专员等进阶销售岗位。\n\n"
            + "个人简介\n\n"
            + "我拥有三年专业销售工作经验，具备扎实的客户开发与维护能力，熟悉销售业务流程及团队协作模式。在工作中，我善于与客户沟通，具有较强的目标意识和执行能力，能够独立完成销售任务，并积极推动团队业绩提升。我希望在更具挑战性的销售岗位上持续积累经验、提升专业能力，实现个人职业发展与企业业务增长的共同目标。\n\n"
            + "工作经历\n\n"
            + "2019年7月至2022年8月，我在上海某科技有限公司担任销售专员，主要负责B端客户的开发、维护及销售业务拓展。在任职期间，我能够独立完成年度销售额目标，年均销售业绩增长达到20%。通过积极开拓市场，我累计拓展了70余家新客户，并与多个重点客户建立了长期稳定的合作关系，客户续签率超过80%。\n\n"
            + "在业务开展过程中，我积极参与大型项目的投标及商务谈判工作，协助团队成功签约多个百万级订单。同时，我定期开展市场调研，收集和分析行业动态、市场竞争情况及客户需求，为部门制定和优化销售策略提供有效支持。此外，我还承担了部分新员工指导工作，帮助新入职同事熟悉产品知识、销售流程及客户沟通技巧，促进团队整体业务能力与销售业绩提升。\n\n"
            + "教育背景\n\n"
            + "2015年9月至2019年6月，我就读于上海师范大学市场营销专业，取得本科学历。在校期间，我系统学习了市场营销相关专业知识，为后续从事销售、客户关系管理及市场分析等工作奠定了基础。\n\n"
            + "专业技能\n\n"
            + "我熟悉销售业务全流程，能够熟练运用CRM客户关系管理系统开展客户信息管理、销售跟进及业务分析工作。我具备良好的客户沟通能力、客户关系维护能力及商务谈判技巧，能够根据不同客户需求制定相应的销售方案。同时，我具有一定的市场分析、数据整理及业务报告撰写能力，能够熟练使用Word、Excel、PowerPoint等Office办公软件，满足日常销售管理、数据统计及工作汇报需求。\n\n"
            + "自我评价\n\n"
            + "我性格积极乐观，工作态度认真负责，具备较强的抗压能力和自我驱动力。在实际工作中，我坚持以目标为导向，注重执行效率和工作成果，能够快速适应新的工作环境及业务要求，并通过持续努力取得良好业绩。同时，我注重团队合作与沟通协调，具有较强的学习能力和自我提升意识，能够主动总结工作经验，不断优化销售方法，提高专业水平。\n\n"
            + "求职意向\n\n"
            + "我希望应聘销售主管或高级销售专员岗位，目标行业包括互联网、科技及快速消费品等领域，期望工作地点为上海市。对于薪资待遇，我持开放态度，具体可根据岗位职责、企业发展情况及个人能力进行面议。";

    private Spinner agent, source, careerType;
    private EditText jd, resume, fileUrl, questionNumber;
    private String jobTitle = "";
    private Spinner interviewRole, referenceAnswer;
    private LinearLayout interviewSettings;
    private TextView status, fileInfo;
    private Button begin, choose, cancel, useSample;
    private final ExecutorService preparationExecutor = Executors.newCachedThreadPool();
    private Future<?> preparationTask;
    private AgentApiClient preparationClient;
    // 仅主线程读写；每次取消先递增，已排队的成功/失败回调也会失效。
    private long generation;
    private boolean preparing;
    private Uri fileUri;
    private String filename = "resume", mime = "application/octet-stream";
    private boolean initializing = true;
    private int lastAgent, lastSource, lastType;

    public static void start(Context context) {
        launch(context, new Intent(context, AgentStreamActivity.class));
    }

    public static void start(Context context, String agentId) {
        Intent intent = new Intent(context, AgentStreamActivity.class);
        intent.putExtra(EXTRA_AGENT_ID, agentId);
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
        careerType = spinner(settings, "晋升路径", "转型建议");
        jd = editor(settings, "岗位招聘要求", 4);
        useSample = button("使用示例数据");
        settings.addView(useSample, UiDecor.cardLp(this, 8));
        useSample.setOnClickListener(v -> {
            // 仅主动点击时覆盖资料，切换到文本来源，避免继续发送历史简历或文件。
            jd.setText(SAMPLE_JD);
            resume.setText(SAMPLE_RESUME);
            jobTitle = "高级销售专员 / 销售主管";
            source.setSelection(TEXT);
            updateSource();
            status.setText("已填入示例数据");
        });
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
        label(inputs, "简历来源");
        source = spinner(inputs, "手动填写简历", "上传本地简历文件", "在线简历文件链接");
        resume = editor(inputs, "简历正文", 5);
        fileUrl = editor(inputs, "简历文件链接", 2);
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
            status.setText("已取消");
        });
        status = UiDecor.subtitle(this, "");
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
        jd.setVisibility(career ? View.GONE : View.VISIBLE);
        jd.setHint(definition == AgentDefinition.OPTIMIZATION_AGENT
                ? "岗位招聘要求（选填）" : "岗位招聘要求（必填）");
    }

    private void updateSource() {
        int mode = source.getSelectedItemPosition();
        resume.setVisibility(mode == TEXT ? View.VISIBLE : View.GONE);
        fileUrl.setVisibility(mode == URL_FILE ? View.VISIBLE : View.GONE);
        choose.setVisibility(mode == LOCAL ? View.VISIBLE : View.GONE);
        fileInfo.setVisibility(mode == LOCAL && fileUri != null ? View.VISIBLE : View.GONE);
        fileInfo.setText(mode == LOCAL && fileUri != null ? filename : "");
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
        status.setText("文件已选择");
    }

    private void startRequest() {
        if (invalidParameters || preparing || isFinishing() || isDestroyed()) return;
        AgentDefinition definition = AGENTS[agent.getSelectedItemPosition()];
        String key = definition.apiKey();
        if (key == null || key.trim().isEmpty()) {
            status.setText("该功能尚未配置服务，请联系管理员后重试。");
            return;
        }
        final int mode = source.getSelectedItemPosition();
        final String resumeText = resume.getText().toString();
        final String jdText = jd.getText().toString();
        final String titleText = jobTitle;
        final String planningType = careerType.getSelectedItem().toString();
        final String explicitUrl = fileUrl.getText().toString();
        String suppliedCount = questionNumber.getText().toString().trim();
        final String count = suppliedCount.isEmpty() ? "5" : suppliedCount;
        final int role = interviewRole.getSelectedItemPosition() + 1;
        final int reference = referenceAnswer.getSelectedItemPosition();
        if (definition == AgentDefinition.INTERVIEW_AGENT && !count.matches("[5-9]|1[0-5]")) {
            status.setText("面试题数支持 5–15，留空默认 5。"); return;
        }
        if ((definition == AgentDefinition.DIAGNOSIS_AGENT || definition == AgentDefinition.INTERVIEW_AGENT)
                && jdText.trim().isEmpty()) {
            status.setText("请填写岗位招聘要求"); return;
        }
        if ((mode == TEXT && resumeText.trim().isEmpty()) || (mode == LOCAL && fileUri == null)
                || (mode == URL_FILE && explicitUrl.isEmpty())) {
            status.setText("请提供简历"); return;
        }
        if (mode == URL_FILE && !isPublicFileUrl(explicitUrl)) {
            status.setText("请填写可公开访问的完整简历文件链接");
            return;
        }
        final AgentApiClient client;
        try { client = new AgentApiClient(key); }
        catch (IllegalArgumentException e) {
            status.setText("服务配置有误，请联系管理员后重试。"); return;
        }
        // 冻结全部选择参数，工作线程不读取任何控件或可变的文件字段。
        final Uri selectedUri = fileUri;
        final String selectedName = filename, selectedMime = mime;
        final String summary = initialSummary(definition, planningType, titleText, mode,
                resumeText, selectedName)
                + (definition == AgentDefinition.INTERVIEW_AGENT ? "\n面试：" + count + " 题 · "
                + (role == 1 ? "HR综合面试" : "专业面试")
                + (reference == 1 ? " · 生成参考答案" : " · 不生成参考答案") : "");
        if (mode == TEXT || mode == URL_FILE) {
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
        status.setText("正在上传文件，可取消准备。");
        preparationTask = preparationExecutor.submit(() -> {
            try {
                final String uploadedFile;
                // openInputStream 可能不响应中断；返回后仍交给已取消的 client，确保关闭。
                try (InputStream input = getContentResolver().openInputStream(selectedUri)) {
                    uploadedFile = client.upload(input, selectedName, selectedMime);
                }
                JsonObject body = definition.buildRequest(planningType, jdText, titleText, "", uploadedFile,
                        selectedName, count, role, reference);
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
        String business = definition == AgentDefinition.CAREER_AGENT ? "规划类型：" + type : "";
        if (definition == AgentDefinition.OPTIMIZATION_AGENT) business = "优化方式：直接改写";
        if ((definition == AgentDefinition.DIAGNOSIS_AGENT || definition == AgentDefinition.INTERVIEW_AGENT)
                && !title.trim().isEmpty()) {
            business += (business.isEmpty() ? "" : "\n") + "职位：" + title.trim();
        }
        if (mode == TEXT) {
            String content = resumeText.trim();
            int end = content.offsetByCodePoints(0, Math.min(120, content.codePointCount(0, content.length())));
            return business + (business.isEmpty() ? "" : "\n") + "简历：" + content.substring(0, end);
        }
        // URL 的路径/查询可能携带凭据，不从 URL 提取文件名，不展示 URL 或下载 token。
        return business + (business.isEmpty() ? "" : "\n") + "文件：" + (mode == URL_FILE ? "在线简历文件" : (name.isEmpty() ? "未命名" : name));
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
        for (View view : new View[]{agent, source, careerType, jd, resume, fileUrl, choose,
                questionNumber, interviewRole, referenceAnswer, useSample}) {
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
        if (wasPreparing) status.setText("上传已取消，请重新开始");
        super.onSaveInstanceState(state);
        state.putBoolean("invalid_parameters", invalidParameters);
        state.putInt("agent", agent.getSelectedItemPosition());
        state.putInt("source", source.getSelectedItemPosition());
        state.putInt("type", careerType.getSelectedItemPosition());
        state.putString("question_number", questionNumber.getText().toString());
        state.putInt("interview_role", interviewRole.getSelectedItemPosition());
        state.putInt("reference_answer", referenceAnswer.getSelectedItemPosition());
        state.putString("jd", jd.getText().toString());
        state.putString("title", jobTitle);
        state.putString("resume", resume.getText().toString());
        state.putString("file_url", fileUrl.getText().toString());
        state.putString("uri", fileUri == null ? "" : fileUri.toString());
        state.putString("filename", filename);
        state.putString("mime", mime);
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
        jobTitle = state.getString("title", "");
        resume.setText(state.getString("resume", ""));
        fileUrl.setText(state.getString("file_url", ""));
        String uri = state.getString("uri", "");
        if (!uri.isEmpty()) fileUri = Uri.parse(uri);
        filename = state.getString("filename", "resume");
        mime = state.getString("mime", "application/octet-stream");
        status.setText(state.getString("status", "就绪"));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private int dp(int value) { return UiDecor.dp(this, value); }
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
