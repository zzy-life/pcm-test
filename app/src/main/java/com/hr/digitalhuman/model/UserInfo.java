package com.hr.digitalhuman.model;

public class UserInfo {
    public String userId;
    public String displayName;
    public String username;
    public String phone;
    public String idCard;
    public String authType;
    public String token;
    /** 凭证过期时间（毫秒时间戳，来自后端 tokenExpireTime） */
    public long tokenExpireTime;
}
