package com.hr.digitalhuman.model.agent;

import java.util.ArrayList;
import java.util.List;

/** 对话 / 工具回调接口的 data 载荷。 */
public class AgentTurn {
    public String sessionId;
    public String content;
    public List<AgentStep> steps = new ArrayList<>();

    public boolean hasRobotSteps() {
        if (steps == null) {
            return false;
        }
        for (AgentStep step : steps) {
            if (step != null && step.isRobotTool()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasContent() {
        return content != null && !content.trim().isEmpty();
    }
}
