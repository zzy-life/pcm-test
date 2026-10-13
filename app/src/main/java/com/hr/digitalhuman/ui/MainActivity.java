package com.hr.digitalhuman.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.agent.AgentSession;
import com.hr.digitalhuman.agent.RobotToolRuntime;
import com.hr.digitalhuman.api.ApiService;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.app.SessionStore;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.ime.SoftImeController;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.ChatSession;
import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.robot.NavMapHelper;
import com.hr.digitalhuman.robot.PersonPresenceTracker;
import com.hr.digitalhuman.robot.RobotSdkBridge;
import com.hr.digitalhuman.state.RobotState;
import com.hr.digitalhuman.state.RobotStateMachine;
import com.hr.digitalhuman.ui.fragment.HistoryFragment;
import com.hr.digitalhuman.ui.fragment.HomeFragment;
import com.hr.digitalhuman.ui.fragment.InitFragment;
import com.hr.digitalhuman.ui.fragment.LoginFragment;
import com.hr.digitalhuman.ui.fragment.NavigatingFragment;
import com.hr.digitalhuman.ui.fragment.ResumeCenterFragment;
import com.hr.digitalhuman.ui.fragment.RobotAdminFragment;
import com.hr.digitalhuman.ui.fragment.StandbyFragment;
import com.hr.digitalhuman.ui.fragment.WelcomeFragment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements RobotToolRuntime.Host, AgentSession.Listener {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DigitalHumanApp app;
    private SessionStore store;
    private RobotStateMachine fsm;
    private RobotSdkBridge bridge;
    private ApiService api;
    private AgentSession agent;
    private RobotToolRuntime toolRuntime;
    private HomeFragment homeFragment;
    private ResumeCenterFragment resumeCenterFragment;
    private final com.hr.digitalhuman.robot.FocusFollowController focusFollowController =
            new com.hr.digitalhuman.robot.FocusFollowController();
    private final PersonPresenceTracker presenceTracker = new PersonPresenceTracker();

    private boolean speaking;
    /** 用户手动点了停止播报。随后的播报结束回调不要再写成「播报完成」。 */
    private boolean userStoppedCaption;
    /** 思考过程中忽略全部语音，结束后再开麦。 */
    private boolean ignoreVoiceForThinking;
    private String pendingNavTarget;
    private boolean navFinished;
    private boolean agentPagePaused;
    private boolean standbyPendingAfterAgent;
    /** 回到待机必须同时满足：距上次点击超过此时长，且 1.5 米内无人。 */
    private static final long STANDBY_AFTER_CLICK_MS = 20_000L;
    private long lastClickAt = System.currentTimeMillis();
    private long lastStandbyBlockLogAt;
    private final Runnable standbyRecheck = () -> requestStandby("watch");

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
            noteClick();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 只记录点击，语音和人脸变化不重置这 20 秒。 */
    private void noteClick() {
        lastClickAt = System.currentTimeMillis();
        scheduleStandbyRecheck();
    }

    private void scheduleStandbyRecheck() {
        mainHandler.removeCallbacks(standbyRecheck);
        if (fsm == null) {
            return;
        }
        RobotState state = fsm.getState();
        if (state == RobotState.STANDBY || state == RobotState.INIT || state == RobotState.SUSPENDED) {
            return;
        }
        long since = System.currentTimeMillis() - lastClickAt;
        long delay = since >= STANDBY_AFTER_CLICK_MS ? 1000L : (STANDBY_AFTER_CLICK_MS - since);
        mainHandler.postDelayed(standbyRecheck, delay);
    }

    /**
     * 回待机的两个必要条件，缺一不可。
     * 面前有人：1.5 米内有人脸或人形。
     */
    private boolean standbyConditionsMet() {
        if (System.currentTimeMillis() - lastClickAt < STANDBY_AFTER_CLICK_MS) {
            return false;
        }
        return presenceTracker == null || !presenceTracker.blocksStandby();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        DisplayDensityHelper.apply(newBase);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayDensityHelper.apply(this);
        setContentView(R.layout.activity_main);

        app = DigitalHumanApp.getInstance();
        store = app.getSessionStore();
        fsm = app.getStateMachine();
        bridge = app.getRobotBridge();
        api = app.getApiService();
        toolRuntime = new RobotToolRuntime(this);
        agent = new AgentSession(api, store, io, toolRuntime, this);

        bridge.setSpeechTextListener(new RobotSdkBridge.SpeechTextListener() {
            @Override
            public void onAsrResult(String text) {
                onAsrText(text);
            }

            @Override
            public void onAsrPartial(String text) {
                handleAsrPartial(text);
            }
        });
        bridge.setTtsStateListener(this::updateStopSpeakButton);
        // 开麦策略：演示模式、思考中、播报中、视野内无人或超出配置距离时关麦
        bridge.setListeningPolicy(() -> {
            if (app.isAgentPageVisible() || store.isDemoMode() || speaking || bridge.isTtsPlaying() || isThinkingPhase()) {
                return false;
            }
            ResumeCenterFragment resume = currentResumeCenter();
            if (resume != null && resume.isChatMode() && !resume.isVoiceCapturing()) {
                return false;
            }
            return presenceTracker.shouldAcceptAsr();
        });
        bridge.setPresenceTracker(presenceTracker);
        SoftImeController.get().attach(this);
        showFragment(new InitFragment(), false);
        startBootstrap();
    }

    private void startBootstrap() {
        fsm.transition(RobotState.INIT, "bootstrap");
        bridge.init(new RobotSdkBridge.InitCallback() {
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> {
                    Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                    if (f instanceof InitFragment) {
                        ((InitFragment) f).setStatus(message);
                    }
                });
            }

            @Override
            public void onFailed(String message) {
                runOnUiThread(() -> {
                    Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                    if (f instanceof InitFragment) {
                        ((InitFragment) f).setStatus(message);
                    }
                });
            }

            @Override
            public void onReady(String sn, boolean sdkConnected) {
                if (!sdkConnected) {
                    runOnUiThread(() -> {
                        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                        if (f instanceof InitFragment) {
                            ((InitFragment) f).setStatus("机器人未连接，禁止离线运行");
                        }
                    });
                    return;
                }
                io.execute(() -> {
                    ApiResponse<RobotConfig> resp = fetchRobotConfigWithRetry(sn, 3);
                    if (resp.isOk() && resp.data != null) {
                        api.syncNavMaps(sn, NavMapHelper.loadMapSnapshot());
                        mainHandler.post(() -> applyRobotConfig(resp.data));
                    } else {
                        DebugLog.e("MainActivity", "getRobotConfig failed: " + resp.message
                                + " api=" + com.hr.digitalhuman.BuildConfig.API_BASE_URL);
                        mainHandler.post(() -> {
                            Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                            if (f instanceof InitFragment) {
                                ((InitFragment) f).setStatus("拉取配置失败: " + resp.message);
                            }
                        });
                        return;
                    }
                    mainHandler.post(() -> enterStandby());
                });
            }
        });
    }

    private ApiResponse<RobotConfig> fetchRobotConfigWithRetry(String sn, int maxAttempts) {
        ApiResponse<RobotConfig> last = ApiResponse.fail(50001, "未知错误");
        for (int i = 1; i <= maxAttempts; i++) {
            last = api.getRobotConfig(sn);
            if (last.isOk() && last.data != null) {
                return last;
            }
            if (i < maxAttempts) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last;
    }

    private void applyRobotConfig(RobotConfig config) {
        if (config == null) {
            return;
        }
        store.setConfig(config);
        fsm.setConfig(config);
        bridge.applyTtsConfig(config.tts);
        if (config.focusFollow != null) {
            // 远端旧配置可能过大；本地限制在近场，但给面前迎宾留足余量
            if (config.focusFollow.welcomeDistanceM <= 0 || config.focusFollow.welcomeDistanceM > 1.8f) {
                config.focusFollow.welcomeDistanceM = 1.5f;
            }
            if (config.focusFollow.detectDistanceM <= 0 || config.focusFollow.detectDistanceM > 1.8f) {
                config.focusFollow.detectDistanceM = 1.5f;
            }
            if (config.focusFollow.maxDistanceM <= 0 || config.focusFollow.maxDistanceM > 1.8f) {
                config.focusFollow.maxDistanceM = 1.5f;
            }
            if (config.focusFollow.welcomeMaxFaceAngleX <= 0) {
                config.focusFollow.welcomeMaxFaceAngleX = 45f;
            }
        }
        // 恢复/保持较宽的系统声源扇区，避免此前收窄导致硬件层几乎不收音
        float soundRange = (config.focusFollow != null && config.focusFollow.soundAngelRangeDeg > 0)
                ? config.focusFollow.soundAngelRangeDeg
                : 90f;
        bridge.applyFrontSoundGate(0f, soundRange);
        DisplayDensityHelper.apply(MainActivity.this, config.display);
        DisplayDensityHelper.apply(getApplicationContext(), config.display);
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (f instanceof StandbyFragment) {
            ((StandbyFragment) f).applyConfig(config);
        } else if (f instanceof HomeFragment) {
            ((HomeFragment) f).applyConfig(config);
        }
    }

    private void refreshRobotConfigQuietly() {
        String sn = store.getSn();
        if (sn == null || sn.isEmpty()) {
            return;
        }
        io.execute(() -> {
            ApiResponse<RobotConfig> resp = api.getRobotConfig(sn);
            if (resp.isOk() && resp.data != null) {
                mainHandler.post(() -> applyRobotConfig(resp.data));
            }
        });
    }

    private void startPresenceTracking() {
        presenceTracker.start(new PersonPresenceTracker.Listener() {
            @Override
            public void onPresenceChanged(boolean present) {
                ResumeCenterFragment resume = currentResumeCenter();
                if (resume != null && resume.isChatMode()) {
                    if (present && resume.isVoiceCapturing()) {
                        bridge.enableListening();
                    } else {
                        bridge.disableListening();
                    }
                    return;
                }
                // 面前无人：关麦。短宽限内先保持开麦，避免测距抖一下就把正在说的话掐掉。
                if (present || presenceTracker.isVoiceEligible()) {
                    bridge.enableListening();
                    if (!present) {
                        mainHandler.postDelayed(() -> {
                            if (!presenceTracker.isVoiceEligible()) {
                                bridge.disableListening();
                            }
                        }, 3200);
                    }
                } else {
                    bridge.disableListening();
                }
            }

            @Override
            public void onPersonLeftTimeout() {
                handlePersonLeftStandby();
            }

            @Override
            public void onPersonNearby(boolean nearby) {
                if (!nearby || fsm == null) {
                    return;
                }
                RobotState state = fsm.getState();
                if (state == RobotState.STANDBY || state == RobotState.INIT
                        || state == RobotState.NAVIGATING) {
                    return;
                }
                resetIdle();
            }
        });
    }

    /**
     * 人员离开只是回待机的其中一个条件，不能单独触发。
     * 还必须距离上次点击超过 20 秒。
     */
    private void handlePersonLeftStandby() {
        requestStandby("person_left");
    }

    private void stopPresenceTracking() {
        presenceTracker.stop();
    }

    /**
     * ASR 业务门禁：面前无人一律忽略；仅正前方近场（含短宽限）放行。
     * 思考中、播报中、简历采集未点「语音讲话」时同样忽略。
     */
    private boolean shouldProcessVoice() {
        if (app.isAgentPageVisible()) return false;
        if (store != null && store.isDemoMode()) {
            return false;
        }
        if (isThinkingPhase()) {
            return false;
        }
        if (speaking || (bridge != null && bridge.isTtsPlaying())) {
            return false;
        }
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null && resume.isChatMode() && !resume.isVoiceCapturing()) {
            return false;
        }
        return presenceTracker.shouldAcceptAsr();
    }

    /** 思考界面或状态机处于 THINKING：这段时间不接收语音。 */
    private boolean isThinkingPhase() {
        if (ignoreVoiceForThinking) {
            return true;
        }
        return fsm != null && fsm.getState() == RobotState.THINKING;
    }

    private void holdVoiceDuringThinking() {
        ignoreVoiceForThinking = true;
        if (bridge != null) {
            bridge.disableListening();
        }
    }

    private void releaseThinkingVoiceHold() {
        ignoreVoiceForThinking = false;
    }

    /**
     * 暂停聆听开关。关闭时关麦并清掉播报/思考占用；恢复时重启拾音管线。
     * 在场标记可能没变，不能只等 onPresenceChanged 再开麦。
     */
    public void onListenPauseChanged(boolean paused) {
        if (bridge == null) {
            return;
        }
        DebugLog.i("MainActivity", paused ? "listen paused" : "listen resumed");
        speaking = false;
        releaseThinkingVoiceHold();
        try {
            bridge.stopTts();
        } catch (Throwable ignored) {
        }
        if (paused) {
            if (agent != null) {
                try {
                    agent.interrupt("listen_pause");
                } catch (Throwable t) {
                    DebugLog.w("MainActivity", "interrupt on listen pause: " + t.getMessage());
                }
            }
            restoreHomeAfterListenToggle("listen_pause");
            bridge.pauseListeningFully();
            return;
        }
        restoreHomeAfterListenToggle("listen_resume");
        if (presenceTracker != null) {
            presenceTracker.refreshNow("listen_resume");
        }
        bridge.resumeListeningAfterPause();
    }

    private void restoreHomeAfterListenToggle(String reason) {
        if (fsm == null) {
            return;
        }
        RobotState state = fsm.getState();
        if (state == RobotState.LISTENING || state == RobotState.THINKING
                || state == RobotState.SPEAKING) {
            fsm.transition(RobotState.HOME, reason);
        }
    }

    /**
     * 简历采集场景下同步开/关麦：默认关；仅「语音讲话」且面前有人时开。
     */
    public void syncResumeListening() {
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null && resume.isChatMode()) {
            if (resume.isVoiceCapturing() && presenceTracker.isVoiceEligible()) {
                bridge.enableListening();
            } else {
                bridge.disableListening();
            }
            return;
        }
        if (presenceTracker.isVoiceEligible()) {
            bridge.enableListening();
        } else {
            bridge.disableListening();
        }
    }

    /**
     * 回待机唯一入口（启动时的首次待机除外）。
     * 1.5 米内有人脸或人形，或距离上次点击不足 20 秒，一律取消。
     * 播报/思考/迎宾不永久拦住：人已离开且点击已满 20 秒时停播并回待机。
     */
    private void requestStandby(String reason) {
        if (app.isAgentPageVisible()) return;
        RobotState state = fsm.getState();
        if (state == RobotState.STANDBY || state == RobotState.INIT || state == RobotState.SUSPENDED) {
            return;
        }
        boolean navigating = agent != null && agent.isNavigating();
        boolean personNear = presenceTracker != null && presenceTracker.blocksStandby();
        boolean clickOk = System.currentTimeMillis() - lastClickAt >= STANDBY_AFTER_CLICK_MS;
        if (navigating || personNear || !clickOk) {
            long now = System.currentTimeMillis();
            if (now - lastStandbyBlockLogAt > 5000) {
                lastStandbyBlockLogAt = now;
                DebugLog.i("MainActivity", "standby blocked (" + reason + "): personInFront="
                        + personNear + " sinceClickMs=" + (now - lastClickAt)
                        + " navigating=" + navigating);
            }
            if (personNear && presenceTracker != null
                    && presenceTracker.hasLivePersonBlockingStandby()) {
                presenceTracker.acknowledgeStillHere();
            }
            scheduleStandbyRecheck();
            return;
        }
        exitToStandby(reason);
    }

    /**
     * 真正回待机前再核一次两个条件，避免检测和点击刚好落在边界上。
     */
    private void exitToStandby(String reason) {
        if (!standbyConditionsMet()) {
            DebugLog.i("MainActivity", "standby cancelled (" + reason + ")");
            if (presenceTracker != null && presenceTracker.hasLivePersonBlockingStandby()) {
                presenceTracker.acknowledgeStillHere();
            }
            scheduleStandbyRecheck();
            return;
        }
        mainHandler.removeCallbacks(standbyRecheck);
        DebugLog.i("MainActivity", "enter standby (" + reason + ")");
        speaking = false;
        releaseThinkingVoiceHold();
        if (toolRuntime != null) {
            toolRuntime.cancelCaptionTicker();
        }
        try {
            bridge.stopTts();
        } catch (Throwable ignored) {
        }
        if (agent != null) {
            try {
                agent.interrupt(reason);
            } catch (Throwable t) {
                DebugLog.w("MainActivity", "interrupt on standby: " + t.getMessage());
            }
        }
        enterStandby();
    }

    public void enterStandby() {
        if (app.isAgentPageVisible()) {
            standbyPendingAfterAgent = true;
            return;
        }
        // 回待机时一律停播，避免离开后仍继续说话
        speaking = false;
        if (toolRuntime != null) {
            toolRuntime.cancelCaptionTicker();
        }
        try {
            bridge.stopTts();
        } catch (Throwable ignored) {
        }
        // 待机态清空登录：公共机器人下一位用户不应继承上一位账号
        if (store != null && store.isLoggedIn()) {
            logoutUser("enter_standby");
        }
        fsm.cancelIdleTimer();
        mainHandler.removeCallbacks(standbyRecheck);
        fsm.transition(RobotState.STANDBY, "enter_standby");
        showFragment(new StandbyFragment(), false);
        startPresenceTracking();
        bridge.notifyForeground();
        focusFollowController.resetWelcomeCycle();
        startFocusFollowMonitoring();
        refreshRobotConfigQuietly();
    }

    /**
     * 退出登录：清本地 token/会话，打断进行中的对话，刷新首页登录区。
     * @param reason 日志原因，如 home_button / enter_standby
     */
    public void logoutUser(String reason) {
        if (store == null || !store.isLoggedIn()) {
            return;
        }
        DebugLog.i("MainActivity", "logout user, reason=" + reason);
        releaseThinkingVoiceHold();
        store.logout();
        if (agent != null) {
            try {
                agent.interrupt(reason == null ? "logout" : reason);
            } catch (Throwable t) {
                DebugLog.w("MainActivity", "interrupt on logout: " + t.getMessage());
            }
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.refreshLoginUser();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bridge.notifyForeground();
        scheduleStandbyRecheck();
        if (fsm.getState() == RobotState.STANDBY) {
            startFocusFollowMonitoring();
        }
    }

    private void startFocusFollowMonitoring() {
        if (app.isAgentPageVisible()) return;
        focusFollowController.start(new com.hr.digitalhuman.robot.FocusFollowController.Callback() {
            @Override
            public void onFollowingChanged(boolean following) {
                Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (f instanceof StandbyFragment) {
                    ((StandbyFragment) f).setFollowing(following);
                }
            }

            @Override
            public void onPersonReadyForWelcome(String personId) {
                triggerWelcome(personId);
            }

            @Override
            public boolean isChassisBusy() {
                return isNavigating();
            }
        });
    }

    private void stopFocusFollowMonitoring() {
        focusFollowController.stop();
    }

    public void triggerWelcome(String personId) {
        if (app.isAgentPageVisible()) return;
        if (!fsm.canAcceptPersonApproach()) {
            DebugLog.w("MainActivity", "welcome blocked: state=" + fsm.getState());
            return;
        }
        if (fsm.isBusyForWelcome()) {
            DebugLog.w("MainActivity", "welcome blocked: busy state=" + fsm.getState());
            return;
        }
        if (!fsm.allowWelcome(personId)) {
            DebugLog.w("MainActivity", "welcome blocked: debounce personId=" + personId);
            return;
        }
        fsm.markWelcome(personId);
        fsm.transition(RobotState.WELCOME, "person_approach");
        speaking = true;
        showFragment(new WelcomeFragment(), false);
    }

    /** 迎宾 TTS 播报态：离开超时逻辑勿打断 */
    public void markWelcomeSpeaking(boolean value) {
        speaking = value;
    }

    private void resumeFocusFollowIfStandby() {
        if (fsm.getState() == RobotState.STANDBY) {
            startFocusFollowMonitoring();
        }
    }

    public void onWelcomeComplete() {
        io.execute(() -> {
            ApiResponse<ChatSession> resp = api.createSession(store.getSn(), "welcome_complete");
            if (resp.isOk() && resp.data != null) {
                store.setSessionId(resp.data.sessionId);
            }
            mainHandler.post(() -> {
                if (!app.isAgentPageVisible()) enterHome();
            });
        });
    }

    public void enterHome() {
        fsm.transition(RobotState.HOME, "enter_home");
        homeFragment = new HomeFragment();
        showFragment(homeFragment, false);
        startPresenceTracking();
        bridge.enableListening();
        focusFollowController.releaseWelcomeSpeechHold();
        startFocusFollowMonitoring();
        resetIdle();
        scheduleStandbyRecheck();
    }

    public void resetIdle() {
        fsm.resetIdleTimer(this::onSessionIdle);
    }

    /** 配置里的空闲计时到点，仍要同时满足「1.5 米无人」和「距上次点击超过 20 秒」。 */
    private void onSessionIdle() {
        requestStandby("idle");
    }

    public void onUserInteraction() {
        if (agent != null && agent.isNavigating()) {
            return;
        }
        resetIdle();
    }

    private void handleAsrPartial(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (!shouldProcessVoice()) {
            return;
        }
        if (fsm.getState() == RobotState.HOME) {
            fsm.transition(RobotState.LISTENING, "asr_partial");
        }
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null) {
            // 采集中未点「语音讲话」：忽略环境音，不切首页
            if (resume.isChatMode()) {
                if (resume.isVoiceCapturing()) {
                    resume.setVoicePreview(text, false);
                }
                return;
            }
            return;
        }
        ensureHomeForVoice(() -> {
            if (homeFragment != null && homeFragment.isAdded()) {
                homeFragment.setInputPreview(text);
            }
        });
    }

    private void onAsrText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (!shouldProcessVoice()) {
            DebugLog.w("MainActivity", (isThinkingPhase() ? "ASR ignored: thinking text=" : "ASR ignored: not near-field text=") + text);
            return;
        }
        ResumeCenterFragment resumeCenter = currentResumeCenter();
        // 简历采集：默认不接收语音；仅「语音讲话」中写入输入框，不自动发送
        if (resumeCenter != null && resumeCenter.isChatMode()) {
            if (resumeCenter.isVoiceCapturing()) {
                resumeCenter.setVoicePreview(text, true);
                onUserInteraction();
            }
            return;
        }
        onUserInteraction();
        if (agent != null && agent.isNavigating()) {
            if (homeFragment != null && homeFragment.isAdded()) {
                homeFragment.setInputPreview(text);
            }
            agent.enqueueQuestion(text);
            speakLocal("我正在带路，到了再为您解答。");
            return;
        }
        if (isReplyBusy()) {
            interruptSpeaking("barge_in");
            if (agent != null) {
                agent.interrupt("barge_in");
            }
        }
        RobotState state = fsm.getState();
        if (!fsm.canAcceptUserInput() && state != RobotState.HOME) {
            if (state == RobotState.STANDBY || state == RobotState.WELCOME) {
                enterHome();
            } else if (currentResumeCenter() == null) {
                DebugLog.w("MainActivity", "ASR dropped in state=" + state + " text=" + text);
                if (homeFragment != null && homeFragment.isAdded()) {
                    homeFragment.setInputPreview(text);
                }
                return;
            }
        }
        if (resumeCenter != null) {
            // 工作台语音仍走首页对话链路（非采集）
            ensureHomeForVoice(() -> {
                if (homeFragment != null && homeFragment.isAdded()) {
                    homeFragment.fillInputAndSend(text, "voice");
                } else {
                    sendChat(text, "voice");
                }
            });
            return;
        }
        ensureHomeForVoice(() -> {
            if (homeFragment != null && homeFragment.isAdded()) {
                homeFragment.fillInputAndSend(text, "voice");
            } else {
                sendChat(text, "voice");
            }
        });
    }

    private boolean isReplyBusy() {
        RobotState state = fsm.getState();
        return state == RobotState.THINKING || state == RobotState.SPEAKING || speaking
                || (agent != null && agent.isBusy());
    }

    private void interruptSpeaking(String reason) {
        boolean ttsOn = bridge != null && bridge.isTtsPlaying();
        if (!speaking && fsm.getState() != RobotState.SPEAKING && !ttsOn) {
            return;
        }
        speaking = false;
        if (toolRuntime != null) {
            toolRuntime.cancelCaptionTicker();
        }
        if (bridge != null) {
            bridge.stopTts();
        }
        if (fsm.getState() == RobotState.SPEAKING) {
            fsm.transition(RobotState.HOME, reason);
        }
    }

    private void ensureHomeForVoice(Runnable action) {
        if (homeFragment != null && homeFragment.isAdded()) {
            action.run();
            return;
        }
        RobotState state = fsm.getState();
        if (state == RobotState.STANDBY || state == RobotState.HOME
                || state == RobotState.LISTENING || state == RobotState.WELCOME) {
            if (homeFragment == null || !homeFragment.isAdded()) {
                enterHome();
            }
            retryHomeForVoice(action, 0);
            return;
        }
        action.run();
    }

    private void retryHomeForVoice(Runnable action, int attempt) {
        if (homeFragment != null && homeFragment.isAdded()) {
            action.run();
            return;
        }
        if (attempt >= 10) {
            DebugLog.w("MainActivity", "home fragment not ready, send without preview");
            action.run();
            return;
        }
        mainHandler.postDelayed(() -> retryHomeForVoice(action, attempt + 1), 80);
    }

    public void sendChat(String content, String inputSource) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        onUserInteraction();
        if (agent != null && agent.isNavigating()) {
            agent.enqueueQuestion(content);
            speakLocal("我正在带路，到了再为您解答。");
            return;
        }
        interruptSpeaking("new_chat");
        if (agent != null) {
            agent.interrupt("new_chat");
        }
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null && resume.isChatMode()) {
            // 采集作答改由简历中心直连状态机，避免 LLM 规划失败
            resume.submitAnswerFromHost(content);
            return;
        }
        if (resume != null) {
            // 工作台非采集：仍走规划（如语音说做简历）
            fsm.transition(RobotState.THINKING, "send_chat_resume");
            bridge.disableListening();
            agent.sendUserMessage(content);
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.appendUserMessage(content);
        } else {
            enterHome();
            mainHandler.postDelayed(() -> {
                if (homeFragment != null) {
                    homeFragment.appendUserMessage(content);
                }
            }, 200);
        }
        fsm.transition(RobotState.THINKING, "send_chat");
        bridge.disableListening();
        agent.sendUserMessage(content);
    }

    private ResumeCenterFragment currentResumeCenter() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (f instanceof ResumeCenterFragment) {
            resumeCenterFragment = (ResumeCenterFragment) f;
            return resumeCenterFragment;
        }
        return null;
    }

    private void speakLocal(String text) {
        if (app.isAgentPageVisible()) return;
        bridge.playTts(text, null);
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null) {
            resume.showSpeech(text);
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.showCaption(com.hr.digitalhuman.ui.display.CaptionBarView.splitSentences(text), true);
        }
    }

    // ---------- AgentSession.Listener ----------

    @Override
    public void onThinking() {
        holdVoiceDuringThinking();
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null) {
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.showThinking();
        }
    }

    @Override
    public void onStatus(String text, int pulseMode) {
        if (currentResumeCenter() != null) {
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.setAiStatus(text, pulseMode);
        }
    }

    @Override
    public void onTurnFinished() {
        speaking = false;
        releaseThinkingVoiceHold();
        if (fsm.getState() == RobotState.THINKING || fsm.getState() == RobotState.SPEAKING) {
            fsm.transition(RobotState.HOME, "agent_done");
        }
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null && resume.isChatMode()) {
            // 采集中不自动开麦，避免语音干扰
            syncResumeListening();
            resume.refreshProgressAsync();
            resetIdle();
            return;
        }
        bridge.enableListening();
        if (resume != null) {
            resume.refreshProgressAsync();
            resetIdle();
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.onAgentIdle();
        }
        resetIdle();
    }

    @Override
    public void onError(String message) {
        releaseThinkingVoiceHold();
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null) {
            resume.showSpeech(message == null ? "服务暂时不可用" : message);
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.showError(message == null ? "服务暂时不可用" : message);
        }
    }

    @Override
    public void onFallbackSpeak(String text) {
        agent.speakFallback(text, new RobotToolRuntime.SpeakCallback() {
            @Override
            public void onComplete() {
                onTurnFinished();
            }

            @Override
            public void onError(String msg) {
                onTurnFinished();
            }
        });
    }

    // ---------- RobotToolRuntime.Host ----------

    @Override
    public boolean isNavigating() {
        return agent != null && agent.isNavigating();
    }

    @Override
    public void speak(String text, float rate, boolean interrupt, RobotToolRuntime.SpeakCallback callback) {
        if (app.isAgentPageVisible()) {
            if (callback != null) callback.onError("智能体页面使用中，机器人播报已暂停");
            return;
        }
        if (interrupt) {
            interruptSpeaking("tool_interrupt");
        }
        speaking = true;
        userStoppedCaption = false;
        fsm.transition(RobotState.SPEAKING, "tts_start");
        bridge.disableListening();
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.onSpeakStarted();
        }
        final int gen = agent == null ? 0 : agent.currentGeneration();
        bridge.playTts(text, new RobotSdkBridge.TtsCallback() {
            @Override
            public void onComplete() {
                if (agent != null && callback != null && gen != agent.currentGeneration()) {
                    return;
                }
                speaking = false;
                if (callback != null) {
                    callback.onComplete();
                } else {
                    if (fsm.getState() == RobotState.SPEAKING) {
                        fsm.transition(RobotState.HOME, "tts_complete");
                    }
                    syncListeningAfterTts();
                }
            }

            @Override
            public void onError(String msg) {
                if (agent != null && callback != null && gen != agent.currentGeneration()) {
                    return;
                }
                speaking = false;
                if (callback != null) {
                    callback.onError(msg);
                } else {
                    fsm.transition(RobotState.HOME, "tts_error");
                    syncListeningAfterTts();
                }
            }
        });
    }

    private void syncListeningAfterTts() {
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null && resume.isChatMode()) {
            syncResumeListening();
            return;
        }
        bridge.enableListening();
    }

    @Override
    public void stopSpeak() {
        interruptSpeaking("stop_speak");
    }

    @Override
    public void showCaption(List<String> sentences, boolean visible) {
        // 简历中心用表单/输入框展示问题，避免字幕渲染冲掉 FORM
        if (currentResumeCenter() != null) {
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.showCaption(sentences, visible);
        }
    }

    @Override
    public void highlightCaption(int index) {
        if (currentResumeCenter() != null) {
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.highlightCaption(index);
        }
    }

    @Override
    public void finishCaption() {
        if (currentResumeCenter() != null) {
            return;
        }
        if (userStoppedCaption) {
            userStoppedCaption = false;
            if (homeFragment != null && homeFragment.isAdded()) {
                homeFragment.onSpeakStopped();
            }
            return;
        }
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.finishCaption();
        }
    }

    @Override
    public void renderDisplay(List<DisplaySection> sections, String footnote) {
        ResumeCenterFragment resume = currentResumeCenter();
        if (resume != null) {
            resume.renderDisplay(sections, footnote);
            return;
        }
        ensureHomeForVoice(() -> {
            if (homeFragment != null && homeFragment.isAdded()) {
                homeFragment.renderDisplay(sections, footnote);
            }
        });
    }

    @Override
    public void applyExpression(String expression) {
        showFaceExpression(expression);
    }

    @Override
    public void startNavigation(String mapId, String poiId, String placeName, String target) {
        String name = target != null && !target.isEmpty() ? target : placeName;
        pendingNavTarget = name;
        navFinished = false;
        if (agent != null) {
            agent.setNavigating(true);
        }
        stopFocusFollowMonitoring();
        fsm.cancelIdleTimer();
        fsm.transition(RobotState.NAVIGATING, "nav_start");
        showFragment(NavigatingFragment.newInstance(name, placeName), false);
        bridge.startNavigation(placeName, null, new RobotSdkBridge.NavListener() {
            @Override
            public void onArrived() {
                finishNavigation(true, "已到达：" + pendingNavTarget);
            }

            @Override
            public void onStopped() {
                finishNavigation(false, "已取消导航");
            }

            @Override
            public void onFailed(int code, String msg) {
                finishNavigation(false, navFailText(msg));
            }

            @Override
            public void onStatus(String hint) {
                Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (f instanceof NavigatingFragment) {
                    ((NavigatingFragment) f).setNavStatus(hint);
                }
            }
        });
    }

    @Override
    public void cancelNavigation() {
        navFinished = true;
        if (agent != null) {
            agent.setNavigating(false);
        }
        bridge.stopNavigation();
        if (fsm.getState() == RobotState.NAVIGATING) {
            fsm.transition(RobotState.HOME, "nav_cancel");
        }
        goHomeClearBackStack();
    }

    @Override
    public void openPage(String page, com.google.gson.JsonObject params) {
        if (page == null || page.isEmpty()) {
            return;
        }
        onUserInteraction();
        mainHandler.post(() -> {
            if (app.isAgentPageVisible()) return;
            switch (page) {
                case "login":
                case "open_login":
                case "login_required":
                    // 主动登录默认回首页；业务入口应先 setPending 再 openLogin
                    openLogin();
                    break;
                case "open_history":
                case "history":
                    openHistory();
                    break;
                case "resume_center":
                case "open_resume":
                case "resume":
                case "open_resume_center":
                    openResumeCenter();
                    break;
                case "home":
                    goHomeClearBackStack();
                    break;
                default:
                    DebugLog.w("MainActivity", "robot_open_page 暂未实现: " + page
                            + (params != null ? " params=" + params : ""));
                    break;
            }
        });
    }

    public void onUserCancelNavigation() {
        cancelNavigation();
        mainHandler.postDelayed(() -> {
            if (agent != null) {
                agent.onNavigationFinished(false, "已取消导航");
            }
        }, 300);
    }

    private void finishNavigation(boolean arrived, String message) {
        if (navFinished) {
            return;
        }
        navFinished = true;
        if (agent != null) {
            agent.setNavigating(false);
        }
        fsm.transition(RobotState.HOME, arrived ? "nav_arrived" : "nav_end");
        goHomeClearBackStack();
        mainHandler.postDelayed(() -> {
            if (agent != null) {
                agent.onNavigationFinished(arrived, message);
            }
        }, 300);
    }

    public RobotSdkBridge getRobotBridge() {
        return bridge;
    }

    public void showFaceExpression(String expression) {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (f instanceof StandbyFragment) {
            ((StandbyFragment) f).applyFaceExpression(expression);
        } else if (f instanceof WelcomeFragment) {
            ((WelcomeFragment) f).applyFaceExpression(expression);
        }
    }

    private static String navFailText(String msg) {
        if (msg == null) {
            return "导航未能完成，请稍后再试";
        }
        if (msg.contains("not_estimated")) {
            return "机器人还没定位，请先在地图工具完成定位后再带路";
        }
        if (msg.contains("map_mismatch")) {
            return "目标不在当前地图。请先切换到对应地图并重新定位";
        }
        if (msg.contains("place_not_on_map")) {
            return "当前地图没有这个点位，暂时不能带路";
        }
        if (msg.contains("place_blocked")) {
            return "该点位当前不可到达（禁行或在地图外）";
        }
        if (msg.contains("chassis")) {
            return "底盘未就绪，暂时不能带路";
        }
        return "导航未能完成，请稍后再试";
    }

    public void openRobotAdmin() {
        onUserInteraction();
        showFragment(new RobotAdminFragment(), true);
    }

    public void stopSpeakingFromUser() {
        DebugLog.i("MainActivity", "user stop speak");
        userStoppedCaption = true;
        speaking = false;
        releaseThinkingVoiceHold();
        if (toolRuntime != null) {
            toolRuntime.cancelCaptionTicker();
        }
        try {
            if (bridge != null) {
                bridge.stopTts();
            }
        } catch (Throwable ignored) {
        }
        restoreHomeAfterListenToggle("user_stop_tts");
        if (homeFragment != null && homeFragment.isAdded()) {
            homeFragment.onSpeakStopped();
        }
        if (agent != null) {
            try {
                agent.interrupt("user_stop_tts");
            } catch (Throwable t) {
                DebugLog.w("MainActivity", "stop tts interrupt: " + t.getMessage());
            }
        }
        updateStopSpeakButton(false);
        // 播报中会关麦；这台机器只 setRecognizable(true) 不会重新收音，必须重启拾音管线
        if (bridge != null) {
            bridge.resumeListeningAfterPause();
        }
    }

    private void updateStopSpeakButton(boolean playing) {
        Fragment face = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (face instanceof StandbyFragment || face instanceof WelcomeFragment) {
            return;
        }
        TextView btn = findViewById(R.id.btn_stop_speak);
        if (btn == null) {
            return;
        }
        if (!playing) {
            btn.setVisibility(android.view.View.GONE);
            return;
        }
        btn.setOnClickListener(v -> stopSpeakingFromUser());
        btn.setVisibility(android.view.View.VISIBLE);
    }

    public void openHistory() {
        onUserInteraction();
        if (!store.isLoggedIn()) {
            store.setPendingAfterLogin(SessionStore.AFTER_LOGIN_HISTORY);
            openLogin();
            return;
        }
        showFragment(HistoryFragment.newInstance(), true);
    }

    public void openLogin() {
        onUserInteraction();
        showFragment(LoginFragment.newInstance(), true);
    }

    /**
     * 打开简历中心；未登录则标记登录成功后进入简历中心。
     * 进入前必须打断首页播报与规划，避免仍在念首页内容。
     */
    public void openResumeCenter() {
        onUserInteraction();
        abortHomeSpeechAndAgent("open_resume_center");
        if (!store.isLoggedIn()) {
            store.setPendingAfterLogin(SessionStore.AFTER_LOGIN_RESUME);
            showFragment(LoginFragment.newInstance(), true);
            return;
        }
        ResumeCenterFragment f = ResumeCenterFragment.newInstance();
        resumeCenterFragment = f;
        showFragment(f, true);
    }

    /** 通用智能体页面不使用机器人语音，复用现有业务中断流程。 */
    public void prepareForAgentPage() {
        agentPagePaused = true;
        // 新页面遮挡导航取消按钮前，先停止底盘；中断工具链本身并不会停止导航。
        if (isNavigating()) cancelNavigation();
        abortHomeSpeechAndAgent("open_agent_page");
        bridge.disableListening();
        mainHandler.removeCallbacks(standbyRecheck);
        stopFocusFollowMonitoring();
    }

    public void restoreAfterAgentPage() {
        if (!agentPagePaused || app.isAgentPageVisible()) return;
        agentPagePaused = false;
        if (standbyPendingAfterAgent) {
            standbyPendingAfterAgent = false;
            enterStandby();
        }
        if (fsm.getState() == RobotState.WELCOME) enterHome();
        if (fsm.getState() == RobotState.LISTENING) {
            fsm.transition(RobotState.HOME, "return_from_agent_page");
        }
        noteClick();
        if (fsm.getState() == RobotState.STANDBY || fsm.getState() == RobotState.HOME) {
            startFocusFollowMonitoring();
        }
        bridge.resumeListeningAfterPause();
    }

    /** 离开首页业务页时：停 TTS、取消字幕、打断 Agent 多步工具链 */
    private void abortHomeSpeechAndAgent(String reason) {
        speaking = false;
        releaseThinkingVoiceHold();
        if (toolRuntime != null) {
            toolRuntime.cancelCaptionTicker();
        }
        if (agent != null) {
            try {
                agent.interrupt(reason);
            } catch (Throwable t) {
                DebugLog.w("MainActivity", "abort interrupt failed: " + t.getMessage());
            }
        }
        bridge.stopTts();
        RobotState state = fsm.getState();
        if (state == RobotState.SPEAKING || state == RobotState.THINKING) {
            fsm.transition(RobotState.HOME, reason);
        }
    }

    /** 登录成功后的分流：简历 → 简历中心；历史 → 历史；其余 → 首页 */
    public void onLoginSuccessNavigate() {
        String target = store.consumePendingAfterLogin();
        if (SessionStore.AFTER_LOGIN_RESUME.equals(target)) {
            // 清掉登录页再进中心
            getSupportFragmentManager().popBackStack();
            openResumeCenter();
            return;
        }
        if (SessionStore.AFTER_LOGIN_HISTORY.equals(target)) {
            getSupportFragmentManager().popBackStack();
            openHistory();
            return;
        }
        goHomeClearBackStack();
        if (homeFragment != null) {
            homeFragment.refreshLoginUser();
        }
    }

    /** 简历中心开场/提示播报（先停掉可能残留的首页 TTS） */
    public void speakResumeHint(String text) {
        if (app.isAgentPageVisible()) return;
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        bridge.stopTts();
        bridge.playTts(text, null);
    }

    public void goHomeClearBackStack() {
        getSupportFragmentManager().popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        enterHome();
    }

    private void showFragment(Fragment fragment, boolean addToBackStack) {
        SoftImeController.get().hide();
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(0, 0)
                .replace(R.id.fragment_container, fragment);
        if (addToBackStack) {
            tx.addToBackStack(fragment.getClass().getSimpleName());
        }
        tx.commitAllowingStateLoss();
        if (fragment instanceof HomeFragment) {
            homeFragment = (HomeFragment) fragment;
        }
        if (fragment instanceof ResumeCenterFragment) {
            resumeCenterFragment = (ResumeCenterFragment) fragment;
        }
        mainHandler.post(() -> updateStopSpeakButton(bridge != null && bridge.isTtsPlaying()));
    }

    @Override
    public void onBackPressed() {
        if (SoftImeController.get().onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        SoftImeController.get().detach();
        DebugLog.flush();
        stopFocusFollowMonitoring();
        stopPresenceTracking();
        io.shutdownNow();
        fsm.cancelIdleTimer();
        super.onDestroy();
    }
}
