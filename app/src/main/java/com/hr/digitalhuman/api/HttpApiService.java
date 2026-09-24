package com.hr.digitalhuman.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.hr.digitalhuman.BuildConfig;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.app.SessionStore;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.AppLogEntry;
import com.hr.digitalhuman.model.ChatSession;
import com.hr.digitalhuman.model.HistoryRecord;
import com.hr.digitalhuman.model.NavMapInfo;
import com.hr.digitalhuman.model.NavPoint;
import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.model.UserInfo;
import com.hr.digitalhuman.model.agent.AgentStep;
import com.hr.digitalhuman.model.agent.AgentTurn;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实后端 HTTP 实现（对接 /jqrserv/api）
 */
public class HttpApiService implements ApiService {

    private static final String TAG = "HttpApiService";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 15000;
    /** 对话可能含：重复判定 + needKnowledge 两轮，需大于单次 LLM 耗时 */
    private static final int READ_TIMEOUT_MS = 120000;

    private final String baseUrl;
    private final Gson gson = new Gson();

    public HttpApiService() {
        String url = BuildConfig.API_BASE_URL;
        if (url == null || url.trim().isEmpty()) {
            url = "http://xxx";
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 媒体地址：data URL / 纯 Base64 原样或补前缀返回；http(s) 原样；相对路径拼 baseUrl。
     * 简历模板样例图优先存 Base64。
     */
    public String resolveMediaUrl(String pathOrUrl) {
        if (pathOrUrl == null) {
            return null;
        }
        String s = pathOrUrl.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("data:image/")) {
            return s;
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        // 纯 Base64（无 data 前缀）：避免被当成相对路径
        if (looksLikeRawBase64(s)) {
            return "data:image/png;base64," + s.replaceAll("\\s", "");
        }
        if (s.startsWith("/")) {
            return baseUrl + s;
        }
        return baseUrl + "/" + s;
    }

    private static boolean looksLikeRawBase64(String s) {
        if (s.length() < 64) {
            return false;
        }
        // 路径类特征
        if (s.indexOf('.') >= 0 || s.contains("profile") || s.contains("upload")
                || s.startsWith("http")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                continue;
            }
            return false;
        }
        return true;
    }

    @Override
    public ApiResponse<RobotConfig> getRobotConfig(String sn) {
        return get("/robot/config?sn=" + enc(sn), RobotConfig.class, null);
    }

    @Override
    public ApiResponse<ChatSession> createSession(String sn, String trigger) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("trigger", trigger);
        Long userId = currentUserId();
        if (userId != null) {
            body.put("userId", userId);
        }
        return post("/chat/plan/session", body, ChatSession.class, null);
    }

    @Override
    public ApiResponse<AgentTurn> sendAgentMessage(String sn, String sessionId, String content, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("sessionId", sessionId);
        body.put("content", content);
        body.put("role", role == null || role.isEmpty() ? "user" : role);
        body.put("inputSource", "voice");
        Long userId = currentUserId();
        if (userId != null) {
            body.put("userId", userId);
        }
        return postAgent("/chat/plan/message", body);
    }

    @Override
    public ApiResponse<AgentTurn> submitToolResult(String sn, String sessionId, String content,
                                                   String role, boolean callLlm, String toolCallId, String tool) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("sessionId", sessionId);
        body.put("content", content == null ? "" : content);
        body.put("role", role == null || role.isEmpty() ? "tool" : role);
        body.put("callLlm", callLlm);
        if (toolCallId != null && !toolCallId.isEmpty()) {
            body.put("tool_call_id", toolCallId);
        }
        if (tool != null && !tool.isEmpty()) {
            body.put("tool", tool);
        }
        Long userId = currentUserId();
        if (userId != null) {
            body.put("userId", userId);
        }
        return postAgent("/chat/plan/message", body);
    }

    private ApiResponse<AgentTurn> postAgent(String path, Object body) {
        ApiResponse<JsonObject> raw = postRaw(path, body, null);
        if (!raw.isOk()) {
            return ApiResponse.fail(raw.code, raw.message);
        }
        AgentTurn turn = parseAgentTurn(raw.data);
        ApiResponse<AgentTurn> resp = ApiResponse.ok(turn);
        resp.code = raw.code;
        resp.message = raw.message;
        resp.timestamp = raw.timestamp;
        return resp;
    }

    private AgentTurn parseAgentTurn(JsonObject data) {
        AgentTurn turn = new AgentTurn();
        if (data == null) {
            return turn;
        }
        turn.sessionId = firstNonEmpty(optString(data, "sessionId"), optString(data, "session_id"));
        turn.content = firstNonEmpty(optString(data, "content"), optString(data, "message"));
        if (data.has("value") && (turn.content == null || turn.content.isEmpty())) {
            turn.content = optString(data, "value");
        }
        JsonElement stepsEl = data.get("steps");
        if (stepsEl != null && stepsEl.isJsonArray()) {
            for (JsonElement el : stepsEl.getAsJsonArray()) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                AgentStep step = new AgentStep();
                step.tool = firstNonEmpty(optString(o, "tool"), optString(o, "name"));
                step.toolCallId = firstNonEmpty(optString(o, "tool_call_id"), optString(o, "toolCallId"));
                if (o.has("params")) {
                    step.params = asJsonObject(o.get("params"));
                } else if (o.has("arguments")) {
                    step.params = asJsonObject(o.get("arguments"));
                } else {
                    step.params = new JsonObject();
                }
                turn.steps.add(step);
            }
        }
        return turn;
    }

    @Override
    public ApiResponse<Void> closeSession(String sn, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("sessionId", sessionId);
        return postVoid("/chat/plan/session/close", body, null);
    }

    @Override
    public ApiResponse<Void> syncNavPoints(String sn, List<NavPoint> points) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("mapName", "default");
        List<Map<String, Object>> pointList = new ArrayList<>();
        if (points != null) {
            for (NavPoint p : points) {
                Map<String, Object> m = new HashMap<>();
                m.put("pointId", p.pointId);
                m.put("robotMapPlaceName", p.robotMapPlaceName);
                m.put("displayName", p.displayName);
                m.put("floor", p.floor);
                m.put("description", p.description);
                m.put("tags", p.tags);
                pointList.add(m);
            }
        }
        body.put("points", pointList);
        body.put("replaceAll", true);
        return postVoid("/navigation/points/sync", body, null);
    }

    @Override
    public ApiResponse<Void> syncNavMaps(String sn, List<NavMapInfo> maps) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("replaceScope", "current");
        List<Map<String, Object>> mapList = new ArrayList<>();
        if (maps != null) {
            for (NavMapInfo info : maps) {
                if (info == null || info.mapName == null) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("mapName", info.mapName);
                m.put("current", info.current);
                List<Map<String, Object>> pointList = new ArrayList<>();
                if (info.points != null) {
                    for (NavPoint p : info.points) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("pointId", p.pointId);
                        row.put("robotMapPlaceName", p.robotMapPlaceName);
                        row.put("displayName", p.displayName);
                        row.put("description", p.description);
                        row.put("poseX", p.poseX);
                        row.put("poseY", p.poseY);
                        row.put("poseTheta", p.poseTheta);
                        row.put("status", p.status);
                        pointList.add(row);
                    }
                }
                m.put("points", pointList);
                mapList.add(m);
            }
        }
        body.put("maps", mapList);
        return postVoid("/navigation/points/sync", body, null);
    }

    @Override
    public ApiResponse<UserInfo> login(String sn, String username, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("username", username);
        body.put("password", password);
        ApiResponse<JsonObject> raw = postRaw("/robot/user/login", body, null);
        if (!raw.isOk()) {
            return ApiResponse.fail(raw.code, raw.message);
        }
        UserInfo user = parseRobotUser(raw.data);
        return ApiResponse.ok(user);
    }

    private UserInfo parseRobotUser(JsonObject data) {
        UserInfo user = new UserInfo();
        if (data == null) {
            return user;
        }
        user.userId = jsonScalarString(data, "userId");
        user.token = optString(data, "token");
        user.displayName = firstNonEmpty(optString(data, "userName"), optString(data, "username"));
        user.username = firstNonEmpty(optString(data, "username"), user.displayName);
        user.phone = optString(data, "phone");
        user.idCard = optString(data, "idCard");
        user.authType = firstNonEmpty(optString(data, "authType"), "sim");
        user.tokenExpireTime = jsonScalarLong(data, "tokenExpireTime");
        return user;
    }

    private static String jsonScalarString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = o.get(key);
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return null;
    }

    private static long jsonScalarLong(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return 0L;
        }
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return el.getAsLong();
        } catch (Exception e) {
            try {
                return Long.parseLong(el.getAsString());
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    @Override
    public ApiResponse<List<HistoryRecord>> getHistory(String sn, String token) {
        if (token == null || token.isEmpty()) {
            return ApiResponse.fail(40101, "未登录");
        }
        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.fail(40101, "未登录");
        }
        // 走 /robot/**（已放行），按登录用户从 jqr_chat_history 取问答对
        StringBuilder path = new StringBuilder("/robot/chat/history?userId=").append(userId)
                .append("&userToken=").append(enc(token))
                .append("&limit=30");
        if (sn != null && !sn.isEmpty()) {
            path.append("&sn=").append(enc(sn));
        }
        ApiResponse<JsonObject> raw = getRaw(path.toString(), null);
        if (!raw.isOk()) {
            return ApiResponse.fail(raw.code, raw.message);
        }
        if (raw.data != null && raw.data.has("needLogin")
                && raw.data.get("needLogin").getAsBoolean()) {
            return ApiResponse.fail(40101, "未登录或登录已过期");
        }
        List<HistoryRecord> list = new ArrayList<>();
        if (raw.data != null && raw.data.has("list") && raw.data.get("list").isJsonArray()) {
            for (JsonElement el : raw.data.getAsJsonArray("list")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                HistoryRecord h = new HistoryRecord();
                h.recordId = firstNonEmpty(optString(o, "id"), optString(o, "recordId"));
                h.sessionId = optString(o, "sessionId");
                h.role = firstNonEmpty(optString(o, "role"), "qa");
                h.questionSummary = firstNonEmpty(optString(o, "question"), optString(o, "content"));
                h.answerSummary = optString(o, "answer");
                h.content = h.questionSummary;
                h.createdAt = firstNonEmpty(optString(o, "time"), optString(o, "createdAt"));
                list.add(h);
            }
        }
        return ApiResponse.ok(list);
    }

    @Override
    public ApiResponse<Void> uploadAppLogs(String sn, String appVersion, List<AppLogEntry> logs) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("appVersion", appVersion);
        List<Map<String, Object>> items = new ArrayList<>();
        if (logs != null) {
            for (AppLogEntry e : logs) {
                if (e == null || e.message == null || e.message.isEmpty()) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("level", e.level);
                m.put("tag", e.tag);
                m.put("message", e.message);
                m.put("loggedAt", e.loggedAt);
                items.add(m);
            }
        }
        body.put("logs", items);
        return postVoid("/robot/log", body, null);
    }

    @Override
    public ApiResponse<JsonObject> getResumeHub(String sn, Long userId, String userToken) {
        StringBuilder path = new StringBuilder("/robot/resume/hub?sn=").append(enc(sn));
        if (userId != null) {
            path.append("&userId=").append(userId);
        }
        if (userToken != null && !userToken.isEmpty()) {
            path.append("&userToken=").append(enc(userToken));
        }
        // 机器人凭证走 query userToken，不走 Authorization Bearer（非后台 JWT）
        return getRaw(path.toString(), null);
    }

    @Override
    public ApiResponse<JsonObject> resumeChatStart(String sn, Long userId, Long templateId, boolean forceNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("userId", userId);
        body.put("templateId", templateId);
        body.put("forceNew", forceNew);
        return postRaw("/robot/resume/chat/start", body, null);
    }

    @Override
    public ApiResponse<JsonObject> resumeChatContinue(String sn, Long userId, Long templateId) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("userId", userId);
        body.put("templateId", templateId);
        return postRaw("/robot/resume/chat/continue", body, null);
    }

    @Override
    public ApiResponse<JsonObject> resumeChatMessage(String sn, Long userId, String message) {
        return resumeChatMessage(sn, userId, message, null, null);
    }

    @Override
    public ApiResponse<JsonObject> resumeChatMessage(String sn, Long userId, String message, String action) {
        return resumeChatMessage(sn, userId, message, action, null);
    }

    @Override
    public ApiResponse<JsonObject> resumeChatMessage(String sn, Long userId, String message,
                                                     String action, String resumeName) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        body.put("userId", userId);
        if (message != null && !message.isEmpty()) {
            body.put("message", message);
        }
        if (action != null && !action.isEmpty()) {
            body.put("action", action);
        }
        if (resumeName != null && !resumeName.trim().isEmpty()) {
            body.put("resumeName", resumeName.trim());
        }
        return postRaw("/robot/resume/chat/message", body, null);
    }

    @Override
    public ApiResponse<JsonObject> getResumeChatProgress(String sn) {
        return getRaw("/robot/resume/chat/progress?sn=" + enc(sn), null);
    }

    @Override
    public ApiResponse<JsonObject> getResumeStatus(String sn, Long recordId, Long userId) {
        StringBuilder path = new StringBuilder("/robot/resume/status?sn=")
                .append(enc(sn))
                .append("&recordId=")
                .append(recordId == null ? "" : recordId);
        if (userId != null) {
            path.append("&userId=").append(userId);
        }
        return getRaw(path.toString(), null);
    }

    @Override
    public ApiResponse<JsonObject> deleteResumeRecord(String sn, Long userId, Long recordId) {
        Map<String, Object> body = new HashMap<>();
        body.put("sn", sn);
        if (userId != null) {
            body.put("userId", userId);
        }
        body.put("recordId", recordId);
        return postRaw("/robot/resume/record/delete", body, null);
    }

    @Override
    public ApiResponse<JsonObject> abandonResumeDraft(Long userId, Long templateId) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("templateId", templateId);
        return postRaw("/robot/resume/draft/abandon", body, null);
    }

    // ---------- HTTP helpers ----------

    private <T> ApiResponse<T> get(String path, Class<T> clazz, String token) {
        ApiResponse<JsonObject> raw = getRaw(path, token);
        if (!raw.isOk()) {
            return ApiResponse.fail(raw.code, raw.message);
        }
        T data = null;
        if (raw.data != null) {
            try {
                data = gson.fromJson(raw.data, clazz);
            } catch (Exception e) {
                DebugLog.e(TAG, "parse data failed path=" + path + ": " + e.getMessage());
                return ApiResponse.fail(50002, "数据解析失败: " + e.getMessage());
            }
        }
        ApiResponse<T> r = ApiResponse.ok(data);
        r.code = raw.code;
        r.message = raw.message;
        r.timestamp = raw.timestamp;
        return r;
    }

    private <T> ApiResponse<T> post(String path, Object body, Class<T> clazz, String token) {
        ApiResponse<JsonObject> raw = postRaw(path, body, token);
        if (!raw.isOk()) {
            return ApiResponse.fail(raw.code, raw.message);
        }
        T data = raw.data == null ? null : gson.fromJson(raw.data, clazz);
        return ApiResponse.ok(data);
    }

    private ApiResponse<Void> postVoid(String path, Object body, String token) {
        ApiResponse<JsonObject> raw = postRaw(path, body, token);
        if (!raw.isOk()) {
            return ApiResponse.fail(raw.code, raw.message);
        }
        return ApiResponse.ok(null);
    }

    private ApiResponse<JsonObject> getRaw(String path, String token) {
        return request("GET", path, null, token);
    }

    private ApiResponse<JsonObject> postRaw(String path, Object body, String token) {
        return request("POST", path, body, token);
    }

    private ApiResponse<JsonObject> request(String method, String path, Object body, String token) {
        HttpURLConnection conn = null;
        String reqId = UUID.randomUUID().toString();
        String reqBodyJson = body != null ? gson.toJson(body) : null;
        boolean silent = path != null && path.contains("/robot/log");
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Request-Id", reqId);
            String snHeader = extractSn(path, body);
            if (snHeader != null) {
                conn.setRequestProperty("X-Robot-SN", snHeader);
            }
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
                conn.setDoOutput(true);
                byte[] bytes = reqBodyJson.getBytes(UTF8);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }

            int httpCode = conn.getResponseCode();
            InputStream is = httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = normalizeResponseText(readFully(is));
            if (text == null || text.isEmpty()) {
                if (httpCode >= 200 && httpCode < 300) {
                    return ApiResponse.ok(null);
                }
                if (!silent) {
                    logApiFailure(method, path, reqId, reqBodyJson, httpCode, 50001, "空响应", null);
                }
                return ApiResponse.fail(50001, "空响应 HTTP " + httpCode);
            }
            if (!looksLikeJson(text)) {
                String hint = buildNonJsonError(httpCode, text);
                if (!silent) {
                    logApiFailure(method, path, reqId, reqBodyJson, httpCode, 50001, hint, text);
                }
                return ApiResponse.fail(50001, hint);
            }
            JsonObject root = parseJsonObject(text);
            int code = parseBizCode(root);
            String message = "";
            if (root.has("message") && !root.get("message").isJsonNull()) {
                message = root.get("message").getAsString();
            } else if (root.has("msg") && !root.get("msg").isJsonNull()) {
                // 若依 AjaxResult 用 msg
                message = root.get("msg").getAsString();
            }
            long ts = root.has("timestamp") ? root.get("timestamp").getAsLong() : System.currentTimeMillis();
            ApiResponse<JsonObject> resp = new ApiResponse<>();
            resp.code = code;
            resp.message = message;
            resp.timestamp = ts;
            if (root.has("data") && !root.get("data").isJsonNull()) {
                JsonElement dataEl = root.get("data");
                if (dataEl.isJsonObject()) {
                    resp.data = dataEl.getAsJsonObject();
                } else {
                    JsonObject wrap = new JsonObject();
                    wrap.add("value", dataEl);
                    resp.data = wrap;
                }
            }
            if (code != 0 && (message == null || message.isEmpty())) {
                resp.message = "请求失败 code=" + code;
            }
            if (!silent && (httpCode >= 400 || code != 0)) {
                logApiFailure(method, path, reqId, reqBodyJson, httpCode, code, message, text);
            }
            return resp;
        } catch (Exception e) {
            if (!silent) {
                logApiFailure(method, path, reqId, reqBodyJson, -1, 50001,
                        "网络异常: " + e.getMessage(), null);
            }
            return ApiResponse.fail(50001, "网络异常: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void logApiFailure(String method, String path, String reqId, String bodyJson,
                               int httpCode, int bizCode, String message, String rawText) {
        String safeBody = maskSensitiveBody(bodyJson);
        String dataPreview = truncate(maskSensitiveBody(rawText), 800);
        DebugLog.w(TAG, method + " " + path + " reqId=" + reqId
                + " http=" + httpCode + " code=" + bizCode
                + " msg=" + (message == null ? "" : message)
                + (safeBody != null ? " body=" + truncate(safeBody, 400) : "")
                + (dataPreview != null && !dataPreview.isEmpty() ? " resp=" + dataPreview : ""));
    }

    private static String maskSensitiveBody(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        return json.replaceAll("\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"***\"")
                .replaceAll("\"token\"\\s*:\\s*\"[^\"]{8,}\"", "\"token\":\"***\"");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= maxLen) {
            return oneLine;
        }
        return oneLine.substring(0, maxLen) + "…(" + oneLine.length() + " chars)";
    }

    private static String readFully(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString(UTF8.name());
    }

    private static String normalizeResponseText(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static boolean looksLikeJson(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return first == '{' || first == '[';
    }

    private static String buildNonJsonError(int httpCode, String text) {
        String preview = truncate(text, 80);
        if (text.startsWith("<")) {
            return "服务器返回异常页面(HTTP " + httpCode + ")，请检查后端/Nginx 是否正常";
        }
        return "服务器响应非JSON(HTTP " + httpCode + "): " + preview;
    }

    /** 步骤 params 可能是对象，也可能被模型整体序列化成 JSON 字符串。 */
    private static JsonObject asJsonObject(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return new JsonObject();
        }
        if (el.isJsonObject()) {
            return el.getAsJsonObject();
        }
        if (el.isJsonPrimitive()) {
            try {
                JsonElement parsed = new JsonParser().parse(el.getAsString());
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (Exception ignored) {
            }
        }
        return new JsonObject();
    }

    private static JsonObject parseJsonObject(String text) throws Exception {
        try {
            return new JsonParser().parse(text).getAsJsonObject();
        } catch (Exception strictError) {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            JsonElement el = new JsonParser().parse(reader);
            if (el != null && el.isJsonObject()) {
                return el.getAsJsonObject();
            }
            throw strictError;
        }
    }

    private static String extractSn(String path, Object body) {
        if (body instanceof Map) {
            Object sn = ((Map<?, ?>) body).get("sn");
            if (sn != null) {
                return String.valueOf(sn);
            }
        }
        if (path != null) {
            int idx = path.indexOf("sn=");
            if (idx >= 0) {
                String rest = path.substring(idx + 3);
                int amp = rest.indexOf('&');
                return amp >= 0 ? rest.substring(0, amp) : rest;
            }
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /** 兼容 code 为数字 / 字符串 / 空（新智能体接口示例）。0、空、success、200 视为成功。 */
    private static int parseBizCode(JsonObject root) {
        if (root == null || !root.has("code") || root.get("code").isJsonNull()) {
            return 0;
        }
        JsonElement c = root.get("code");
        if (!c.isJsonPrimitive()) {
            return -1;
        }
        if (c.getAsJsonPrimitive().isNumber()) {
            int n = c.getAsInt();
            return n == 200 ? 0 : n;
        }
        String s = c.getAsString().trim();
        if (s.isEmpty() || "success".equalsIgnoreCase(s) || "ok".equalsIgnoreCase(s) || "200".equals(s)) {
            return 0;
        }
        try {
            int n = Integer.parseInt(s);
            return n == 200 ? 0 : n;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b;
    }

    private static Long currentUserId() {
        try {
            SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
            if (store == null || store.getUser() == null || store.getUser().userId == null) {
                return null;
            }
            return Long.parseLong(store.getUser().userId);
        } catch (Exception e) {
            return null;
        }
    }
}
