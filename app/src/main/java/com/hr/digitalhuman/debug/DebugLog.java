package com.hr.digitalhuman.debug;

import android.util.Log;

/**
 * App 运行日志：输出到 Logcat；远程仅上报告警(W)与错误(E)。
 */
public final class DebugLog {

    public interface Sink {
        void onLog(String level, String tag, String message, long timestampMs);
    }

    private static final String ROOT_TAG = "pcmHrDigitalHuman";
    private static volatile Sink sink;

    private DebugLog() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void flush() {
        Sink s = sink;
        if (s instanceof RemoteLogSink) {
            ((RemoteLogSink) s).flushNow();
        }
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        emit("D", tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        emit("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        emit("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        emit("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        String detail = msg;
        if (t != null) {
            detail = detail + " | " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        emit("E", tag, detail);
    }

    private static void emit(String level, String tag, String msg) {
        // 远程仅上报告警与错误，避免 D/I 刷屏
        if (!"W".equals(level) && !"E".equals(level)) {
            return;
        }
        Sink s = sink;
        if (s == null) {
            return;
        }
        try {
            s.onLog(level, tag, msg, System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(ROOT_TAG, "log sink failed: " + e.getMessage());
        }
    }
}
