package com.yoyi.pilates.marketing.application;

import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventConsumer;
import com.yoyi.pilates.infra.mq.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 邀请奖励消费者: 被邀请人购买后给邀请人发积分.
 */
@Component
@RequiredArgsConstructor
public class InvitationRewardConsumer implements EventConsumer {

    private final InvitationService invitationService;

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
        invitationService.rewardOnOrderPaid(userId);
    }
}
