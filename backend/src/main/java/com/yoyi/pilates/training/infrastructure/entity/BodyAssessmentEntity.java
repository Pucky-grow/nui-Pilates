package com.yoyi.pilates.training.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 身体评估(body_assessment 表). 仅记录身体状态与体态观察, 不做医学诊断.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("body_assessment")
public class BodyAssessmentEntity extends BaseEntity {

    private Long userId;
    private Long coachId;
    private LocalDate assessDate;
    private BigDecimal height;
    private BigDecimal weight;
    private BigDecimal bodyFatRate;
    private BigDecimal waist;
    private BigDecimal hip;
    private BigDecimal thigh;
    private String headForward;
    private String roundedShoulder;
    private String thoracic;
    private String pelvis;
    private String shoulderLevel;
    private String knee;
    private String foot;
    private String frontPhoto;
    private String sidePhoto;
    private String backPhoto;
    private String remark;
}
