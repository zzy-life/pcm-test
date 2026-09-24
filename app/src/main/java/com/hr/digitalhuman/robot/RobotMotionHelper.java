package com.hr.digitalhuman.robot;

import android.os.Handler;
import android.os.Looper;

import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.ActionListener;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.hr.digitalhuman.debug.DebugLog;

/**
 * 对齐当前工程 robotservice.jar：
 * moveHead / resetHead / startPlayAction / stopPlayAction。
 * 部分 ROM 另有 playAction(name) / startDance，按名称反射调用。
 */
public final class RobotMotionHelper {

    private static final String TAG = "RobotMotion";
    private static final int REQ_ACTION = 2101;
    private static final int REQ_HEAD = 2102;
    private static final int REQ_DANCE = 2103;

    private static final Handler main = new Handler(Looper.getMainLooper());

    private RobotMotionHelper() {
    }

    public static void greeting() {
        if (playNamedAction("迎宾") || playNamedAction("hello")) {
            return;
        }
        moveHead("relative", "relative", 0, 16);
        main.postDelayed(RobotMotionHelper::resetHead, 800);
    }

    public static void nod() {
        if (playNamedAction("点头")) {
            return;
        }
        moveHead("relative", "relative", 0, 18);
        main.postDelayed(RobotMotionHelper::resetHead, 700);
    }

    public static void smile() {
        if (playNamedAction("高兴")) {
            return;
        }
        moveHead("relative", "relative", 0, 10);
        main.postDelayed(RobotMotionHelper::resetHead, 500);
    }

    public static void dance() {
        if (invokeInt("startDance", REQ_DANCE) || playNamedAction("跳舞")) {
            DebugLog.i(TAG, "startDance");
            return;
        }
        moveHead("relative", "relative", 25, 0);
        main.postDelayed(() -> {
            moveHead("relative", "relative", -50, 0);
            main.postDelayed(() -> {
                moveHead("relative", "relative", 25, 0);
                main.postDelayed(RobotMotionHelper::resetHead, 400);
            }, 400);
        }, 400);
    }

    public static void stopMotion() {
        invokeInt("stopDance", REQ_DANCE);
        invokeInt("stopAction", REQ_ACTION);
        try {
            RobotApi.getInstance().stopPlayAction(REQ_ACTION);
        } catch (Throwable t) {
            DebugLog.w(TAG, "stopPlayAction: " + t.getMessage());
        }
        try {
            RobotApi.getInstance().stopAllAction(REQ_ACTION);
        } catch (Throwable t) {
            DebugLog.w(TAG, "stopAllAction: " + t.getMessage());
        }
        resetHead();
    }

    /**
     * 新文档接口 playAction(reqId, actionName, ActionListener) 本 jar 未导出，反射调用。
     */
    private static boolean playNamedAction(String action) {
        try {
            java.lang.reflect.Method m = RobotApi.getInstance().getClass()
                    .getMethod("playAction", int.class, String.class, ActionListener.class);
            m.invoke(RobotApi.getInstance(), REQ_ACTION, action, new ActionListener() {
                @Override
                public void onResult(int status, String responseString) throws android.os.RemoteException {
                    DebugLog.i(TAG, "playAction " + action + " result=" + status + " " + responseString);
                }

                @Override
                public void onError(int errorCode, String errorString) throws android.os.RemoteException {
                    DebugLog.w(TAG, "playAction " + action + " error=" + errorCode + " " + errorString);
                }
            });
            DebugLog.i(TAG, "playAction " + action);
            return true;
        } catch (Throwable t) {
            DebugLog.w(TAG, "playAction " + action + " unavailable: " + t.getMessage());
            return false;
        }
    }

    /**
     * 官方：moveHead(reqId, hMode, vMode, hAngle, vAngle, CommandListener)
     * hMode/vMode: absolute | relative
     * hAngle: -120~120，vAngle: 0~90
     */
    private static void moveHead(String hMode, String vMode, int hAngle, int vAngle) {
        try {
            RobotApi.getInstance().moveHead(REQ_HEAD, hMode, vMode, hAngle, vAngle, headListener("moveHead"));
            DebugLog.i(TAG, "moveHead " + hMode + "/" + vMode + " h=" + hAngle + " v=" + vAngle);
        } catch (Throwable t) {
            DebugLog.w(TAG, "moveHead failed: " + t.getMessage());
        }
    }

    private static void resetHead() {
        try {
            RobotApi.getInstance().resetHead(REQ_HEAD, headListener("resetHead"));
        } catch (Throwable t) {
            DebugLog.w(TAG, "resetHead failed: " + t.getMessage());
        }
    }

    private static CommandListener headListener(String tag) {
        return new CommandListener() {
            @Override
            public void onResult(int result, String message) {
                try {
                    if (message != null && (message.contains("ok") || message.contains("OK"))) {
                        DebugLog.i(TAG, tag + " ok");
                    } else {
                        DebugLog.i(TAG, tag + " " + result + " " + message);
                    }
                } catch (Throwable t) {
                    DebugLog.i(TAG, tag + " " + result + " " + message);
                }
            }
        };
    }

    private static boolean invokeInt(String method, int reqId) {
        try {
            java.lang.reflect.Method m = RobotApi.getInstance().getClass().getMethod(method, int.class);
            m.invoke(RobotApi.getInstance(), reqId);
            return true;
        } catch (Throwable t) {
            DebugLog.w(TAG, method + " unavailable: " + t.getMessage());
            return false;
        }
    }
}
