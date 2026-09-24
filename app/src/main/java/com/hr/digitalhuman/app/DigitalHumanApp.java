package com.hr.digitalhuman.app;

import android.app.Application;
import android.content.Context;

import com.hr.digitalhuman.api.ApiService;
import com.hr.digitalhuman.api.HttpApiService;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.debug.RemoteLogSink;
import com.hr.digitalhuman.robot.AppModuleCallback;
import com.hr.digitalhuman.robot.AppSpeechCallback;
import com.hr.digitalhuman.robot.RobotSdkBridge;
import com.hr.digitalhuman.state.RobotStateMachine;
import com.hr.digitalhuman.ui.DisplayDensityHelper;

public class DigitalHumanApp extends Application {

    private static final String TAG = "DigitalHumanApp";
    private static DigitalHumanApp instance;

    private SessionStore sessionStore;
    private RobotStateMachine stateMachine;
    private RobotSdkBridge robotBridge;
    private AppModuleCallback moduleCallback;
    private AppSpeechCallback speechCallback;
    private ApiService apiService;
    private RemoteLogSink remoteLogSink;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        DisplayDensityHelper.apply(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DisplayDensityHelper.apply(this);
        sessionStore = new SessionStore(this);
        stateMachine = new RobotStateMachine();
        robotBridge = new RobotSdkBridge(this);
        moduleCallback = new AppModuleCallback();
        speechCallback = new AppSpeechCallback();
        apiService = new HttpApiService();
        remoteLogSink = new RemoteLogSink(apiService, () -> {
            if (sessionStore == null) {
                return null;
            }
            return sessionStore.getSn();
        });
        DebugLog.setSink(remoteLogSink);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            DebugLog.e(TAG, "Uncaught in " + t.getName(), e);
            if (previous != null) {
                previous.uncaughtException(t, e);
            }
        });
    }

    public static DigitalHumanApp getInstance() {
        return instance;
    }

    public SessionStore getSessionStore() {
        return sessionStore;
    }

    public RobotStateMachine getStateMachine() {
        return stateMachine;
    }

    public RobotSdkBridge getRobotBridge() {
        return robotBridge;
    }

    public AppModuleCallback getModuleCallback() {
        return moduleCallback;
    }

    public AppSpeechCallback getSpeechCallback() {
        return speechCallback;
    }

    public ApiService getApiService() {
        return apiService;
    }

    public String resolveMediaUrl(String pathOrUrl) {
        if (apiService instanceof HttpApiService) {
            return ((HttpApiService) apiService).resolveMediaUrl(pathOrUrl);
        }
        return pathOrUrl;
    }

    @Override
    public void onTerminate() {
        DebugLog.flush();
        super.onTerminate();
    }
}
