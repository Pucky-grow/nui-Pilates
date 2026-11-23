package com.yoyi.pilates.user.interfaces.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资料更新请求(小程序 MP_USER).
 */
@Data
public class ProfileUpdateRequest {

    @Size(max = 32, message = "昵称最长32字符")
    private String nickname;

    @Size(max = 512, message = "头像地址过长")
    private String avatarUrl;

    private Integer gender;

    @Size(max = 32, message = "姓名最长32字符")
    private String realName;

    private LocalDate birthday;

    private BigDecimal height;
    private BigDecimal weight;
}
