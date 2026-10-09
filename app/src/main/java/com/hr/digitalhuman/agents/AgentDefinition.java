package com.hr.digitalhuman.agents;

import com.google.gson.JsonObject;
import com.hr.digitalhuman.BuildConfig;

/** 两个已确认协议的轻量配置；不推测未知智能体字段。 */
public final class AgentDefinition {
    public static final String CAREER = "career";
    public static final String RESUME_DIAGNOSIS = "resume_diagnosis";
    public static final AgentDefinition CAREER_AGENT =
            new AgentDefinition(CAREER, "职业规划", "agents.career.api.key");
    public static final AgentDefinition DIAGNOSIS_AGENT =
            new AgentDefinition(RESUME_DIAGNOSIS, "简历诊断", "agents.resume.diagnosis.api.key");

    public final String id;
    public final String name;
    public final String configurationName;

    private AgentDefinition(String id, String name, String configurationName) {
        this.id = id;
        this.name = name;
        this.configurationName = configurationName;
    }

    /** 未知 ID 不默默切换协议。 */
    public static AgentDefinition fromId(String id) {
        if (CAREER.equals(id)) return CAREER_AGENT;
        if (RESUME_DIAGNOSIS.equals(id)) return DIAGNOSIS_AGENT;
        throw new IllegalArgumentException("请选择支持的智能体");
    }

    public String apiKey() {
        return CAREER.equals(id) ? BuildConfig.AGENTS_CAREER_API_KEY
                : BuildConfig.AGENTS_RESUME_DIAGNOSIS_API_KEY;
    }

    /** 简历文本与文件严格互斥；文件可传已上传 cos_key 或已确认支持的公网 URL，不传下载凭据。 */
    public JsonObject buildRequest(String type, String jobInfo, String jobTitle,
                                   String resumeContent, String cosKey) {
        String resume = clean(resumeContent);
        String file = clean(cosKey);
        if (resume.isEmpty() == file.isEmpty()) {
            throw new IllegalArgumentException("请选择一种有效的简历来源");
        }
        JsonObject inputs = new JsonObject();
        if (!resume.isEmpty()) inputs.addProperty("resume_content", resume);
        else inputs.addProperty("file_url", file);
        String query;
        if (CAREER.equals(id)) {
            if (!"晋升路径".equals(type) && !"转型建议".equals(type)) {
                throw new IllegalArgumentException("请选择晋升路径或转型建议");
            }
            inputs.addProperty("type", type);
            query = "分析简历，提出职业建议";
        } else {
            String jd = clean(jobInfo);
            if (jd.isEmpty()) throw new IllegalArgumentException("简历诊断需要填写职位 JD");
            inputs.addProperty("job_info", jd);
            if (!clean(jobTitle).isEmpty()) inputs.addProperty("job_title", clean(jobTitle));
            // 按 Unicode 码点截取，避免把 emoji 等补充字符的代理对切成两半。
            query = jd.substring(0, jd.offsetByCodePoints(0,
                    Math.min(20, jd.codePointCount(0, jd.length()))));
        }
        JsonObject body = new JsonObject();
        body.add("inputs", inputs);
        body.addProperty("query", query);
        body.addProperty("response_mode", "streaming");
        return body;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
