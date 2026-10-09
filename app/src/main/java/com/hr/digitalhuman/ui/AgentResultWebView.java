package com.hr.digitalhuman.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** 只加载 APK 内渲染资源，不向 JavaScript 暴露原生 API。 */
final class AgentResultWebView extends WebView {
    private static final String ORIGIN = "https://agent-renderer.invalid/";
    private boolean ready, following = true;
    private boolean reset = true, busy, disposed;
    private int readingY;
    private long revision, session;
    private String pending = "", delivered = "";
    private boolean pendingRunning;
    private boolean dirty = true;
    private Runnable failureListener;

    @SuppressLint("SetJavaScriptEnabled")
    AgentResultWebView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(18, 30, 46));
        setSaveEnabled(false);
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setBlockNetworkImage(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return true; }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return resource(url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return resource(request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!disposed && ORIGIN.concat("index.html").equals(url)) {
                    ready = true;
                    reset = true;
                    dirty = true;
                    post(pump);
                }
            }
        });
        loadUrl(ORIGIN + "index.html");
        postDelayed(() -> {
            if (!disposed && (!ready || revision == 0) && failureListener != null) failureListener.run();
        }, 10000);
    }

    private WebResourceResponse resource(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if ("https".equals(uri.getScheme()) && "agent-renderer.invalid".equals(uri.getHost())
                && ("/index.html".equals(path) || "/renderer.js".equals(path) || "/renderer.css".equals(path))) {
            try {
                String mime = path.endsWith(".js") ? "application/javascript"
                        : path.endsWith(".css") ? "text/css" : "text/html";
                return new WebResourceResponse(mime, "UTF-8",
                        getContext().getAssets().open("agent-renderer" + path));
            } catch (IOException ignored) { /* 固定错误交由加载超时提示，不泄漏路径。 */ }
        }
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    void setFailureListener(Runnable listener) { failureListener = listener; }

    void render(String markdown, boolean running) {
        pending = markdown;
        pendingRunning = running;
        dirty = true;
        if (ready && !busy) { removeCallbacks(pump); post(pump); }
    }

    // 一次只保留一个在途 JS 调用；常规发送增量，恢复/重试发送完整快照。
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            if (disposed || !ready || busy) return;
            busy = true;
            evaluateJavascript("window.agentRenderer ? window.agentRenderer.state() : null", state -> {
                busy = false;
                if (disposed) return;
                try {
                    JsonObject value = JsonParser.parseString(state).getAsJsonObject();
                    if (!value.get("ready").getAsBoolean()) { postDelayed(this, 50); return; }
                    if (!reset) {
                        following = value.get("following").getAsBoolean();
                        readingY = value.get("y").getAsInt();
                    }
                    if (value.get("failed").getAsBoolean() && failureListener != null) failureListener.run();
                    if (dirty && (reset || value.get("committed").getAsLong() >= revision)) {
                        JsonObject payload = new JsonObject();
                        boolean snapshot = reset || !pending.startsWith(delivered);
                        payload.addProperty("snapshot", snapshot);
                        payload.addProperty("text", snapshot ? pending : pending.substring(delivered.length()));
                        delivered = pending;
                        payload.addProperty("running", pendingRunning);
                        payload.addProperty("session", session);
                        payload.addProperty("revision", ++revision);
                        payload.addProperty("reset", reset);
                        payload.addProperty("following", following);
                        payload.addProperty("y", readingY);
                        dirty = false;
                        reset = false;
                        busy = true;
                        evaluateJavascript("window.agentRenderer.update(" + payload + ")", ignored -> {
                            busy = false;
                            if (!disposed) postDelayed(this, 50);
                        });
                    } else postDelayed(this, 100);
                } catch (RuntimeException ignored) {
                    // 旧 WebView / 前端初始化异常不影响原生正文复制。
                    postDelayed(this, 250);
                }
            });
        }
    };

    void resetResult() {
        session++;
        following = true;
        readingY = 0;
        reset = true;
        render("", false);
    }

    void returnToLatest() {
        following = true;
        if (ready) evaluateJavascript("window.agentRenderer && window.agentRenderer.bottom()", null);
    }

    boolean isFollowing() { return following; }
    int readingPosition() { return readingY; }
    void restorePosition(boolean follow, int y) { following = follow; readingY = y; reset = true; }

    void dispose() {
        disposed = true;
        removeCallbacks(pump);
        stopLoading();
        setWebViewClient(new WebViewClient());
        destroy();
    }
}
