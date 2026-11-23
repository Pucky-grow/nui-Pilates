package com.yoyi.pilates.training.application;

import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventConsumer;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.marketing.application.PointsService;
import com.yoyi.pilates.system.application.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 评价送积分(REVIEW_CREATED → points.review, 业务键幂等).
 */
@Component
@RequiredArgsConstructor
public class ReviewPointsConsumer implements EventConsumer {

    private final PointsService pointsService;
    private final SystemConfigService configService;

    @Override
    public String topic() {
        return Topics.TRAINING_EVENT;
    }

    @Override
    public void onMessage(DomainEvent event) {
        if (!"REVIEW_CREATED".equals(event.getTag())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.getPayload();
        Long userId = Long.valueOf(payload.get("userId").toString());
        int points = configService.getInt("points.review", 20);
        pointsService.earn(userId, points, "评价", "REVIEW", event.getKey());
    }
}
