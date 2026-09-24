package com.hr.digitalhuman.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.ime.SoftImeController;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.UserInfo;
import com.hr.digitalhuman.ui.MainActivity;

import java.util.concurrent.Executors;

public class LoginFragment extends Fragment {

    public static LoginFragment newInstance() {
        return new LoginFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_login, container, false);
        v.findViewById(R.id.btn_back).setOnClickListener(view -> requireActivity().onBackPressed());
        EditText etUser = v.findViewById(R.id.et_username);
        EditText etPassword = v.findViewById(R.id.et_password);
        SoftImeController.get().bind(etUser);
        SoftImeController.get().bind(etPassword);
        Button btn = v.findViewById(R.id.btn_login);

        btn.setOnClickListener(view -> {
            String username = etUser.getText() != null ? etUser.getText().toString().trim() : "";
            String password = etPassword.getText() != null ? etPassword.getText().toString() : "";
            if (username.isEmpty()) {
                Toast.makeText(requireContext(), "请输入用户名", Toast.LENGTH_SHORT).show();
                return;
            }
            btn.setEnabled(false);
            Executors.newSingleThreadExecutor().execute(() -> {
                ApiResponse<UserInfo> resp = DigitalHumanApp.getInstance().getApiService()
                        .login(DigitalHumanApp.getInstance().getSessionStore().getSn(),
                                username, password);
                new Handler(Looper.getMainLooper()).post(() -> {
                    btn.setEnabled(true);
                    if (!resp.isOk() || resp.data == null || resp.data.token == null
                            || resp.data.token.isEmpty()) {
                        Toast.makeText(requireContext(),
                                resp.message != null ? resp.message : "登录失败",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    DigitalHumanApp.getInstance().getSessionStore().saveUser(resp.data);
                    Toast.makeText(requireContext(), "登录成功", Toast.LENGTH_SHORT).show();
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).onLoginSuccessNavigate();
                    }
                });
            });
        });
        return v;
    }
}
