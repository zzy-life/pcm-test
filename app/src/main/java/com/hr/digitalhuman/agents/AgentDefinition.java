package com.hr.digitalhuman.agents;

import com.google.gson.JsonObject;
import com.hr.digitalhuman.BuildConfig;

/** 智能体配置及首轮请求；仅发送各业务文档定义的字段。 */
public final class AgentDefinition {
    public static final String CAREER = "career";
    public static final String RESUME_DIAGNOSIS = "resume_diagnosis";
    public static final AgentDefinition CAREER_AGENT =
            new AgentDefinition(CAREER, "职业规划", "agents.career.api.key");
    public static final AgentDefinition DIAGNOSIS_AGENT =
            new AgentDefinition(RESUME_DIAGNOSIS, "简历诊断", "agents.resume.diagnosis.api.key");

    public static final String RESUME_OPTIMIZATION = "resume_optimization";
    public static final String MOCK_INTERVIEW = "mock_interview";
    public static final AgentDefinition OPTIMIZATION_AGENT =
            new AgentDefinition(RESUME_OPTIMIZATION, "简历优化", "agents.resume.optimization.api.key");
    public static final AgentDefinition INTERVIEW_AGENT =
            new AgentDefinition(MOCK_INTERVIEW, "模拟面试", "agents.mock.interview.api.key");

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
        if (RESUME_OPTIMIZATION.equals(id)) return OPTIMIZATION_AGENT;
        if (MOCK_INTERVIEW.equals(id)) return INTERVIEW_AGENT;
        throw new IllegalArgumentException("请选择支持的智能体");
    }

    public String apiKey() {
        switch (id) {
            case CAREER: return BuildConfig.AGENTS_CAREER_API_KEY;
            case RESUME_DIAGNOSIS: return BuildConfig.AGENTS_RESUME_DIAGNOSIS_API_KEY;
            case RESUME_OPTIMIZATION: return BuildConfig.AGENTS_RESUME_OPTIMIZATION_API_KEY;
            case MOCK_INTERVIEW: return BuildConfig.AGENTS_MOCK_INTERVIEW_API_KEY;
            default: throw new IllegalArgumentException("不支持的智能体");
        }
    }

    /** 简历文本与文件严格互斥；文件可传已上传 cos_key 或已确认支持的公网 URL，不传下载凭据。 */
    public JsonObject buildRequest(String type, String jobInfo, String jobTitle,
                                   String resumeContent, String cosKey, String fileName,
                                   String questionNumber, int interviewRole, int referenceAnswer) {
        String resume = clean(resumeContent);
        String file = clean(cosKey);
        if ((!resume.isEmpty() && !file.isEmpty())
                || (!MOCK_INTERVIEW.equals(id) && resume.isEmpty() && file.isEmpty())) {
            throw new IllegalArgumentException("请选择一种有效的简历来源");
        }
        JsonObject inputs = new JsonObject();
        if (!resume.isEmpty()) inputs.addProperty("resume_content", resume);
        else if (!file.isEmpty()) inputs.addProperty("file_url", file);
        String query;
        if (CAREER.equals(id)) {
            if (!"晋升路径".equals(type) && !"转型建议".equals(type)) {
                throw new IllegalArgumentException("请选择晋升路径或转型建议");
            }
            inputs.addProperty("type", type);
            query = "分析简历，提出职业建议";
        } else if (RESUME_OPTIMIZATION.equals(id)) {
            if (!clean(jobInfo).isEmpty()) inputs.addProperty("job_info", clean(jobInfo));
            inputs.addProperty("optimization_type", "0");
            query = "开始任务";
        } else {
            String jd = clean(jobInfo);
            if (jd.isEmpty()) throw new IllegalArgumentException(name + "需要填写职位 JD");
            if (MOCK_INTERVIEW.equals(id)) {
                String count = clean(questionNumber);
                if (count.isEmpty()) count = "5";
                // 限制题数，为 30 轮会话上限内的追问和失败重试预留空间。
                if (!count.matches("[5-9]|1[0-5]")) {
                    throw new IllegalArgumentException("面试题数须为 5–15，留空默认 5");
                }
                if ((interviewRole != 1 && interviewRole != 2)
                        || (referenceAnswer != 0 && referenceAnswer != 1)) {
                    throw new IllegalArgumentException("面试设置无效");
                }
                inputs.addProperty("question_number", count);
                inputs.addProperty("interview_role", interviewRole);
                inputs.addProperty("reference_answer", referenceAnswer);
                if (!file.isEmpty() && !clean(fileName).isEmpty()) {
                    inputs.addProperty("file_name", clean(fileName));
                }
            }
            inputs.addProperty("job_info", jd);
            if (!clean(jobTitle).isEmpty()) inputs.addProperty("job_title", clean(jobTitle));
            if (MOCK_INTERVIEW.equals(id)) {
                query = "开始面试";
            } else {
                // 简历诊断按 Unicode 码点截取，避免切断补充字符的代理对。
                query = jd.substring(0, jd.offsetByCodePoints(0,
                        Math.min(20, jd.codePointCount(0, jd.length()))));
            }
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
