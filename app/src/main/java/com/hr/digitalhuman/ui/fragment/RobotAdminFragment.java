package com.hr.digitalhuman.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.app.DigitalHumanApp;
import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.NavMapInfo;
import com.hr.digitalhuman.model.NavPoint;
import com.hr.digitalhuman.robot.NavMapHelper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 管理口令通过后的地图列表。同步只覆盖当前地图的点位。 */
public class RobotAdminFragment extends Fragment {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView tvList;
    private TextView btnSync;
    private List<NavMapInfo> maps;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_robot_admin, container, false);
        tvList = v.findViewById(R.id.tv_map_list);
        btnSync = v.findViewById(R.id.btn_sync_maps);
        v.findViewById(R.id.btn_back).setOnClickListener(view -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });
        btnSync.setOnClickListener(view -> syncMaps());
        loadMaps();
        return v;
    }

    private void loadMaps() {
        tvList.setText("正在读取地图…");
        io.execute(() -> {
            List<NavMapInfo> loaded = NavMapHelper.loadMapSnapshot();
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                maps = loaded;
                tvList.setText(render(loaded));
            });
        });
    }

    private void syncMaps() {
        if (maps == null) {
            Toast.makeText(requireContext(), "地图还在读取", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSync.setEnabled(false);
        btnSync.setText("同步中…");
        List<NavMapInfo> snapshot = maps;
        io.execute(() -> {
            String sn = DigitalHumanApp.getInstance().getSessionStore().getSn();
            ApiResponse<Void> resp = DigitalHumanApp.getInstance().getApiService()
                    .syncNavMaps(sn, snapshot);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                btnSync.setEnabled(true);
                btnSync.setText("同步地图到后台");
                Toast.makeText(requireContext(),
                        resp.isOk() ? "已同步当前地图点位，其它地图保持不变" : ("同步失败：" + resp.message),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private static String render(List<NavMapInfo> list) {
        if (list == null || list.isEmpty()) {
            return "没有读到地图。请先在机器人地图工具建图并定位。";
        }
        StringBuilder sb = new StringBuilder();
        for (NavMapInfo m : list) {
            sb.append(m.current ? "【当前】" : "【其他】").append(m.mapName == null ? "未命名" : m.mapName);
            int n = m.points == null ? 0 : m.points.size();
            sb.append("  ·  ").append(n).append(" 个点位\n");
            if (m.points != null) {
                for (NavPoint p : m.points) {
                    sb.append("    · ").append(p.displayName == null ? p.robotMapPlaceName : p.displayName);
                    if (p.status == 1) {
                        sb.append("（禁行）");
                    } else if (p.status == 2) {
                        sb.append("（地图外）");
                    }
                    sb.append("\n");
                }
            } else if (!m.current) {
                sb.append("    （未加载。同步只更新当前地图的点位，这张地图已有点位会保留）\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
