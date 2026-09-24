package com.hr.digitalhuman.ui.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.app.SessionStore;
import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.model.UserInfo;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.ui.MainActivity;
import com.hr.digitalhuman.ui.UiDecor;
import com.hr.digitalhuman.ui.display.CaptionBarView;
import com.hr.digitalhuman.ui.display.DisplayCanvas;
import com.hr.digitalhuman.ime.SoftImeController;
import com.hr.digitalhuman.view.AiPulseView;

import java.util.List;

/**
 * 对话面屏：字幕 + 结构化画布。表情在待机/迎宾独立全屏，此处不展示。
 */
public class HomeFragment extends Fragment {

    private LinearLayout quickTags;
    private ScrollView scrollCanvas;
    private EditText etInput;
    private TextView tvUserLatest;
    private View userSaidPanel;
    private ScrollView scrollUserSaid;
    private TextView tvAiStatus;
    private AiPulseView aiPulse;
    private CaptionBarView captionBar;
    private DisplayCanvas displayCanvas;
    private TextView btnLoginUser;
    private TextView btnHistory;
    private TextView btnLogout;
    private TextView tvHomeTitle;
    private ImageView ivCoverEars;
    private TextView tvListenHint;
    private View listenDot;
    private long nameTapAt;
    private int nameTapCount;
    private long sloganTapAt;
    private int sloganTapCount;
    private boolean showingThinking;
    private boolean showingError;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        quickTags = v.findViewById(R.id.quick_tags);
        scrollCanvas = v.findViewById(R.id.scroll_canvas);
        etInput = v.findViewById(R.id.et_input);
        tvUserLatest = v.findViewById(R.id.tv_user_latest);
        userSaidPanel = v.findViewById(R.id.user_said_panel);
        scrollUserSaid = v.findViewById(R.id.scroll_user_said);
        tvAiStatus = v.findViewById(R.id.tv_ai_status);
        aiPulse = v.findViewById(R.id.ai_pulse);
        captionBar = v.findViewById(R.id.caption_bar);
        displayCanvas = v.findViewById(R.id.display_canvas);
        TextView btnSend = v.findViewById(R.id.btn_send);
        btnHistory = v.findViewById(R.id.btn_history);
        btnLoginUser = v.findViewById(R.id.btn_login_user);
        btnLogout = v.findViewById(R.id.btn_logout);
        TextView title = v.findViewById(R.id.tv_home_title);
        TextView slogan = v.findViewById(R.id.tv_home_slogan);
        tvHomeTitle = title;
        ivCoverEars = v.findViewById(R.id.iv_cover_ears);
        tvListenHint = v.findViewById(R.id.tv_listen_hint);
        listenDot = v.findViewById(R.id.listen_dot);
        if (title != null) {
            title.setOnClickListener(view -> onRobotNameTap());
        }
        if (slogan != null) {
            slogan.setOnClickListener(view -> onSloganTap());
        }

        SoftImeController.get().bind(etInput);
        com.hr.digitalhuman.view.AmbientFxView fx = v.findViewById(R.id.ambient_fx);
        if (fx != null) {
            fx.setIntensity(0.35f);
        }
        UiDecor.pulseDot(v.findViewById(R.id.listen_dot));

        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        String name = config != null && config.robotName != null ? config.robotName : "人社小助手";
        String sloganText = config != null && config.theme != null && config.theme.homeSlogan != null
                ? config.theme.homeSlogan : "智慧人社 · 贴心服务";
        if (title != null) {
            title.setText(name);
        }
        refreshRobotName();
        if (slogan != null) {
            slogan.setText(sloganText);
        }
        displayCanvas.setFormListener((summary, values) -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).sendChat(summary, "form");
            }
        });
        showIdleWelcome(name, sloganText);

        if (config != null && config.homeQuickTags != null && !config.homeQuickTags.isEmpty()) {
            for (RobotConfig.QuickTag tag : config.homeQuickTags) {
                addQuickTag(tag.label, tag.presetQuestion);
            }
        } else {
            addQuickTag("失业保险金", "失业保险金怎么领？");
            addQuickTag("办事材料", "办社保卡需要带什么材料？");
        }

        btnHistory.setOnClickListener(view -> {
            if (!view.isEnabled() || !(getActivity() instanceof MainActivity)) {
                return;
            }
            ((MainActivity) getActivity()).openHistory();
        });
        btnLoginUser.setOnClickListener(view -> {
            if (!view.isEnabled() || !(getActivity() instanceof MainActivity)) {
                return;
            }
            if (!DigitalHumanApp.getInstance().getSessionStore().isLoggedIn()) {
                DigitalHumanApp.getInstance().getSessionStore()
                        .setPendingAfterLogin(SessionStore.AFTER_LOGIN_HOME);
                ((MainActivity) getActivity()).openLogin();
            }
        });
        if (btnLogout != null) {
            btnLogout.setOnClickListener(view -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).logoutUser("home_button");
                }
            });
        }
        refreshLoginUser();
        btnSend.setOnClickListener(view -> submitInput());
        return v;
    }

    /** 连点名称 3 次：进入/退出演示模式，演示模式下忽略全部语音。 */
    private void onRobotNameTap() {
        long now = System.currentTimeMillis();
        if (now - nameTapAt > 1200) {
            nameTapCount = 0;
        }
        nameTapAt = now;
        nameTapCount++;
        if (nameTapCount < 3) {
            return;
        }
        nameTapCount = 0;
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        boolean on = !store.isDemoMode();
        store.setDemoMode(on);
        refreshRobotName();
        Toast.makeText(requireContext(), on ? "已暂停聆听，不再响应语音" : "已恢复聆听",
                Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onListenPauseChanged(on);
        }
    }

    /** 连点标语 3 次：校验后台配置的管理口令。 */
    private void onSloganTap() {
        long now = System.currentTimeMillis();
        if (now - sloganTapAt > 1200) {
            sloganTapCount = 0;
        }
        sloganTapAt = now;
        sloganTapCount++;
        if (sloganTapCount < 3) {
            return;
        }
        sloganTapCount = 0;
        showAdminPasscodeDialog();
    }

    private void showAdminPasscodeDialog() {
        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        String expect = config != null && config.admin != null && config.admin.passcode != null
                ? config.admin.passcode.trim() : "";
        if (expect.isEmpty()) {
            expect = "888888";
        }
        final String passcode = expect;
        EditText input = new EditText(requireContext());
        input.setHint("请输入管理口令");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = UiDecor.dp(requireContext(), 16);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(requireContext())
                .setTitle("管理口令")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("进入", (d, w) -> {
                    String got = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!passcode.equals(got)) {
                        Toast.makeText(requireContext(), "口令不正确", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).openRobotAdmin();
                    }
                })
                .show();
    }

    private void refreshRobotName() {
        if (!isAdded()) {
            return;
        }
        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        String name = config != null && config.robotName != null && !config.robotName.trim().isEmpty()
                ? config.robotName.trim() : "人社小助手";
        if (tvHomeTitle != null) {
            tvHomeTitle.setText(name);
        }
        boolean paused = DigitalHumanApp.getInstance().getSessionStore().isDemoMode();
        if (ivCoverEars != null) {
            ivCoverEars.setVisibility(paused ? View.VISIBLE : View.GONE);
        }
        if (tvListenHint != null) {
            tvListenHint.setText(paused ? R.string.pause_listen : R.string.home_listen_hint);
            tvListenHint.setTextColor(ContextCompat.getColor(requireContext(),
                    paused ? R.color.accent_warm : R.color.primary_bright));
        }
        if (listenDot != null) {
            listenDot.setBackgroundResource(paused ? R.drawable.bg_pause_dot : R.drawable.bg_listen_dot);
        }
        applyIdleStatusVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshLoginUser();
        refreshRobotName();
    }

    /** 右上角：未登录显示「登录」；已登录显示姓名 +「退出登录」。 */
    public void refreshLoginUser() {
        if (btnLoginUser == null || !isAdded()) {
            return;
        }
        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        if (store.isLoggedIn()) {
            UserInfo user = store.getUser();
            String name = resolveDisplayName(user);
            btnLoginUser.setText(name);
            btnLoginUser.setTextColor(UiDecor.color(requireContext(), R.color.text));
            btnLoginUser.setTypeface(btnLoginUser.getTypeface(), android.graphics.Typeface.NORMAL);
            if (btnLogout != null) {
                btnLogout.setVisibility(View.VISIBLE);
            }
        } else {
            btnLoginUser.setText(R.string.login);
            btnLoginUser.setTextColor(UiDecor.color(requireContext(), R.color.primary_bright));
            btnLoginUser.setTypeface(btnLoginUser.getTypeface(), android.graphics.Typeface.BOLD);
            if (btnLogout != null) {
                btnLogout.setVisibility(View.GONE);
            }
        }
    }

    private static String resolveDisplayName(UserInfo user) {
        if (user == null) {
            return "用户";
        }
        if (user.displayName != null && !user.displayName.trim().isEmpty()) {
            return user.displayName.trim();
        }
        if (user.username != null && !user.username.trim().isEmpty()) {
            return user.username.trim();
        }
        if (user.phone != null && !user.phone.trim().isEmpty()) {
            return user.phone.trim();
        }
        return "用户";
    }

    /** 后台改了通用配置后重新绑定标题、口号和快捷入口。 */
    public void applyConfig(RobotConfig config) {
        if (!isAdded() || getView() == null) {
            return;
        }
        String sloganText = config != null && config.theme != null && config.theme.homeSlogan != null
                ? config.theme.homeSlogan : "智慧人社 · 贴心服务";
        TextView title = getView().findViewById(R.id.tv_home_title);
        TextView slogan = getView().findViewById(R.id.tv_home_slogan);
        if (title != null) {
            tvHomeTitle = title;
        }
        refreshRobotName();
        if (slogan != null) {
            slogan.setText(sloganText);
        }
        if (quickTags != null) {
            quickTags.removeAllViews();
            if (config != null && config.homeQuickTags != null && !config.homeQuickTags.isEmpty()) {
                for (RobotConfig.QuickTag tag : config.homeQuickTags) {
                    addQuickTag(tag.label, tag.presetQuestion);
                }
            } else {
                addQuickTag("失业保险金", "失业保险金怎么领？");
                addQuickTag("办事材料", "办社保卡需要带什么材料？");
                addQuickTag("带路", "请带我去社保窗口");
            }
        }
    }

    private void addQuickTag(String label, String question) {
        if (quickTags == null || label == null || label.trim().isEmpty()) {
            return;
        }
        TextView chip = actionChip(label, iconFor(label));
        final String q = question == null || question.trim().isEmpty() ? label : question.trim();
        chip.setOnClickListener(view -> {
            if (!(getActivity() instanceof MainActivity)) {
                return;
            }
            if (isResumeEntry(label, q)) {
                ((MainActivity) getActivity()).openResumeCenter();
                return;
            }
            ((MainActivity) getActivity()).sendChat(q, "quick_tag");
        });
        quickTags.addView(chip);
    }

    private TextView actionChip(String label, int icon) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextColor(UiDecor.color(requireContext(), R.color.text));
        chip.setTextSize(12);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setSingleLine(true);
        chip.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        chip.setCompoundDrawablePadding(UiDecor.dp(requireContext(), 4));
        chip.setBackgroundResource(R.drawable.bg_home_chip);
        int h = UiDecor.dp(requireContext(), 30);
        chip.setPadding(UiDecor.dp(requireContext(), 8), 0, UiDecor.dp(requireContext(), 10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, h);
        lp.rightMargin = UiDecor.dp(requireContext(), 6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private int iconFor(String label) {
        String s = label == null ? "" : label;
        if (s.contains("简历") || s.contains("材料") || s.contains("仲裁")) {
            return R.drawable.ic_home_doc;
        }
        if (s.contains("带") || s.contains("路") || s.contains("卫生间") || s.contains("窗口") || s.contains("出口")) {
            return R.drawable.ic_home_nav;
        }
        if (s.contains("社保") || s.contains("养老") || s.contains("医保")) {
            return R.drawable.ic_home_shield;
        }
        if (s.contains("就业") || s.contains("失业") || s.contains("岗位")) {
            return R.drawable.ic_home_job;
        }
        if (s.contains("登录") || s.contains("人才")) {
            return R.drawable.ic_home_user;
        }
        return R.drawable.ic_home_chat;
    }

    private static boolean isResumeEntry(String label, String question) {
        String s = ((label == null ? "" : label) + " " + (question == null ? "" : question));
        return s.contains("简历");
    }

    /** 用户原话放到对话区整段换行；超过约四行时可滚动看完，不再在顶栏截断。 */
    private void showUserUtterance(String text) {
        if (!isAdded()) {
            return;
        }
        String show = text == null ? "" : text.trim();
        if (show.isEmpty()) {
            if (userSaidPanel != null) {
                userSaidPanel.setVisibility(View.GONE);
            }
            if (tvUserLatest != null) {
                tvUserLatest.setText("");
            }
            return;
        }
        if (userSaidPanel != null) {
            userSaidPanel.setVisibility(View.VISIBLE);
        }
        if (tvUserLatest == null) {
            return;
        }
        tvUserLatest.setText(show);
        if (scrollUserSaid == null) {
            return;
        }
        tvUserLatest.post(() -> {
            if (!isAdded() || scrollUserSaid == null || tvUserLatest == null) {
                return;
            }
            int width = scrollUserSaid.getWidth();
            if (width <= 0 && userSaidPanel != null) {
                width = userSaidPanel.getWidth();
            }
            if (width <= 0) {
                return;
            }
            int max = UiDecor.dp(requireContext(), 132);
            tvUserLatest.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = tvUserLatest.getMeasuredHeight();
            ViewGroup.LayoutParams lp = scrollUserSaid.getLayoutParams();
            lp.height = h > max ? max : ViewGroup.LayoutParams.WRAP_CONTENT;
            scrollUserSaid.setLayoutParams(lp);
            scrollUserSaid.scrollTo(0, 0);
        });
    }

    private void showIdleWelcome(String robotName, String slogan) {
        setAiStatus("就绪 · 请直接说话", AiPulseView.MODE_LISTEN);
        if (userSaidPanel != null) {
            userSaidPanel.setVisibility(View.GONE);
        }
        if (tvUserLatest != null) {
            tvUserLatest.setText("");
        }
        if (captionBar != null) {
            captionBar.clearCaption();
        }
        if (displayCanvas != null) {
            displayCanvas.showWelcome(robotName, slogan);
        }
    }

    public void setInputPreview(String text) {
        if (etInput == null || !isAdded()) {
            return;
        }
        final String show = text == null ? "" : text;
        Runnable apply = () -> {
            if (etInput == null) {
                return;
            }
            etInput.setText(show);
            showUserUtterance(show);
            if (!show.isEmpty()) {
                setAiStatus("正在聆听…", AiPulseView.MODE_LISTEN);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply.run();
        } else {
            etInput.post(apply);
        }
    }

    public void fillInputAndSend(String text, String inputSource) {
        if (text == null || !isAdded()) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        setInputPreview(trimmed);
        if (etInput != null) {
            etInput.postDelayed(() -> {
                if (!isAdded() || !(getActivity() instanceof MainActivity)) {
                    return;
                }
                etInput.setText("");
                ((MainActivity) getActivity()).sendChat(trimmed, inputSource);
            }, 400);
        } else if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).sendChat(trimmed, inputSource);
        }
    }

    private void submitInput() {
        String text = etInput.getText() != null ? etInput.getText().toString().trim() : "";
        if (text.isEmpty()) {
            return;
        }
        etInput.setText("");
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).sendChat(text, "keyboard");
        }
    }

    public void appendUserMessage(String text) {
        if (!isAdded()) {
            return;
        }
        String show = text == null ? "" : text.trim();
        if (show.isEmpty()) {
            return;
        }
        showUserUtterance(show);
        if (captionBar != null) {
            captionBar.clearCaption();
        }
        setAiStatus("已收到问题", AiPulseView.MODE_LISTEN);
    }

    public void showThinking() {
        if (!isAdded()) {
            return;
        }
        showingThinking = true;
        showingError = false;
        String name = "小助手";
        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        if (config != null && config.robotName != null && !config.robotName.trim().isEmpty()) {
            name = config.robotName.trim();
        }
        String line = name + "正在努力思考中";
        setAiStatus(line, AiPulseView.MODE_THINK);
        if (displayCanvas != null) {
            displayCanvas.showThinking(line);
        }
        setAccountButtonsEnabled(false);
    }

    public void showError(String message) {
        if (!isAdded()) {
            return;
        }
        showingThinking = false;
        showingError = true;
        setAiStatus("暂时无法回答", AiPulseView.MODE_LISTEN);
        DisplaySection s = new DisplaySection();
        s.kind = DisplaySection.TEXT;
        s.title = "暂时无法回答";
        s.text = message == null || message.trim().isEmpty()
                ? "服务暂时不可用，请稍后再试，或前往综合窗口咨询。"
                : message.trim();
        java.util.ArrayList<DisplaySection> list = new java.util.ArrayList<>();
        list.add(s);
        if (displayCanvas != null) {
            displayCanvas.render(list, null, false);
        }
        setAccountButtonsEnabled(true);
    }

    public void showCaption(List<String> sentences, boolean visible) {
        if (!isAdded() || captionBar == null) {
            return;
        }
        if (!visible || sentences == null || sentences.isEmpty()) {
            captionBar.clearCaption();
            return;
        }
        captionBar.setSentences(sentences);
        showingThinking = false;
        scrollTop();
    }

    public void highlightCaption(int index) {
        if (isAdded() && captionBar != null) {
            captionBar.highlight(index);
        }
    }

    public void finishCaption() {
        if (!isAdded() || captionBar == null) {
            return;
        }
        captionBar.markFinished();
        setAiStatus(getString(R.string.speak_done_hint), AiPulseView.MODE_SPEAK);
        setAccountButtonsEnabled(true);
        scheduleListenStatus();
    }

    /** 用户点了停止播报：字幕改成已播部分，状态不再显示正在播报。 */
    public void onSpeakStopped() {
        if (!isAdded()) {
            return;
        }
        if (captionBar != null) {
            captionBar.markStopped();
        }
        setAiStatus(getString(R.string.speak_stopped_hint), AiPulseView.MODE_LISTEN);
        setAccountButtonsEnabled(true);
        scheduleListenStatus();
    }

    public void renderDisplay(List<DisplaySection> sections, String footnote) {
        if (!isAdded() || displayCanvas == null) {
            return;
        }
        showingThinking = false;
        showingError = false;
        displayCanvas.render(sections, footnote);
        scrollTop();
    }

    public void setAiStatus(String status, int mode) {
        if (tvAiStatus != null) {
            tvAiStatus.setText(status);
            applyIdleStatusVisibility();
        }
        if (aiPulse != null) {
            aiPulse.setMode(mode);
        }
    }

    /** 暂停聆听时不显示空闲提示，包括「就绪 · 请直接说话」和「正在聆听」。 */
    private void applyIdleStatusVisibility() {
        if (tvAiStatus == null) {
            return;
        }
        boolean paused = DigitalHumanApp.getInstance().getSessionStore().isDemoMode();
        tvAiStatus.setVisibility(paused && isIdleListenStatus(tvAiStatus.getText())
                ? View.GONE : View.VISIBLE);
    }

    private static boolean isIdleListenStatus(CharSequence text) {
        if (text == null) {
            return false;
        }
        String s = text.toString();
        return "就绪 · 请直接说话".equals(s)
                || "正在聆听".equals(s)
                || "正在聆听…".equals(s)
                || "正在聆听...".equals(s);
    }

    public void onSpeakStarted() {
        setAiStatus("正在播报", AiPulseView.MODE_SPEAK);
        setAccountButtonsEnabled(false);
    }

    public void onAgentIdle() {
        if (showingError) {
            setAiStatus("暂时无法回答", AiPulseView.MODE_LISTEN);
            return;
        }
        setAiStatus("正在聆听", AiPulseView.MODE_LISTEN);
        if (showingThinking) {
            showingThinking = false;
            DisplaySection s = new DisplaySection();
            s.kind = DisplaySection.TEXT;
            s.title = "可以继续提问";
            s.text = "我在听，请直接说出您的问题。";
            java.util.ArrayList<DisplaySection> list = new java.util.ArrayList<>();
            list.add(s);
            if (displayCanvas != null) {
                displayCanvas.render(list, null, false);
            }
        }
        setAccountButtonsEnabled(true);
    }

    /** 思考、播报期间不可点登录和历史。 */
    private void setAccountButtonsEnabled(boolean enabled) {
        applyAccountButton(btnHistory, enabled);
        applyAccountButton(btnLoginUser, enabled);
    }

    private static void applyAccountButton(TextView btn, boolean enabled) {
        if (btn == null) {
            return;
        }
        btn.setEnabled(enabled);
        btn.setClickable(enabled);
        btn.setAlpha(enabled ? 1f : 0.35f);
    }

    private void scheduleListenStatus() {
        if (aiPulse != null) {
            aiPulse.postDelayed(() -> {
                if (isAdded()) {
                    setAiStatus("正在聆听", AiPulseView.MODE_LISTEN);
                }
            }, 2200);
        }
    }

    private void scrollTop() {
        if (scrollCanvas != null) {
            scrollCanvas.post(() -> scrollCanvas.fullScroll(View.FOCUS_UP));
        }
    }
}
