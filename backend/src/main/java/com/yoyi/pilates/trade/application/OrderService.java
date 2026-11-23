package com.yoyi.pilates.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.marketing.application.CouponService;
import com.yoyi.pilates.marketing.application.PointsService;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.entity.OrderItemEntity;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderItemMapper;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单服务: 课包下单(优惠券 + 积分抵扣) / 关闭 / 查询.
 *
 * <p>金额口径: payAmount = totalAmount - discountAmount(券) - pointsAmount(积分),
 * 最低 0 元(0 元单直接走发放流程).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final org.springframework.beans.factory.ObjectProvider<OrderService> self;
    private final OrderItemMapper orderItemMapper;
    private final PackageService packageService;
    private final CouponService couponService;
    private final PointsService pointsService;
    private final SystemConfigService configService;
    @org.springframework.beans.factory.annotation.Value("${yoyi.sales.package-enabled:false}")
    private boolean packageSalesEnabled;

    /**
     * 创建课包订单.
     *
     * @return orderId
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(Long userId, Long packageId, Long userCouponId, Integer pointsUsed) {
        if (!packageSalesEnabled) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "当前暂停销售课包");
        }
        PackageEntity pkg = packageService.requirePackage(packageId);
        if (!"ON_SHELF".equals(pkg.getStatus())) {
            throw BusinessException.of(ResultCode.PACKAGE_OFF_SHELF);
        }
        validatePurchaseEligibility(userId, pkg);

        // ---- 金额计算 ----
        BigDecimal total = pkg.getSalePrice();
        BigDecimal discount = couponService.computeDiscount(userCouponId, userId, packageId, total);
        BigDecimal afterCoupon = total.subtract(discount);
        int points = normalizePoints(userId, pointsUsed, afterCoupon);
        BigDecimal pointsAmount = pointsToYuan(points).min(afterCoupon);
        BigDecimal payAmount = afterCoupon.subtract(pointsAmount);

        // ---- 落单 ----
        OrderEntity order = new OrderEntity();
        order.setOrderNo(NoGenerator.orderNo());
        order.setUserId(userId);
        order.setOrderType("PACKAGE");
        order.setTotalAmount(total);
        order.setDiscountAmount(discount);
        order.setPointsAmount(pointsAmount);
        order.setPayAmount(payAmount);
        order.setUserCouponId(userCouponId);
        order.setStatus("PENDING");
        orderMapper.insert(order);

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderId(order.getId());
        item.setItemType("PACKAGE");
        item.setItemId(packageId);
        item.setItemName(pkg.getName());
        item.setQuantity(1);
        item.setUnitPrice(total);
        item.setTotalPrice(total);
        orderItemMapper.insert(item);

        // ---- 券与积分: 下单即占用, 关单即退回 ----
        if (userCouponId != null) {
            couponService.markUsed(userCouponId, userId, order.getId());
        }
        if (points > 0) {
            pointsService.spend(userId, points, "订单抵扣", "ORDER", order.getOrderNo());
        }
        return order.getId();
    }

    /** 用户取消待支付订单 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long userId, Long orderId) {
        OrderEntity order = requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        closeOrderInternal(order);
    }

    /** 关闭超时未支付订单(定时任务调用) */
    public int closeTimeoutOrders(int timeoutMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int closed=0; long cursor=0;
        while(true) {
        List<OrderEntity> timeoutOrders = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getStatus, "PENDING")
                .lt(OrderEntity::getCreatedAt, deadline)
                .gt(OrderEntity::getId,cursor).orderByAsc(OrderEntity::getId)
                .last("LIMIT 200"));
        if(timeoutOrders.isEmpty())break;
        cursor=timeoutOrders.get(timeoutOrders.size()-1).getId();
        for (OrderEntity order : timeoutOrders) {
            try {
                self.getObject().closeOrderInternal(order);
                closed++;
            } catch (Exception e) {
                // 单个失败不阻塞批量(可能已被支付回调推进), 记录日志便于排查
                log.warn("关闭超时订单失败 orderId={} errorType={}", order.getId(), e.getClass().getSimpleName());
            }
        }
        }
        return closed;
    }

    /** 关单: 状态推进 + 退回券与积分(幂等: 仅 PENDING → CLOSED) */
    @Transactional(rollbackFor = Exception.class)
    public void closeOrderInternal(OrderEntity order) {
        if (orderMapper.closeIfPending(order.getId(), LocalDateTime.now()) == 0) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_ILLEGAL, "订单已支付或已关闭");
        }
        if (order.getUserCouponId() != null) {
            couponService.restore(order.getUserCouponId(), order.getId());
        }
        if (order.getPointsAmount().compareTo(BigDecimal.ZERO) > 0) {
            int points = yuanToPoints(order.getPointsAmount());
            pointsService.refund(order.getUserId(), points, order.getOrderNo());
        }
    }

    // ---------------- 查询 ----------------

    public OrderEntity requireOrder(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.of(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    public Page<OrderEntity> pageMyOrders(Long userId, String status, long page, long size) {
        return orderMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getUserId, userId)
                        .eq(status != null && !status.isBlank(), OrderEntity::getStatus, status)
                        .orderByDesc(OrderEntity::getId));
    }

    public List<OrderItemEntity> itemsOf(Long orderId) {
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemEntity>().eq(OrderItemEntity::getOrderId, orderId));
    }

    public Page<OrderEntity> pageAdmin(String orderNo, Long userId, String status,
                                       LocalDateTime from, LocalDateTime to, long page, long size) {
        return orderMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(orderNo != null && !orderNo.isBlank(), OrderEntity::getOrderNo, orderNo)
                        .eq(userId != null, OrderEntity::getUserId, userId)
                        .eq(status != null && !status.isBlank(), OrderEntity::getStatus, status)
                        .ge(from != null, OrderEntity::getCreatedAt, from)
                        .le(to != null, OrderEntity::getCreatedAt, to)
                        .orderByDesc(OrderEntity::getId));
    }

    // ---------------- 内部 ----------------

    private void validatePurchaseEligibility(Long userId, PackageEntity pkg) {
        // 新人专享: 无任何支付成功订单才可购买
        if (Integer.valueOf(1).equals(pkg.getIsNewUserOnly())) {
            Long paidCount = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
                    .eq(OrderEntity::getUserId, userId)
                    .eq(OrderEntity::getStatus, "PAID"));
            if (paidCount > 0) {
                throw BusinessException.of(ResultCode.PACKAGE_NEW_USER_ONLY);
            }
        }
        // 限购: 已支付 + 待支付的同款课包数量
        if (pkg.getPurchaseLimit() != null && pkg.getPurchaseLimit() > 0) {
            Long bought = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
                    .eq(OrderEntity::getUserId, userId)
                    .in(OrderEntity::getStatus, List.of("PENDING", "PAYING", "PAID"))
                    .exists("SELECT 1 FROM order_item oi WHERE oi.order_id = `order`.id "
                            + "AND oi.item_id = " + pkg.getId() + " AND oi.deleted = 0"));
            if (bought >= pkg.getPurchaseLimit()) {
                throw BusinessException.of(ResultCode.PACKAGE_PURCHASE_LIMIT);
            }
        }
    }

    private int normalizePoints(Long userId, Integer pointsUsed, BigDecimal afterCoupon) {
        if (pointsUsed == null || pointsUsed <= 0) {
            return 0;
        }
        int balance = pointsService.balanceOf(userId);
        int rate = configService.getInt("points.deduct_rate", 100);
        int maxByAmount = afterCoupon.multiply(BigDecimal.valueOf(rate))
                .setScale(0, RoundingMode.DOWN).intValue();
        return Math.min(Math.min(pointsUsed, balance), maxByAmount);
    }

    private BigDecimal pointsToYuan(int points) {
        int rate = configService.getInt("points.deduct_rate", 100);
        return BigDecimal.valueOf(points).divide(BigDecimal.valueOf(rate), 2, RoundingMode.HALF_UP);
    }

    private int yuanToPoints(BigDecimal yuan) {
        int rate = configService.getInt("points.deduct_rate", 100);
        return yuan.multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
