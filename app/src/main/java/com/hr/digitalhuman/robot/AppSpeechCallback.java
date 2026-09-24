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
        // 0正常 1other 2噪音 3超时 4强制取消 5未经过NLU提前结束；status=4 为打断/取消，无需记录
    }

    @Override
    public void onQueryAsrResult(String asrResult) throws RemoteException {
        if (asrResult != null && !asrResult.trim().isEmpty()) {
            DigitalHumanApp.getInstance().getRobotBridge().dispatchAsr(asrResult.trim(), "asr");
        }
    }

    @Override
    public void onError(String type, int code, String message) throws RemoteException {
        DebugLog.w(TAG, "onError type=" + type + " code=" + code + " msg=" + message);
    }
}
