package com.hr.digitalhuman.api;

import com.hr.digitalhuman.model.ApiResponse;
import com.hr.digitalhuman.model.AppLogEntry;
import com.hr.digitalhuman.model.ChatSession;
import com.hr.digitalhuman.model.HistoryRecord;
import com.hr.digitalhuman.model.NavMapInfo;
import com.hr.digitalhuman.model.NavPoint;
import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.model.UserInfo;
import com.hr.digitalhuman.model.agent.AgentTurn;

import java.util.List;

public interface ApiService {
    ApiResponse<RobotConfig> getRobotConfig(String sn);

    ApiResponse<ChatSession> createSession(String sn, String trigger);

    ApiResponse<AgentTurn> sendAgentMessage(String sn, String sessionId, String content, String role);

    /**
     * 机器人工具执行结果回传，走 /chat/plan/message，role=tool。
     * @param callLlm 到达/失败等需要模型继续决策时传 true，展示类工具默认 false
     * @param tool    工具编码（如 robot_navigate），可空
     */
    ApiResponse<AgentTurn> submitToolResult(String sn, String sessionId, String content,
                                            String role, boolean callLlm, String toolCallId, String tool);

    ApiResponse<Void> closeSession(String sn, String sessionId);

    ApiResponse<Void> syncNavPoints(String sn, List<NavPoint> points);

    /** 全量同步地图与当前地图点位。后台按 sn 清空后重写。 */
    ApiResponse<Void> syncNavMaps(String sn, List<NavMapInfo> maps);

    /**
     * 用户名密码登录。
     */
    ApiResponse<UserInfo> login(String sn, String username, String password);

    ApiResponse<List<HistoryRecord>> getHistory(String sn, String token);

    ApiResponse<Void> uploadAppLogs(String sn, String appVersion, List<AppLogEntry> logs);

    /** 简历中心工作台 */
    ApiResponse<com.google.gson.JsonObject> getResumeHub(String sn, Long userId, String userToken);

    /** 按模板开始/重做采集 */
    ApiResponse<com.google.gson.JsonObject> resumeChatStart(String sn, Long userId, Long templateId, boolean forceNew);

    /** 按模板续做草稿 */
    ApiResponse<com.google.gson.JsonObject> resumeChatContinue(String sn, Long userId, Long templateId);

    /** 采集中用户作答 / 工具动作（action 对齐 server_resume_chat schema） */
    ApiResponse<com.google.gson.JsonObject> resumeChatMessage(String sn, Long userId, String message);

    /** 同上，可显式传 action（confirm/revise/expand/exit/turn…） */
    ApiResponse<com.google.gson.JsonObject> resumeChatMessage(String sn, Long userId, String message, String action);

    /** 同上，确认生成时可带简历名称 */
    ApiResponse<com.google.gson.JsonObject> resumeChatMessage(String sn, Long userId, String message,
                                                             String action, String resumeName);

    /** 进行中会话采集进度 */
    ApiResponse<com.google.gson.JsonObject> getResumeChatProgress(String sn);

    /** 简历异步生成进度（轮询 recordId） */
    ApiResponse<com.google.gson.JsonObject> getResumeStatus(String sn, Long recordId, Long userId);

    /** App 用户删除自己的历史简历 */
    ApiResponse<com.google.gson.JsonObject> deleteResumeRecord(String sn, Long userId, Long recordId);

    /** App 用户删除（放弃）某模板草稿 */
    ApiResponse<com.google.gson.JsonObject> abandonResumeDraft(Long userId, Long templateId);
}
