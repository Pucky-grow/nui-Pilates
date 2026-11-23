package com.yoyi.pilates.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.entity.PaymentEntity;
import com.yoyi.pilates.trade.infrastructure.entity.RefundEntity;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import com.yoyi.pilates.trade.infrastructure.mapper.PaymentMapper;
import com.yoyi.pilates.trade.infrastructure.mapper.RefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款服务(后台发起):
 * 规则: 仅支付成功且课包完全未使用(remaining==total)可原路退款;
 * mock 渠道即时成功, 微信渠道调用退款 API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundMapper refundMapper;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final OrderService orderService;
    private final PackageService packageService;
    private final WechatPayClient wechatPayClient;

    @org.springframework.beans.factory.annotation.Value("${yoyi.pay.refund-enabled:false}") private boolean refundEnabled;

    @Transactional(rollbackFor = Exception.class)
    public String refund(Long orderId, String reason, String operator) {
        if(!refundEnabled) throw BusinessException.of(ResultCode.FORBIDDEN,"当前未开放线上退款，请联系门店核对历史订单");
        OrderEntity order = orderService.requireOrder(orderId);
        if (!"PAID".equals(order.getStatus())) {
            throw BusinessException.of(ResultCode.REFUND_NOT_ALLOWED, "仅支付成功的订单可退款");
        }
        // 校验课包未使用
        var items = orderService.itemsOf(orderId);
        for (var item : items) {
            if (!"PACKAGE".equals(item.getItemType())) {
                continue;
            }
            UserPackageEntity up = packageService.requireUserPackage(
                    findUserPackageId(order.getUserId(), orderId, item.getItemId()));
            if (!up.getRemainingCredits().equals(up.getTotalCredits())) {
                throw BusinessException.of(ResultCode.REFUND_NOT_ALLOWED,
                        "课包已使用课时, 不支持整单退款, 请线下协商处理");
            }
        }
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, orderId)
                .eq(PaymentEntity::getStatus, "SUCCESS")
                .last("LIMIT 1"));
        if (payment == null) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_ILLEGAL, "未找到支付记录");
        }

        // 每单仅退款一次: 退款单号由订单号派生, 保证重试时微信侧 out_refund_no 幂等(不重复退款)
        String refundNo = "RF" + order.getOrderNo();

        // 先乐观推进订单状态(防并发/重复退款), 失败即拒绝, 避免重复调用微信退款
        if (orderMapper.markRefundedIfPaid(orderId) == 0) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_ILLEGAL, "订单已退款");
        }

        // 微信渠道退款: 用同一 refundNo 作为 out_refund_no, 微信侧按此幂等
        if ("WECHAT".equals(payment.getChannel())) {
            String resp = wechatPayClient.refund(payment.getTransactionId(), payment.getOutTradeNo(),
                    refundNo, order.getPayAmount(), order.getPayAmount());
            log.info("退款接口响应已接收（内容不记录）");
        }

        RefundEntity refund = new RefundEntity();
        refund.setRefundNo(refundNo);
        refund.setOrderId(orderId);
        refund.setPaymentId(payment.getId());
        refund.setAmount(order.getPayAmount());
        refund.setReason(reason);
        refund.setStatus("SUCCESS");
        refund.setRefundedAt(LocalDateTime.now());
        refundMapper.insert(refund);

        // 回收课包: 状态置 REFUNDED, 课时清零走流水
        for (var item : items) {
            if (!"PACKAGE".equals(item.getItemType())) {
                continue;
            }
            UserPackageEntity up = packageService.requireUserPackage(
                    findUserPackageId(order.getUserId(), orderId, item.getItemId()));
            packageService.adjust(up.getId(), -up.getRemainingCredits(), operator, "订单退款回收课包");
            up.setStatus("REFUNDED");
            packageService.updateStatus(up);
        }
        return refund.getRefundNo();
    }

    public List<RefundEntity> refundsOf(Long orderId) {
        return refundMapper.selectList(
                new LambdaQueryWrapper<RefundEntity>().eq(RefundEntity::getOrderId, orderId));
    }

    private Long findUserPackageId(Long userId, Long orderId, Long packageId) {
        UserPackageEntity up = packageService.findByOrder(userId, orderId, packageId);
        if (up == null) {
            throw BusinessException.of(ResultCode.USER_PACKAGE_NOT_FOUND, "未找到发放的课包");
        }
        return up.getId();
    }
}
