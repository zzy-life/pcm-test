package com.hr.digitalhuman.model.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 回传给后台的机器人工具执行结果。 */
public class ToolResult {
    public boolean ok;
    public String tool;
    public String status;
    public String message;
    public Map<String, Object> extra = new LinkedHashMap<>();

    public static ToolResult ok(String tool, String status, String message) {
        ToolResult r = new ToolResult();
        r.ok = true;
        r.tool = tool;
        r.status = status;
        r.message = message;
        return r;
    }

    public static ToolResult fail(String tool, String status, String message) {
        ToolResult r = new ToolResult();
        r.ok = false;
        r.tool = tool;
        r.status = status;
        r.message = message;
        return r;
    }

    public ToolResult put(String key, Object value) {
        if (key != null && value != null) {
            extra.put(key, value);
        }
        return this;
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        if (tool != null) {
            o.addProperty("tool", tool);
        }
        if (status != null) {
            o.addProperty("status", status);
        }
        if (message != null) {
            o.addProperty("message", message);
        }
        Gson gson = new Gson();
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            o.add(e.getKey(), gson.toJsonTree(e.getValue()));
        }
        return o.toString();
    }
}
