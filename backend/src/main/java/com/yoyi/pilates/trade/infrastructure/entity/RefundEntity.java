package com.yoyi.pilates.trade.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单(refund 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("refund")
public class RefundEntity extends BaseEntity {

    private String refundNo;
    private Long orderId;
    private Long paymentId;
    private BigDecimal amount;
    private String reason;
    /** PENDING / SUCCESS / FAILED */
    private String status;
    private LocalDateTime refundedAt;
}
