package com.hr.digitalhuman.agents;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hr.digitalhuman.app.DigitalHumanApp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 同步客户端：由页面在工作线程调用，Listener 也在该线程回调，不修改页面提供的 body。
 * 每个实例同一时间只支持一个操作；cancel 为永久取消，重试须创建新实例。
 * 上传接管并关闭传入的 InputStream；所有网络错误只暴露阶段和 HTTP 状态。
 */
public final class AgentApiClient {
    private static final String CHAT_URL =
            "https://api.pincaimao.com/agents/v1/chat/chat-messages";
    private static final String UPLOAD_URL =
            "https://api.pincaimao.com/agents/v1/files/upload";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_EVENT_CHARS = 1024 * 1024;

    private final String apiKey;
    private final Gson gson = new Gson();
    private final Object lock = new Object();
    private final Set<InputStream> activeInputs = new HashSet<>();
    private HttpURLConnection activeConnection;
    private boolean busy;
    private volatile boolean cancelled;

    public interface Listener {
        void onAnswer(String chunk);
        void onComplete();
        default void onConversationId(String id) {}
    }

    public AgentApiClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty() || hasControl(apiKey)) {
            throw new IllegalArgumentException("智能体密钥无效");
        }
        this.apiKey = apiKey.trim();
    }

    public void stream(JsonObject body, Listener listener) throws IOException {
        if (body == null || listener == null) {
            throw failure("对话参数无效");
        }
        begin();
        try {
            HttpURLConnection conn = open(new URL(CHAT_URL));
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] payload = gson.toJson(body).getBytes(UTF8);
            conn.setFixedLengthStreamingMode(payload.length);
            checkCancelled();
            try (OutputStream out = conn.getOutputStream()) {
                checkCancelled();
                out.write(payload);
            }
            requireSuccess(conn, "对话");
            String contentType = conn.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT)
                    .startsWith("text/event-stream")) {
                throw failure("对话响应不是 SSE");
            }
            try (InputStream input = track(conn.getInputStream());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8))) {
                StringBuilder data = new StringBuilder();
                boolean hasData = false;
                String line;
                boolean firstLine = true;
                while ((line = readEventLine(reader)) != null) {
                    checkCancelled();
                    if (firstLine && line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    firstLine = false;
                    if (line.isEmpty()) {
                        if (hasData && dispatch(data.toString(), listener)) {
                            return;
                        }
                        data.setLength(0);
                        hasData = false;
                    } else if (line.equals("data") || line.startsWith("data:")) {
                        String value = line.equals("data") ? "" : line.substring(5);
                        if (value.startsWith(" ")) {
                            value = value.substring(1);
                        }
                        if ((long) data.length() + value.length() + 1 > MAX_EVENT_CHARS) {
                            throw failure("对话事件超过读取上限");
                        }
                        if (hasData) {
                            data.append('\n');
                        }
                        data.append(value);
                        hasData = true;
                    }
                    // 注释、event/id/retry 等字段不作为答案；以 JSON event 为业务类型。
                }
                // 兼容最后一个 data 事件后直接 EOF；仍必须有明确结束事件，不能将断流当成功。
                if (hasData && dispatch(data.toString(), listener)) return;
                throw failure("对话流已中断，未收到结束事件");
            }
        } catch (IOException | RuntimeException e) {
            throw sanitize("对话", e);
        } finally {
            finish();
        }
    }

    private boolean dispatch(String data, Listener listener) throws IOException {
        return handleSseEvent(parseObject(data, "对话事件格式无效"), listener);
    }

    /**
     * 参考 dify-sse.ts 集中分发业务事件；回调在网络线程执行。
     * 返回 true 表示已收到终态，读取循环应退出；取消和错误通过异常中止。
     */
    private boolean handleSseEvent(JsonObject event, Listener listener) throws IOException {
        checkCancelled();
        String type = stringField(event, "event");
        if (type == null) throw failure("对话事件缺少 event 类型");

        String conversationId = stringField(event, "conversation_id");
        if (conversationId != null && !conversationId.isEmpty()) {
            if (conversationId.length() > 256 || hasControl(conversationId)) {
                throw failure("会话 ID 无效");
            }
            listener.onConversationId(conversationId);
            checkCancelled();
        }
        switch (type) {
            case "message":
            case "agent_message":
                String answer = stringField(event, "answer");
                if (answer != null && !answer.isEmpty()) {
                    listener.onAnswer(answer);
                    checkCancelled();
                }
                return false;

            case "node_started":
                // 暂不展示节点进度；可从 data.title 读取节点名称。
                return false;

            case "node_finished":
                // 暂不使用 data.title / data.process_data；节点完成不等于整个请求完成。
                // 不追加节点中的完整文本，避免与 message 分片重复。
                return false;

            case "message_end":
                listener.onComplete();
                return true;

            case "workflow_finished":
                JsonElement detail = event.get("data");
                if (detail != null && detail.isJsonObject()) {
                    String status = stringField(detail.getAsJsonObject(), "status");
                    if (status != null && !"succeeded".equals(status)) {
                        throw failure("工作流未成功完成");
                    }
                }
                listener.onComplete();
                return true;

            case "error":
                // 不透传服务端 message，以免暴露简历、凭据等敏感内容。
                throw failure("对话服务返回错误");

            default:
                // message_start、心跳等暂不处理；未知事件不追加正文，也不视为成功。
                return false;
        }
    }

    /** null 表示报告/JSON 尚未生成；其他失败交由调用方提示并允许重试。 */
    public String fetchReportJson(String conversationId, int timeoutMs) throws IOException {
        begin();
        try {
            if (conversationId == null || !conversationId.matches("[A-Za-z0-9-]{1,256}")) {
                throw failure("报告会话 ID 无效");
            }
            HttpURLConnection conn = open(new URL("https://api.pincaimao.com/agents/v1/agents/reports"
                    + "?conversation_id=" + URLEncoder.encode(conversationId, "UTF-8")));
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            // 报告接口使用应用密钥原值（app-...），不是对话接口的 Bearer 格式。
            conn.setRequestProperty("Authorization", apiKey);
            conn.setRequestProperty("Accept", "application/json");
            int http = conn.getResponseCode();
            checkCancelled();
            if (http == 404) return null;
            requireSuccess(conn, "报告查询");
            JsonObject response;
            try (InputStream input = track(conn.getInputStream())) {
                response = parseObject(readResponse(input), "报告响应格式无效");
            }
            JsonElement code = response.get("code");
            if (code == null || !code.isJsonPrimitive()
                    || !code.getAsJsonPrimitive().isNumber()) throw failure("报告状态无效");
            if (code.getAsInt() == 404) return null;
            if (code.getAsInt() != 0) throw failure("报告查询失败");
            JsonElement data = response.get("data");
            if (data == null || data.isJsonNull()) return null;
            if (!data.isJsonObject()) throw failure("报告数据无效");
            String returnedId = stringField(data.getAsJsonObject(), "conversation_id");
            if (!conversationId.equals(returnedId)) throw failure("报告会话 ID 不一致");
            String encoded = stringField(data.getAsJsonObject(), "json_base64");
            if (encoded == null || encoded.trim().isEmpty() || "null".equals(encoded.trim())) return null;
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            String json = UTF8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonElement parsed = com.google.gson.JsonParser.parseString(json);
            if (!parsed.isJsonObject() && !parsed.isJsonArray()) throw failure("报告 JSON 格式无效");
            // 重新序列化，保证复制的是标准 JSON，而非解析器容忍的非标准原文。
            String normalized = gson.toJson(parsed);
            // 剪贴板走 Binder，限制结果大小，避免事务超限。
            if (normalized.length() > 100000) throw failure("报告 JSON 超过复制上限");
            checkCancelled();
            return normalized;
        } catch (IOException | RuntimeException e) {
            throw sanitize("报告查询或解码", e);
        } finally {
            finish();
        }
    }

    public String upload(InputStream input, String filename, String mimeType) throws IOException {
        if (input == null) {
            throw failure("上传输入无效");
        }
        // 即使参数错误、已取消或实例忙，也履行接管输入流的关闭约定。
        try (InputStream owned = input) {
            begin();
            try {
                return uploadInternal(owned, filename, mimeType);
            } catch (IOException | RuntimeException e) {
                throw sanitize("上传", e);
            } finally {
                finish();
            }
        } catch (IOException | RuntimeException e) {
            throw sanitize("上传", e);
        }
    }

    private String uploadInternal(InputStream input, String filename, String mimeType)
            throws IOException {
        track(input);
        String safeName = safeFilename(filename);
        String safeMime = safeMimeType(mimeType);
        String boundary = "AgentBoundary" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = open(new URL(UPLOAD_URL));
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(8192);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        checkCancelled();
        try (OutputStream out = conn.getOutputStream()) {
            checkCancelled();
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeName + "\"\r\nContent-Type: " + safeMime + "\r\n\r\n";
            out.write(header.getBytes(UTF8));
            copyLimited(input, out, "上传");
            checkCancelled();
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(UTF8));
        }
        requireSuccess(conn, "上传");
        JsonObject response;
        try (InputStream responseInput = track(conn.getInputStream())) {
            response = parseObject(readResponse(responseInput), "上传响应格式无效");
        }
        String cosKey = stringField(response, "cos_key");
        if (cosKey == null || cosKey.trim().isEmpty()) {
            throw failure("上传响应缺少 cos_key");
        }
        checkCancelled();
        return cosKey;
    }

    public String uploadResume(String downloadToken) throws IOException {
        begin();
        File temporary = null;
        try {
            String token = downloadToken == null ? "" : downloadToken.trim();
            if (token.isEmpty() || token.length() > 2048 || hasControl(token)
                    || token.equals(".") || token.equals("..")
                    || token.indexOf('/') >= 0 || token.indexOf('\\') >= 0
                    || token.indexOf(':') >= 0 || token.indexOf('?') >= 0
                    || token.indexOf('#') >= 0) {
                throw failure("简历下载凭据无效");
            }
            DigitalHumanApp app = DigitalHumanApp.getInstance();
            if (app == null) {
                throw failure("简历下载环境不可用");
            }
            // 编码单个路径段，不允许 token 注入路径、查询或外部 URL。
            String encoded = URLEncoder.encode(token, "UTF-8").replace("+", "%20");
            String resolved = app.resolveMediaUrl(
                    "/robot/resume/download/" + encoded + "?format=pdf");
            URL url = new URL(resolved);
            validateUrl(url);
            HttpURLConnection conn = open(url);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/pdf");
            // 下载只凭 token，不发送智能体密钥、后台 JWT 或其他外部认证。
            requireSuccess(conn, "简历下载");
            String length = conn.getHeaderField("Content-Length");
            if (length != null && Long.parseLong(length) > MAX_FILE_BYTES) {
                throw failure("简历下载超过 50MB 限制");
            }
            temporary = File.createTempFile("agent_resume_", ".pdf", app.getCacheDir());
            try (InputStream input = track(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(temporary)) {
                copyPdfLimited(input, out);
            }
            releaseConnection();
            checkCancelled();
            try (InputStream input = new FileInputStream(temporary)) {
                return uploadInternal(input, "resume.pdf", "application/pdf");
            }
        } catch (IOException | RuntimeException e) {
            throw sanitize("简历转上传", e);
        } finally {
            finish();
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                // 不输出缓存路径或 token，且不删除任何非本方法创建的文件。
                temporary.deleteOnExit();
            }
        }
    }

    /** 永久取消：先标记状态，再断开连接及关闭所有活动输入。 */
    public void cancel() {
        HttpURLConnection conn;
        Set<InputStream> inputs;
        synchronized (lock) {
            cancelled = true;
            conn = activeConnection;
            inputs = new HashSet<>(activeInputs);
        }
        if (conn != null) {
            conn.disconnect();
        }
        for (InputStream input : inputs) {
            closeQuietly(input);
        }
    }

    private void begin() throws IOException {
        synchronized (lock) {
            checkCancelled();
            if (busy) {
                throw failure("客户端已有进行中的请求");
            }
            busy = true;
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        validateUrl(url);
        synchronized (lock) {
            // 与 cancel 使用同一锁，禁止取消之后登记或创建新的连接。
            checkCancelled();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            activeConnection = conn;
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            return conn;
        }
    }

    private InputStream track(InputStream input) throws IOException {
        synchronized (lock) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                closeQuietly(input);
                throw failure("请求已取消");
            }
            activeInputs.add(input);
            return input;
        }
    }

    private void releaseConnection() {
        HttpURLConnection conn;
        Set<InputStream> inputs;
        synchronized (lock) {
            conn = activeConnection;
            activeConnection = null;
            inputs = new HashSet<>(activeInputs);
            activeInputs.clear();
        }
        if (conn != null) {
            conn.disconnect();
        }
        for (InputStream input : inputs) {
            closeQuietly(input);
        }
    }

    private void finish() {
        releaseConnection();
        synchronized (lock) {
            busy = false;
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw failure("请求已取消");
        }
    }

    private void requireSuccess(HttpURLConnection conn, String stage) throws IOException {
        checkCancelled();
        int status = conn.getResponseCode();
        checkCancelled();
        if (status < 200 || status >= 300) {
            // 不读取错误正文；3xx 同样失败，不自动携带认证跟随跳转。
            throw failure(stage + "失败（HTTP " + status + "）");
        }
    }

    private long copyLimited(InputStream input, OutputStream out, String stage)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        while (true) {
            checkCancelled();
            int n = input.read(buffer);
            checkCancelled();
            if (n < 0) {
                return total;
            }
            total += n;
            if (total > MAX_FILE_BYTES) {
                throw failure(stage + "超过 50MB 限制");
            }
            out.write(buffer, 0, n);
        }
    }

    /**
     * 仅缓存前 1024 字节并验证 PDF 签名，再写入临时文件。
     * 不依赖 Content-Type，兼容以 application/octet-stream 返回的真实 PDF。
     * 这是文件头检查而非完整 PDF 结构校验；可阻止普通 HTML/JSON 错误页被上传。
     */
    private void copyPdfLimited(InputStream input, OutputStream out) throws IOException {
        byte[] prefix = new byte[1024];
        int count = 0;
        while (count < prefix.length) {
            checkCancelled();
            int n = input.read(prefix, count, prefix.length - count);
            checkCancelled();
            if (n < 0) {
                break;
            }
            count += n;
        }
        boolean pdf = false;
        for (int i = 0; i + 5 <= count; i++) {
            if (prefix[i] == '%' && prefix[i + 1] == 'P' && prefix[i + 2] == 'D'
                    && prefix[i + 3] == 'F' && prefix[i + 4] == '-') {
                pdf = true;
                break;
            }
        }
        if (!pdf) {
            throw failure("简历下载未返回PDF");
        }
        checkCancelled();
        out.write(prefix, 0, count);
        byte[] buffer = new byte[8192];
        long total = count;
        while (true) {
            checkCancelled();
            int n = input.read(buffer);
            checkCancelled();
            if (n < 0) {
                return;
            }
            total += n;
            if (total > MAX_FILE_BYTES) {
                throw failure("简历下载超过 50MB 限制");
            }
            out.write(buffer, 0, n);
        }
    }

    private String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            checkCancelled();
            int n = input.read(buffer);
            checkCancelled();
            if (n < 0) {
                return new String(bytes.toByteArray(), UTF8);
            }
            if ((long) bytes.size() + n > MAX_RESPONSE_BYTES) {
                throw failure("上传响应超过读取上限");
            }
            bytes.write(buffer, 0, n);
        }
    }

    // 不用无上限的 readLine，防止单行恶意响应占满内存；支持 LF/CRLF/CR。
    private String readEventLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            checkCancelled();
            int ch = reader.read();
            if (ch == -1) {
                return line.length() == 0 ? null : line.toString();
            }
            if (ch == '\n') {
                return line.toString();
            }
            if (ch == '\r') {
                reader.mark(1);
                int next = reader.read();
                if (next != '\n' && next != -1) {
                    reader.reset();
                }
                return line.toString();
            }
            if (line.length() >= MAX_EVENT_CHARS) {
                throw failure("对话事件超过读取上限");
            }
            line.append((char) ch);
        }
    }

    private JsonObject parseObject(String value, String error) throws IOException {
        try {
            JsonElement element = gson.fromJson(value, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw failure(error);
            }
            return element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw failure(error);
        }
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static String safeFilename(String filename) throws IOException {
        if (filename == null || filename.trim().isEmpty() || filename.length() > 255
                || hasControl(filename)) {
            throw failure("上传文件名无效");
        }
        // 清除路径及引号/反斜线，避免 multipart 头注入；保留正常中文文件名。
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replace('"', '_');
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..")) {
            throw failure("上传文件名无效");
        }
        return name;
    }

    private static String safeMimeType(String mimeType) {
        if (mimeType == null || mimeType.length() > 127
                || !mimeType.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")) {
            return "application/octet-stream";
        }
        return mimeType.toLowerCase(Locale.ROOT);
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) {
                return true;
            }
        }
        return false;
    }

    private static void validateUrl(URL url) throws IOException {
        if (!("http".equalsIgnoreCase(url.getProtocol())
                || "https".equalsIgnoreCase(url.getProtocol()))
                || url.getHost() == null || url.getHost().isEmpty()
                || url.getUserInfo() != null || url.getRef() != null) {
            throw failure("请求地址无效");
        }
    }

    private IOException sanitize(String stage, Exception error) {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            return failure("请求已取消");
        }
        return error instanceof SafeFailure ? (IOException) error : failure(stage + "失败");
    }

    private static SafeFailure failure(String message) {
        return new SafeFailure(message);
    }

    private static final class SafeFailure extends IOException {
        SafeFailure(String message) {
            super(message);
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
            // 不记录异常原因，避免底层异常携带敏感 URL 或响应内容。
        }
    }
}
