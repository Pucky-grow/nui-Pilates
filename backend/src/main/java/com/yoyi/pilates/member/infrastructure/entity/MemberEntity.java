package com.yoyi.pilates.member.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员(member 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("member")
public class MemberEntity extends BaseEntity {

    private String memberNo;
    private Long userId;
    private Long levelId;
    private LocalDate validUntil;
    private Integer totalSessions;
    private BigDecimal totalConsumption;
    private LocalDateTime lastTrainingAt;
    /** ACTIVE / EXPIRED / DISABLED */
    private String status;
}
