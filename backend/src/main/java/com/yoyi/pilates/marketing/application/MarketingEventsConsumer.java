package com.yoyi.pilates.marketing.application;

import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventConsumer;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 营销事件消费者: 注册送积分+新人礼包 / 消费返积分 / 签到送积分.
 * 所有入账均经 PointsService 业务键幂等, 事件重复消费安全.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingEventsConsumer implements EventConsumer {

    private final PointsService pointsService;
    private final CouponService couponService;
    private final SystemConfigService configService;
    private final UserMapper users;

    @Override
    public String topic() {
        // 同时关心 MEMBER/ORDER/BOOKING 三个 Topic —— 内存总线与 RocketMQ 均按 topic 匹配,
        // 因此注册三个同名消费者 Bean 会重复; 这里采用监听全部事件的注册方式:
        // 实际订阅在 Publisher 按 topic 匹配, 单个消费者只能声明一个 topic,
        // 选择 MEMBER_EVENT, 其余两个 Topic 由下方两个静态内部类消费者承担。
        return Topics.MEMBER_EVENT;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void onMessage(DomainEvent event) {
        if ("USER_REGISTERED".equals(event.getTag())) {
            handleUserRegistered(event);
        }
    }

    private void handleUserRegistered(DomainEvent event) {
        // The OpenID can have moved to the retained offline archive before the queued event arrives.
        var user = users.lockByOpenid(event.getKey());
        if (user == null) return;
        Long userId = user.getId();
        int registerPoints = configService.getInt("points.register", 50);
        pointsService.earn(userId, registerPoints, "注册", "USER", event.getKey());
        int gifts = couponService.issueNewUserGift(userId);
        log.info("新用户营销动作完成: userId={} 积分+{} 新人券{}张", userId, registerPoints, gifts);
    }

    /** ORDER_PAID → 消费返积分 */
    @Component
    @RequiredArgsConstructor
    public static class OrderPaidPointsConsumer implements EventConsumer {

        private final PointsService pointsService;
        private final SystemConfigService configService;

        @Override
        public String topic() {
            return Topics.ORDER_EVENT;
        }

        @Override
        public void onMessage(DomainEvent event) {
            if (!"ORDER_PAID".equals(event.getTag())) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.getPayload();
            Long userId = Long.valueOf(payload.get("userId").toString());
            BigDecimal payAmount = new BigDecimal(payload.get("payAmount").toString());
            int perYuan = configService.getInt("points.per_yuan", 1);
            int points = payAmount.intValue() * perYuan;
            pointsService.earn(userId, points, "购买", "ORDER", event.getKey());
        }
    }

    /** BOOKING_CHECKED_IN → 签到送积分 */
    @Component
    @RequiredArgsConstructor
    public static class CheckinPointsConsumer implements EventConsumer {

        private final PointsService pointsService;
        private final SystemConfigService configService;

        @Override
        public String topic() {
            return Topics.BOOKING_EVENT;
        }

        @Override
        public void onMessage(DomainEvent event) {
            if (!"BOOKING_CHECKED_IN".equals(event.getTag())) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.getPayload();
            Long userId = Long.valueOf(payload.get("userId").toString());
            int checkinPoints = configService.getInt("points.checkin", 5);
            pointsService.earn(userId, checkinPoints, "签到", "BOOKING", event.getKey());
        }
    }
}
