package com.yoyi.pilates.infra.delivery;

import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.infra.mq.*;
import com.yoyi.pilates.system.security.LoginProtection;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Enqueue in the business transaction; never acknowledge a failed durable write. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="yoyi.mq.type", havingValue="outbox")
public class OutboxPublisher implements EventPublisher {
    private final JdbcTemplate jdbc;
    @Override @Transactional
    public void publish(String topic, DomainEvent event) {
        String key = LoginProtection.digest(topic + ":" + event.getTag() + ":" + event.getKey());
        jdbc.update("INSERT INTO event_outbox(event_key,topic,tag,business_key,payload) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE event_key=event_key",
                key, topic, event.getTag(), event.getKey(), JsonUtils.toJson(event));
    }
}
