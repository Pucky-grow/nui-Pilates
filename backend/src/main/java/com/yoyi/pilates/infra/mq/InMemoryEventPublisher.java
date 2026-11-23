package com.yoyi.pilates.infra.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 开发环境内存事件总线:
 * - 有事务时注册 afterCommit 回调再派发, 保证"先落库后通知"
 * - 单消费者异常不影响其他消费者(逐个 try/catch)
 * - 消费者经 ObjectProvider 延迟解析, 避免与业务 Service 形成构造期循环依赖
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yoyi.mq.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventPublisher implements EventPublisher {

    private final ObjectProvider<EventConsumer> consumerProvider;

    @Override
    public void publish(String topic, DomainEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(topic, event);
                }
            });
        } else {
            dispatch(topic, event);
        }
    }

    private void dispatch(String topic, DomainEvent event) {
        List<EventConsumer> topicConsumers = consumerProvider.stream()
                .filter(c -> c.topic().equals(topic))
                .toList();
        if (topicConsumers.isEmpty()) {
            log.debug("[MQ-MEMORY] topic={} 无消费者, tag={} key={}", topic, event.getTag(), event.getKey());
            return;
        }
        for (EventConsumer consumer : topicConsumers) {
            try {
                log.info("[MQ-MEMORY] topic={} tag={} key={} -> {}",
                        topic, event.getTag(), event.getKey(), consumer.getClass().getSimpleName());
                consumer.onMessage(event);
            } catch (Exception e) {
                // 内存总线无重投能力, 记录错误日志; 生产 RocketMQ 由 broker 重试
                log.error("[MQ-MEMORY] 消费失败 topic={} tag={} key={} consumer={}",
                        topic, event.getTag(), event.getKey(), consumer.getClass().getSimpleName(), e);
            }
        }
    }
}
