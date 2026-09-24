package com.hr.digitalhuman.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.view.RobotFaceView;

public class StandbyFragment extends Fragment {

    private TextView hintView;
    private String standbyHint;
    private String followingHint;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_standby, container, false);
        RobotFaceView face = v.findViewById(R.id.robot_face);
        hintView = v.findViewById(R.id.tv_standby_hint);

        RobotConfig config = DigitalHumanApp.getInstance().getSessionStore().getConfig();
        if (config != null && config.standby != null) {
            standbyHint = config.standby.hintText;
            followingHint = config.standby.followingHintText != null
                    ? config.standby.followingHintText : "正在跟随来宾…";
            hintView.setText(standbyHint);
            face.applyStandbyMode(
                    config.standby.playfulEyeEnabled,
                    config.standby.playfulEyeIntervalSecMin,
                    config.standby.playfulEyeIntervalSecMax);
        } else {
            standbyHint = getString(R.string.standby_hint);
            followingHint = "正在跟随来宾…";
            face.applyStandbyMode(true, 3, 8);
        }
        return v;
    }

    public void applyConfig(RobotConfig config) {
        if (config == null || config.standby == null) {
            return;
        }
        standbyHint = config.standby.hintText;
        followingHint = config.standby.followingHintText != null
                ? config.standby.followingHintText : "正在跟随来宾…";
        if (hintView != null) {
            hintView.setText(standbyHint);
        }
        View v = getView();
        if (v != null) {
            RobotFaceView face = v.findViewById(R.id.robot_face);
            if (face != null) {
                face.applyStandbyMode(
                        config.standby.playfulEyeEnabled,
                        config.standby.playfulEyeIntervalSecMin,
                        config.standby.playfulEyeIntervalSecMax);
            }
        }
    }

    public void setFollowing(boolean following) {
        if (hintView == null) {
            return;
        }
        hintView.setText(following ? followingHint : standbyHint);
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
