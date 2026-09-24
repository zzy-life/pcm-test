package com.hr.digitalhuman.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.hr.digitalhuman.model.RobotConfig;
import com.hr.digitalhuman.model.UserInfo;

public class SessionStore {
    private static final String PREF = "hr_digital_human";
    private static final String KEY_SN = "robot_sn";
    private static final String KEY_TOKEN = "user_token";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY = "display_name";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_PHONE = "user_phone";
    private static final String KEY_ID_CARD = "user_id_card";
    private static final String KEY_AUTH_TYPE = "auth_type";
    private static final String KEY_TOKEN_EXPIRE = "token_expire";

    /** 登录成功后去向：home / history / resume_center（内存态，不落盘） */
    public static final String AFTER_LOGIN_HOME = "home";
    public static final String AFTER_LOGIN_HISTORY = "history";
    public static final String AFTER_LOGIN_RESUME = "resume_center";

    private final SharedPreferences prefs;
    private RobotConfig config;
    private String sessionId;
    private UserInfo user;
    private String pendingAfterLogin = AFTER_LOGIN_HOME;
    /** 演示模式：忽略一切语音，仅内存，重启后退出 */
    private boolean demoMode;

    public boolean isDemoMode() {
        return demoMode;
    }

    public void setDemoMode(boolean demoMode) {
        this.demoMode = demoMode;
    }

    public SessionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        if (token != null) {
            user = new UserInfo();
            user.token = token;
            user.username = prefs.getString(KEY_USERNAME, "");
            user.displayName = prefs.getString(KEY_DISPLAY, "");
            user.userId = prefs.getString(KEY_USER_ID, "");
            user.phone = prefs.getString(KEY_PHONE, "");
            user.idCard = prefs.getString(KEY_ID_CARD, "");
            user.authType = prefs.getString(KEY_AUTH_TYPE, "sim");
            user.tokenExpireTime = prefs.getLong(KEY_TOKEN_EXPIRE, 0L);
        }
    }

    public void saveSn(String sn) {
        prefs.edit().putString(KEY_SN, sn).apply();
    }

    public String getSn() {
        return prefs.getString(KEY_SN, null);
    }

    public void setConfig(RobotConfig config) {
        this.config = config;
    }

    public RobotConfig getConfig() {
        return config;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void saveUser(UserInfo userInfo) {
        this.user = userInfo;
        if (userInfo == null) {
            prefs.edit()
                    .remove(KEY_TOKEN).remove(KEY_USERNAME).remove(KEY_DISPLAY)
                    .remove(KEY_USER_ID).remove(KEY_PHONE).remove(KEY_ID_CARD)
                    .remove(KEY_AUTH_TYPE).remove(KEY_TOKEN_EXPIRE)
                    .apply();
            return;
        }
        prefs.edit()
                .putString(KEY_TOKEN, userInfo.token)
                .putString(KEY_USERNAME, userInfo.username)
                .putString(KEY_DISPLAY, userInfo.displayName)
                .putString(KEY_USER_ID, userInfo.userId)
                .putString(KEY_PHONE, userInfo.phone)
                .putString(KEY_ID_CARD, userInfo.idCard)
                .putString(KEY_AUTH_TYPE, userInfo.authType)
                .putLong(KEY_TOKEN_EXPIRE, userInfo.tokenExpireTime)
                .apply();
    }

    public UserInfo getUser() {
        return user;
    }

    public boolean isLoggedIn() {
        return user != null && user.token != null && !user.token.isEmpty();
    }

    public void logout() {
        saveUser(null);
        sessionId = null;
        pendingAfterLogin = AFTER_LOGIN_HOME;
    }

    public void setPendingAfterLogin(String target) {
        this.pendingAfterLogin = target == null || target.isEmpty() ? AFTER_LOGIN_HOME : target;
    }

    /** 读取并重置为首页（登录成功后调用一次）。 */
    public String consumePendingAfterLogin() {
        String v = pendingAfterLogin == null ? AFTER_LOGIN_HOME : pendingAfterLogin;
        pendingAfterLogin = AFTER_LOGIN_HOME;
        return v;
    }
}
