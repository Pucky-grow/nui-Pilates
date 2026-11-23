package com.yoyi.pilates.user.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 用户资料(user_profile 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_profile")
public class UserProfileEntity extends BaseEntity {

    private Long userId;
    private String realName;
    private LocalDate birthday;
    private BigDecimal height;
    private BigDecimal weight;
    private String remark;
}
