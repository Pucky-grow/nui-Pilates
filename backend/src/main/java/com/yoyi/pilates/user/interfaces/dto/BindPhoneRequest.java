package com.yoyi.pilates.user.interfaces.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 绑定手机号请求.
 * 生产: getPhoneNumber 返回的 code; mock: 直接传明文 phone.
 */
@Data
public class BindPhoneRequest {

    /** 新版 getPhoneNumber 返回的 code */
    private String code;

    /** mock 模式明文手机号(开发调试用) */
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;
}
