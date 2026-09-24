package com.hr.digitalhuman.agent;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.agent.AgentStep;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.model.agent.ToolResult;
import com.hr.digitalhuman.model.NavPoint;
import com.hr.digitalhuman.robot.NavMapHelper;
import com.hr.digitalhuman.robot.RobotMotionHelper;
import com.hr.digitalhuman.ui.display.CaptionBarView;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人端工具执行器。模型只出意图，此处落地为 TTS / 屏幕组件 / 动作 / 导航。
 */
public class RobotToolRuntime {

    private static final String TAG = "RobotToolRuntime";

    public interface Host {
        boolean isNavigating();

        void speak(String text, float rate, boolean interrupt, SpeakCallback callback);

        void stopSpeak();

        void showCaption(List<String> sentences, boolean visible);

        void highlightCaption(int index);

        void finishCaption();

        void renderDisplay(List<DisplaySection> sections, String footnote);

        void applyExpression(String expression);

        void startNavigation(String mapId, String poiId, String placeName, String target);

        void cancelNavigation();

        /** 打开 App 原生页面（robot_open_page）。 */
        void openPage(String page, JsonObject params);
    }

    public interface SpeakCallback {
        void onComplete();

        void onError(String msg);
    }

    public interface StepCallback {
        void onFinished(ToolResult result, boolean callLlmAfter);
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable captionTicker;
    private int captionGeneration;

    public RobotToolRuntime(Host host) {
        this.host = host;
    }

    public void execute(AgentStep step, StepCallback callback) {
        if (step == null) {
            callback.onFinished(ToolResult.fail("unknown", "invalid", "空步骤"), false);
            return;
        }
        String tool = RobotTools.canonical(step.toolName());
        JsonObject p = step.params == null ? new JsonObject() : step.params;
        DebugLog.i(TAG, "exec " + tool + " id=" + step.toolCallId);

        if (host.isNavigating() && isBlockedDuringNav(tool)) {
            callback.onFinished(ToolResult.fail(tool, "blocked", "导航进行中，该动作已跳过"), false);
            return;
        }

        switch (tool) {
            case RobotTools.DISPLAY:
                execDisplay(p, callback);
                break;
            case RobotTools.SPEAK:
                execSpeak(p, callback);
                break;
            case RobotTools.ACT:
                execAct(p, callback);
                break;
            case RobotTools.NAVIGATE:
                execNavigate(p, callback);
                break;
            case RobotTools.NAV_CANCEL:
                execNavCancel(callback);
                break;
            case RobotTools.ASK_USER:
                execAskUser(p, callback);
                break;
            case RobotTools.OPEN_PAGE:
                execOpenPage(p, callback);
                break;
            default:
                if (tool.startsWith("server_")) {
                    callback.onFinished(ToolResult.ok(tool, "skipped", "服务端工具由后台执行"), false);
                } else {
                    callback.onFinished(ToolResult.fail(tool, "unknown", "未支持的机器人工具"), false);
                }
                break;
        }
    }

    public void cancelCaptionTicker() {
        captionGeneration++;
        if (captionTicker != null) {
            main.removeCallbacks(captionTicker);
            captionTicker = null;
        }
    }

    private boolean isBlockedDuringNav(String tool) {
        return RobotTools.ACT.equals(tool)
                || RobotTools.DISPLAY.equals(tool)
                || RobotTools.NAVIGATE.equals(tool)
                || RobotTools.ASK_USER.equals(tool);
    }

    private void execDisplay(JsonObject p, StepCallback callback) {
        List<DisplaySection> sections = parseSections(p);
        String footnote = str(p, "footnote");
        host.renderDisplay(sections, footnote);
        callback.onFinished(ToolResult.ok(RobotTools.DISPLAY, "shown", "屏幕已更新")
                .put("sectionCount", sections.size()), false);
    }

    /** 优先读 params.sections；模型把 title/bullets 摊在 params 根上时也能渲染。 */
    private static List<DisplaySection> parseSections(JsonObject p) {
        List<DisplaySection> sections = DisplaySection.listFrom(p.get("sections"));
        if (!sections.isEmpty() || p == null) {
            return sections;
        }
        if (p.has("title") || p.has("items") || p.has("bullets")
                || p.has("kind") || p.has("content") || p.has("rows")) {
            return DisplaySection.listFrom(p);
        }
        return sections;
    }

    private void execSpeak(JsonObject p, StepCallback callback) {
        String text = str(p, "text");
        if (text == null || text.trim().isEmpty()) {
            callback.onFinished(ToolResult.fail(RobotTools.SPEAK, "empty", "播报文本为空"), false);
            return;
        }
        boolean caption = bool(p, "caption", true);
        boolean interrupt = bool(p, "interrupt", false);
        float rate = num(p, "rate", 1.0f);
        if (rate < 0.5f) {
            rate = 0.5f;
        }
        if (rate > 2.0f) {
            rate = 2.0f;
        }
        List<DisplaySection> sections = parseSections(p);
        if (!sections.isEmpty() && !host.isNavigating()) {
            host.renderDisplay(sections, str(p, "footnote"));
        }
        List<String> sentences = CaptionBarView.splitSentences(text);
        if (caption) {
            host.showCaption(sentences, true);
            startCaptionTicker(sentences, rate);
        } else {
            host.showCaption(null, false);
        }
        host.speak(text, rate, interrupt, new SpeakCallback() {
            @Override
            public void onComplete() {
                cancelCaptionTicker();
                host.finishCaption();
                callback.onFinished(ToolResult.ok(RobotTools.SPEAK, "spoken", "播报完成"), false);
            }

            @Override
            public void onError(String msg) {
                cancelCaptionTicker();
                host.finishCaption();
                callback.onFinished(ToolResult.fail(RobotTools.SPEAK, "tts_error",
                        msg == null ? "播报失败" : msg), false);
            }
        });
    }

    private void startCaptionTicker(List<String> sentences, float rate) {
        cancelCaptionTicker();
        if (sentences == null || sentences.size() <= 1) {
            return;
        }
        final int gen = captionGeneration;
        final float safeRate = rate <= 0 ? 1f : rate;
        scheduleCaption(sentences, 0, gen, safeRate);
    }

    private void scheduleCaption(List<String> sentences, int index, int gen, float rate) {
        if (index >= sentences.size() || gen != captionGeneration) {
            return;
        }
        host.highlightCaption(index);
        int chars = sentences.get(index).length();
        long delay = (long) Math.max(600, chars * (210f / rate));
        captionTicker = () -> scheduleCaption(sentences, index + 1, gen, rate);
        main.postDelayed(captionTicker, delay);
    }

    private void execAct(JsonObject p, StepCallback callback) {
        String action = str(p, "action");
        if (action == null) {
            action = str(p, "expression");
        }
        if (action == null) {
            callback.onFinished(ToolResult.fail(RobotTools.ACT, "empty", "缺少 action"), false);
            return;
        }
        String a = action.trim().toLowerCase();
        switch (a) {
            case "greeting":
            case "welcome":
            case "hello":
                host.applyExpression("greeting");
                RobotMotionHelper.greeting();
                break;
            case "dance":
                host.applyExpression("dance");
                RobotMotionHelper.dance();
                break;
            case "nod":
                host.applyExpression("nod");
                RobotMotionHelper.nod();
                break;
            case "smile":
            case "happy":
                host.applyExpression("smile");
                RobotMotionHelper.smile();
                break;
            default:
                host.applyExpression(a);
                break;
        }
        callback.onFinished(ToolResult.ok(RobotTools.ACT, "done", "已执行动作")
                .put("action", a), false);
    }

    private void execNavigate(JsonObject p, StepCallback callback) {
        String placeName = firstNonEmpty(str(p, "placeName"), str(p, "destName"),
                str(p, "robotMapPlaceName"), str(p, "target"), str(p, "name"));
        String poiId = str(p, "poiId");
        List<NavPoint> local = NavMapHelper.loadLocalNavPoints();
        String dest = NavMapHelper.resolvePlaceName(placeName, poiId);
        if (dest == null || dest.trim().isEmpty()) {
            if (local != null && !local.isEmpty()) {
                callback.onFinished(ToolResult.fail(RobotTools.NAVIGATE, "place_not_on_map",
                        "当前地图没有点位「" + (placeName == null ? "" : placeName)
                                + "」，不能开始导航"), false);
                return;
            }
            dest = placeName;
        }
        if (dest == null || dest.trim().isEmpty()) {
            callback.onFinished(ToolResult.fail(RobotTools.NAVIGATE, "invalid",
                    "没有地图点位名称，无法开始导航"), false);
            return;
        }
        dest = dest.trim();
        host.startNavigation(null, poiId, dest, dest);
        callback.onFinished(ToolResult.ok(RobotTools.NAVIGATE, "started", "导航已开始")
                .put("placeName", dest)
                .put("target", dest), false);
    }

    private void execNavCancel(StepCallback callback) {
        host.cancelNavigation();
        callback.onFinished(ToolResult.ok(RobotTools.NAV_CANCEL, "cancelled", "已取消导航"), true);
    }

    private void execAskUser(JsonObject p, StepCallback callback) {
        DisplaySection form = new DisplaySection();
        form.kind = DisplaySection.FORM;
        form.title = firstNonEmpty(str(p, "question"), str(p, "title"), "请选择");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("title", form.title);
        JsonObject props = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("type", "string");
        field.addProperty("title", form.title);
        field.addProperty("description", "请输入或直接说话");
        if (p.has("options") && p.get("options").isJsonArray()) {
            field.add("enum", p.getAsJsonArray("options"));
        } else if (p.has("choices") && p.get("choices").isJsonArray()) {
            field.add("enum", p.getAsJsonArray("choices"));
        }
        props.add("answer", field);
        schema.add("properties", props);
        com.google.gson.JsonArray req = new com.google.gson.JsonArray();
        req.add("answer");
        schema.add("required", req);
        form.schema = schema;
        List<DisplaySection> sections = new ArrayList<>();
        sections.add(form);
        host.renderDisplay(sections, null);
        callback.onFinished(ToolResult.ok(RobotTools.ASK_USER, "shown", "已展示选项"), false);
    }

    private void execOpenPage(JsonObject p, StepCallback callback) {
        String page = str(p, "page");
        if (page == null || page.trim().isEmpty()) {
            callback.onFinished(ToolResult.fail(RobotTools.OPEN_PAGE, "empty", "缺少 page 参数"), false);
            return;
        }
        host.openPage(page.trim().toLowerCase(), p);
        callback.onFinished(ToolResult.ok(RobotTools.OPEN_PAGE, "opened", "已打开页面")
                .put("page", page.trim()), false);
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        JsonElement el = o.get(key);
        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                return el.getAsBoolean();
            }
            String s = el.getAsString();
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
                return false;
            }
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static float num(JsonObject o, String key, float def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        try {
            return o.get(key).getAsFloat();
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return String.valueOf(o.get(key));
        }
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
