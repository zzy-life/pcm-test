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

public class InitFragment extends Fragment {
    private TextView tvStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_init, container, false);
        tvStatus = v.findViewById(R.id.tv_init_status);
        return v;
    }

    public void setStatus(String status) {
        if (tvStatus != null) {
            tvStatus.setText(status);
        }
    }
}
