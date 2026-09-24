package com.hr.digitalhuman.model;

public class HistoryRecord {
    public String recordId;
    public String sessionId;
    public String role;
    public String content;
    public String toolCallId;
    public String questionSummary;
    public String answerSummary;
    public String createdAt;

    /** 列表标题：优先问题概要 */
    public String displayTitle() {
        if (questionSummary != null && !questionSummary.trim().isEmpty()) {
            String q = questionSummary.trim();
            return q.length() > 36 ? q.substring(0, 36) + "…" : q;
        }
        if ("assistant".equals(role)) {
            return "助手回复";
        }
        if ("tool".equals(role)) {
            return toolCallId != null && !toolCallId.isEmpty() ? "工具 #" + toolCallId : "工具";
        }
        if (content != null && !content.trim().isEmpty()) {
            String c = content.trim();
            return c.length() > 36 ? c.substring(0, 36) + "…" : c;
        }
        return "对话记录";
    }

    /** 列表正文：回答要点 */
    public String displayBody() {
        if (answerSummary != null && !answerSummary.trim().isEmpty()) {
            String a = answerSummary.trim();
            return a.length() > 120 ? a.substring(0, 120) + "…" : a;
        }
        if (content != null && !content.trim().isEmpty()
                && (questionSummary == null || !content.equals(questionSummary))) {
            return content;
        }
        return null;
    }
}
