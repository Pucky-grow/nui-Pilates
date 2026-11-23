package com.yoyi.pilates.course.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 教练(coach 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coach")
public class CoachEntity extends BaseEntity {

    private Long sysUserId;
    /** 教练绑定的微信 openid(绑定后可用微信一键登录教练端) */
    private String openid;
    private String name;
    private String avatar;
    private String title;
    private Integer years;
    private String specialties;
    private String bio;
    private BigDecimal rating;
    private Integer sort;
    private String status;
}
