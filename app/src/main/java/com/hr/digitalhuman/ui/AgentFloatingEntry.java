package com.hr.digitalhuman.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 应用内浮层，不申请系统悬浮窗权限，随 Activity 销毁。 */
public final class AgentFloatingEntry implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "agent_floating_entry";
    private volatile int visibleAgentPages;
    private java.lang.ref.WeakReference<Activity> resumedActivity = new java.lang.ref.WeakReference<>(null);
    private java.lang.ref.WeakReference<MainActivity> mainActivity = new java.lang.ref.WeakReference<>(null);

    private static boolean isAgentActivity(Activity activity) {
        return activity instanceof AgentStreamActivity || activity instanceof AgentResultActivity;
    }

    public boolean isAgentPageVisible() {
        return visibleAgentPages > 0;
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (activity instanceof MainActivity) {
            mainActivity = new java.lang.ref.WeakReference<>((MainActivity) activity);
        }
        if (isAgentActivity(activity)) {
            visibleAgentPages++;
            MainActivity main = mainActivity.get();
            if (main != null) main.prepareForAgentPage();
        }
    }

    @Override public void onActivityStarted(Activity activity) { }

    @Override public void onActivityResumed(Activity activity) {
        resumedActivity = new java.lang.ref.WeakReference<>(activity);
        if (visibleAgentPages == 0) restoreMainIfResumed();
        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        View existing = content.findViewWithTag(TAG);
        if (existing != null) {
            existing.bringToFront();
            return;
        }
        TextView entry = UiDecor.chip(activity, "智能体");
        entry.setTag(TAG);
        entry.setGravity(Gravity.CENTER);
        entry.setContentDescription(isAgentActivity(activity)
                ? "当前为智能体分析页面" : "打开智能体分析页面");
        entry.setClickable(true);
        entry.setFocusable(true);
        entry.setMinHeight(UiDecor.dp(activity, 48));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        params.setMargins(UiDecor.dp(activity, 16), UiDecor.dp(activity, 16),
                UiDecor.dp(activity, 16), UiDecor.dp(activity, 16));
        content.addView(entry, params);
        if (isAgentActivity(activity)) entry.setEnabled(false);
        entry.setOnClickListener(v -> {
            if (!(isAgentActivity(activity))) {
                AgentStreamActivity.start(activity);
            }
        });
    }

    private void restoreMainIfResumed() {
        MainActivity main = mainActivity.get();
        if (main != null && resumedActivity.get() == main) main.restoreAfterAgentPage();
    }

    @Override public void onActivityPaused(Activity activity) {
        if (resumedActivity.get() == activity) resumedActivity.clear();
    }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        // 文件选择器或应用退后台不结束隔离；真正退出后仅恢复前台 MainActivity。
        if (isAgentActivity(activity)) {
            visibleAgentPages = Math.max(0, visibleAgentPages - 1);
            if (visibleAgentPages == 0) restoreMainIfResumed();
        }
        if (mainActivity.get() == activity) mainActivity.clear();
    }
}
