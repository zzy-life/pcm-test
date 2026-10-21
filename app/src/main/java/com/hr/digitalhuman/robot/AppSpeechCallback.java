package com.hr.digitalhuman.robot;

import android.os.RemoteException;

import com.ainirobot.coreservice.client.speech.SkillCallback;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.debug.DebugLog;

/**
 * 对齐官方 Demo / 文档：
 * ASR 临时结果 → onSpeechParResult
 * ASR 最终结果 → onQueryAsrResult
 * 不要在 onStop/onQueryEnded 里反复 setRecognizable，以免打断本轮识别。
 */
public class AppSpeechCallback extends SkillCallback {

    private static final String TAG = "AppSpeechCallback";

    @Override
    public void onSpeechParResult(String s) throws RemoteException {
        if (s != null && !s.isEmpty()) {
            DigitalHumanApp.getInstance().getRobotBridge().dispatchAsrPartial(s);
        }
    }

    @Override
    public void onStart() throws RemoteException {
    }

    @Override
    public void onStop() throws RemoteException {
        // 与 Demo 一致：不在此重新 enableListening
    }

    @Override
    public void onVolumeChange(int i) throws RemoteException {
    }

    @Override
    public void onQueryEnded(int status) throws RemoteException {
        // 持续拾音的单轮结束不等于临时采集结束；重启管线也会产生取消事件。
        // 不在这里关麦或重开，临时采集由 Bridge 的无文本超时统一收尾。
        DigitalHumanApp.getInstance().getRobotBridge().logVoiceCaptureQueryEnded(status);
    }

    @Override
    public void onQueryAsrResult(String asrResult) throws RemoteException {
        if (asrResult != null && !asrResult.trim().isEmpty()) {
            DigitalHumanApp.getInstance().getRobotBridge().dispatchAsr(asrResult.trim(), "asr");
        }
    }

    @Override
    public void onError(String type, int code, String message) throws RemoteException {
        DebugLog.w(TAG, "onError type=" + type + " code=" + code);
        // 全局错误不带请求代次，可能属于刚停止的旧识别，不能据此关闭新采集。
        // 若当前采集确实失效，由 Bridge 的无文本超时释放占用并通知页面。
    }
}
