package com.hr.digitalhuman.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** 跟踪智能体页面生命周期，协调主页面的暂停与恢复。 */
public final class AgentFloatingEntry implements Application.ActivityLifecycleCallbacks {
    private volatile int visibleAgentPages;
    private java.lang.ref.WeakReference<Activity> resumedActivity = new java.lang.ref.WeakReference<>(null);
    private java.lang.ref.WeakReference<MainActivity> mainActivity = new java.lang.ref.WeakReference<>(null);

    private static boolean isAgentActivity(Activity activity) {
        return activity instanceof AgentStreamActivity || activity instanceof AgentResultActivity
                || activity instanceof ResumePortraitActivity || activity instanceof InterviewSdkActivity;
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
