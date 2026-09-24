package com.hr.digitalhuman.model.agent;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

/** 智能体一轮返回中的单步工具调用。 */
public class AgentStep {
    public String tool;

    @SerializedName("tool_call_id")
    public String toolCallId;

    public JsonObject params;

    public String toolName() {
        return tool == null ? "" : tool.trim();
    }

    public boolean isRobotTool() {
        String n = toolName().toLowerCase();
        return n.startsWith("robot_")
                || "speak".equals(n)
                || "display".equals(n)
                || "act".equals(n)
                || "navigate".equals(n)
                || "nav_cancel".equals(n)
                || "ask_user_question".equals(n);
    }

    public boolean isServerTool() {
        return toolName().toLowerCase().startsWith("server_");
    }
}
