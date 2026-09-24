package com.hr.digitalhuman.state;

import android.os.Handler;
import android.os.Looper;

import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.RobotConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Global robot behavior state machine to avoid concurrent action conflicts.
 */
public class RobotStateMachine {

    private static final String TAG = "RobotStateMachine";

    public interface Listener {
        void onStateChanged(RobotState from, RobotState to, String reason);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private RobotState state = RobotState.INIT;
    private RobotState beforeSuspend = RobotState.STANDBY;
    private RobotConfig config;
    private Runnable idleTimeoutRunnable;
    private long lastWelcomeAt;
    private String lastWelcomePersonId;

    public synchronized RobotState getState() {
        return state;
    }

    public void setConfig(RobotConfig config) {
        this.config = config;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized boolean transition(RobotState target, String reason) {
        if (state == target) {
            return true;
        }
        if (!canTransition(state, target)) {
            DebugLog.w(TAG, "Blocked transition " + state + " -> " + target + " (" + reason + ")");
            return false;
        }
        RobotState from = state;
        state = target;
        notifyListeners(from, target, reason);
        return true;
    }

    public synchronized void onSuspend() {
        if (state == RobotState.SUSPENDED) {
            return;
        }
        beforeSuspend = state;
        transition(RobotState.SUSPENDED, "api_suspend");
    }

    public synchronized void onRecovery() {
        if (state != RobotState.SUSPENDED) {
            return;
        }
        transition(beforeSuspend, "api_recovery");
    }

    public boolean canAcceptPersonApproach() {
        return state == RobotState.STANDBY;
    }

    public boolean canAcceptUserInput() {
        return state == RobotState.HOME
                || state == RobotState.SPEAKING
                || state == RobotState.LISTENING;
    }

    public boolean isBusyForWelcome() {
        return state == RobotState.WELCOME
                || state == RobotState.NAVIGATING
                || state == RobotState.THINKING
                || state == RobotState.SPEAKING
                || state == RobotState.SUSPENDED;
    }

    public boolean allowWelcome(String personId) {
        if (config == null || config.welcomeDebounce == null || !config.welcomeDebounce.enabled) {
            return true;
        }
        RobotConfig.WelcomeDebounce d = config.welcomeDebounce;
        long now = System.currentTimeMillis();
        if (d.globalDebounceSec > 0 && now - lastWelcomeAt < d.globalDebounceSec * 1000L) {
            return false;
        }
        if (d.samePersonIdDebounce && personId != null && personId.equals(lastWelcomePersonId)
                && now - lastWelcomeAt < d.debounceSec * 1000L) {
            return false;
        }
        if (!d.samePersonIdDebounce && now - lastWelcomeAt < d.debounceSec * 1000L) {
            return false;
        }
        return true;
    }

    public void markWelcome(String personId) {
        lastWelcomeAt = System.currentTimeMillis();
        lastWelcomePersonId = personId;
    }

    public void resetIdleTimer(Runnable onTimeout) {
        cancelIdleTimer();
        if (config == null || config.session == null) {
            return;
        }
        int sec = config.session.idleTimeoutSec;
        if (sec <= 0) {
            return;
        }
        idleTimeoutRunnable = onTimeout;
        mainHandler.postDelayed(idleTimeoutRunnable, sec * 1000L);
    }

    public void cancelIdleTimer() {
        if (idleTimeoutRunnable != null) {
            mainHandler.removeCallbacks(idleTimeoutRunnable);
            idleTimeoutRunnable = null;
        }
    }

    private boolean canTransition(RobotState from, RobotState to) {
        if (to == RobotState.SUSPENDED) {
            return true;
        }
        if (from == RobotState.SUSPENDED) {
            return true;
        }
        switch (from) {
            case INIT:
                return to == RobotState.STANDBY || to == RobotState.INIT;
            case STANDBY:
                return setOf(RobotState.WELCOME, RobotState.HOME).contains(to);
            case WELCOME:
                return setOf(RobotState.HOME, RobotState.LISTENING, RobotState.STANDBY).contains(to);
            case HOME:
                return setOf(RobotState.LISTENING, RobotState.THINKING, RobotState.STANDBY,
                        RobotState.NAVIGATING, RobotState.SPEAKING).contains(to);
            case LISTENING:
                return setOf(RobotState.THINKING, RobotState.HOME, RobotState.STANDBY,
                        RobotState.SPEAKING, RobotState.NAVIGATING).contains(to);
            case THINKING:
                return setOf(RobotState.SPEAKING, RobotState.HOME, RobotState.NAVIGATING,
                        RobotState.STANDBY).contains(to);
            case SPEAKING:
                return setOf(RobotState.HOME, RobotState.LISTENING, RobotState.STANDBY,
                        RobotState.NAVIGATING, RobotState.THINKING).contains(to);
            case NAVIGATING:
                return setOf(RobotState.HOME, RobotState.STANDBY).contains(to);
            default:
                return false;
        }
    }

    private Set<RobotState> setOf(RobotState first, RobotState... rest) {
        return EnumSet.of(first, rest);
    }

    private void notifyListeners(RobotState from, RobotState to, String reason) {
        mainHandler.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onStateChanged(from, to, reason);
            }
        });
    }
}
