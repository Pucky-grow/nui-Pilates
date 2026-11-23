package com.yoyi.pilates.notify.application;

import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventConsumer;
import com.yoyi.pilates.infra.mq.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息事件消费者: 预约成功/取消/支付成功 → 站内信 + 订阅消息.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer implements EventConsumer {

    private final NotificationService notificationService;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseMapper courseMapper;

    @Override
    public String topic() {
        return Topics.BOOKING_EVENT;
    }

    @Override
    public void onMessage(DomainEvent event) {
        if (!java.util.Set.of("BOOKING_CREATED", "BOOKING_CANCELLED", "COURSE_CANCELLED_NOTIFY").contains(event.getTag())) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.getPayload();
        Long userId = Long.valueOf(payload.get("userId").toString());
        switch (event.getTag()) {
            case "BOOKING_CREATED" -> {
                Long scheduleId = Long.valueOf(payload.get("scheduleId").toString());
                Map<String, String> vars = scheduleVars(scheduleId);
                notificationService.send(userId, "BOOKING_SUCCESS", vars, "BOOKING", event.getKey());
                notificationService.pushSubscribe(userId, "BOOKING_SUCCESS", vars, "pages/booking/booking");
            }
            case "BOOKING_CANCELLED" -> {
                Long scheduleId = payload.get("scheduleId") == null ? null
                        : Long.valueOf(payload.get("scheduleId").toString());
                Map<String, String> vars = scheduleId == null ? new HashMap<>() : scheduleVars(scheduleId);
                notificationService.send(userId, "COURSE_CANCEL", vars, "BOOKING_CANCEL", event.getKey());
            }
            case "COURSE_CANCELLED_NOTIFY" -> {
                Long scheduleId = Long.valueOf(payload.get("scheduleId").toString());
                Map<String, String> vars = scheduleVars(scheduleId);
                CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
                String reason = schedule == null || schedule.getCancelReason() == null
                        ? "" : "\n门店说明：" + schedule.getCancelReason();
                notificationService.sendText(userId, "COURSE_CANCEL", "门店已取消课程",
                        vars.getOrDefault("courseName", "课程") + " · " + vars.getOrDefault("courseDate", "")
                                + " " + vars.getOrDefault("startTime", "") + reason,
                        "COURSE_CANCELLED", payload.get("bookingId").toString());
            }
            default -> {
            }
        }
    }

    private Map<String, String> scheduleVars(Long scheduleId) {
        Map<String, String> vars = new HashMap<>();
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return vars;
        }
        vars.put("courseDate", schedule.getCourseDate().toString());
        vars.put("startTime", schedule.getStartTime().toString());
        CourseEntity course = courseMapper.selectById(schedule.getCourseId());
        if (course != null) {
            vars.put("courseName", course.getName());
        }
        return vars;
    }

    /** ORDER_PAID → 支付成功通知 */
    @Component
    @RequiredArgsConstructor
    public static class OrderPaidNotifyConsumer implements EventConsumer {

        private final NotificationService notificationService;

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
            notificationService.send(userId, "ORDER_PAID", new HashMap<>(),
                    "ORDER_PAID", event.getKey());
        }
    }
}
