package com.hr.digitalhuman.debug;

import android.os.Handler;
import android.os.Looper;

import com.hr.digitalhuman.BuildConfig;
import com.hr.digitalhuman.api.ApiService;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.AppLogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将 DebugLog 异步批量上报至 POST /robot/log。
 */
public final class RemoteLogSink implements DebugLog.Sink {

    private static final int BATCH_SIZE = 20;
    private static final long FLUSH_INTERVAL_MS = 5000L;
    private static final int MAX_MESSAGE_LEN = 2000;

    private final ApiService api;
    private final SnProvider snProvider;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AppLogEntry> buffer = new ArrayList<>();
    private final Runnable scheduleFlush = this::flushNow;

    public interface SnProvider {
        String getSn();
    }

    public RemoteLogSink(ApiService api, SnProvider snProvider) {
        this.api = api;
        this.snProvider = snProvider;
    }

    @Override
    public void onLog(String level, String tag, String message, long timestampMs) {
        if (message == null) {
            return;
        }
        String msg = message.length() > MAX_MESSAGE_LEN
                ? message.substring(0, MAX_MESSAGE_LEN) + "…"
                : message;
        synchronized (buffer) {
            AppLogEntry entry = new AppLogEntry();
            entry.level = level;
            entry.tag = tag;
            entry.message = msg;
            entry.loggedAt = timestampMs;
            buffer.add(entry);
            if (buffer.size() >= BATCH_SIZE) {
                mainHandler.removeCallbacks(scheduleFlush);
                flushNow();
            } else {
                mainHandler.removeCallbacks(scheduleFlush);
                mainHandler.postDelayed(scheduleFlush, FLUSH_INTERVAL_MS);
            }
        }
    }

    public void flushNow() {
        final List<AppLogEntry> batch;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }
        executor.execute(() -> uploadBatch(batch));
    }

    private void uploadBatch(List<AppLogEntry> batch) {
        String sn = snProvider != null ? snProvider.getSn() : null;
        if (sn == null || sn.trim().isEmpty() || api == null || batch.isEmpty()) {
            return;
        }
        try {
            ApiResponse<Void> resp = api.uploadAppLogs(sn, BuildConfig.VERSION_NAME, batch);
            if (resp != null && !resp.isOk()) {
                android.util.Log.w("RemoteLogSink", "upload failed code=" + resp.code + " msg=" + resp.message);
            }
        } catch (Exception e) {
            android.util.Log.w("RemoteLogSink", "upload error: " + e.getMessage());
        }
    }
}
