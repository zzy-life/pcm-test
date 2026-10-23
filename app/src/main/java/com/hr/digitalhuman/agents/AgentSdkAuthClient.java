package com.hr.digitalhuman.agents;

import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hr.digitalhuman.BuildConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 内部演示用客户端签名；正式分发须将 AK/SK 和签名逻辑迁到后端。 */
public final class AgentSdkAuthClient {
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    public static boolean isConfigured() {
        return !BuildConfig.AGENTS_SDK_SECRET_ID.trim().isEmpty()
                && !BuildConfig.AGENTS_SDK_SECRET_KEY.trim().isEmpty();
    }

    public String fetchToken(String user) throws IOException {
        HttpURLConnection conn = null;
        try {
            if (cancelled || !isConfigured()) throw new IOException();
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            // 签名路径不含 /agents/v1 前缀和 query，末尾斜杠不可省略。
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(BuildConfig.AGENTS_SDK_SECRET_KEY.getBytes("UTF-8"), "HmacSHA1"));
            String signature = Base64.encodeToString(mac.doFinal(
                    ("GET@/auth/access-token/@" + timestamp).getBytes("UTF-8")), Base64.NO_WRAP);
            conn = (HttpURLConnection) new URL("https://api.pincaimao.com/agents/v1/auth/access-token"
                    + "?user=" + URLEncoder.encode(user, "UTF-8")).openConnection();
            connection = conn;
            if (cancelled) throw new IOException();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("x-secret-id", BuildConfig.AGENTS_SDK_SECRET_ID);
            conn.setRequestProperty("x-timestamp", timestamp);
            conn.setRequestProperty("x-signature", signature);
            if (conn.getResponseCode() != 200) throw new IOException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    if (cancelled || bytes.size() + size > 65536) throw new IOException();
                    bytes.write(buffer, 0, size);
                }
            }
            JsonObject result = JsonParser.parseString(bytes.toString("UTF-8")).getAsJsonObject();
            if (result.get("code").getAsInt() != 0) throw new IOException();
            String token = result.getAsJsonObject("data").get("token").getAsString();
            if (cancelled || token.trim().isEmpty()) throw new IOException();
            return token;
        } catch (Exception e) {
            // 不透传包含凭据、签名或响应正文的异常。
            throw new IOException("面试服务授权失败，请检查网络和 SDK 配置");
        } finally {
            if (conn != null) conn.disconnect();
            connection = null;
        }
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection conn = connection;
        if (conn != null) conn.disconnect();
    }
}
