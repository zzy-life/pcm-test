package com.hr.digitalhuman.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hr.digitalhuman.R;
import com.hr.digitalhuman.agents.AgentApiClient;
import com.hr.digitalhuman.agents.AgentDefinition;
import com.hr.digitalhuman.agents.AgentSdkAuthClient;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.model.UserInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.noties.markwon.Markwon;
import io.noties.markwon.ext.tables.TablePlugin;

/** SDK 独占本页面的音视频；报告等待阶段不允许应用内返回或其他操作。 */
public final class InterviewSdkActivity extends AppCompatActivity {
    private static final String PAGE = "https://appassets.androidplatform.net/interview-sdk.html";
    private static final int MEDIA_PERMISSION = 6201;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FrameLayout content;
    private WebView webView;
    private PermissionRequest pendingPermission;
    private static final int MICROPHONE_PERMISSION = 6202;
    private final ExecutorService microphoneCleanup = Executors.newSingleThreadExecutor();
    private volatile boolean microphoneAllowed;
    private volatile MicrophoneCapture microphoneCapture;

    /** 厂商麦克风阵列固定使用 MIC / 16kHz / 双声道 PCM16，左声道为有效语音。 */
    private static final class MicrophoneCapture {
        final AudioRecord recorder;
        final java.util.concurrent.ArrayBlockingQueue<byte[]> frames =
                new java.util.concurrent.ArrayBlockingQueue<>(8);
        volatile boolean running = true;
        volatile String error;
        Thread thread;

        MicrophoneCapture(AudioRecord recorder) { this.recorder = recorder; }

        void readLoop() {
            short[] stereo = new short[1280]; // 40ms，双声道交错排列。
            boolean left = true;
            try {
                while (running) {
                    int count = recorder.read(stereo, 0, stereo.length);
                    if (count <= 0) {
                        if (running) throw new IllegalStateException("AudioRecord.read=" + count);
                        break;
                    }
                    byte[] mono = new byte[((count + (left ? 1 : 0)) / 2) * 2];
                    int offset = 0;
                    for (int i = 0; i < count; i++, left = !left) {
                        if (!left) continue;
                        mono[offset++] = (byte) stereo[i];
                        mono[offset++] = (byte) (stereo[i] >> 8);
                    }
                    // 页面阻塞时丢弃最旧帧，不无限堆积录音和延迟。
                    if (!frames.offer(mono)) { frames.poll(); frames.offer(mono); }
                }
            } catch (RuntimeException e) {
                if (running) {
                    error = "麦克风采集失败，请退出后重试";
                    Log.e("InterviewMicrophone", "Capture failed", e);
                }
            } finally {
                synchronized (this) {
                    running = false;
                    recorder.release();
                }
            }
        }

        synchronized void stop() {
            if (!running) return;
            running = false;
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            frames.clear();
        }
    }
    private AgentSdkAuthClient authClient;
    private AgentApiClient reportClient;
    private String conversationId = "", user;
    private boolean polling, terminal, destroyed, authorizing, initialized, interviewEnded;
    private long reportDeadline;
    private final Runnable reportTimeout = () -> {
        if (!polling || destroyed) return;
        AgentApiClient client = reportClient;
        showFailure("报告生成超过 5 分钟，请稍后重新查询", true);
        if (client != null) new Thread(client::cancel, "sdk-report-timeout").start();
    };
    private final Runnable nextPoll = this::fetchReport;
    private final Runnable loadTimeout = () -> {
        if (!terminal && !polling) showFailure("面试服务加载超时，请检查网络后重新进入");
    };

    public static void start(Context context, JsonObject inputs) {
        Intent intent = new Intent(context, InterviewSdkActivity.class);
        intent.putExtra("inputs", inputs.toString());
        context.startActivity(intent);
    }

    @Override protected void attachBaseContext(Context base) {
        com.hr.digitalhuman.model.RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        super.attachBaseContext(DisplayDensityHelper.createPageContext(base, config == null ? null : config.display));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // SDK 独占麦克风：仅关闭识别不能停止机器人持续拾音。
        com.hr.digitalhuman.robot.RobotSdkBridge bridge = DigitalHumanApp.getInstance().getRobotBridge();
        if (bridge != null) bridge.acquireExternalMicrophone(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_page);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        UserInfo currentUser = DigitalHumanApp.getInstance().getSessionStore().getUser();
        user = state != null ? state.getString("sdk_user") : null;
        if (user == null) user = currentUser != null && currentUser.userId != null
                && !currentUser.userId.isEmpty() ? "robot-user-" + currentUser.userId : "robot-session-" + UUID.randomUUID();
        if (state != null) {
            conversationId = state.getString("conversation_id", "");
            interviewEnded = state.getBoolean("interview_ended");
            if (state.getBoolean("polling")) {
                reportDeadline = state.getLong("report_deadline", 0);
                beginReport();
            } else if (interviewEnded && !conversationId.isEmpty()) {
                showFailure("面试已结束，请重新查询报告", true);
            } else {
                // 不自动重建 SDK，以免重新录音或重复提交回答。
                showFailure("面试页面已中断，请返回资料页重新开始");
            }
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            showFailure("当前系统不支持 SDK 音视频面试，请使用文本面试");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showMessage("请允许使用麦克风", false);
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
        } else {
            microphoneAllowed = true;
            createWebView();
        }
    }

    @android.annotation.SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        content.removeAllViews();
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // 用 HTTPS 本地来源承载页面，使 getUserMedia 处于安全上下文。
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!PAGE.equals(request.getUrl().toString())) return null;
                try {
                    return new WebResourceResponse("text/html", "UTF-8", getAssets().open("interview-sdk.html"));
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 不允许 SDK 把有原生桥的 WebView 导航到其他页面。
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (PAGE.equals(url) && !terminal && !polling && !initialized) {
                    initialized = true;
                    requestToken(false);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestMedia(request));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingPermission == request) pendingPermission = null;
                });
            }
        });
        webView.addJavascriptInterface(new SdkBridge(), "InterviewHost");
        content.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        webView.loadUrl(PAGE);
        handler.postDelayed(loadTimeout, 45000);
    }

    private synchronized String startMicrophone() {
        if (!microphoneAllowed) return "麦克风不可用或页面已退出";
        if (microphoneCapture != null && !microphoneCapture.thread.isAlive()) microphoneCapture = null;
        if (microphoneCapture != null) return "麦克风已在使用";
        AudioRecord recorder = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) return "设备不支持机器人麦克风采集参数";
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, 5120));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord initialization failed");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord did not start");
            MicrophoneCapture capture = new MicrophoneCapture(recorder);
            capture.thread = new Thread(capture::readLoop, "interview-microphone");
            microphoneCapture = capture;
            capture.thread.start();
            return "";
        } catch (RuntimeException e) {
            if (recorder != null) recorder.release();
            microphoneCapture = null;
            Log.e("InterviewMicrophone", "Cannot start MIC/16000/stereo", e);
            return "机器人麦克风启动失败，请检查录音权限或设备占用";
        }
    }

    private synchronized void stopMicrophone() {
        MicrophoneCapture capture = microphoneCapture;
        if (capture == null) return;
        capture.stop();
        // 确保上一轮已释放硬件，设备检测和逐题录制才能立即重新申请。
        try { capture.thread.join(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (!capture.thread.isAlive()) microphoneCapture = null;
    }

    private final class SdkBridge {
        @JavascriptInterface public String startMicrophone() {
            return InterviewSdkActivity.this.startMicrophone();
        }
        @JavascriptInterface public String readMicrophone() {
            MicrophoneCapture capture = microphoneCapture;
            if (capture == null) return "";
            if (capture.error != null) return "!" + capture.error;
            byte[] frame = capture.frames.poll();
            return frame == null ? "" : Base64.encodeToString(frame, Base64.NO_WRAP);
        }
        @JavascriptInterface public void stopMicrophone() {
            InterviewSdkActivity.this.stopMicrophone();
        }
        @JavascriptInterface public void event(String type, String id) {
            runOnUiThread(() -> {
                if (destroyed || terminal || polling || webView == null) return;
                if (id != null && id.matches("[A-Za-z0-9-]{1,256}")) conversationId = id;
                switch (type) {
                    case "ready": handler.removeCallbacks(loadTimeout); break;
                    case "conversation": break;
                    case "complete": beginReport(); break;
                    case "tokenInvalid": requestToken(true); break;
                    case "closed": finish(); break;
                    case "loadError": showFailure("面试组件加载失败，请检查网络后重新进入"); break;
                    case "error": showFailure("面试服务或录制发生错误，请返回后重试"); break;
                    default: break;
                }
            });
        }
    }

    private void requestToken(boolean refresh) {
        if (authorizing || destroyed || terminal || polling) return;
        authorizing = true;
        AgentSdkAuthClient client = new AgentSdkAuthClient();
        authClient = client;
        executor.execute(() -> {
            try {
                String token = client.fetchToken(user);
                runOnUiThread(() -> {
                    authorizing = false;
                    if (destroyed || terminal || polling || webView == null) return;
                    try {
                        if (refresh) {
                            webView.evaluateJavascript("window.updateInterviewToken(" + new com.google.gson.Gson().toJson(token) + ")", null);
                        } else {
                            JsonObject config = new JsonObject();
                            config.addProperty("token", token);
                            JsonObject original = JsonParser.parseString(getIntent().getStringExtra("inputs")).getAsJsonObject();
                            // 只透传 SDK 文档确认支持的参数，所有值均为字符串。
                            JsonObject inputs = new JsonObject();
                            for (String field : new String[]{"job_info", "resume_content", "file_url", "question_number"}) {
                                if (original.has(field)) inputs.addProperty(field, original.get(field).getAsString());
                            }
                            config.add("inputs", inputs);
                            webView.evaluateJavascript("window.initializeInterview(" + config + ")", null);
                        }
                    } catch (RuntimeException e) {
                        showFailure("面试参数无效，请返回资料页重新开始");
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    authorizing = false;
                    if (!destroyed && !terminal && !polling) showFailure("面试授权失败，请检查网络和 SDK 配置");
                });
            }
        });
    }

    private void requestMedia(PermissionRequest request) {
        if (destroyed || terminal || polling || webView == null
                || !Uri.parse(PAGE).getScheme().equals(request.getOrigin().getScheme())
                || !Uri.parse(PAGE).getAuthority().equals(request.getOrigin().getAuthority())) {
            request.deny();
            return;
        }
        for (String resource : request.getResources()) {
            if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && !PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                request.deny();
                return;
            }
        }
        if (pendingPermission != null) pendingPermission.deny();
        pendingPermission = request;
        List<String> missing = new ArrayList<>();
        for (String resource : request.getResources()) {
            String permission = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) grantMedia();
        else ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), MEDIA_PERMISSION);
    }

    private void grantMedia() {
        PermissionRequest request = pendingPermission;
        pendingPermission = null;
        if (request == null) return;
        for (String resource : request.getResources()) {
            String permission = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                request.deny();
                return;
            }
        }
        if (destroyed || terminal || polling || webView == null) request.deny();
        else request.grant(request.getResources());
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == MEDIA_PERMISSION) grantMedia();
        if (code == MICROPHONE_PERMISSION && !destroyed && !terminal) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                microphoneAllowed = true;
                createWebView();
            } else showFailure("请授予麦克风权限后重新进入面试");
        }
    }

    private void beginReport() {
        if (destroyed || terminal || polling) return;
        if (conversationId.isEmpty()) {
            showFailure("未取得面试会话，无法查询报告");
            return;
        }
        interviewEnded = true;
        polling = true;
        destroyWebView();
        showMessage("面试已完成，正在生成报告，请稍候…", true);
        if (reportDeadline == 0) reportDeadline = android.os.SystemClock.elapsedRealtime() + 300000;
        long remaining = reportDeadline - android.os.SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            reportTimeout.run();
            return;
        }
        handler.postDelayed(reportTimeout, remaining);
        try {
            reportClient = new AgentApiClient(AgentDefinition.INTERVIEW_AGENT.apiKey());
        } catch (IllegalArgumentException e) {
            showFailure("报告服务未配置，请联系管理员", true);
            return;
        }
        fetchReport();
    }

    private void fetchReport() {
        if (destroyed || !polling) return;
        final AgentApiClient client = reportClient;
        executor.execute(() -> {
            try {
                AgentApiClient.ReportData report = client.fetchReportData(conversationId, 10000);
                runOnUiThread(() -> {
                    if (destroyed || !polling || reportClient != client) return;
                    if (android.os.SystemClock.elapsedRealtime() >= reportDeadline) {
                        reportTimeout.run();
                        return;
                    }
                    if (report == null) handler.postDelayed(nextPoll, 3000);
                    else showReport(report);
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (!destroyed && polling && reportClient == client) showFailure("报告查询失败，请检查网络后重试", true);
                });
            }
        });
    }

    private void showReport(AgentApiClient.ReportData report) {
        polling = false;
        terminal = true;
        handler.removeCallbacks(reportTimeout);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setPadding(dp(20), dp(16), dp(20), dp(16));
        text.setTextColor(UiDecor.color(this, R.color.text));
        text.setTextSize(16);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        if (report.content != null && !report.content.trim().isEmpty()) {
            Markwon.builder(this).usePlugin(TablePlugin.create(this)).build().setMarkdown(text, report.content);
        } else {
            text.setText(new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(JsonParser.parseString(report.json)));
        }
    }

    private void showFailure(String message) { showFailure(message, false); }

    private void showFailure(String message, boolean retryReport) {
        if (destroyed) return;
        polling = false;
        terminal = true;
        handler.removeCallbacks(nextPoll);
        handler.removeCallbacks(reportTimeout);
        handler.removeCallbacks(loadTimeout);
        destroyWebView();
        LinearLayout panel = showMessage(message, false);
        if (retryReport) {
            Button retry = UiDecor.button(this, "重新查询报告", true);
            panel.addView(retry);
            retry.setOnClickListener(v -> {
                terminal = false;
                reportDeadline = 0;
                beginReport();
            });
        }
    }

    private LinearLayout showMessage(String message, boolean loading) {
        content.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER);
        if (loading) panel.addView(new ProgressBar(this));
        TextView text = UiDecor.subtitle(this, message);
        text.setPadding(dp(16), dp(16), dp(16), dp(16));
        text.setGravity(android.view.Gravity.CENTER);
        panel.addView(text);
        content.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        return panel;
    }

    private void destroyWebView() {
        microphoneAllowed = false;
        microphoneCleanup.execute(this::stopMicrophone);
        handler.removeCallbacks(loadTimeout);
        if (pendingPermission != null) { pendingPermission.deny(); pendingPermission = null; }
        if (webView == null) return;
        // 销毁整个 WebView，不仅隐藏组件，确保 SDK 不在后台录制/自动答题。
        WebView old = webView;
        webView = null;
        old.removeJavascriptInterface("InterviewHost");
        old.stopLoading();
        old.loadUrl("about:blank");
        ((ViewGroup) old.getParent()).removeView(old);
        old.destroy();
    }

    @Override public void onBackPressed() {
        if (!polling) super.onBackPressed();
    }

    @Override protected void onStop() {
        super.onStop();
        // 权限弹窗只触发 onPause；真正离开前台时结束录制，避免隐私采集。
        if (webView != null && !isChangingConfigurations()) showFailure("面试已中断，请返回资料页重新开始");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("conversation_id", conversationId);
        state.putString("sdk_user", user);
        state.putBoolean("polling", polling);
        state.putBoolean("interview_ended", interviewEnded);
        state.putLong("report_deadline", reportDeadline);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        destroyWebView();
        com.hr.digitalhuman.robot.RobotSdkBridge bridge = DigitalHumanApp.getInstance().getRobotBridge();
        microphoneCleanup.execute(() -> {
            MicrophoneCapture capture = microphoneCapture;
            if (capture != null) {
                try { capture.thread.join(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            // 真正释放录音硬件后才归还机器人拾音租约。
            if (bridge != null) handler.post(() -> bridge.releaseExternalMicrophone(this));
        });
        microphoneCleanup.shutdown();
        if (authClient != null) new Thread(authClient::cancel, "sdk-auth-cancel").start();
        if (reportClient != null) new Thread(reportClient::cancel, "sdk-report-cancel").start();
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) { return UiDecor.dp(this, value); }
}
