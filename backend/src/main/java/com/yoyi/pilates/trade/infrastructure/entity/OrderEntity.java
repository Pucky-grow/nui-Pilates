package com.yoyi.pilates.trade.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单(order 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("`order`")
public class OrderEntity extends BaseEntity {

    private String orderNo;
    private Long userId;
    /** PACKAGE / MEMBER / ACTIVITY */
    private String orderType;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal pointsAmount;
    private BigDecimal payAmount;
    private Long userCouponId;
    /** WECHAT / MOCK */
    private String payType;
    /** PENDING / PAYING / PAID / FAILED / CLOSED / REFUNDING / REFUNDED */
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
    private String remark;
}
