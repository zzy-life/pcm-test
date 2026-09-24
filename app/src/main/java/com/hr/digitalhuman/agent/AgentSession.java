package com.hr.digitalhuman.agent;

import android.os.Handler;
import android.os.Looper;

import com.hr.digitalhuman.api.ApiService;
import com.hr.digitalhuman.app.SessionStore;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.ChatSession;
import com.hr.digitalhuman.model.agent.AgentStep;
import com.hr.digitalhuman.model.agent.AgentTurn;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.model.agent.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 智能体会话循环：用户话 → 后台 → 执行 robot_* 工具 → 回调 → 可能继续。
 * 旧版意图/动作分发已废弃。
 */
public class AgentSession {

    private static final String TAG = "AgentSession";
    private static final int MAX_STEPS_PER_TURN = 12;

    public interface Listener {
        void onThinking();

        void onStatus(String text, int pulseMode);

        void onTurnFinished();

        void onError(String message);

        void onFallbackSpeak(String text);
    }

    private final ApiService api;
    private final SessionStore store;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RobotToolRuntime runtime;
    private final Listener listener;

    private int generation;
    private boolean busy;
    private String pendingQuestion;
    private boolean navigating;
    /** 最近一次 robot_navigate 的 tool_call_id，到达/失败回执时带回后台 */
    private String lastNavigateToolCallId;

    public AgentSession(ApiService api, SessionStore store, ExecutorService io,
                        RobotToolRuntime runtime, Listener listener) {
        this.api = api;
        this.store = store;
        this.io = io;
        this.runtime = runtime;
        this.listener = listener;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setNavigating(boolean navigating) {
        this.navigating = navigating;
    }

    public boolean isNavigating() {
        return navigating;
    }

    public void enqueueQuestion(String question) {
        if (question != null && !question.trim().isEmpty()) {
            pendingQuestion = question.trim();
        }
    }

    public String takePendingQuestion() {
        String q = pendingQuestion;
        pendingQuestion = null;
        return q;
    }

    public int currentGeneration() {
        return generation;
    }

    public void sendUserMessage(String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        if (navigating) {
            enqueueQuestion(content);
            listener.onStatus("正在带路，到达后为您解答", 3);
            return;
        }
        generation++;
        final int gen = generation;
        busy = true;
        listener.onThinking();
        io.execute(() -> {
            ensureSession();
            if (gen != generation) {
                return;
            }
            ApiResponse<AgentTurn> resp = api.sendAgentMessage(
                    store.getSn(), store.getSessionId(), content.trim(), "user");
            main.post(() -> handleTurn(gen, resp, true));
        });
    }

    public void onNavigationFinished(boolean arrived, String message) {
        navigating = false;
        ToolResult result = arrived
                ? ToolResult.ok(RobotTools.NAVIGATE, "arrived", message == null ? "已到达" : message)
                : ToolResult.fail(RobotTools.NAVIGATE, "failed", message == null ? "导航结束" : message);
        String pending = takePendingQuestion();
        if (pending != null) {
            result.put("pendingQuestion", pending);
        }
        generation++;
        final int gen = generation;
        busy = true;
        listener.onThinking();
        io.execute(() -> {
            ensureSession();
            ApiResponse<AgentTurn> resp = api.submitToolResult(
                    store.getSn(), store.getSessionId(),
                    toolFeedbackText(result),
                    "tool", true,
                    lastNavigateToolCallId == null ? "" : lastNavigateToolCallId,
                    RobotTools.NAVIGATE);
            main.post(() -> handleTurn(gen, resp, true));
        });
    }

    public void interrupt(String reason) {
        generation++;
        busy = false;
        runtime.cancelCaptionTicker();
        DebugLog.i(TAG, "interrupt " + reason);
    }

    private void handleTurn(int gen, ApiResponse<AgentTurn> resp, boolean fallbackSpeak) {
        if (gen != generation) {
            return;
        }
        if (resp == null || !resp.isOk() || resp.data == null) {
            busy = false;
            listener.onError(resp != null && resp.message != null ? resp.message : "服务暂时不可用");
            listener.onTurnFinished();
            return;
        }
        AgentTurn turn = resp.data;
        if (turn.sessionId != null && !turn.sessionId.isEmpty()) {
            store.setSessionId(turn.sessionId);
        }
        List<AgentStep> robotSteps = filterRobotSteps(turn.steps);
        if (robotSteps.isEmpty()) {
            if (fallbackSpeak && isUserFacing(turn.content)) {
                listener.onFallbackSpeak(turn.content.trim());
                return;
            }
            busy = false;
            listener.onTurnFinished();
            return;
        }
        runSteps(gen, robotSteps, 0, isUserFacing(turn.content) ? turn.content : null);
    }

    private void runSteps(int gen, List<AgentStep> steps, int index, String leftoverContent) {
        if (gen != generation) {
            return;
        }
        if (index >= steps.size() || index >= MAX_STEPS_PER_TURN) {
            if (isUserFacing(leftoverContent) && !hadSpeak(steps)) {
                listener.onFallbackSpeak(leftoverContent.trim());
                return;
            }
            busy = false;
            listener.onTurnFinished();
            return;
        }
        AgentStep step = steps.get(index);
        if (RobotTools.NAVIGATE.equals(RobotTools.canonical(step.toolName()))
                && step.toolCallId != null && !step.toolCallId.isEmpty()) {
            lastNavigateToolCallId = step.toolCallId;
        }
        runtime.execute(step, (result, callLlmAfter) -> {
            if (gen != generation) {
                return;
            }
            io.execute(() -> {
                ApiResponse<AgentTurn> next = api.submitToolResult(
                        store.getSn(), store.getSessionId(),
                        toolFeedbackText(result),
                        "tool",
                        callLlmAfter,
                        step.toolCallId == null ? "" : step.toolCallId,
                        step.toolName());
                main.post(() -> {
                    if (gen != generation) {
                        return;
                    }
                    if (next != null && next.isOk() && next.data != null && next.data.hasRobotSteps()) {
                        runSteps(gen, filterRobotSteps(next.data.steps), 0, next.data.content);
                        return;
                    }
                    if (next != null && next.isOk() && next.data != null
                            && isUserFacing(next.data.content) && !hadSpeak(steps)) {
                        listener.onFallbackSpeak(next.data.content.trim());
                        return;
                    }
                    runSteps(gen, steps, index + 1, leftoverContent);
                });
            });
        });
    }

    private void ensureSession() {
        if (store.getSessionId() != null && !store.getSessionId().isEmpty()) {
            return;
        }
        ApiResponse<ChatSession> s = api.createSession(store.getSn(), "agent");
        if (s.isOk() && s.data != null && s.data.sessionId != null) {
            store.setSessionId(s.data.sessionId);
        }
    }

    private static List<AgentStep> filterRobotSteps(List<AgentStep> steps) {
        List<AgentStep> list = new ArrayList<>();
        if (steps == null) {
            return list;
        }
        for (AgentStep step : steps) {
            if (step != null && step.isRobotTool()) {
                list.add(step);
            }
        }
        return list;
    }

    private static String toolFeedbackText(ToolResult result) {
        if (result == null) {
            return "";
        }
        if (result.message != null && !result.message.trim().isEmpty()) {
            return result.message.trim();
        }
        return result.toJson();
    }

    private static boolean hadSpeak(List<AgentStep> steps) {
        if (steps == null) {
            return false;
        }
        for (AgentStep step : steps) {
            if (step != null && RobotTools.SPEAK.equals(RobotTools.canonical(step.toolName()))) {
                return true;
            }
        }
        return false;
    }

    /** 协议回执、执行中提示不播给用户，只播真正面向人的正文。 */
    private static boolean isUserFacing(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.contains("工具执行结果已记录") || s.startsWith("正在执行：")) {
            return false;
        }
        return true;
    }

    /** 无 robot_speak 时，把 content 当作播报兜底。 */
    public void speakFallback(String text, RobotToolRuntime.SpeakCallback cb) {
        AgentStep step = new AgentStep();
        step.tool = RobotTools.SPEAK;
        step.params = new com.google.gson.JsonObject();
        step.params.addProperty("text", text);
        step.params.addProperty("caption", true);
        runtime.execute(step, (result, callLlm) -> {
            if (cb != null) {
                if (result != null && result.ok) {
                    cb.onComplete();
                } else {
                    cb.onError(result == null ? "fail" : result.message);
                }
            }
        });
    }

    public static List<DisplaySection> thinkingPlaceholder() {
        DisplaySection s = new DisplaySection();
        s.kind = DisplaySection.TEXT;
        s.title = "正在思考";
        s.text = "请稍候，正在努力思考中";
        List<DisplaySection> list = new ArrayList<>();
        list.add(s);
        return list;
    }
}
