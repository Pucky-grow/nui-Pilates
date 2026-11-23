package com.yoyi.pilates.trade.interfaces.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图.
 */
@Data
public class OrderView {

    private Long id;
    private String orderNo;
    private String orderType;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal pointsAmount;
    private BigDecimal payAmount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    // ---- 商品信息 ----
    private Long packageId;
    private String itemName;
    private BigDecimal unitPrice;

    // ---- 可操作标记 ----
    private boolean canPay;
    private boolean canCancel;
    private boolean canRefund;
}
