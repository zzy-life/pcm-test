package com.hr.digitalhuman.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 跟踪智能体页面生命周期，并保留 test 分支的调试入口。 */
public final class AgentFloatingEntry implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "agent_floating_entry";
    private volatile int visibleAgentPages;
    private java.lang.ref.WeakReference<Activity> resumedActivity = new java.lang.ref.WeakReference<>(null);
    private java.lang.ref.WeakReference<MainActivity> mainActivity = new java.lang.ref.WeakReference<>(null);

    private static boolean isAgentActivity(Activity activity) {
        return activity instanceof AgentStreamActivity || activity instanceof AgentResultActivity
                || activity instanceof ResumePortraitActivity;
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
        // 智能体页已有自己的操作栏，浮层不能遮挡发送或开始按钮。
        if (isAgentActivity(activity)) {
            if (existing != null) content.removeView(existing);
            return;
        }
        if (existing != null) {
            existing.bringToFront();
            return;
        }
        TextView entry = UiDecor.chip(activity, "进入系统");
        entry.setTag(TAG);
        entry.setGravity(Gravity.CENTER);
        entry.setContentDescription("跳过机器人初始化，进入系统首页");
        entry.setClickable(true);
        entry.setFocusable(true);
        entry.setMinHeight(UiDecor.dp(activity, 48));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        params.setMargins(UiDecor.dp(activity, 16), UiDecor.dp(activity, 16),
                UiDecor.dp(activity, 16), UiDecor.dp(activity, 16));
        content.addView(entry, params);
        entry.setOnClickListener(v -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).enterSystemForDebug();
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
