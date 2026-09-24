package com.hr.digitalhuman.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.ui.MainActivity;
import com.hr.digitalhuman.ui.UiDecor;

public class NavigatingFragment extends Fragment {

    private TextView statusView;

    public static NavigatingFragment newInstance(String displayName, String mapPlace) {
        NavigatingFragment f = new NavigatingFragment();
        Bundle b = new Bundle();
        b.putString("displayName", displayName);
        b.putString("mapPlace", mapPlace);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_nav, container, false);
        TextView msg = v.findViewById(R.id.tv_nav_msg);
        TextView sub = v.findViewById(R.id.tv_nav_sub);
        statusView = v.findViewById(R.id.tv_nav_status);
        Button primary = v.findViewById(R.id.btn_primary);
        Button secondary = v.findViewById(R.id.btn_secondary);
        v.findViewById(R.id.nav_progress).setVisibility(View.VISIBLE);

        String displayName = getArguments() != null ? getArguments().getString("displayName") : "";
        msg.setText("正在带您前往");
        if (sub != null) {
            sub.setText(displayName == null ? "" : displayName);
        }
        if (statusView != null) {
            statusView.setText("请跟我来");
        }
        UiDecor.playEnter(msg, 60);

        if (primary != null) {
            primary.setVisibility(View.GONE);
        }
        secondary.setText(R.string.cancel_nav);
        secondary.setOnClickListener(view -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onUserCancelNavigation();
            }
        });
        return v;
    }

    public void setNavStatus(String hint) {
        if (statusView != null && hint != null) {
            statusView.setText(hint);
        }
    }
}
