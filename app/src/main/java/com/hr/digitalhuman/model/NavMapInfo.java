package com.hr.digitalhuman.model;

import java.util.ArrayList;
import java.util.List;

/** 一张导航地图及其点位。点位仅当前加载地图能读到坐标。 */
public class NavMapInfo {
    public String mapName;
    public boolean current;
    public List<NavPoint> points = new ArrayList<>();
}
