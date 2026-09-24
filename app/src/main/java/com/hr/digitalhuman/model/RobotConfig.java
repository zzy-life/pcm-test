package com.hr.digitalhuman.model;

import java.util.ArrayList;
import java.util.List;

/** Robot runtime config from GET /robot/config */
public class RobotConfig {
    public String sn;
    public String robotName = "福宝";
    public String orgName = "";
    public DisplayConfig display = new DisplayConfig();
    public TtsConfig tts = new TtsConfig();
    public WelcomeConfig welcome = new WelcomeConfig();
    public WelcomeDebounce welcomeDebounce = new WelcomeDebounce();
    public StandbyConfig standby = new StandbyConfig();
    public SessionConfig session = new SessionConfig();
    public ThemeConfig theme = new ThemeConfig();
    public List<QuickTag> homeQuickTags = new ArrayList<>();

    public static class DisplayConfig {
        public String orientation = "landscape";
        public int width = 1920;
        public int height = 1080;
        /** 设备实际密度，机器人屏为 560 */
        public int densityDpi = 560;
        /**
         * 布局设计宽度（dp）。按屏宽像素 / designWidthDp 重算 density。
         * 默认 960：在 1920 宽屏上得到 density≈2.0，避免系统 560dpi 把界面挤乱。
         */
        public float designWidthDp = 960f;
        public String scaleMode = "fit";
    }

    public static class TtsConfig {
        public String voiceGender = "female";
        public String voiceId = "";
        public String voiceName = "标准女声";
        public float speed = 1.0f;
        public float volume = 1.0f;
        public float pitch = 1.0f;
    }

    public static class WelcomeConfig {
        public String text = "您好，欢迎光临！我是人社数字人福宝。\n\n"
                + "使用提示：请在安静环境下面向我清晰说话；具体业务请按指引前往窗口或自助设备办理。\n\n"
                + "我可为您提供业务咨询、业务导办与带路、常见问题解答，以及就业、社保、劳动关系、培训、人才、仲裁等业务的办理渠道指引。"
                + "智能填报请通过大厅自助机或线上渠道完成，本机器人仅作说明引导。\n\n"
                + "请问有什么可以帮您？";
        public String expression = "welcome";
    }

    public static class WelcomeDebounce {
        public boolean enabled = true;
        public int debounceSec = 30;
        public float personDetectDistanceM = 1.2f;
        public float approachHoldSec = 1.0f;
        public boolean samePersonIdDebounce = true;
        public int globalDebounceSec = 10;
        public boolean homePersonNearEnabled = false;
        public String homePersonNearAction = "none";
    }

    public static class StandbyConfig {
        public String hintText = "待机中 · 欢迎咨询";
        public String followingHintText = "正在跟随来宾…";
        public String expression = "neutral";
        public boolean playfulEyeEnabled = true;
        public int playfulEyeIntervalSecMin = 3;
        public int playfulEyeIntervalSecMax = 8;
        public boolean mouthMoveEnabled = false;
    }

    /** OrionStar startFocusFollow — 待机时人靠近，机器人头部/底盘联动跟随 */
    public static class FocusFollowConfig {
        public boolean enabled = true;
        /** 识别丢失上报超时（秒），一般 5~10 */
        public int lostTimeoutSec = 8;
        /** 跟随最大距离（米），超距上报 STATUS_GUEST_FARAWAY */
        public float maxDistanceM = 2.5f;
        /** 检测到人并启动跟随的距离（米），宜近场 */
        public float detectDistanceM = 1.8f;
        /** 触发迎宾/唤醒的距离（米）：须站在机器人面前近场 */
        public float welcomeDistanceM = 1.5f;
        /** 在迎宾距离内持续多久后触发迎宾（秒） */
        public float approachHoldSec = 0.8f;
        /** 迎宾时人脸水平偏角上限（度） */
        public float welcomeMaxFaceAngleX = 45f;
        /** true=优先正前方人脸；极近场仍允许身体兜底 */
        public boolean welcomeRequireFace = true;
        /**
         * 允许处理语音的距离（米）：须站在机器人面前近场内，默认 2.0m。
         * 本机人脸测距常把面前的人报成 1.8~2.2 米，低于在场距离时开麦逻辑会再抬到在场距离。
         */
        public float voiceListenDistanceM = 2.0f;
        /** 正前方人脸/身体水平偏角上限（度） */
        public float voiceMaxFaceAngleX = 60f;
        /**
         * true=正前方判定优先人脸；false=无正脸时允许身体。
         */
        public boolean voiceRequireFace = false;
        /**
         * 写入 RobotOS 系统声源扇区半宽（度）。0=不改系统设置（推荐，避免硬件层失聪）。
         */
        public float soundAngelRangeDeg = 0f;
    }

    public FocusFollowConfig focusFollow = new FocusFollowConfig();

    public static class SessionConfig {
        public int idleTimeoutSec = 60;
        public int listeningTimeoutSec = 15;
        public int thinkingTimeoutSec = 10;
        /** 人员离开后自动回待机秒数；默认 5 */
        public int personLeaveStandbySec = 5;
    }

    public static class ThemeConfig {
        public String primaryColor = "#1B6BDB";
        public String backgroundColor = "#0A1E3C";
        public String homeSlogan = "智慧人社 · 贴心服务";
    }

    /** 首页连点标语后的管理口令，来自后台配置 */
    public static class AdminConfig {
        public String passcode = "888888";
    }

    public AdminConfig admin = new AdminConfig();

    public static class QuickTag {
        public String id;
        public String label;
        public String presetQuestion;
    }
}
