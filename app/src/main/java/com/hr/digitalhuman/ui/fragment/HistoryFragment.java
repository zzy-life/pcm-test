package com.hr.digitalhuman.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.app.SessionStore;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.HistoryRecord;
import com.hr.digitalhuman.model.UserInfo;
import com.hr.digitalhuman.ui.MainActivity;
import com.hr.digitalhuman.ui.UiDecor;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * 登录用户历史对话列表（数据来自 jqr_chat_history，接口聚合为问答对）。
 */
public class HistoryFragment extends Fragment {

    public static HistoryFragment newInstance() {
        return new HistoryFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_list, container, false);
        ((TextView) v.findViewById(R.id.tv_title)).setText(R.string.history);
        v.findViewById(R.id.btn_back).setOnClickListener(view -> requireActivity().onBackPressed());
        LinearLayout list = v.findViewById(R.id.list_container);
        com.hr.digitalhuman.view.AmbientFxView fx = v.findViewById(R.id.ambient_fx);
        if (fx != null) {
            fx.setIntensity(0.8f);
        }

        SessionStore store = DigitalHumanApp.getInstance().getSessionStore();
        if (!store.isLoggedIn()) {
            TextView tip = UiDecor.subtitle(requireContext(), "请先登录后查看历史对话");
            tip.setPadding(0, UiDecor.dp(requireContext(), 24), 0, 0);
            list.addView(tip);
            if (getActivity() instanceof MainActivity) {
                store.setPendingAfterLogin(SessionStore.AFTER_LOGIN_HISTORY);
                ((MainActivity) getActivity()).openLogin();
            }
            return v;
        }

        UserInfo user = store.getUser();
        String token = user != null ? user.token : null;
        String sn = store.getSn();

        Executors.newSingleThreadExecutor().execute(() -> {
            ApiResponse<List<HistoryRecord>> resp = DigitalHumanApp.getInstance().getApiService()
                    .getHistory(sn, token);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (!resp.isOk()) {
                    Toast.makeText(requireContext(),
                            resp.message != null ? resp.message : "加载失败",
                            Toast.LENGTH_SHORT).show();
                    TextView err = UiDecor.subtitle(requireContext(),
                            resp.message != null ? resp.message : "加载失败");
                    err.setPadding(0, UiDecor.dp(requireContext(), 24), 0, 0);
                    list.addView(err);
                    return;
                }
                if (resp.data == null || resp.data.isEmpty()) {
                    TextView empty = UiDecor.subtitle(requireContext(), "暂无历史对话");
                    empty.setPadding(0, UiDecor.dp(requireContext(), 24), 0, 0);
                    list.addView(empty);
                    return;
                }
                int delay = 0;
                for (HistoryRecord h : resp.data) {
                    LinearLayout row = new LinearLayout(requireContext());
                    row.setOrientation(LinearLayout.VERTICAL);
                    UiDecor.styleCard(requireContext(), row);
                    row.setLayoutParams(UiDecor.cardLp(requireContext(), 10));

                    if (h.createdAt != null && !h.createdAt.isEmpty()) {
                        TextView time = new TextView(requireContext());
                        time.setText(h.createdAt);
                        time.setTextColor(UiDecor.color(requireContext(), R.color.primary));
                        time.setTextSize(11);
                        row.addView(time);
                    }
                    TextView title = UiDecor.title(requireContext(), "问：" + h.displayTitle());
                    row.addView(title);
                    String body = h.displayBody();
                    if (body != null && !body.isEmpty()) {
                        row.addView(UiDecor.subtitle(requireContext(), "答：" + body));
                    } else {
                        row.addView(UiDecor.subtitle(requireContext(), "答：（暂无回复）"));
                    }
                    UiDecor.playEnter(row, Math.min(delay, 240));
                    delay += 35;
                    list.addView(row);
                }
            });
        });
        return v;
    }
}
