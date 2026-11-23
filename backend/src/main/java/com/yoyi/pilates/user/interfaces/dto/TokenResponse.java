package com.yoyi.pilates.user.interfaces.dto;

import lombok.Data;

import java.util.Set;

/**
 * 登录响应.
 */
@Data
public class TokenResponse {

    private String token;
    /** MP_USER / ADMIN */
    private String userType;
    /** 是否新注册用户(小程序端可引导新人礼包/完善资料) */
    private boolean isNew;

    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String phone;

    /** ADMIN 登录时返回 */
    private String name;
    private Set<String> roles;
    private Set<String> permissions;
    private Long coachId;
}
