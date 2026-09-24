package com.hr.digitalhuman.agent;

/** 机器人端工具名（含后台可能出现的别名）。 */
public final class RobotTools {

    public static final String SPEAK = "robot_speak";
    public static final String DISPLAY = "robot_display";
    public static final String ACT = "robot_act";
    public static final String NAVIGATE = "robot_navigate";
    public static final String NAV_CANCEL = "robot_nav_cancel";
    public static final String ASK_USER = "ask_user_question";
    public static final String OPEN_PAGE = "robot_open_page";

    private RobotTools() {
    }

    public static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        String n = raw.trim().toLowerCase();
        switch (n) {
            case "speak":
            case "robot_speak":
            case "tts":
                return SPEAK;
            case "display":
            case "robot_display":
            case "show":
                return DISPLAY;
            case "act":
            case "robot_act":
            case "action":
            case "expression":
                return ACT;
            case "navigate":
            case "robot_navigate":
            case "nav":
                return NAVIGATE;
            case "nav_cancel":
            case "robot_nav_cancel":
            case "cancel_nav":
                return NAV_CANCEL;
            case "ask_user_question":
            case "robot_ask_user":
            case "ask_user":
                return ASK_USER;
            case "open_page":
            case "robot_open_page":
            case "nav_page":
                return OPEN_PAGE;
            default:
                return n;
        }
    }
}
