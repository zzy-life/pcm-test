package com.hr.digitalhuman.robot;

import android.os.RemoteException;
import android.text.TextUtils;

import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.module.ModuleCallbackApi;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.debug.DebugLog;

import org.json.JSONObject;

/**
 * 对齐官方文档：NLP / 唤醒走 ModuleCallback.onSendRequest。
 * 面前无人时丢弃语音；明显侧后方声源亦忽略。
 */
public class AppModuleCallback extends ModuleCallbackApi {

    private static final String TAG = "AppModuleCallback";

    @Override
    public boolean onSendRequest(int reqId, String reqType, String reqText, String reqParam)
            throws RemoteException {
        // return true 表示 App 已消费该请求，不交给系统默认技能处理
        PersonPresenceTracker presence = DigitalHumanApp.getInstance()
                .getRobotBridge().getPresenceTracker();
        Double soundAngle = parseSoundAngle(reqType, reqParam);
        if (soundAngle != null) {
            if (presence != null && !presence.shouldAcceptSoundAngle(soundAngle)) {
                DebugLog.i(TAG, "ignore side sound type=" + reqType
                        + " angle=" + soundAngle + " text=" + reqText);
                return true;
            }
        }

        if (Definition.REQ_SPEECH_WAKEUP.equals(reqType)) {
            DebugLog.i(TAG, "wakeup angle=" + soundAngle);
            return true;
        }

        if (reqText != null && !reqText.trim().isEmpty()) {
            if (presence != null && !presence.shouldAcceptAsr()) {
                DebugLog.i(TAG, "ignore NLP: no person in front text=" + reqText);
                return true;
            }
            DigitalHumanApp.getInstance().getRobotBridge().dispatchAsr(reqText.trim(), "nlp");
        }
        return true;
    }

    @Override
    public void onHWReport(int function, String type, String message) throws RemoteException {
    }

    @Override
    public void onSuspend() throws RemoteException {
        DigitalHumanApp.getInstance().getStateMachine().onSuspend();
    }

    @Override
    public void onRecovery() throws RemoteException {
        DigitalHumanApp.getInstance().getStateMachine().onRecovery();
        DigitalHumanApp.getInstance().getRobotBridge().enableListening();
    }

    private static Double parseSoundAngle(String reqType, String reqParam) {
        if (TextUtils.isEmpty(reqParam)) {
            return null;
        }
        String raw = reqParam.trim();
        if (Definition.REQ_SPEECH_WAKEUP.equals(reqType)) {
            Double d = tryParseDouble(raw);
            if (d != null) {
                return d;
            }
        }
        try {
            JSONObject json = new JSONObject(raw);
            if (json.has("soundAngle")) {
                return json.optDouble("soundAngle");
            }
            if (json.has("angle")) {
                return json.optDouble("angle");
            }
            if (json.has(Definition.JSON_NAVI_ANGLE)) {
                return json.optDouble(Definition.JSON_NAVI_ANGLE);
            }
        } catch (Throwable ignored) {
        }
        return tryParseDouble(raw);
    }

    private static Double tryParseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
