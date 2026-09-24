package com.hr.digitalhuman.ui.fragment;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.app.SessionStore;
import com.hr.digitalhuman.ime.SoftImeController;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.UserInfo;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.ui.MainActivity;
import com.hr.digitalhuman.ui.UiDecor;
import com.hr.digitalhuman.ui.display.DisplayCanvas;
import com.hr.digitalhuman.ui.display.ResumePdfViewer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * 简历中心：工作台（历史/草稿/选模板）+ 对话采集区（进度常驻）。
 * 不改动首页对话；采集回答通过 MainActivity.sendChat 走现有规划链路。
 */
public class ResumeCenterFragment extends Fragment {

    private LinearLayout hubContainer;
    private ScrollView scrollHub;
    private View panelChat;
    private View panelChatInput;
    private DisplayCanvas displayCanvas;
    private LinearLayout progressContainer;
    private TextView btnToHub;
    private TextView tvTitle;
    private EditText etAnswer;
    private TextView btnVoiceToggle;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean chatMode;
    /** 采集中仅在用户主动「语音讲话」时接收 ASR */
    private boolean voiceCapturing;
    private final StringBuilder voiceCommitted = new StringBuilder();
    /** 异步生成中的记录 ID，用于轮询进度 */
    private Long generatingRecordId;
    /** 仅查看历史简历详情（扫码页），返回时不走「退出」接口，避免「正在处理」 */
    private boolean viewingRecordOnly;
    private final Runnable genPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollGenerationStatus();
        }
    };

    public static ResumeCenterFragment newInstance() {
        return new ResumeCenterFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_resume_center, container, false);
        tvTitle = v.findViewById(R.id.tv_title);
        hubContainer = v.findViewById(R.id.hub_container);
        scrollHub = v.findViewById(R.id.scroll_hub);
        panelChat = v.findViewById(R.id.panel_chat);
        panelChatInput = v.findViewById(R.id.panel_chat_input);
        displayCanvas = v.findViewById(R.id.resume_display);
        progressContainer = v.findViewById(R.id.progress_container);
        btnToHub = v.findViewById(R.id.btn_to_hub);
        etAnswer = v.findViewById(R.id.et_answer);
        btnVoiceToggle = v.findViewById(R.id.btn_voice_toggle);
        com.hr.digitalhuman.view.AmbientFxView fx = v.findViewById(R.id.ambient_fx);
        if (fx != null) {
            fx.setIntensity(0.8f);
        }
        v.findViewById(R.id.btn_back).setOnClickListener(view -> {
            if (chatMode) {
                stopVoiceCapture(false);
                exitChatOrHub();
            } else {
                requireActivity().onBackPressed();
            }
        });
        btnToHub.setOnClickListener(view -> {
            stopVoiceCapture(false);
            exitChatOrHub();
        });
        v.findViewById(R.id.btn_exit_save).setOnClickListener(view -> {
            stopVoiceCapture(false);
            exitChatOrHub();
        });
        View btnSend = v.findViewById(R.id.btn_send_answer);
        if (btnSend != null) {
            btnSend.setOnClickListener(view -> {
                if (voiceCapturing) {
                    Toast.makeText(requireContext(), "请先点击「完成」结束语音", Toast.LENGTH_SHORT).show();
                    return;
                }
                submitTypedAnswer();
            });
        }
        if (btnVoiceToggle != null) {
            btnVoiceToggle.setOnClickListener(view -> {
                if (voiceCapturing) {
                    stopVoiceCapture(true);
                } else {
                    startVoiceCapture();
                }
            });
        }
        if (etAnswer != null) {
            SoftImeController.get().bind(etAnswer);
            etAnswer.setOnEditorActionListener((tv, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    if (voiceCapturing) {
                        Toast.makeText(requireContext(), "请先点击「完成」结束语音", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    submitTypedAnswer();
                    return true;
                }
                return false;
            });
        }
        displayCanvas.setFormListener((summary, values) -> {
            if (voiceCapturing) {
                stopVoiceCapture(false);
            }
            SoftImeController.get().hide();
            String msg = null;
            if (values != null) {
                if (values.containsKey("answer") && values.get("answer") != null
                        && !values.get("answer").trim().isEmpty()) {
                    msg = values.get("answer").trim();
                } else if (!values.isEmpty()) {
                    // 取第一个非空值
                    for (String v0 : values.values()) {
                        if (v0 != null && !v0.trim().isEmpty()) {
                            msg = v0.trim();
                            break;
                        }
                    }
                }
            }
            if (msg == null || msg.isEmpty()) {
                msg = summary;
            }
            // 底部输入框若有内容且表单答为空，兜底用底部内容
            if ((msg == null || msg.isEmpty()) && etAnswer != null && etAnswer.getText() != null) {
                msg = etAnswer.getText().toString().trim();
            }
            if (msg == null || msg.isEmpty()) {
                Toast.makeText(requireContext(), "请先填写后再发送", Toast.LENGTH_SHORT).show();
                return;
            }
            if ("继续修改".equals(msg) || "我要修改".equals(msg) || "修改一下".equals(msg)) {
                // 提交给后端拿可配置引导话术，不要只 Toast「没有内容」
                clearAnswerInput();
                submitResumeMessage("继续修改", false, null);
                if (etAnswer != null) {
                    etAnswer.requestFocus();
                    SoftImeController.get().showFor(etAnswer);
                }
                return;
            }
            String resumeName = null;
            if (values != null && values.containsKey("resumeName")) {
                resumeName = values.get("resumeName");
            }
            clearAnswerInput();
            submitResumeMessage(msg, false, resumeName);
        });
        displayCanvas.setActionListener(section -> {
            if (section == null || section.actionUrl == null || section.actionUrl.trim().isEmpty()) {
                Toast.makeText(requireContext(), "暂无简历文件", Toast.LENGTH_SHORT).show();
                return;
            }
            ResumePdfViewer.open(requireContext(), section.actionUrl.trim());
        });
        showHub();
        loadHub(false);
        return v;
    }

    public boolean isChatMode() {
        return chatMode;
    }

    public boolean isVoiceCapturing() {
        return voiceCapturing;
    }

    /** 供 MainActivity 在采集态转发用户输入（直连状态机） */
    public void submitAnswerFromHost(String message) {
        if (voiceCapturing) {
            stopVoiceCapture(false);
        }
        submitResumeMessage(message, false);
    }

    private void startVoiceCapture() {
        if (!chatMode || voiceCapturing) {
            return;
        }
        voiceCapturing = true;
        voiceCommitted.setLength(0);
        if (etAnswer != null && etAnswer.getText() != null) {
            String existing = etAnswer.getText().toString().trim();
            if (!existing.isEmpty()) {
                voiceCommitted.append(existing);
            }
        }
        SoftImeController.get().hide();
        updateVoiceButtonUi();
        if (etAnswer != null) {
            etAnswer.setHint(R.string.resume_voice_listening);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).syncResumeListening();
        }
        Toast.makeText(requireContext(), "请开始讲话，说完后点「完成」", Toast.LENGTH_SHORT).show();
    }

    /** @param keepText 是否保留已识别文字在输入框 */
    private void stopVoiceCapture(boolean keepText) {
        if (!voiceCapturing) {
            updateVoiceButtonUi();
            return;
        }
        voiceCapturing = false;
        if (keepText && etAnswer != null) {
            // 优先保留输入框当前展示（含尚未落 final 的 partial），避免点「完成」丢字
            String show = etAnswer.getText() == null ? "" : etAnswer.getText().toString().trim();
            if (show.isEmpty() && voiceCommitted.length() > 0) {
                show = voiceCommitted.toString().trim();
            }
            etAnswer.setText(show);
            etAnswer.setSelection(show.length());
            if (show.isEmpty()) {
                Toast.makeText(requireContext(), "未识别到内容，请再说一次或手动输入", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "语音已结束，可修改后点发送", Toast.LENGTH_SHORT).show();
            }
        }
        voiceCommitted.setLength(0);
        updateVoiceButtonUi();
        if (etAnswer != null) {
            etAnswer.setHint(R.string.resume_answer_hint);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).syncResumeListening();
        }
    }

    private void updateVoiceButtonUi() {
        if (btnVoiceToggle == null || !isAdded()) {
            return;
        }
        if (voiceCapturing) {
            btnVoiceToggle.setText(R.string.resume_voice_done);
            btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_primary);
            btnVoiceToggle.setTextColor(UiDecor.color(requireContext(), R.color.white));
        } else {
            btnVoiceToggle.setText(R.string.resume_voice_speak);
            btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnVoiceToggle.setTextColor(UiDecor.color(requireContext(), R.color.text));
        }
    }

    /**
     * 语音中间/最终结果：仅在「语音讲话」模式下写入输入框，不自动发送。
     */
    public void setVoicePreview(String text, boolean isFinal) {
        if (!isAdded() || !chatMode || !voiceCapturing || etAnswer == null || text == null) {
            return;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return;
        }
        if (isFinal) {
            if (voiceCommitted.length() > 0
                    && !Character.isWhitespace(voiceCommitted.charAt(voiceCommitted.length() - 1))) {
                voiceCommitted.append(' ');
            }
            voiceCommitted.append(t);
            etAnswer.setText(voiceCommitted.toString());
            etAnswer.setSelection(voiceCommitted.length());
        } else {
            String show = voiceCommitted.length() == 0 ? t : voiceCommitted.toString() + t;
            etAnswer.setText(show);
            etAnswer.setSelection(show.length());
        }
    }

    /** @deprecated 使用 {@link #setVoicePreview(String, boolean)} */
    public void setVoicePreview(String text) {
        setVoicePreview(text, false);
    }

    private void submitTypedAnswer() {
        SoftImeController.get().hide();
        if (etAnswer == null) {
            return;
        }
        String text = etAnswer.getText() == null ? "" : etAnswer.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "请先输入回答", Toast.LENGTH_SHORT).show();
            return;
        }
        clearAnswerInput();
        submitResumeMessage(text, false);
    }

    private void clearAnswerInput() {
        if (etAnswer != null) {
            etAnswer.setText("");
        }
        voiceCommitted.setLength(0);
    }

    /** 将 UI/口语映射为工具 action（与 jqr_agent_tool.param_schema_json 一致） */
    private static String resolveResumeAction(String msg, boolean afterExit) {
        if (afterExit) {
            return "exit";
        }
        if (msg == null) {
            return "turn";
        }
        String t = msg.trim();
        if ("确认生成".equals(t) || "确认".equals(t) || "确定生成".equals(t)) {
            return "confirm";
        }
        if ("继续修改".equals(t) || "我要修改".equals(t) || "修改一下".equals(t)) {
            return "revise";
        }
        if (t.matches(".*(把\\s*(姓名|名字|电话|手机|邮箱|学校|专业|岗位|公司|工作经历|项目经历).{0,12}(改成|改为|换成)|"
                + "(姓名|电话|手机|邮箱|学校|专业|岗位|公司|工作经历).{0,8}(改成|改为|换成)|"
                + "说错了|填错了).*")
                && t.length() <= 120) {
            return "revise";
        }
        if (t.matches(".*(帮我(自动)?生成|自动生成|你来写|帮我写|替我写|帮我扩写|帮我起草|"
                + "不会写|你看着办|帮我编|生成一[段个条]|起草一[段个条]).*")
                && t.length() <= 80) {
            return "expand";
        }
        if ("退出".equals(t) || "先退出".equals(t) || "回工作台".equals(t) || "返回工作台".equals(t)) {
            return "exit";
        }
        if ("做好了吗".equals(t) || "生成好了吗".equals(t) || "进度".equals(t)) {
            return "progress";
        }
        return "turn";
    }

    private boolean submitting;

    /** 历史详情直接回工作台；采集/生成会话才调「退出」接口 */
    private void exitChatOrHub() {
        if (viewingRecordOnly) {
            viewingRecordOnly = false;
            submitting = false;
            showHub();
            loadHub(true);
            return;
        }
        submitResumeMessage("退出", true, null);
    }

    /**
     * 采集作答：与规划工具 server_resume_chat 的 action schema 对齐。
     *
     * @param afterExit 是否在成功/完成后回到工作台（退出场景）
     */
    private void submitResumeMessage(String message, boolean afterExit) {
        submitResumeMessage(message, afterExit, null);
    }

    private void submitResumeMessage(String message, boolean afterExit, String resumeName) {
        if (!isAdded() || message == null || message.trim().isEmpty()) {
            return;
        }
        if (submitting) {
            Toast.makeText(requireContext(), "正在处理，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        submitting = true;
        SoftImeController.get().hide();
        stopVoiceCapture(false);
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        UserInfo user = store.getUser();
        Long userId = parseLong(user == null ? null : user.userId);
        final String msg = message.trim();
        // 与规划工具 server_resume_chat.action 对齐，便于后端/模型统一分派
        final String action = resolveResumeAction(msg, afterExit);
        final String nameArg = resumeName;
        if (!afterExit && displayCanvas != null && chatMode) {
            DisplaySection u = new DisplaySection();
            u.kind = DisplaySection.TEXT;
            u.title = "我";
            u.text = msg;
            List<DisplaySection> cur = new ArrayList<>();
            cur.add(u);
            DisplaySection think = new DisplaySection();
            think.kind = DisplaySection.TEXT;
            think.title = "小助手";
            think.text = "收到，正在处理…";
            cur.add(think);
            displayCanvas.render(cur, null);
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .resumeChatMessage(store.getSn(), userId, msg, action, nameArg);
            mainHandler.post(() -> {
                submitting = false;
                if (!isAdded()) {
                    return;
                }
                if (!resp.isOk() || resp.data == null) {
                    Toast.makeText(requireContext(),
                            resp.message != null ? resp.message : "提交失败，请重试",
                            Toast.LENGTH_SHORT).show();
                    if (!afterExit) {
                        showSpeech(resp.message != null ? resp.message : "提交失败，请重试");
                    }
                    return;
                }
                String status = opt(resp.data, "status");
                if (afterExit || "saved_draft".equals(status) || "open_hub".equals(status)) {
                    // 退出：只用 Toast，禁止莫名 TTS；回工作台后不再在聊天画布上播进度语音
                    String toast = opt(resp.data, "toast");
                    if (toast == null || toast.isEmpty()) {
                        toast = opt(resp.data, "speech");
                    }
                    if (toast != null && !toast.isEmpty()) {
                        Toast.makeText(requireContext(), toast, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(), "已退出", Toast.LENGTH_SHORT).show();
                    }
                    Long rid = optLong(resp.data, "recordId");
                    String token = opt(resp.data, "downloadToken");
                    String st = opt(resp.data, "recordStatus");
                    final boolean openRecords = resp.data.has("openRecords")
                            && resp.data.get("openRecords").getAsBoolean()
                            || rid != null;
                    showHub();
                    loadHub(openRecords);
                    if (token != null && !token.isEmpty()
                            && ("3".equals(st) || "2".equals(st))) {
                        mainHandler.postDelayed(() -> {
                            if (isAdded()) {
                                showQr(token, "简历已生成");
                            }
                        }, 450L);
                    } else if (rid != null && !"3".equals(st) && !"2".equals(st) && !"4".equals(st)) {
                        // 仍在生成：后台静默轮询，完成后弹二维码，不播报
                        generatingRecordId = rid;
                        startGenerationPolling(rid);
                    }
                    return;
                }
                String name = tvTitle == null ? null : tvTitle.getText().toString();
                applyChatStart(resp, name);
            });
        });
    }

    public void renderDisplay(List<DisplaySection> sections, String footnote) {
        if (!isAdded() || displayCanvas == null) {
            return;
        }
        if (!chatMode) {
            enterChatMode(null);
        }
        displayCanvas.render(sections, footnote);
    }

    public void showSpeech(String speech) {
        if (!isAdded() || speech == null || speech.trim().isEmpty()) {
            return;
        }
        if (!chatMode) {
            enterChatMode(null);
        }
        DisplaySection s = new DisplaySection();
        s.kind = DisplaySection.TEXT;
        s.title = "小助手";
        s.text = speech.trim();
        List<DisplaySection> list = new ArrayList<>();
        list.add(s);
        displayCanvas.render(list, null);
    }

    public void applyCollectedProgress(JsonObject progress) {
        if (!isAdded() || progressContainer == null || progress == null) {
            return;
        }
        progressContainer.removeAllViews();
        int filled = progress.has("filled") ? progress.get("filled").getAsInt() : 0;
        int total = progress.has("total") ? progress.get("total").getAsInt() : 0;
        int percent = progress.has("percent") ? progress.get("percent").getAsInt() : 0;
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        styleProgressHeader(header);
        header.setLayoutParams(UiDecor.cardLp(requireContext(), 12));

        TextView title = new TextView(requireContext());
        title.setText("采集进度");
        title.setTextColor(UiDecor.color(requireContext(), R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView meta = new TextView(requireContext());
        int reqFilled = progress.has("requiredFilled") ? progress.get("requiredFilled").getAsInt() : -1;
        int reqTotal = progress.has("requiredTotal") ? progress.get("requiredTotal").getAsInt() : -1;
        String metaText = filled + " / " + total + " 项已完成 · " + percent + "%";
        if (reqTotal > 0) {
            metaText = "必填 " + Math.max(0, reqFilled) + "/" + reqTotal
                    + " · 全部 " + filled + "/" + total + " · " + percent + "%";
        }
        meta.setText(metaText);
        meta.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
        meta.setTextSize(13);
        meta.setPadding(0, UiDecor.dp(requireContext(), 4), 0, UiDecor.dp(requireContext(), 10));
        header.addView(meta);

        ProgressBar bar = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(percent);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiDecor.dp(requireContext(), 8));
        bar.setLayoutParams(barLp);
        header.addView(bar);
        progressContainer.addView(header);

        if (!progress.has("items") || !progress.get("items").isJsonArray()) {
            return;
        }
        JsonArray items = progress.getAsJsonArray("items");
        int delay = 0;
        for (JsonElement el : items) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject it = el.getAsJsonObject();
            boolean ok = it.has("filled") && it.get("filled").getAsBoolean();
            boolean required = it.has("required") && it.get("required").getAsBoolean();
            String module = opt(it, "module");
            String field = opt(it, "field");
            String value = opt(it, "value");
            View row = buildProgressItem(module, field, value, ok, required);
            progressContainer.addView(row);
            UiDecor.playEnter(row, delay);
            delay += 40;
        }
    }

    private void styleProgressHeader(View view) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A4A7A, 0xFF123056});
        bg.setCornerRadius(UiDecor.dp(requireContext(), 14));
        bg.setStroke(UiDecor.dp(requireContext(), 1), 0x665B9BFF);
        view.setBackground(bg);
        int p = UiDecor.dp(requireContext(), 14);
        view.setPadding(p, p, p, p);
    }

    private View buildProgressItem(String module, String field, String value,
                                   boolean filled, boolean required) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiDecor.dp(requireContext(), 12));
        if (filled) {
            bg.setColor(0x332B7DE9);
            bg.setStroke(UiDecor.dp(requireContext(), 1), 0x665B9BFF);
        } else if (required) {
            // 未填必填项：暖色描边提醒
            bg.setColor(0x33C47A2C);
            bg.setStroke(UiDecor.dp(requireContext(), 1), 0x88E0A05A);
        } else {
            bg.setColor(0x2212202E);
            bg.setStroke(UiDecor.dp(requireContext(), 1), 0x334A6070);
        }
        row.setBackground(bg);
        int pad = UiDecor.dp(requireContext(), 10);
        row.setPadding(pad, pad, pad, pad);
        row.setLayoutParams(UiDecor.cardLp(requireContext(), 8));

        TextView badge = new TextView(requireContext());
        badge.setText(filled ? "✓" : (required ? "!" : "○"));
        badge.setTextSize(16);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(UiDecor.color(requireContext(),
                filled ? R.color.accent_warm
                        : (required ? R.color.accent_warm : R.color.text_dim)));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                UiDecor.dp(requireContext(), 28), UiDecor.dp(requireContext(), 28));
        badgeLp.rightMargin = UiDecor.dp(requireContext(), 10);
        badge.setLayoutParams(badgeLp);
        row.addView(badge);

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout labelRow = new LinearLayout(requireContext());
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(requireContext());
        String labelText = (module == null ? "" : module)
                + (field == null || field.isEmpty() ? "" : " · " + field);
        label.setText(labelText.isEmpty() ? "字段" : labelText);
        label.setTextColor(UiDecor.color(requireContext(), R.color.text));
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRow.addView(label);

        TextView tag = new TextView(requireContext());
        tag.setText(required ? "必需" : "选填");
        tag.setTextSize(11);
        tag.setPadding(UiDecor.dp(requireContext(), 8), UiDecor.dp(requireContext(), 2),
                UiDecor.dp(requireContext(), 8), UiDecor.dp(requireContext(), 2));
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setCornerRadius(UiDecor.dp(requireContext(), 8));
        if (required) {
            tagBg.setColor(0x55C47A2C);
            tag.setTextColor(0xFFFFE0B0);
        } else {
            tagBg.setColor(0x334A6070);
            tag.setTextColor(UiDecor.color(requireContext(), R.color.text_dim));
        }
        tag.setBackground(tagBg);
        labelRow.addView(tag);
        texts.addView(labelRow);

        TextView valueTv = new TextView(requireContext());
        if (filled) {
            valueTv.setText(value == null || value.isEmpty() ? "已填写" : value);
            valueTv.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
        } else {
            valueTv.setText(required ? "待采集（必需）" : "待采集（选填）");
            valueTv.setTextColor(UiDecor.color(requireContext(),
                    required ? R.color.accent_warm : R.color.text_dim));
        }
        valueTv.setTextSize(12);
        valueTv.setMaxLines(6);
        valueTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        valueTv.setPadding(0, UiDecor.dp(requireContext(), 2), 0, 0);
        texts.addView(valueTv);
        row.addView(texts);
        return row;
    }

    /** 对话一轮结束后刷新右侧进度 */
    public void refreshProgressAsync() {
        if (!isAdded() || !chatMode) {
            return;
        }
        String sn = DigitalHumanApp.getInstance().getSessionStore().getSn();
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .getResumeChatProgress(sn);
            mainHandler.post(() -> {
                if (!isAdded() || !resp.isOk() || resp.data == null) {
                    return;
                }
                if (resp.data.has("collectedProgress")
                        && resp.data.get("collectedProgress").isJsonObject()) {
                    applyCollectedProgress(resp.data.getAsJsonObject("collectedProgress"));
                }
            });
        });
    }

    private void showHub() {
        viewingRecordOnly = false;
        stopVoiceCapture(false);
        stopGenerationPolling();
        chatMode = false;
        if (scrollHub != null) {
            scrollHub.setVisibility(View.VISIBLE);
        }
        if (panelChat != null) {
            panelChat.setVisibility(View.GONE);
        }
        if (panelChatInput != null) {
            panelChatInput.setVisibility(View.GONE);
        }
        if (btnToHub != null) {
            btnToHub.setVisibility(View.GONE);
        }
        if (tvTitle != null) {
            tvTitle.setText(R.string.resume_center);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).syncResumeListening();
        }
    }

    private void enterChatMode(String templateName) {
        chatMode = true;
        stopVoiceCapture(false);
        if (scrollHub != null) {
            scrollHub.setVisibility(View.GONE);
        }
        if (panelChat != null) {
            panelChat.setVisibility(View.VISIBLE);
        }
        // 历史详情只看结果：不展示底部输入框
        if (panelChatInput != null) {
            panelChatInput.setVisibility(viewingRecordOnly ? View.GONE : View.VISIBLE);
        }
        // 恢复右侧进度栏（详情模式再单独隐藏）
        setProgressPanelVisible(!viewingRecordOnly);
        if (btnToHub != null) {
            btnToHub.setVisibility(View.VISIBLE);
        }
        if (tvTitle != null) {
            tvTitle.setText(templateName == null || templateName.isEmpty()
                    ? getString(R.string.resume_collecting) : templateName);
        }
        updateVoiceButtonUi();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).syncResumeListening();
        }
    }

    /** 右侧进度/扫码提示栏显隐 */
    private void setProgressPanelVisible(boolean visible) {
        if (progressContainer == null) {
            return;
        }
        View parent = progressContainer.getParent() instanceof View
                ? (View) progressContainer.getParent() : progressContainer;
        parent.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            progressContainer.removeAllViews();
        }
    }

    private void loadHub() {
        loadHub(false);
    }

    /**
     * @param scrollToRecords 是否在渲染后滚到「历史简历」区域（退出生成后使用）
     */
    private void loadHub(boolean scrollToRecords) {
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        if (!store.isLoggedIn()) {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openResumeCenter();
            }
            return;
        }
        UserInfo user = store.getUser();
        String sn = store.getSn();
        Long userId = parseLong(user == null ? null : user.userId);
        String token = user == null ? null : user.token;
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .getResumeHub(sn, userId, token);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (!resp.isOk() || resp.data == null) {
                    Toast.makeText(requireContext(),
                            resp.message != null ? resp.message : "加载失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (resp.data.has("needLogin") && resp.data.get("needLogin").getAsBoolean()) {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).openResumeCenter();
                    }
                    return;
                }
                renderHub(resp.data, scrollToRecords);
            });
        });
    }

    private void renderHub(JsonObject data) {
        renderHub(data, false);
    }

    private void renderHub(JsonObject data, boolean scrollToRecords) {
        hubContainer.removeAllViews();
        String speech = opt(data, "speech");
        if (speech != null && !speech.isEmpty()) {
            hubContainer.addView(UiDecor.subtitle(requireContext(), speech));
        }

        hubContainer.addView(sectionTitle("我的草稿（一模板一份）"));
        JsonArray drafts = data.has("drafts") && data.get("drafts").isJsonArray()
                ? data.getAsJsonArray("drafts") : new JsonArray();
        if (drafts.size() == 0) {
            hubContainer.addView(UiDecor.subtitle(requireContext(), "暂无未完成草稿"));
        } else {
            for (JsonElement el : drafts) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject d = el.getAsJsonObject();
                Long templateId = asLong(d, "templateId");
                String name = opt(d, "templateName");
                int filled = d.has("progressFilled") ? d.get("progressFilled").getAsInt() : 0;
                int total = d.has("progressTotal") ? d.get("progressTotal").getAsInt() : 0;
                final String displayName = name == null ? "草稿" : name;
                LinearLayout row = draftRow(displayName,
                        "进度 " + filled + "/" + total + " · 点击继续",
                        templateId,
                        v -> continueDraft(templateId, name));
                hubContainer.addView(row);
            }
        }

        // 历史简历放在模板列表之前，避免生成后回中心时被模板列表淹没
        final View recordsAnchor = sectionTitle("历史简历");
        hubContainer.addView(recordsAnchor);
        JsonArray records = data.has("records") && data.get("records").isJsonArray()
                ? data.getAsJsonArray("records") : new JsonArray();
        if (records.size() == 0) {
            hubContainer.addView(UiDecor.subtitle(requireContext(), "暂无已生成/生成中的简历"));
        } else {
            for (JsonElement el : records) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject r = el.getAsJsonObject();
                String resumeName = opt(r, "resumeName");
                String name = (resumeName != null && !resumeName.trim().isEmpty())
                        ? resumeName.trim() : opt(r, "templateName");
                String pos = opt(r, "targetPosition");
                String st = opt(r, "status");
                boolean downloadable = r.has("downloadable") && r.get("downloadable").getAsBoolean();
                boolean scannable = r.has("scannable") && r.get("scannable").getAsBoolean();
                String token = opt(r, "downloadToken");
                int progress = r.has("progress") && !r.get("progress").isJsonNull()
                        ? r.get("progress").getAsInt() : -1;
                String statusLabel = resumeStatusLabel(st, downloadable, progress);
                String created = formatResumeTime(r, "createTime");
                StringBuilder sub = new StringBuilder();
                if (created != null && !created.isEmpty()) {
                    sub.append(created);
                }
                if (pos != null && !pos.isEmpty()
                        && (resumeName == null || !resumeName.contains(pos))) {
                    if (sub.length() > 0) {
                        sub.append(" · ");
                    }
                    sub.append(pos);
                }
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append(statusLabel);
                Long recordId = asLong(r, "recordId");
                final String displayName = name == null ? "简历" : name;
                LinearLayout row = historyRecordRow(displayName, sub.toString(), recordId,
                        token != null && !token.isEmpty() && (downloadable || scannable
                                || "3".equals(st) || "2".equals(st) || "0".equals(st) || "1".equals(st))
                                ? v -> {
                            final String qrTitle = downloadable || "3".equals(st)
                                    ? displayName
                                    : displayName + "（生成中）";
                            openRecordDetail(token, qrTitle);
                        } : null);
                hubContainer.addView(row);
            }
        }

        hubContainer.addView(sectionTitle("选择模板 · 新建简历"));
        JsonArray templates = data.has("templates") && data.get("templates").isJsonArray()
                ? data.getAsJsonArray("templates") : new JsonArray();
        if (templates.size() == 0) {
            hubContainer.addView(UiDecor.subtitle(requireContext(), "暂无可用模板，请联系管理员配置"));
        } else {
            int idx = 1;
            for (JsonElement el : templates) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject t = el.getAsJsonObject();
                Long templateId = asLong(t, "templateId");
                String name = opt(t, "templateName");
                boolean hasDraft = t.has("hasDraft") && t.get("hasDraft").getAsBoolean();
                String sampleImage = opt(t, "sampleImage");
                String sub = (opt(t, "templateType") == null ? "" : opt(t, "templateType"))
                        + (hasDraft ? " · 已有草稿" : "")
                        + (sampleImage != null && !sampleImage.isEmpty() ? " · 可预览效果" : "");
                final int order = idx;
                LinearLayout row = templateCard(idx + ". " + (name == null ? "模板" : name),
                        sub, sampleImage,
                        v -> onPickTemplate(templateId, name, hasDraft, order));
                hubContainer.addView(row);
                idx++;
            }
        }

        if (scrollToRecords && scrollHub != null) {
            scrollHub.post(() -> {
                int y = recordsAnchor.getTop();
                scrollHub.smoothScrollTo(0, Math.max(0, y - 24));
            });
        }
    }

    private static String resumeStatusLabel(String st, boolean downloadable, int progress) {
        if (downloadable || "3".equals(st)) {
            return "已完成 · 点击扫码下载";
        }
        if ("2".equals(st)) {
            return "待复核 · 可扫码查看";
        }
        if ("1".equals(st) || "0".equals(st)) {
            return progress >= 0
                    ? ("生成中 " + progress + "% · 可扫码看进度")
                    : "生成中 · 可扫码看进度";
        }
        if ("4".equals(st)) {
            return "生成失败";
        }
        if ("5".equals(st)) {
            return "已过期";
        }
        return "处理中";
    }

    private void onPickTemplate(Long templateId, String name, boolean hasDraft, int order) {
        if (templateId == null) {
            return;
        }
        if (hasDraft) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("该模板已有草稿")
                    .setMessage("继续上次进度，还是覆盖重做？")
                    .setPositiveButton("继续", (d, w) -> continueDraft(templateId, name))
                    .setNegativeButton("重做", (d, w) -> startTemplate(templateId, name, true))
                    .setNeutralButton("取消", null)
                    .show();
            return;
        }
        startTemplate(templateId, name, false);
    }

    private void continueDraft(Long templateId, String name) {
        if (templateId == null) {
            return;
        }
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        UserInfo user = store.getUser();
        Long userId = parseLong(user == null ? null : user.userId);
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .resumeChatContinue(store.getSn(), userId, templateId);
            mainHandler.post(() -> applyChatStart(resp, name));
        });
    }

    private void startTemplate(Long templateId, String name, boolean forceNew) {
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        UserInfo user = store.getUser();
        Long userId = parseLong(user == null ? null : user.userId);
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .resumeChatStart(store.getSn(), userId, templateId, forceNew);
            mainHandler.post(() -> applyChatStart(resp, name));
        });
    }

    private void applyChatStart(ApiResponse<JsonObject> resp, String templateName) {
        if (!isAdded()) {
            return;
        }
        viewingRecordOnly = false;
        if (!resp.isOk() || resp.data == null) {
            Toast.makeText(requireContext(),
                    resp.message != null ? resp.message : "启动失败", Toast.LENGTH_SHORT).show();
            return;
        }
        enterChatMode(templateName);
        clearAnswerInput();
        String speech = opt(resp.data, "speech");
        if (resp.data.has("collectedProgress") && resp.data.get("collectedProgress").isJsonObject()) {
            applyCollectedProgress(resp.data.getAsJsonObject("collectedProgress"));
        }

        // 已生成完成：直接展示结果（二维码）
        if (isGenerationDone(resp.data)) {
            stopGenerationPolling();
            showGenerationResult(resp.data, speech);
            return;
        }
        // 生成中：主区展示「正在生成中」，右侧进度条，完成后自动弹结果
        if (isGenerationInProgress(resp.data)) {
            showGeneratingUi(resp.data, true);
            Long rid = optLong(resp.data, "recordId");
            if (rid != null) {
                startGenerationPolling(rid);
            }
            if (speech != null && !speech.trim().isEmpty() && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).speakResumeHint(speech.trim());
            }
            return;
        }

        String qSpeech = null;
        List<DisplaySection> sections = new ArrayList<>();
        if (speech != null && !speech.isEmpty()) {
            DisplaySection t = new DisplaySection();
            t.kind = DisplaySection.TEXT;
            t.title = "小助手";
            t.text = speech;
            sections.add(t);
        }

        if (resp.data.has("question") && resp.data.get("question").isJsonObject()) {
            JsonObject q = resp.data.getAsJsonObject("question");
            qSpeech = opt(q, "speech");
            if (qSpeech == null || qSpeech.trim().isEmpty()) {
                qSpeech = opt(q, "prompt");
            }
            if (qSpeech == null || qSpeech.trim().isEmpty()) {
                qSpeech = "请继续回答";
            }
            JsonArray enums = null;
            if (q.has("enumValues") && q.get("enumValues").isJsonArray()) {
                enums = q.getAsJsonArray("enumValues");
            } else if (q.has("options") && q.get("options").isJsonArray()) {
                enums = q.getAsJsonArray("options");
            } else if (q.has("hints") && q.get("hints").isJsonArray()) {
                enums = q.getAsJsonArray("hints");
            }
            sections.add(buildAskFormSection(qSpeech, enums));
            if (etAnswer != null) {
                etAnswer.setHint(qSpeech);
            }
            displayCanvas.render(sections, "请填写后点「确认发送」，或在下方输入后点发送");
        } else if (resp.data.has("preview")) {
            DisplaySection p = new DisplaySection();
            p.kind = DisplaySection.TEXT;
            p.title = "简历预览";
            p.text = opt(resp.data, "preview");
            sections.add(p);
            sections.add(buildConfirmGenerateSection(resp.data));
            displayCanvas.render(sections, "请确认简历名称后点「确认生成」，或说明要修改的内容");
        } else if (!sections.isEmpty()) {
            displayCanvas.render(sections, null);
        }

        // 播报：恢复提示 + 当前问题（如「您的邮箱是多少」）
        StringBuilder toSpeak = new StringBuilder();
        if (speech != null && !speech.trim().isEmpty()) {
            toSpeak.append(speech.trim());
        }
        if (qSpeech != null && !qSpeech.trim().isEmpty()) {
            if (toSpeak.length() > 0 && !toSpeak.toString().endsWith("。")
                    && !toSpeak.toString().endsWith("！")
                    && !toSpeak.toString().endsWith("？")) {
                toSpeak.append("。");
            }
            if (toSpeak.length() > 0) {
                toSpeak.append('\n');
            }
            toSpeak.append(qSpeech.trim());
        }
        if (getActivity() instanceof MainActivity && toSpeak.length() > 0) {
            ((MainActivity) getActivity()).speakResumeHint(toSpeak.toString());
        }
    }

    /** 生成完成（可下载） */
    private static boolean isGenerationDone(JsonObject data) {
        if (data == null) {
            return false;
        }
        String st = opt(data, "status");
        if ("3".equals(st) || "2".equals(st)) {
            String token = opt(data, "downloadToken");
            return token != null && !token.isEmpty();
        }
        return false;
    }

    /** 异步生成进行中（含刚提交的 generating） */
    private static boolean isGenerationInProgress(JsonObject data) {
        if (data == null || isGenerationDone(data)) {
            return false;
        }
        String st = opt(data, "status");
        if ("4".equals(st)) {
            return false;
        }
        if ("generating".equals(st) || "0".equals(st) || "1".equals(st)) {
            return true;
        }
        Long recordId = optLong(data, "recordId");
        return recordId != null && data.has("progress") && data.has("stage")
                && !"3".equals(st) && !"2".equals(st);
    }

    /** 主画布 + 右侧进度：正在生成中 */
    private void showGeneratingUi(JsonObject data, boolean replaceCanvas) {
        if (!isAdded()) {
            return;
        }
        if (!chatMode) {
            enterChatMode("正在生成中");
        } else if (tvTitle != null) {
            tvTitle.setText("正在生成中");
        }
        applyGenerationProgress(data);
        if (!replaceCanvas || displayCanvas == null) {
            return;
        }
        int percent = data != null && data.has("progress") && !data.get("progress").isJsonNull()
                ? data.get("progress").getAsInt() : 0;
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }
        String stage = stageLabel(opt(data, "stage"), opt(data, "status"));
        DisplaySection tip = new DisplaySection();
        tip.kind = DisplaySection.TEXT;
        tip.title = "正在生成中";
        tip.text = "简历正在生成，请稍候…\n当前：" + stage + " · " + percent + "%"
                + "\n\n生成完成后将自动展示下载二维码，无需反复询问。";
        List<DisplaySection> sections = new ArrayList<>();
        sections.add(tip);
        displayCanvas.render(sections, "完成后自动展示结果");
        if (etAnswer != null) {
            etAnswer.setHint("生成中，也可问「做好了吗」");
        }
    }

    /** 生成完成：进度条置满 + 主区展示二维码结果；隐藏底部对话输入框 */
    private void showGenerationResult(JsonObject data, String speech) {
        if (!isAdded()) {
            return;
        }
        viewingRecordOnly = false;
        applyGenerationProgress(data);
        String token = opt(data, "downloadToken");
        if (token == null || token.isEmpty()) {
            return;
        }
        showQr(token, "简历已生成");
        // 生成完成后只展示结果，不再继续对话采集
        stopVoiceCapture(false);
        clearAnswerInput();
        if (panelChatInput != null) {
            panelChatInput.setVisibility(View.GONE);
        }
        String say = speech;
        if (say == null || say.trim().isEmpty()) {
            say = "简历已经做好了！请扫码下载。";
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).speakResumeHint(say.trim());
        }
    }

    /** 采集完成后的确认操作区：简历名称（默认同岗位）+ 确认/修改 */
    private DisplaySection buildConfirmGenerateSection(JsonObject data) {
        DisplaySection ask = new DisplaySection();
        ask.kind = DisplaySection.FORM;
        ask.title = "确认生成";
        ask.text = "请确认简历名称，然后选择下一步";
        JsonArray enums = new JsonArray();
        if (data != null && data.has("confirmActions") && data.get("confirmActions").isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray("confirmActions")) {
                if (e != null && !e.isJsonNull()) {
                    enums.add(e.getAsString());
                }
            }
        }
        if (enums.size() == 0) {
            enums.add("确认生成");
            enums.add("继续修改");
        }
        for (JsonElement e : enums) {
            ask.items.add(e.getAsString());
        }
        String defaultName = null;
        if (data != null) {
            defaultName = opt(data, "defaultResumeName");
            if (defaultName == null || defaultName.isEmpty()) {
                defaultName = opt(data, "resumeName");
            }
        }
        if (defaultName == null || defaultName.trim().isEmpty()) {
            defaultName = "个人应聘简历";
        }

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("title", "确认生成");
        JsonObject props = new JsonObject();

        JsonObject nameField = new JsonObject();
        nameField.addProperty("type", "string");
        nameField.addProperty("title", "简历名称");
        nameField.addProperty("description", "默认取岗位名称，可修改");
        nameField.addProperty("default", defaultName.trim());
        props.add("resumeName", nameField);

        JsonObject field = new JsonObject();
        field.addProperty("type", "string");
        field.addProperty("title", "下一步");
        field.add("enum", enums);
        props.add("answer", field);

        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("resumeName");
        req.add("answer");
        schema.add("required", req);
        ask.schema = schema;
        return ask;
    }

    private void applyGenerationProgress(JsonObject data) {
        if (!isAdded() || progressContainer == null || data == null) {
            return;
        }
        int percent = data.has("progress") && !data.get("progress").isJsonNull()
                ? data.get("progress").getAsInt() : 0;
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }
        String stage = opt(data, "stage");
        String status = opt(data, "status");
        String stageLabel = stageLabel(stage, status);
        String errorMsg = opt(data, "errorMsg");

        progressContainer.removeAllViews();
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        styleProgressHeader(header);
        header.setLayoutParams(UiDecor.cardLp(requireContext(), 12));

        TextView title = new TextView(requireContext());
        title.setText("正在生成中");
        if ("3".equals(status) || "2".equals(status)) {
            title.setText("简历已生成");
            percent = Math.max(percent, 100);
        } else if ("4".equals(status)) {
            title.setText("生成失败");
        }
        title.setTextColor(UiDecor.color(requireContext(), R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView meta = new TextView(requireContext());
        meta.setText(stageLabel + " · " + percent + "%");
        meta.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
        meta.setTextSize(13);
        meta.setPadding(0, UiDecor.dp(requireContext(), 4), 0, UiDecor.dp(requireContext(), 10));
        header.addView(meta);

        ProgressBar bar = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(percent);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiDecor.dp(requireContext(), 8));
        bar.setLayoutParams(barLp);
        header.addView(bar);

        if (errorMsg != null && !errorMsg.isEmpty()) {
            TextView err = new TextView(requireContext());
            err.setText(errorMsg);
            err.setTextColor(0xFFFF8A80);
            err.setTextSize(12);
            err.setPadding(0, UiDecor.dp(requireContext(), 8), 0, 0);
            header.addView(err);
        } else {
            TextView tip = new TextView(requireContext());
            tip.setText("生成完成后将自动展示下载二维码");
            tip.setTextColor(UiDecor.color(requireContext(), R.color.text_dim));
            tip.setTextSize(12);
            tip.setPadding(0, UiDecor.dp(requireContext(), 8), 0, 0);
            header.addView(tip);
        }
        progressContainer.addView(header);
    }

    private static String stageLabel(String stage, String status) {
        if ("4".equals(status)) {
            return "生成失败";
        }
        if ("3".equals(status) || "2".equals(status)) {
            return "已完成";
        }
        if (stage == null) {
            return "处理中";
        }
        switch (stage) {
            case "extract":
                return "整理素材";
            case "fill":
                return "填充模块";
            case "polish":
                return "润色优化";
            case "validate":
                return "内容校验";
            case "render":
                return "排版出稿";
            default:
                return "处理中";
        }
    }

    private void startGenerationPolling(Long recordId) {
        generatingRecordId = recordId;
        mainHandler.removeCallbacks(genPollRunnable);
        mainHandler.postDelayed(genPollRunnable, 1500L);
    }

    private void stopGenerationPolling() {
        generatingRecordId = null;
        mainHandler.removeCallbacks(genPollRunnable);
    }

    private void pollGenerationStatus() {
        if (!isAdded() || generatingRecordId == null) {
            return;
        }
        final Long rid = generatingRecordId;
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        UserInfo user = store.getUser();
        Long userId = parseLong(user == null ? null : user.userId);
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .getResumeStatus(store.getSn(), rid, userId);
            mainHandler.post(() -> {
                if (!isAdded() || generatingRecordId == null || !generatingRecordId.equals(rid)) {
                    return;
                }
                if (!resp.isOk() || resp.data == null) {
                    mainHandler.postDelayed(genPollRunnable, 2500L);
                    return;
                }
                String st = opt(resp.data, "status");
                String token = opt(resp.data, "downloadToken");
                // 完成：自动展示结果
                if (("3".equals(st) || "2".equals(st))
                        && token != null && !token.isEmpty()) {
                    stopGenerationPolling();
                    showGenerationResult(resp.data, null);
                    return;
                }
                // 失败
                if ("4".equals(st)) {
                    stopGenerationPolling();
                    applyGenerationProgress(resp.data);
                    DisplaySection fail = new DisplaySection();
                    fail.kind = DisplaySection.TEXT;
                    fail.title = "生成失败";
                    String err = opt(resp.data, "errorMsg");
                    fail.text = (err == null || err.isEmpty())
                            ? "抱歉，简历生成失败了。可以说「确认生成」再试一次。"
                            : ("抱歉，简历生成失败：" + err + "\n可以说「确认生成」再试一次。");
                    List<DisplaySection> sections = new ArrayList<>();
                    sections.add(fail);
                    if (!chatMode) {
                        enterChatMode("生成失败");
                    }
                    displayCanvas.render(sections, null);
                    Toast.makeText(requireContext(), "生成失败，可再说「确认生成」重试",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // 进行中：刷新「正在生成中」
                showGeneratingUi(resp.data, true);
                mainHandler.postDelayed(genPollRunnable, 2000L);
            });
        });
    }

    private static Long optLong(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** 构造带输入框/选项的提问表单，避免「表单配置缺失」 */
    private DisplaySection buildAskFormSection(String question, JsonArray enums) {
        DisplaySection ask = new DisplaySection();
        ask.kind = DisplaySection.FORM;
        ask.title = "请回答";
        ask.text = question;
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("title", question);
        JsonObject props = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("type", "string");
        field.addProperty("title", question);
        field.addProperty("description", "请输入回答");
        if (enums != null && enums.size() > 0) {
            field.add("enum", enums);
            for (JsonElement e : enums) {
                if (e != null && !e.isJsonNull()) {
                    ask.items.add(e.getAsString());
                }
            }
        }
        props.add("answer", field);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("answer");
        schema.add("required", req);
        ask.schema = schema;
        return ask;
    }

    private void openRecordDetail(String downloadToken, String title) {
        viewingRecordOnly = true;
        showQr(downloadToken, title);
    }

    private void showQr(String downloadToken, String title) {
        enterChatMode(title);
        // 历史详情：隐藏右侧空框与底部输入；生成完成态保留右侧进度
        if (viewingRecordOnly) {
            if (panelChatInput != null) {
                panelChatInput.setVisibility(View.GONE);
            }
            setProgressPanelVisible(false);
        } else if (progressContainer != null && progressContainer.getChildCount() == 0) {
            // 仅在无进度内容时补一行提示，避免空白标题框
            progressContainer.addView(UiDecor.subtitle(requireContext(), "生成完成后可扫左侧二维码下载"));
        }
        String token = downloadToken == null ? "" : downloadToken.trim();
        String h5Path = "/resume-dl/" + token;
        String pdfPath = "/robot/resume/download/" + token + "?format=pdf";
        String qrContent = DigitalHumanApp.getInstance().resolveMediaUrl(h5Path);
        if (qrContent == null || qrContent.isEmpty()) {
            qrContent = h5Path;
        }
        String pdfUrl = DigitalHumanApp.getInstance().resolveMediaUrl(pdfPath);
        if (pdfUrl == null || pdfUrl.isEmpty()) {
            pdfUrl = pdfPath;
        }

        DisplaySection qr = new DisplaySection();
        qr.kind = DisplaySection.QRCODE;
        qr.title = title == null ? "简历下载" : title;
        qr.text = qrContent;
        qr.actionLabel = "查看简历";
        qr.actionUrl = pdfUrl;
        qr.caption = "扫码下载到手机 · 或点右侧查看 PDF";

        List<DisplaySection> sections = new ArrayList<>();
        DisplaySection tip = new DisplaySection();
        tip.kind = DisplaySection.TEXT;
        tip.title = "小助手";
        tip.text = "简历已生成。可扫码用手机下载，或点击「查看简历」在本机打开 PDF。";
        sections.add(tip);
        sections.add(qr);
        displayCanvas.render(sections, "本机查看 PDF · 手机扫码下载");
    }

    @Override
    public void onDestroyView() {
        stopGenerationPolling();
        super.onDestroyView();
    }

    private TextView sectionTitle(String text) {
        TextView tv = UiDecor.title(requireContext(), text);
        tv.setPadding(0, UiDecor.dp(requireContext(), 16), 0, UiDecor.dp(requireContext(), 8));
        return tv;
    }

    private LinearLayout cardRow(String title, String sub) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        UiDecor.styleCard(requireContext(), row);
        row.setLayoutParams(UiDecor.cardLp(requireContext(), 10));
        row.addView(UiDecor.title(requireContext(), title));
        if (sub != null && !sub.trim().isEmpty()) {
            row.addView(UiDecor.subtitle(requireContext(), sub));
        }
        return row;
    }

    /** 草稿行：左侧继续，右侧删除 */
    private LinearLayout draftRow(String title, String sub, Long templateId,
                                  View.OnClickListener onContinue) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        UiDecor.styleCard(requireContext(), row);
        row.setLayoutParams(UiDecor.cardLp(requireContext(), 10));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(UiDecor.title(requireContext(), title));
        if (sub != null && !sub.trim().isEmpty()) {
            texts.addView(UiDecor.subtitle(requireContext(), sub));
        }
        if (onContinue != null) {
            texts.setClickable(true);
            texts.setOnClickListener(onContinue);
        }
        row.addView(texts);

        if (templateId != null) {
            TextView btnDel = new TextView(requireContext());
            btnDel.setText("删除");
            btnDel.setTextColor(0xFFFF8A80);
            btnDel.setTextSize(14);
            btnDel.setTypeface(null, android.graphics.Typeface.BOLD);
            btnDel.setPadding(UiDecor.dp(requireContext(), 14), UiDecor.dp(requireContext(), 10),
                    UiDecor.dp(requireContext(), 8), UiDecor.dp(requireContext(), 10));
            btnDel.setOnClickListener(v -> confirmDeleteDraft(templateId, title));
            row.addView(btnDel);
        }
        return row;
    }

    private void confirmDeleteDraft(Long templateId, String title) {
        if (templateId == null || !isAdded()) {
            return;
        }
        String label = title == null || title.trim().isEmpty() ? "这份草稿" : ("「" + title.trim() + "」");
        new AlertDialog.Builder(requireContext())
                .setTitle("删除草稿")
                .setMessage("确定删除" + label + "吗？删除后需重新开始采集。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> deleteDraft(templateId))
                .show();
    }

    private void deleteDraft(Long templateId) {
        if (templateId == null || !isAdded()) {
            return;
        }
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        UserInfo user = store.getUser();
        Long userId = parseLong(user == null ? null : user.userId);
        if (userId == null) {
            Toast.makeText(requireContext(), "请先登录后再删除草稿", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), "正在删除…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .abandonResumeDraft(userId, templateId);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (!resp.isOk()) {
                    Toast.makeText(requireContext(),
                            resp.message != null ? resp.message : "删除失败",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(requireContext(), "草稿已删除", Toast.LENGTH_SHORT).show();
                loadHub(false);
            });
        });
    }

    /** 历史简历行：左侧查看，右侧删除 */
    private LinearLayout historyRecordRow(String title, String sub, Long recordId,
                                          View.OnClickListener onOpen) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        UiDecor.styleCard(requireContext(), row);
        row.setLayoutParams(UiDecor.cardLp(requireContext(), 10));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(UiDecor.title(requireContext(), title));
        if (sub != null && !sub.trim().isEmpty()) {
            texts.addView(UiDecor.subtitle(requireContext(), sub));
        }
        if (onOpen != null) {
            texts.setClickable(true);
            texts.setOnClickListener(onOpen);
            TextView tip = UiDecor.subtitle(requireContext(), "点击查看");
            tip.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
            texts.addView(tip);
        }
        row.addView(texts);

        if (recordId != null) {
            TextView btnDel = new TextView(requireContext());
            btnDel.setText("删除");
            btnDel.setTextColor(0xFFFF8A80);
            btnDel.setTextSize(14);
            btnDel.setTypeface(null, android.graphics.Typeface.BOLD);
            btnDel.setPadding(UiDecor.dp(requireContext(), 14), UiDecor.dp(requireContext(), 10),
                    UiDecor.dp(requireContext(), 8), UiDecor.dp(requireContext(), 10));
            btnDel.setOnClickListener(v -> confirmDeleteRecord(recordId, title));
            row.addView(btnDel);
        }
        return row;
    }

    private void confirmDeleteRecord(Long recordId, String title) {
        if (recordId == null || !isAdded()) {
            return;
        }
        String label = title == null || title.trim().isEmpty() ? "这份简历" : ("「" + title.trim() + "」");
        new AlertDialog.Builder(requireContext())
                .setTitle("删除简历")
                .setMessage("确定删除" + label + "吗？删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> deleteResumeRecord(recordId))
                .show();
    }

    private void deleteResumeRecord(Long recordId) {
        if (recordId == null || !isAdded()) {
            return;
        }
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        UserInfo user = store.getUser();
        Long userId = parseLong(user == null ? null : user.userId);
        Toast.makeText(requireContext(), "正在删除…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<JsonObject> resp = DigitalHumanApp.getInstance().getApiService()
                    .deleteResumeRecord(store.getSn(), userId, recordId);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (!resp.isOk()) {
                    Toast.makeText(requireContext(),
                            resp.message != null ? resp.message : "删除失败",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                // 若正在轮询该记录，停止
                if (generatingRecordId != null && generatingRecordId.equals(recordId)) {
                    stopGenerationPolling();
                }
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
                if (viewingRecordOnly) {
                    viewingRecordOnly = false;
                    showHub();
                }
                loadHub(true);
            });
        });
    }

    /** 模板卡片：左侧样例缩略图 + 右侧文案；点图放大预览，点右侧选择模板 */
    private LinearLayout templateCard(String title, String sub, String sampleImage,
                                      View.OnClickListener onSelect) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        UiDecor.styleCard(requireContext(), row);
        row.setLayoutParams(UiDecor.cardLp(requireContext(), 10));

        if (sampleImage != null && !sampleImage.trim().isEmpty()) {
            ImageView thumb = new ImageView(requireContext());
            int w = UiDecor.dp(requireContext(), 72);
            int h = UiDecor.dp(requireContext(), 96);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(w, h);
            thumbLp.rightMargin = UiDecor.dp(requireContext(), 12);
            thumb.setLayoutParams(thumbLp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(0x3312202E);
            ph.setCornerRadius(UiDecor.dp(requireContext(), 8));
            thumb.setBackground(ph);
            thumb.setContentDescription("样例预览");
            final String mediaUrl = DigitalHumanApp.getInstance().resolveMediaUrl(sampleImage);
            thumb.setOnClickListener(v -> showSamplePreview(title, mediaUrl));
            row.addView(thumb);
            loadRemoteBitmap(thumb, mediaUrl);
        }

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.setClickable(true);
        texts.setOnClickListener(onSelect);
        texts.addView(UiDecor.title(requireContext(), title));
        if (sub != null && !sub.trim().isEmpty()) {
            texts.addView(UiDecor.subtitle(requireContext(), sub));
        }
        if (sampleImage != null && !sampleImage.trim().isEmpty()) {
            TextView tip = UiDecor.subtitle(requireContext(), "点左侧图预览效果 · 点此处选用模板");
            tip.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
            texts.addView(tip);
        } else {
            TextView tip = UiDecor.subtitle(requireContext(), "点击选用此模板");
            tip.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
            texts.addView(tip);
        }
        row.addView(texts);
        if (sampleImage == null || sampleImage.trim().isEmpty()) {
            row.setOnClickListener(onSelect);
        }
        return row;
    }

    private void showSamplePreview(String title, String mediaUrl) {
        if (!isAdded() || mediaUrl == null) {
            return;
        }
        ImageView iv = new ImageView(requireContext());
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = UiDecor.dp(requireContext(), 8);
        iv.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(iv);
        loadRemoteBitmap(iv, mediaUrl);
        new AlertDialog.Builder(requireContext())
                .setTitle(title == null ? "简历样例预览" : title + " · 样例")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void loadRemoteBitmap(ImageView target, String mediaUrl) {
        if (target == null || mediaUrl == null || mediaUrl.isEmpty()) {
            return;
        }
        final int token = target.hashCode();
        target.setTag(token);
        final String src = mediaUrl.trim();
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap bm = null;
            try {
                if (src.startsWith("data:image/")) {
                    bm = decodeDataUrlBitmap(src);
                } else if (src.startsWith("http://") || src.startsWith("https://")) {
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(src).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(15000);
                        conn.setInstanceFollowRedirects(true);
                        InputStream in = conn.getInputStream();
                        bm = BitmapFactory.decodeStream(in);
                        if (in != null) {
                            in.close();
                        }
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                } else {
                    // 兜底：当 resolve 后仍非 http/data 时尝试按纯 Base64 解码
                    bm = decodeRawBase64Bitmap(src);
                }
            } catch (Exception e) {
                bm = null;
            }
            final Bitmap result = bm;
            mainHandler.post(() -> {
                if (!isAdded() || target.getTag() == null
                        || !Integer.valueOf(token).equals(target.getTag())) {
                    return;
                }
                if (result != null) {
                    target.setImageBitmap(result);
                }
            });
        });
    }

    private static Bitmap decodeDataUrlBitmap(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || comma >= dataUrl.length() - 1) {
            return null;
        }
        String b64 = dataUrl.substring(comma + 1).replaceAll("\\s", "");
        return decodeRawBase64Bitmap(b64);
    }

    private static Bitmap decodeRawBase64Bitmap(String b64) {
        if (b64 == null || b64.length() < 32) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static String opt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return String.valueOf(o.get(key));
        }
    }

    /** 历史列表展示生成时间（接口 createTime：时间戳 / ISO / yyyy-MM-dd HH:mm:ss） */
    private static String formatResumeTime(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = o.get(key);
        Date date = null;
        try {
            if (!el.isJsonPrimitive()) {
                return null;
            }
            if (el.getAsJsonPrimitive().isNumber()) {
                long n = el.getAsLong();
                if (n > 0 && n < 100_000_000_000L) {
                    n *= 1000L;
                }
                date = new Date(n);
            } else {
                String s = el.getAsString();
                if (s == null || s.trim().isEmpty()) {
                    return null;
                }
                s = s.trim();
                if (s.matches("^\\d{10,13}$")) {
                    long n = Long.parseLong(s);
                    if (s.length() <= 10) {
                        n *= 1000L;
                    }
                    date = new Date(n);
                } else {
                    String normalized = s.replace('T', ' ');
                    // 去掉时区与毫秒：2024-01-02 12:30:00.000+08:00 → 2024-01-02 12:30:00
                    int cut = normalized.length();
                    for (int i = 0; i < normalized.length(); i++) {
                        char c = normalized.charAt(i);
                        if (c == '.' || c == '+' || (c == '-' && i > 10)) {
                            // 日期里的 '-' 保留；时间后的时区 '-' 截断
                            if (c == '-' && i <= 10) {
                                continue;
                            }
                            cut = i;
                            break;
                        }
                        if (c == 'Z' || c == 'z') {
                            cut = i;
                            break;
                        }
                    }
                    if (cut > 19) {
                        cut = 19;
                    }
                    String head = normalized.substring(0, Math.min(cut, normalized.length())).trim();
                    if (head.length() >= 16) {
                        date = new SimpleDateFormat(
                                head.length() >= 19 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm",
                                Locale.CHINA).parse(head.length() >= 19 ? head.substring(0, 19) : head);
                    } else if (head.length() >= 10) {
                        date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(head.substring(0, 10));
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (date == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(date);
    }

    private static Long asLong(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Exception e) {
            return parseLong(o.get(key).getAsString());
        }
    }

    private static Long parseLong(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
