package com.yoyi.pilates.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.entity.OrderItemEntity;
import com.yoyi.pilates.trade.infrastructure.entity.PaymentEntity;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import com.yoyi.pilates.trade.infrastructure.mapper.PaymentMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付服务: 下单参数 / 回调处理(幂等) / 模拟支付(开发).
 *
 * <p>回调幂等四道防线:
 * 1. payment.out_trade_no 唯一索引
 * 2. payment 状态乐观推进(PENDING → SUCCESS)
 * 3. order 状态乐观推进(PENDING/PAYING → PAID)
 * 4. user_package(order_id, package_id) 唯一索引防重复发课包
 * 任一环节发现"已处理"直接返回成功.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final PackageService packageService;
    private final WechatPayClient wechatPayClient;
    private final UserMapper userMapper;
    private final EventPublisher eventPublisher;
    private final com.yoyi.pilates.common.config.DevelopmentCapabilities capabilities;

    @Value("${yoyi.pay.mock-enabled:false}")
    private boolean mockEnabled;
    @Value("${yoyi.pay.initiation-enabled:false}") private boolean initiationEnabled;

    /**
     * 创建支付参数(小程序调起微信支付; mock 模式返回模拟参数).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createPayParams(Long userId, Long orderId) {
        if (mockEnabled) capabilities.requireMock(true);
        OrderEntity order = orderService.requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        if (!"PENDING".equals(order.getStatus()) && !"PAYING".equals(order.getStatus())) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_ILLEGAL, "订单已支付或已关闭");
        }
        if(!mockEnabled && order.getPayAmount().signum()>0 && !initiationEnabled)
            throw BusinessException.of(ResultCode.FORBIDDEN,"当前未开放新支付，请联系门店核对历史订单");
        PaymentEntity payment = getOrCreatePayment(order);

        Map<String, Object> params = new HashMap<>();
        // 保留历史零元待支付订单结算；生产不需要开放 Mock 支付入口。
        if (!mockEnabled && order.getPayAmount().signum() == 0) {
            settlePaid(payment, "ZERO_" + payment.getOutTradeNo(), "{\"zeroAmount\":true}");
            return Map.of("mock", false, "settled", true, "orderId", orderId);
        }
        if (mockEnabled) {
            // 开发模拟: 小程序拿到后直接调 /api/payment/mock-pay 完成闭环
            params.put("mock", true);
            params.put("orderId", order.getId());
            params.put("orderNo", order.getOrderNo());
            params.put("outTradeNo", payment.getOutTradeNo());
            params.put("amount", order.getPayAmount());
            return params;
        }
        UserEntity user = userMapper.selectById(userId);
        Map<String, String> wxParams = wechatPayClient.createJsapiPayment(
                payment.getOutTradeNo(), "优仪普拉提-课包", order.getPayAmount(), user.getOpenid());
        params.putAll(wxParams);
        params.put("mock", false);
        // 订单推进到支付中(乐观: 不影响重复创建)
        order.setStatus("PAYING");
        orderMapper.updateById(order);
        return params;
    }

    /**
     * 开发环境模拟支付成功: 走与微信回调完全一致的结算路径.
     */
    @Transactional(rollbackFor = Exception.class)
    public void mockPay(Long userId, Long orderId) {
        capabilities.requireMock(mockEnabled);
        OrderEntity order = orderService.requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        // 0 元订单(积分/券全额抵扣)允许直接结算; 否则仅在 mock 环境开放
        if (!mockEnabled && order.getPayAmount().signum() != 0) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "当前环境未开启模拟支付");
        }
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, orderId).orderByDesc(PaymentEntity::getId).last("LIMIT 1"));
        if (payment == null) {
            payment = getOrCreatePayment(order);
        }
        settlePaid(payment, "MOCK_TX_" + System.currentTimeMillis(),
                JsonUtils.toJson(Map.of("mock", true, "orderNo", order.getOrderNo())));
    }

    /**
     * 微信支付回调: 验签 → 解密 → 结算.
     *
     * @return 应答微信的 JSON
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> handleWechatCallback(String timestamp, String nonce,
                                                    String signature, String body) {
        if (!wechatPayClient.verifyCallback(timestamp, nonce, signature, body)) {
            throw BusinessException.of(ResultCode.PAY_SIGN_INVALID);
        }
        Map<String, Object> callback = JsonUtils.fromJson(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> resource = (Map<String, String>) callback.get("resource");
        String plain = wechatPayClient.decryptResource(
                resource.get("associated_data"), resource.get("nonce"), resource.get("ciphertext"));
        Map<String, Object> paidInfo = JsonUtils.fromJson(plain, Map.class);

        String outTradeNo = (String) paidInfo.get("out_trade_no");
        String tradeState = (String) paidInfo.get("trade_state");
        String transactionId = (String) paidInfo.get("transaction_id");

        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOutTradeNo, outTradeNo));
        if (payment == null) {
            // 微信要求对不认识的交易也返回成功, 避免无限重试; 记录告警
            log.error("支付回调找不到支付单: outTradeNo={}", outTradeNo);
            return Map.of("code", "SUCCESS", "message", "成功");
        }
        if ("SUCCESS".equals(tradeState)) {
            settlePaid(payment, transactionId, plain);
        } else {
            payment.setStatus("FAILED");
            payment.setCallbackRaw(plain);
            paymentMapper.updateById(payment);
        }
        return Map.of("code", "SUCCESS", "message", "成功");
    }

    /**
     * 支付成功结算(幂等核心): 支付单/订单乐观推进 + 发课包(唯一索引防重) + 事件.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void settlePaid(PaymentEntity payment, String transactionId, String callbackRaw) {
        // 防线2: 支付单状态乐观推进
        if (paymentMapper.markSuccessIfPending(payment.getId(), transactionId,
                callbackRaw, LocalDateTime.now()) == 0) {
            log.info("支付单已处理(幂等返回): paymentNo={}", payment.getPaymentNo());
            return;
        }
        OrderEntity order = orderService.requireOrder(payment.getOrderId());
        // 防线3: 订单状态乐观推进
        if (orderMapper.markPaidIfPending(order.getId(), LocalDateTime.now()) == 0) {
            log.info("订单已支付(幂等返回): orderNo={}", order.getOrderNo());
            return;
        }
        // 防线4: 发课包 —— (order_id, package_id) 唯一索引, 重复发放触发冲突按成功处理
        List<OrderItemEntity> items = orderService.itemsOf(order.getId());
        for (OrderItemEntity item : items) {
            if (!"PACKAGE".equals(item.getItemType())) {
                continue;
            }
            PackageEntity pkg = packageService.requirePackage(item.getItemId());
            try {
                packageService.grant(order.getUserId(), order.getId(), pkg);
            } catch (DuplicateKeyException e) {
                log.info("课包已发放(幂等跳过): orderId={} packageId={}", order.getId(), pkg.getId());
            }
        }
        // 事件(事务提交后派发): 积分/通知/统计
        eventPublisher.publish(Topics.ORDER_EVENT, DomainEvent.of(
                "ORDER_PAID", order.getOrderNo(),
                Map.of("orderId", order.getId(), "orderNo", order.getOrderNo(),
                        "userId", order.getUserId(), "payAmount", order.getPayAmount().toPlainString())));
    }

    /** 获取或创建支付单(out_trade_no 复用, 防重复下多次单) */
    private PaymentEntity getOrCreatePayment(OrderEntity order) {
        PaymentEntity existing = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, order.getId())
                .eq(PaymentEntity::getStatus, "PENDING")
                .orderByDesc(PaymentEntity::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentNo(NoGenerator.paymentNo());
        payment.setOrderId(order.getId());
        payment.setOutTradeNo(order.getOrderNo());
        payment.setAmount(order.getPayAmount());
        payment.setChannel(mockEnabled ? "MOCK" : "WECHAT");
        payment.setStatus("PENDING");
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            // 并发创建: 读已存在的支付单
            return paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                    .eq(PaymentEntity::getOutTradeNo, order.getOrderNo()));
        }
        return payment;
    }
}
