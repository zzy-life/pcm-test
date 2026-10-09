package com.hr.digitalhuman.robot;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.ainirobot.coreservice.client.ApiListener;
import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.ActionListener;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.ainirobot.coreservice.client.speech.SkillApi;
import com.ainirobot.coreservice.client.speech.entity.TTSEntity;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.RobotConfig;

/**
 * 猎户星空 SDK 桥接：仅真实在线模式，禁止离线降级。
 */
public class RobotSdkBridge {

    private static final String TAG = "RobotSdkBridge";
    private static final int REQ_NAV = 0;

    public interface InitCallback {
        void onReady(String sn, boolean sdkConnected);

        void onProgress(String message);

        void onFailed(String message);
    }

    public interface TtsCallback {
        void onComplete();

        void onError(String msg);
    }

    /**
     * 对齐官方 startNavigation / stopNavigation。
     * 文档：https://doc.orionstar.com/blog/knowledge-base/导航/
     */
    public interface NavListener {
        void onArrived();

        void onStopped();

        void onFailed(int code, String msg);

        void onStatus(String hint);
    }

    public interface SpeechTextListener {
        void onAsrResult(String text);

        /** 中间识别结果，用于对话页输入框实时预览。 */
        default void onAsrPartial(String text) {
        }
    }

    /** 是否允许打开拾音；无人在前方时应返回 false。 */
    public interface ListeningPolicy {
        boolean shouldListen();
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SkillApi skillApi;
    private HandlerThread apiThread;
    private boolean robotConnected;
    private boolean skillConnected;
    private int activeWaitAttempts;
    private String sn;
    private boolean readyNotified;
    private volatile SpeechTextListener speechTextListener;
    // 临时采集独立于默认页面；状态快照及代次变更使用同一把锁。
    private volatile SpeechTextListener voiceCaptureListener;
    private volatile long voiceCaptureGeneration;
    private ListeningPolicy listeningPolicy;
    private PersonPresenceTracker presenceTracker;
    private RobotConfig.TtsConfig ttsConfig;
    private volatile boolean ttsPlaying;
    private TtsStateListener ttsStateListener;

    public interface TtsStateListener {
        void onChanged(boolean playing);
    }

    public void setTtsStateListener(TtsStateListener listener) {
        this.ttsStateListener = listener;
    }

    public boolean isTtsPlaying() {
        return ttsPlaying;
    }

    public RobotSdkBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setSpeechTextListener(SpeechTextListener listener) {
        this.speechTextListener = listener;
    }

    /**
     * 在主线程开始临时采集；返回 true 才表示识别已开启。
     * 同一监听器可重复调用，其他监听器不能抢占；不会替换默认页面监听器。
     */
    public boolean beginVoiceCapture(SpeechTextListener listener) {
        if (Looper.myLooper() != Looper.getMainLooper() || listener == null) {
            return false;
        }
        synchronized (this) {
            if (voiceCaptureListener != null && voiceCaptureListener != listener) {
                return false;
            }
            if (!isChassisReady() || !isSkillConnected() || !allowVoiceInput("capture")) {
                return false;
            }
            if (voiceCaptureListener == null) {
                voiceCaptureGeneration++;
                voiceCaptureListener = listener;
            }
            enableListening();
            if (!isRecognizable()) {
                // 开麦失败也结束本代次，防止已排队的结果泄漏到默认页面。
                endVoiceCapture(listener);
                return false;
            }
            return true;
        }
    }

    /** 仅释放当前采集者；先使排队回调失效，再关闭识别，不恢复默认页面拾音。 */
    public void endVoiceCapture(SpeechTextListener listener) {
        synchronized (this) {
            if (listener == null || voiceCaptureListener != listener) {
                return;
            }
            voiceCaptureGeneration++;
            voiceCaptureListener = null;
            disableListening();
        }
    }

    public void setListeningPolicy(ListeningPolicy policy) {
        this.listeningPolicy = policy;
    }

    public void setPresenceTracker(PersonPresenceTracker tracker) {
        this.presenceTracker = tracker;
    }

    public PersonPresenceTracker getPresenceTracker() {
        return presenceTracker;
    }

    public void applyTtsConfig(RobotConfig.TtsConfig tts) {
        this.ttsConfig = tts;
    }

    /**
     * 按官方声源定位设置，收窄麦克风阵列有效拾音扇区为正前方。
     * 见 SettingsUtil.ROBOT_SETTING_SOUND_ANGEL_CENTER_FLOAT / RANGE_FLOAT。
     */
    public void applyFrontSoundGate(float centerDeg, float rangeDeg) {
        if (rangeDeg <= 0) {
            return;
        }
        try {
            com.ainirobot.coreservice.client.robotsetting.RobotSettingApi api =
                    com.ainirobot.coreservice.client.robotsetting.RobotSettingApi.getInstance();
            String centerKey = com.ainirobot.coreservice.client.SettingsUtil
                    .ROBOT_SETTING_SOUND_ANGEL_CENTER_FLOAT;
            String rangeKey = com.ainirobot.coreservice.client.SettingsUtil
                    .ROBOT_SETTING_SOUND_ANGEL_RANGE_FLOAT;
            if (api.hasRobotSetting(centerKey)) {
                api.setRobotFloat(centerKey, centerDeg);
            }
            if (api.hasRobotSetting(rangeKey)) {
                api.setRobotFloat(rangeKey, rangeDeg);
            }
            DebugLog.i(TAG, "front sound gate center=" + centerDeg + " range=±" + rangeDeg);
        } catch (Throwable t) {
            DebugLog.w(TAG, "applyFrontSoundGate failed: " + t.getMessage());
        }
    }

    public void init(InitCallback callback) {
        readyNotified = false;
        sn = null;
        activeWaitAttempts = 0;
        callback.onProgress("连接 RobotApi…");
        apiThread = new HandlerThread("HrRobotApi");
        apiThread.start();

        try {
            RobotApi.getInstance().connectServer(appContext, new ApiListener() {
                @Override
                public void handleApiDisabled() {
                    DebugLog.w(TAG, "RobotApi disabled");
                    failInit(callback, "RobotApi 不可用，请从机器人 Launcher 启动本应用");
                }

                @Override
                public void handleApiConnected() {
                    robotConnected = true;
                    RobotApi.getInstance().setCallback(DigitalHumanApp.getInstance().getModuleCallback());
                    RobotApi.getInstance().setResponseThread(apiThread);
                    waitForChassisActive(callback);
                    initSkill();
                }

                @Override
                public void handleApiDisconnected() {
                    robotConnected = false;
                }
            });
        } catch (Throwable t) {
            DebugLog.e(TAG, "connectServer failed", t);
            failInit(callback, "连接 RobotApi 失败: " + t.getMessage());
        }

        mainHandler.postDelayed(() -> {
            if (!readyNotified && sn == null) {
                failInit(callback, "连接超时，请确认已从机器人 Launcher 启动并授予底盘权限");
            }
        }, 20000);
    }

    private void waitForChassisActive(InitCallback callback) {
        activeWaitAttempts++;
        if (isChassisReady()) {
            callback.onProgress("获取 SN…");
            fetchSn(callback);
            return;
        }
        if (activeWaitAttempts > 80) {
            DebugLog.w(TAG, "waitForChassisActive timeout, isActive="
                    + safeIsActive() + " connected=" + safeApiConnected());
            failInit(callback, "未获得底盘控制权，禁止离线运行。请从机器人 Launcher 启动本应用");
            return;
        }
        callback.onProgress("等待底盘权限…");
        mainHandler.postDelayed(() -> waitForChassisActive(callback), 300);
    }

    private boolean safeIsActive() {
        try {
            return RobotApi.getInstance().isActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean safeApiConnected() {
        try {
            return RobotApi.getInstance().isApiConnectedService();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isChassisReady() {
        return robotConnected && safeApiConnected() && safeIsActive();
    }

    public void notifyForeground() {
        enableListening();
    }

    /**
     * 保持全程语音监听（TTS/ASR 结束后需重新打开）。
     * 必须同时：onForeground + setRecognizable(true) + setRecognizeMode(true)。
     * 系统设置「语音识别」「持续拾音」也需开启，否则接口不生效。
     */
    public synchronized void enableListening() {
        try {
            if (voiceCaptureListener != null
                    && (!isChassisReady() || !allowVoiceInput("listening"))) {
                disableListening();
                return;
            }
            if (voiceCaptureListener == null
                    && listeningPolicy != null && !listeningPolicy.shouldListen()) {
                disableListening();
                return;
            }
            if (skillApi == null) {
                DebugLog.w(TAG, "enableListening skipped: skillApi null");
                return;
            }
            boolean connected = skillConnected || safeSkillConnected();
            if (!connected) {
                DebugLog.w(TAG, "enableListening skipped: SkillApi not connected");
                return;
            }
            skillConnected = true;
            String pkg = appContext.getPackageName();
            skillApi.onForeground(pkg);
            try {
                skillApi.setASREnabled(true);
            } catch (Throwable ignored) {
                // 部分 ROM 无此接口
            }
            // 先开持续拾音，再允许识别。关掉后再打开时顺序反了，这台机器不会重新收音。
            skillApi.setRecognizeMode(true);
            skillApi.setRecognizable(true);
        } catch (Throwable t) {
            DebugLog.w(TAG, "enableListening failed: " + t.getMessage());
        }
    }

    /**
     * 暂停聆听：同时关掉识别和持续拾音。
     * 只 setRecognizable(false) 时，这台机器退出后不会重新收音。
     */
    public void pauseListeningFully() {
        try {
            if (skillApi == null) {
                return;
            }
            skillApi.setRecognizable(false);
            try {
                skillApi.setRecognizeMode(false);
            } catch (Throwable ignored) {
            }
            DebugLog.i(TAG, "listening fully paused");
        } catch (Throwable t) {
            DebugLog.w(TAG, "pauseListeningFully failed: " + t.getMessage());
        }
    }

    /**
     * 退出暂停聆听后重新挂上语音回调并开麦。
     * 必须先关持续拾音再按「模式 → 识别」打开，只 setRecognizable(true) 这台机器不会重新收音。
     */
    public void resumeListeningAfterPause() {
        try {
            if (skillApi != null && (skillConnected || safeSkillConnected())) {
                skillConnected = true;
                skillApi.registerCallBack(DigitalHumanApp.getInstance().getSpeechCallback());
            }
        } catch (Throwable t) {
            DebugLog.w(TAG, "re-register speech callback failed: " + t.getMessage());
        }
        restartRecognitionPipeline();
        enableListening();
        mainHandler.postDelayed(this::retryEnableAfterPause, 400);
        mainHandler.postDelayed(this::retryEnableAfterPause, 1200);
    }

    private void retryEnableAfterPause() {
        try {
            if (DigitalHumanApp.getInstance().getSessionStore().isDemoMode()) {
                return;
            }
        } catch (Throwable ignored) {
        }
        enableListening();
    }

    private void restartRecognitionPipeline() {
        try {
            if (skillApi == null) {
                return;
            }
            skillApi.setRecognizable(false);
            try {
                skillApi.setRecognizeMode(false);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            DebugLog.w(TAG, "restartRecognitionPipeline failed: " + t.getMessage());
        }
    }

    /** 无人在前方时关闭拾音识别，避免环境噪音进入业务处理。 */
    public void disableListening() {
        try {
            if (skillApi == null) {
                return;
            }
            skillApi.setRecognizable(false);
        } catch (Throwable t) {
            DebugLog.w(TAG, "disableListening failed: " + t.getMessage());
        }
    }

    private boolean safeSkillConnected() {
        try {
            return skillApi != null && skillApi.isApiConnectedService();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean safeIsRecognizable() {
        try {
            return skillApi != null && skillApi.isRecognizable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean safeIsRecognizeContinue() {
        try {
            return skillApi != null && skillApi.isRecognizeContinue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void fetchSn(InitCallback callback) {
        try {
            RobotApi.getInstance().getRobotSn(new CommandListener() {
                @Override
                public void onResult(int result, String message) {
                    if (message != null && !message.trim().isEmpty()) {
                        sn = message.trim();
                        callback.onProgress("连接语音服务…");
                        maybeReady(callback);
                    } else {
                        failInit(callback, "获取机器人 SN 为空");
                    }
                }

                @Override
                public void onError(int errorCode, String errorString) throws android.os.RemoteException {
                    DebugLog.w(TAG, "getRobotSn error " + errorCode + " " + errorString);
                    failInit(callback, "获取机器人 SN 失败: " + errorString);
                }
            });
        } catch (Throwable t) {
            DebugLog.e(TAG, "getRobotSn exception", t);
            failInit(callback, "获取机器人 SN 异常: " + t.getMessage());
        }
    }

    private void initSkill() {
        try {
            skillApi = new SkillApi();
            skillApi.addApiEventListener(new ApiListener() {
                @Override
                public void handleApiDisabled() {
                    DebugLog.w(TAG, "SkillApi disabled");
                    skillConnected = false;
                }

                @Override
                public void handleApiConnected() {
                    skillConnected = true;
                    try {
                        // 与 Demo 一致：连接成功后 registerCallBack，再打开持续拾音
                        skillApi.registerCallBack(DigitalHumanApp.getInstance().getSpeechCallback());
                        enableListening();
                    } catch (Throwable t) {
                        DebugLog.e(TAG, "registerCallBack failed", t);
                    }
                }

                @Override
                public void handleApiDisconnected() {
                    skillConnected = false;
                    DebugLog.w(TAG, "SkillApi disconnected");
                }
            });
            skillApi.connectApi(appContext);
            // 若已连上（重入），立即启用
            mainHandler.postDelayed(() -> {
                if (safeSkillConnected()) {
                    skillConnected = true;
                    enableListening();
                }
            }, 800);
        } catch (Throwable t) {
            DebugLog.e(TAG, "SkillApi init failed", t);
        }
    }

    private void failInit(InitCallback callback, String message) {
        DebugLog.e(TAG, message);
        mainHandler.post(() -> {
            if (!readyNotified) {
                callback.onFailed(message);
            }
        });
    }

    private void maybeReady(InitCallback callback) {
        if (sn == null || sn.isEmpty()) {
            return;
        }
        if (!isChassisReady()) {
            failInit(callback, "底盘未就绪，禁止离线运行");
            return;
        }
        DigitalHumanApp.getInstance().getSessionStore().saveSn(sn);
        mainHandler.post(() -> {
            if (readyNotified) {
                return;
            }
            readyNotified = true;
            callback.onReady(sn, true);
        });
    }

    public String getSn() {
        return sn;
    }

    public boolean isRobotConnected() {
        return isChassisReady();
    }

    public boolean isRobotApiConnected() {
        return robotConnected;
    }

    public boolean isApiServiceConnected() {
        return safeApiConnected();
    }

    public boolean isChassisActive() {
        return safeIsActive();
    }

    public boolean isSkillConnected() {
        return skillConnected || safeSkillConnected();
    }

    /** SkillApi 是否已创建（含连接中）。 */
    public boolean isSkillApiInitialized() {
        return skillApi != null;
    }

    public boolean isRecognizeContinue() {
        return safeIsRecognizeContinue();
    }

    public boolean isRecognizable() {
        return safeIsRecognizable();
    }

    public void playTts(String text, TtsCallback callback) {
        if (text == null || text.isEmpty()) {
            if (callback != null) {
                mainHandler.post(callback::onComplete);
            }
            return;
        }
        if (skillApi == null || !isSkillConnected()) {
            DebugLog.w(TAG, "TTS unavailable: SkillApi not connected");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("tts_unavailable"));
            }
            return;
        }
        skillConnected = true;
        ttsPlaying = true;
        notifyTts(true);
        disableListening();
        try {
            // 播报前确保前台，部分机型否则 TTS 无声
            try {
                skillApi.onForeground(appContext.getPackageName());
            } catch (Throwable ignored) {
            }
            skillApi.playText(new TTSEntity("hr-" + System.currentTimeMillis(), text),
                    new com.ainirobot.coreservice.client.listener.TextListener() {
                        @Override
                        public void onStart() {
                        }

                        @Override
                        public void onStop() {
                            endTts();
                            if (callback != null) {
                                mainHandler.post(callback::onComplete);
                            }
                        }

                        @Override
                        public void onComplete() {
                            endTts();
                            if (callback != null) {
                                mainHandler.post(callback::onComplete);
                            }
                        }

                        @Override
                        public void onError() {
                            DebugLog.w(TAG, "TTS onError");
                            endTts();
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("tts_error"));
                            }
                        }
                    });
        } catch (Throwable t) {
            DebugLog.e(TAG, "playText failed", t);
            endTts();
            if (callback != null) {
                mainHandler.post(() -> callback.onError(t.getMessage()));
            }
        }
    }

    public void stopTts() {
        try {
            if (skillApi != null) {
                skillApi.stopTTS();
            }
        } catch (Throwable ignored) {
        }
        endTts();
    }

    private void endTts() {
        ttsPlaying = false;
        notifyTts(false);
        enableListening();
    }

    private void notifyTts(boolean playing) {
        TtsStateListener l = ttsStateListener;
        if (l == null) {
            return;
        }
        mainHandler.post(() -> l.onChanged(playing));
    }

    public void startNavigation(String placeName, NavListener listener) {
        startNavigation(placeName, null, listener);
    }

    public void startNavigation(String placeName, String expectedMap, NavListener listener) {
        if (placeName == null || placeName.trim().isEmpty()) {
            if (listener != null) {
                mainHandler.post(() -> listener.onFailed(-1, "empty_place"));
            }
            return;
        }
        if (!isChassisReady()) {
            DebugLog.w(TAG, "startNavigation aborted: chassis not ready");
            if (listener != null) {
                mainHandler.post(() -> listener.onFailed(-1, "chassis_not_ready"));
            }
            return;
        }
        final String dest = placeName.trim();
        if (!NavMapHelper.isEstimated(1000)) {
            DebugLog.w(TAG, "startNavigation aborted: not estimated");
            if (listener != null) {
                mainHandler.post(() -> listener.onFailed(-1, "not_estimated"));
            }
            return;
        }
        String currentMap = NavMapHelper.queryMapName(800);
        if (expectedMap != null && !expectedMap.trim().isEmpty()
                && currentMap != null && !currentMap.isEmpty()
                && !"default".equalsIgnoreCase(expectedMap.trim())
                && !expectedMap.trim().equals(currentMap)) {
            DebugLog.w(TAG, "startNavigation map mismatch expect=" + expectedMap + " current=" + currentMap);
            if (listener != null) {
                mainHandler.post(() -> listener.onFailed(-1, "map_mismatch"));
            }
            return;
        }
        for (com.hr.digitalhuman.model.NavPoint p : NavMapHelper.loadLocalNavPoints()) {
            if (p != null && dest.equals(p.robotMapPlaceName) && p.status != 0) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onFailed(-1, "place_blocked"));
                }
                return;
            }
        }
        try {
            // destName 必须是地图点位名；coordinateDeviation 单位米，超时给大厅绕行留余量
            RobotApi.getInstance().startNavigation(REQ_NAV, dest, 0.5, 120 * 1000L,
                    new ActionListener() {
                        @Override
                        public void onResult(int status, String response) throws android.os.RemoteException {
                            DebugLog.i(TAG, "nav onResult status=" + status + " resp=" + response);
                            if (status == Definition.RESULT_OK) {
                                if (listener != null) {
                                    mainHandler.post(listener::onArrived);
                                }
                            } else if (status == Definition.ACTION_RESPONSE_STOP_SUCCESS) {
                                if (listener != null) {
                                    mainHandler.post(listener::onStopped);
                                }
                            }
                        }

                        @Override
                        public void onError(int errorCode, String errorString) throws android.os.RemoteException {
                            DebugLog.w(TAG, "nav onError " + errorCode + " " + errorString);
                            if (listener != null) {
                                mainHandler.post(() -> listener.onFailed(errorCode, errorString));
                            }
                        }

                        @Override
                        public void onStatusUpdate(int status, String data) throws android.os.RemoteException {
                            String hint = mapNavStatus(status);
                            DebugLog.i(TAG, "nav status=" + status + " " + data + " hint=" + hint);
                            if (listener != null) {
                                mainHandler.post(() -> listener.onStatus(hint));
                            }
                        }
                    });
        } catch (Throwable t) {
            DebugLog.e(TAG, "startNavigation failed", t);
            if (listener != null) {
                mainHandler.post(() -> listener.onFailed(-1, t.getMessage()));
            }
        }
    }

    public void stopNavigation() {
        try {
            if (robotConnected) {
                // 仅用于停止 startNavigation，不可与 goPosition 混用
                RobotApi.getInstance().stopNavigation(REQ_NAV);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String mapNavStatus(int status) {
        if (defEq("STATUS_START_NAVIGATION", status) || defEq("STATUS_STARTING_NAVIGATION", status)) {
            return "正在前往目标";
        }
        if (defEq("STATUS_NAVI_AVOID", status) || defEq("STATUS_NAVI_OBSTACLES_AVOID", status)) {
            return "前方有障碍，正在绕行";
        }
        if (defEq("STATUS_DESTINATION_NEAR", status) || defEq("STATUS_GOAL_NEAR", status)) {
            return "即将到达";
        }
        if (defEq("STATUS_NAVI_OUT_MAP", status)) {
            return "已偏出地图范围";
        }
        return "正在带路";
    }

    private static boolean defEq(String field, int status) {
        try {
            return Definition.class.getField(field).getInt(null) == status;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void dispatchAsr(String text) {
        dispatchAsr(text, "asr");
    }

    /**
     * @param source asr=SkillCallback最终结果；nlp=ModuleCallback；手动去重避免双通道重复发送
     */
    public synchronized void dispatchAsr(String text, String source) {
        if (text == null || text.trim().isEmpty() || !allowVoiceInput("asr")) {
            return;
        }
        // 注意：不在此处用 listeningPolicy 丢弃已识别文本。
        // 关麦只影响后续拾音；已产出的 ASR 交给业务层决定是否处理。
        final String trimmed = text.trim();
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (trimmed.equals(lastAsrText) && now - lastAsrAt < 1500L) {
                return;
            }
            lastAsrText = trimmed;
            lastAsrAt = now;
        }
        postSpeechText(trimmed, false);
    }

    public synchronized void dispatchAsrPartial(String text) {
        if (text == null || !allowVoiceInput("partial")) {
            return;
        }
        postSpeechText(text, true);
    }

    private void postSpeechText(String text, boolean partial) {
        final SpeechTextListener target;
        final long generation;
        final boolean temporary;
        synchronized (this) {
            temporary = voiceCaptureListener != null;
            target = temporary ? voiceCaptureListener : speechTextListener;
            generation = voiceCaptureGeneration;
        }
        if (target == null) {
            if (!partial) {
                DebugLog.w(TAG, "speechTextListener is null, ASR dropped");
            }
            return;
        }
        mainHandler.post(() -> {
            synchronized (RobotSdkBridge.this) {
                // 开始/结束/失败回滚都会推进代次；旧结果绝不重新路由到默认页面。
                if (generation != voiceCaptureGeneration
                        || (temporary ? voiceCaptureListener != target
                        : voiceCaptureListener != null || speechTextListener != target)
                        || !allowVoiceInput(partial ? "partial" : "asr")) {
                    return;
                }
                if (partial) {
                    target.onAsrPartial(text);
                } else {
                    target.onAsrResult(text);
                }
            }
        });
    }

    private boolean allowVoiceInput(String source) {
        if (ttsPlaying) {
            DebugLog.i(TAG, "ignore voice during tts source=" + source);
            return false;
        }
        try {
            if (DigitalHumanApp.getInstance().getSessionStore().isDemoMode()) {
                DebugLog.i(TAG, "ignore voice in demo mode source=" + source);
                return false;
            }
        } catch (Throwable ignored) {
        }
        if (presenceTracker != null && !presenceTracker.shouldAcceptAsr()) {
            return false;
        }
        return true;
    }

    private String lastAsrText = "";
    private long lastAsrAt;
}
