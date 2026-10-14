package com.hr.digitalhuman.ime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.hr.digitalhuman.R;

import java.lang.ref.WeakReference;

/**
 * 应用内中文软键盘控制器：屏蔽系统 IME，在 Activity 底部展示拼音键盘。
 */
public final class SoftImeController {

    private static SoftImeController instance;

    private WeakReference<Activity> activityRef;
    private SoftImePanel panel;
    private FrameLayout overlayHost;
    private EditText current;
    private View contentHost;
    private int originalPaddingBottom;
    private Runnable beforeShow;

    public static synchronized SoftImeController get() {
        if (instance == null) {
            instance = new SoftImeController();
        }
        return instance;
    }

    private SoftImeController() {
    }

    public void attach(Activity activity) {
        if (activity != null) attach(activity, activity.findViewById(R.id.fragment_container), null);
    }

    /** 前台页面接管键盘；先释放旧宿主，避免浮层和输入目标跨 Activity 残留。 */
    public void attach(Activity activity, View content, Runnable beforeShow) {
        if (activity == null) return;
        if (activity() != activity || contentHost != content) {
            detach();
            activityRef = new WeakReference<>(activity);
            contentHost = content;
            originalPaddingBottom = content == null ? 0 : content.getPaddingBottom();
        }
        this.beforeShow = beforeShow;
        activity.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ensureOverlay(activity);
    }

    public void detach() {
        hide();
        Activity a = activity();
        if (a != null && overlayHost != null) {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            if (overlayHost.getParent() == decor) {
                decor.removeView(overlayHost);
            }
        }
        overlayHost = null;
        panel = null;
        current = null;
        activityRef = null;
        contentHost = null;
        beforeShow = null;
    }

    /** 后台页面只能释放自己的键盘，不能清理新的前台宿主。 */
    public void detach(Activity owner) {
        if (activity() == owner) detach();
    }

    public void hide(Activity owner) {
        if (activity() == owner) hide();
    }

    /** 绑定单个输入框 */
    public void bind(final EditText editText) {
        if (editText == null) {
            return;
        }
        disableSystemIme(editText);
        // 仅明确点击/触摸时显示，避免布局自动分配焦点或收起后焦点回弹再次开键盘。
        editText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFor(editText);
            }
        });
        editText.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    v.requestFocus();
                    showFor(editText);
                }
                return false;
            }
        });
    }

    /** 递归绑定视图树中的所有 EditText */
    public void bindTree(View root) {
        if (root == null) {
            return;
        }
        if (root instanceof EditText) {
            bind((EditText) root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                bindTree(vg.getChildAt(i));
            }
        }
    }

    public void showFor(EditText editText) {
        Activity activity = activity();
        if (activity == null || editText == null || !editText.isEnabled()
                || !editText.isShown() || !editText.getRootView().equals(activity.getWindow().getDecorView())) {
            return;
        }
        if (beforeShow != null) beforeShow.run();
        ensureOverlay(activity);
        hideSystemIme(editText);
        current = editText;
        panel.attachTarget(editText);
        if (overlayHost.getVisibility() != View.VISIBLE) {
            overlayHost.setVisibility(View.VISIBLE);
        }
        applyContentInset(editText);
    }

    public void hide() {
        clearContentInset();
        if (overlayHost != null) {
            overlayHost.setVisibility(View.GONE);
        }
        if (panel != null) {
            panel.attachTarget(null);
        }
        current = null;
    }

    public boolean isShowing() {
        return overlayHost != null && overlayHost.getVisibility() == View.VISIBLE;
    }

    public boolean onBackPressed() {
        if (isShowing()) {
            hide();
            return true;
        }
        return false;
    }

    private void ensureOverlay(Activity activity) {
        if (overlayHost != null && panel != null) {
            return;
        }
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        overlayHost = new FrameLayout(activity);
        overlayHost.setVisibility(View.GONE);
        overlayHost.setBackgroundColor(0x00000000);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int panelW = (int) (screenW * 0.62f);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hostLp.bottomMargin = dp(activity, 8);
        panel = new SoftImePanel(activity);
        panel.setListener(new SoftImePanel.Listener() {
            @Override
            public void onHideRequested() {
                EditText target = current;
                hide();
                if (target != null) target.clearFocus();
            }
        });
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (isShowing() && (bottom - top) != (oldBottom - oldTop)) {
                    applyContentInset(current);
                }
            }
        });
        overlayHost.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        decor.addView(overlayHost, hostLp);
    }

    /** 把页面整体上移到键盘上方，避免键盘盖住输入框和按钮。 */
    private void applyContentInset(final EditText editText) {
        final Activity activity = activity();
        if (activity == null || panel == null) {
            return;
        }
        final View content = contentHost;
        final SoftImePanel expectedPanel = panel;
        if (content == null) {
            return;
        }
        panel.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || panel != expectedPanel || activity() != activity
                        || contentHost != content || current != editText) {
                    return;
                }
                int h = originalPaddingBottom + panel.getHeight() + dp(activity, 12);
                if (h > dp(activity, 12) && content.getPaddingBottom() != h) {
                    content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                            content.getPaddingRight(), h);
                }
                if (editText != null) {
                    editText.post(new Runnable() {
                        @Override
                        public void run() {
                            if (activity() == activity && panel == expectedPanel
                                    && current == editText && isShowing()) scrollTargetAboveKeyboard(editText);
                        }
                    });
                }
            }
        });
    }

    private void clearContentInset() {
        Activity activity = activity();
        if (activity == null) {
            return;
        }
        View content = contentHost;
        if (content != null && content.getPaddingBottom() != originalPaddingBottom) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), originalPaddingBottom);
        }
    }

    private void scrollTargetAboveKeyboard(EditText editText) {
        if (editText == null || panel == null) {
            return;
        }
        Rect r = new Rect();
        editText.getGlobalVisibleRect(r);
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        int panelTop = loc[1];
        if (panelTop <= 0) {
            return;
        }
        int bottom = r.bottom;
        if (bottom > panelTop - dp(editText.getContext(), 12)) {
            int dy = bottom - panelTop + dp(editText.getContext(), 24);
            View parent = editText;
            while (parent.getParent() instanceof View) {
                parent = (View) parent.getParent();
                if (parent.canScrollVertically(1) || parent.canScrollVertically(-1)) {
                    parent.scrollBy(0, dy);
                    break;
                }
            }
        }
    }

    private static void disableSystemIme(EditText editText) {
        editText.setFocusable(true);
        editText.setFocusableInTouchMode(true);
        editText.setCursorVisible(true);
        editText.setLongClickable(true);
        // 保留文本类型，但尽量不拉起系统键盘
        int type = editText.getInputType();
        if (type == InputType.TYPE_NULL || type == 0) {
            editText.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            editText.setShowSoftInputOnFocus(false);
        }
    }

    private void hideSystemIme(View view) {
        Activity a = activity();
        if (a == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private Activity activity() {
        return activityRef == null ? null : activityRef.get();
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
