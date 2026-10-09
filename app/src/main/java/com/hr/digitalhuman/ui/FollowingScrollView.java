package com.hr.digitalhuman.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/** API 19 可用。只从用户输入判断阅读意图，不从程序滚动推断用户操作。 */
public final class FollowingScrollView extends ScrollView {
    public interface FollowListener { void onFollowChanged(boolean following); }

    private boolean following = true;
    private boolean userScrolling;
    private boolean programScrolling;
    private boolean touching;
    private float lastY;
    private final int slop;
    private final int bottomTolerance;
    private ValueAnimator followAnimation;
    private FollowListener listener;
    private final Runnable followAfterLayout = this::animateToLatest;

    public FollowingScrollView(Context context) { this(context, null); }

    public FollowingScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        bottomTolerance = UiDecor.dp(context, 48);
        setFillViewport(true);
    }

    public void setFollowListener(FollowListener value) {
        listener = value;
        if (listener != null) listener.onFollowChanged(following);
    }

    public boolean isFollowing() { return following; }

    /** 恢复状态不滚动；需等 Markdown 渲染并完成布局。 */
    public void restoreFollowing(boolean value) { setFollowing(value); }

    public void returnToLatest() {
        userScrolling = false;
        setFollowing(true);
        onContentRendered();
    }

    public void onContentRendered() {
        removeCallbacks(followAfterLayout);
        if (following && !touching) post(followAfterLayout);
    }

    private int bottom() {
        if (getChildCount() == 0) return 0;
        return Math.max(0, getChildAt(0).getBottom() + getPaddingBottom() - getHeight());
    }

    private boolean nearBottom() { return bottom() - getScrollY() <= bottomTolerance; }

    private void setFollowing(boolean value) {
        if (following == value) return;
        following = value;
        if (!value) {
            removeCallbacks(followAfterLayout);
            cancelFollowAnimation();
        }
        if (listener != null) listener.onFollowChanged(value);
    }

    private void cancelFollowAnimation() {
        if (followAnimation != null) {
            followAnimation.cancel();
            followAnimation = null;
        }
    }

    private void animateToLatest() {
        if (!following || touching || getHeight() == 0) return;
        // 一次动画 160ms，小于 Markdown 刷新间隔；不会每个 token 重启动画。
        cancelFollowAnimation();
        userScrolling = false;
        int target = bottom();
        if (getScrollY() == target) return;
        followAnimation = ValueAnimator.ofInt(getScrollY(), target);
        followAnimation.setDuration(160);
        followAnimation.setInterpolator(new DecelerateInterpolator());
        followAnimation.addUpdateListener(animation -> {
            programScrolling = true;
            scrollTo(getScrollX(), (Integer) animation.getAnimatedValue());
            programScrolling = false;
        });
        followAnimation.start();
    }

    public void preserveReadingPosition(int y) {
        programScrolling = true;
        scrollTo(0, y);
        programScrolling = false;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        programScrolling = true;
        super.onLayout(changed, l, t, r, b);
        programScrolling = false;
        onContentRendered();
    }

    // 在 dispatch 层观察 DOWN，即使可选中文本消费触摸，也能取消跟随动画。
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                userScrolling = true;
                lastY = event.getY();
                removeCallbacks(followAfterLayout);
                cancelFollowAnimation();
                break;
            case MotionEvent.ACTION_MOVE:
                // 手指向下拖，内容 scrollY 减小：用户正在阅读更早的结果。
                if (event.getY() - lastY > slop) setFollowing(false);
                if (Math.abs(event.getY() - lastY) > slop) lastY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                break;
            default:
                break;
        }
        boolean consumed = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && following) onContentRendered();
        return consumed;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            cancelFollowAnimation();
            userScrolling = true;
            if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) setFollowing(false);
        }
        return super.onGenericMotionEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            boolean up = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_PAGE_UP
                    || key == KeyEvent.KEYCODE_MOVE_HOME
                    || (key == KeyEvent.KEYCODE_SPACE && event.isShiftPressed());
            boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN
                    || key == KeyEvent.KEYCODE_MOVE_END || key == KeyEvent.KEYCODE_SPACE;
            if (up || down) {
                cancelFollowAnimation();
                userScrolling = true;
                if (up) setFollowing(false);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (!programScrolling && userScrolling) {
            if (t < oldt) setFollowing(false);
            else if (t > oldt && nearBottom()) setFollowing(true);
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(followAfterLayout);
        cancelFollowAnimation();
        super.onDetachedFromWindow();
    }
}
