package com.hr.digitalhuman.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.robot.RobotSdkBridge;
import com.hr.digitalhuman.ui.MainActivity;
import com.hr.digitalhuman.view.RobotFaceView;

public class WelcomeFragment extends Fragment {

    private boolean completed;
    private int ttsAttempts;
    private long ttsStartedAt;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String welcomeText = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_welcome, container, false);
        RobotFaceView face = v.findViewById(R.id.robot_face);
        TextView subtitle = v.findViewById(R.id.tv_welcome_subtitle);
        face.applySpeakingMode();

        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        welcomeText = config != null && config.welcome != null && config.welcome.text != null
                ? config.welcome.text
                : getString(R.string.app_name);
        subtitle.setText(welcomeText);

        // 停跟随后再开播，给语音通道让出来
        mainHandler.postDelayed(this::startWelcomeTts, 400L);
        return v;
    }

    private void startWelcomeTts() {
        if (!isAdded() || completed) {
            return;
        }
        ttsAttempts++;
        ttsStartedAt = System.currentTimeMillis();
        RobotSdkBridge bridge = DigitalHumanApp.getInstance().getRobotBridge();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).markWelcomeSpeaking(true);
        }
        bridge.playTts(welcomeText, new RobotSdkBridge.TtsCallback() {
            @Override
            public void onComplete() {
                // 跟随停掉时系统会立刻 onStop，不能当成播完
                if (ttsAttempts < 2 && System.currentTimeMillis() - ttsStartedAt < 800L
                        && isAdded() && !completed) {
                    mainHandler.postDelayed(() -> startWelcomeTts(), 400L);
                    return;
                }
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).markWelcomeSpeaking(false);
                }
                finishWelcome();
            }

            @Override
            public void onError(String msg) {
                DebugLog.w("WelcomeFragment", "welcome TTS error attempt="
                        + ttsAttempts + " msg=" + msg);
                if (ttsAttempts < 2 && isAdded() && !completed) {
                    mainHandler.postDelayed(() -> startWelcomeTts(), 400L);
                    return;
                }
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).markWelcomeSpeaking(false);
                }
                finishWelcome();
            }
        });
    }

    private void finishWelcome() {
        if (completed) {
            return;
        }
        completed = true;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onWelcomeComplete();
        }
    }

    @Override
    public void onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    public void applyFaceExpression(String expression) {
        View v = getView();
        if (v == null) {
            return;
        }
        RobotFaceView face = v.findViewById(R.id.robot_face);
        if (face != null) {
            face.setExpression(expression);
        }
    }
}
