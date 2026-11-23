package com.yoyi.pilates.training.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 身体数据追踪(body_measurement 表, 趋势图数据源).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("body_measurement")
public class BodyMeasurementEntity extends BaseEntity {

    private Long userId;
    private LocalDate measureDate;
    private BigDecimal weight;
    private BigDecimal bodyFatRate;
    private BigDecimal waist;
    private BigDecimal hip;
    private BigDecimal thigh;
    private String remark;
}
