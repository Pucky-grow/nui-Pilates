package com.yoyi.pilates.trade.interfaces;

import com.yoyi.pilates.common.annotation.Idempotent;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.trade.application.OrderService;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.entity.OrderItemEntity;
import com.yoyi.pilates.trade.interfaces.dto.OrderCreateRequest;
import com.yoyi.pilates.trade.interfaces.dto.OrderView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单接口(小程序).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 创建课包订单(幂等防连点重复下单) */
    @PostMapping("/orders")
    @Idempotent(key = "T(com.yoyi.pilates.system.security.SecurityUtils).requireMemberId() + ':order:' + #request.packageId + ':' + #request.userCouponId + ':' + #request.pointsUsed",
            expireSeconds = 5, message = "订单提交中, 请勿重复点击")
    public Result<Map<String, Long>> create(@Valid @RequestBody OrderCreateRequest request) {
        Long orderId = orderService.createOrder(SecurityUtils.requireMemberId(),
                request.getPackageId(), request.getUserCouponId(), request.getPointsUsed());
        return Result.ok(Map.of("orderId", orderId));
    }

    /** 我的订单 */
    @GetMapping("/my/orders")
    public Result<PageResult<OrderView>> myOrders(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(
                orderService.pageMyOrders(SecurityUtils.requireMemberId(), status, page, size),
                this::toView));
    }

    /** 订单详情 */
    @GetMapping("/orders/{id}")
    public Result<OrderView> detail(@PathVariable Long id) {
        OrderEntity order = orderService.requireOrder(id);
        if (!order.getUserId().equals(SecurityUtils.requireMemberId())) {
            return Result.error(40300, "无权查看");
        }
        return Result.ok(toView(order));
    }

    /** 取消待支付订单 */
    @PostMapping("/orders/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancelOrder(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    private OrderView toView(OrderEntity order) {
        OrderView v = new OrderView();
        v.setId(order.getId());
        v.setOrderNo(order.getOrderNo());
        v.setOrderType(order.getOrderType());
        v.setTotalAmount(order.getTotalAmount());
        v.setDiscountAmount(order.getDiscountAmount());
        v.setPointsAmount(order.getPointsAmount());
        v.setPayAmount(order.getPayAmount());
        v.setStatus(order.getStatus());
        v.setCreatedAt(order.getCreatedAt());
        v.setPaidAt(order.getPaidAt());
        List<OrderItemEntity> items = orderService.itemsOf(order.getId());
        if (!items.isEmpty()) {
            v.setPackageId(items.get(0).getItemId());
            v.setItemName(items.get(0).getItemName());
            v.setUnitPrice(items.get(0).getUnitPrice());
        }
        v.setCanPay("PENDING".equals(order.getStatus()));
        v.setCanCancel("PENDING".equals(order.getStatus()));
        v.setCanRefund("PAID".equals(order.getStatus()));
        return v;
    }
}
