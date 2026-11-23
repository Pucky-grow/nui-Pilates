package com.yoyi.pilates.trade.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单(payment 表): out_trade_no 唯一 = 回调幂等第一防线.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment")
public class PaymentEntity extends BaseEntity {

    private String paymentNo;
    private Long orderId;
    private String outTradeNo;
    private String transactionId;
    private BigDecimal amount;
    /** WECHAT / MOCK */
    private String channel;
    /** PENDING / SUCCESS / FAILED */
    private String status;
    private LocalDateTime paidAt;
    private String callbackRaw;
}
