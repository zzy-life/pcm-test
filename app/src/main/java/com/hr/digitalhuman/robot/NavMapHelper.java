package com.hr.digitalhuman.robot;

import android.os.Environment;

import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.actionbean.Pose;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.hr.digitalhuman.debug.DebugLog;
import com.hr.digitalhuman.model.NavMapInfo;
import com.hr.digitalhuman.model.NavPoint;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 从猎户星空地图读取真实点位。
 */
public final class NavMapHelper {

    private static final String TAG = "NavMapHelper";

    private NavMapHelper() {
    }

    public static List<NavPoint> loadLocalNavPoints() {
        return loadPlaces(queryMapName(800));
    }

    /**
     * 地图列表：磁盘 /robot/map 下的地图名 + 当前地图名。
     * 点位坐标只属于当前已加载地图（getPlaceList），其它地图只记名称。
     */
    public static List<NavMapInfo> loadMapSnapshot() {
        String current = queryMapName(1500);
        Set<String> names = new LinkedHashSet<>();
        if (current != null && !current.isEmpty()) {
            names.add(current);
        }
        names.addAll(scanMapDirs());
        if (names.isEmpty()) {
            names.add(current == null || current.isEmpty() ? "default" : current);
        }
        List<NavPoint> places = loadPlaces(current);
        List<NavMapInfo> maps = new ArrayList<>();
        for (String name : names) {
            NavMapInfo info = new NavMapInfo();
            info.mapName = name;
            info.current = name.equals(current);
            if (info.current) {
                info.points = places;
            }
            maps.add(info);
        }
        return maps;
    }

    public static String queryMapName(long timeoutMs) {
        final String[] holder = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        try {
            RobotApi.getInstance().getMapName(2, new CommandListener() {
                @Override
                public void onResult(int result, String message) {
                    holder[0] = message == null ? "" : message.trim();
                    latch.countDown();
                }
            });
            latch.await(Math.max(200, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            DebugLog.w(TAG, "getMapName failed: " + t.getMessage());
        }
        return holder[0] == null ? "" : holder[0];
    }

    public static boolean isEstimated(long timeoutMs) {
        final boolean[] ok = new boolean[]{false};
        final boolean[] answered = new boolean[]{false};
        CountDownLatch latch = new CountDownLatch(1);
        try {
            RobotApi.getInstance().isRobotEstimate(3, new CommandListener() {
                @Override
                public void onResult(int result, String message) {
                    answered[0] = true;
                    ok[0] = "true".equalsIgnoreCase(message == null ? "" : message.trim());
                    latch.countDown();
                }
            });
            latch.await(Math.max(200, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            DebugLog.w(TAG, "isRobotEstimate failed: " + t.getMessage());
            return true;
        }
        return !answered[0] || ok[0];
    }

    private static List<String> scanMapDirs() {
        List<String> names = new ArrayList<>();
        File[] roots = new File[]{
                new File(Environment.getExternalStorageDirectory(), "robot/map"),
                new File("/sdcard/robot/map")
        };
        for (File root : roots) {
            File[] kids = root.listFiles();
            if (kids == null) {
                continue;
            }
            for (File f : kids) {
                if (f != null && f.isDirectory() && !f.getName().startsWith(".")) {
                    if (!names.contains(f.getName())) {
                        names.add(f.getName());
                    }
                }
            }
        }
        return names;
    }

    private static List<NavPoint> loadPlaces(String mapName) {
        List<NavPoint> list = new ArrayList<>();
        try {
            List<Pose> poses = RobotApi.getInstance().getPlaceList();
            if (poses == null || poses.isEmpty()) {
                DebugLog.w(TAG, "getPlaceList empty");
                return list;
            }
            int i = 0;
            for (Pose pose : poses) {
                if (pose == null || pose.getName() == null || pose.getName().trim().isEmpty()) {
                    continue;
                }
                String name = pose.getName().trim();
                NavPoint p = new NavPoint();
                p.pointId = (mapName == null || mapName.isEmpty() ? "map" : mapName) + ":" + name;
                p.robotMapPlaceName = name;
                p.displayName = name;
                p.mapName = mapName;
                p.description = "地图点位";
                p.poseX = (double) pose.getX();
                p.poseY = (double) pose.getY();
                p.poseTheta = (double) pose.getTheta();
                p.status = poseStatus(pose);
                i++;
                if (p.pointId.length() > 120) {
                    p.pointId = "map_" + i + "_" + name.hashCode();
                }
                list.add(p);
            }
        } catch (Throwable t) {
            DebugLog.e(TAG, "loadPlaces failed", t);
        }
        return list;
    }

    private static int poseStatus(Pose pose) {
        try {
            Object v = pose.getClass().getMethod("getStatus").invoke(pose);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 将后台 poiId / 目标名称映射到猎户地图点位名。不得编造：匹配不到则返回 null。
     */
    public static String resolvePlaceName(String poiId, String target) {
        List<NavPoint> points = loadLocalNavPoints();
        String place = match(points, poiId);
        if (place != null) {
            return place;
        }
        place = match(points, target);
        if (place != null) {
            return place;
        }
        if (poiId != null && !poiId.trim().isEmpty() && looksLikePlaceName(poiId, points)) {
            return poiId.trim();
        }
        if (target != null && !target.trim().isEmpty() && looksLikePlaceName(target, points)) {
            return target.trim();
        }
        return null;
    }

    private static String match(List<NavPoint> points, String query) {
        if (query == null || query.trim().isEmpty() || points == null) {
            return null;
        }
        String q = query.trim();
        for (NavPoint p : points) {
            if (p == null) {
                continue;
            }
            if (q.equals(p.pointId) || q.equals(p.robotMapPlaceName) || q.equals(p.displayName)) {
                return p.robotMapPlaceName;
            }
        }
        for (NavPoint p : points) {
            if (p == null || p.robotMapPlaceName == null) {
                continue;
            }
            if (p.robotMapPlaceName.contains(q) || q.contains(p.robotMapPlaceName)
                    || (p.displayName != null && (p.displayName.contains(q) || q.contains(p.displayName)))) {
                return p.robotMapPlaceName;
            }
        }
        return null;
    }

    private static boolean looksLikePlaceName(String name, List<NavPoint> points) {
        if (points == null) {
            return false;
        }
        for (NavPoint p : points) {
            if (p != null && name.equals(p.robotMapPlaceName)) {
                return true;
            }
        }
        return false;
    }
}
