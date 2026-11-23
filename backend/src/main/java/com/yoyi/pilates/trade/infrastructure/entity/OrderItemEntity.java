package com.yoyi.pilates.trade.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 订单明细(order_item 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_item")
public class OrderItemEntity extends BaseEntity {

    private Long orderId;
    /** PACKAGE */
    private String itemType;
    private Long itemId;
    private String itemName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
}
