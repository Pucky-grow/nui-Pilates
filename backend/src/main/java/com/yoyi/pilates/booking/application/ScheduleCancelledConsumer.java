package com.yoyi.pilates.booking.application;

import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventConsumer;
import com.yoyi.pilates.infra.mq.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 兼容旧排课取消事件；新版本退课已在取消事务中同步完成，重投不得再次返还。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCancelledConsumer implements EventConsumer {

    private final BookingService bookingService;

    @Override
    public String topic() {
        return Topics.BOOKING_EVENT;
    }

    @Override
    public void onMessage(DomainEvent event) {
        if (!"SCHEDULE_CANCELLED".equals(event.getTag())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.getPayload();
        Long scheduleId = Long.valueOf(payload.get("scheduleId").toString());
        String reason = payload.get("reason") == null ? "" : payload.get("reason").toString();
        int cancelled = bookingService.cancelBySchedule(scheduleId, reason);
        log.info("排课取消批量退课完成: scheduleId={} 退课{}人", scheduleId, cancelled);
    }
}
