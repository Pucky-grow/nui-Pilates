package com.yoyi.pilates.user.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 小程序登录请求.
 */
@Data
public class WxLoginRequest {

    /** wx.login() 的 code; mock 模式下任意字符串即为一个独立用户 */
    @NotBlank(message = "登录凭证不能为空")
    private String code;

    /** 可选: 用户授权的昵称/头像(首次登录建档用) */
    private String nickname;
    private String avatarUrl;
    private Integer gender;
}
