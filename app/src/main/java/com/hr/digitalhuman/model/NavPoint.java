package com.hr.digitalhuman.model;

import java.util.ArrayList;
import java.util.List;

public class NavPoint {
    public String pointId;
    public String robotMapPlaceName;
    public String displayName;
    public String floor;
    public String description;
    public List<String> tags = new ArrayList<>();
    public String mapName;
    public Double poseX;
    public Double poseY;
    public Double poseTheta;
    public int status;
}
