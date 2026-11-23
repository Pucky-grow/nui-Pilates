package com.yoyi.pilates.booking.application;

import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventConsumer;
import com.yoyi.pilates.infra.mq.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 候补消费者: 监听预约取消事件, 触发候补自动转正.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitlistConsumer implements EventConsumer {

    private final WaitlistService waitlistService;
    @org.springframework.beans.factory.annotation.Value("${yoyi.waitlist.enabled:false}") private boolean enabled;

    @Override
    public String topic() {
        return Topics.BOOKING_EVENT;
    }

    @Override
    public void onMessage(DomainEvent event) {
        if (!enabled) return;
        if (!"BOOKING_CANCELLED".equals(event.getTag())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.getPayload();
        Long scheduleId = payload.get("scheduleId") == null ? null
                : Long.valueOf(payload.get("scheduleId").toString());
        if (scheduleId != null) {
            waitlistService.promote(scheduleId);
        }
    }
}
