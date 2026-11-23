package com.yoyi.pilates.infra.mq;

/**
 * 事件消费者: 每个业务关注点实现一个 Bean, 按 topic 订阅.
 * 消费必须幂等(以 event.key 判重).
 */
public interface EventConsumer {

    /** 订阅的 Topic */
    String topic();

    /**
     * 处理事件.
     *
     * @param event 事件信封(tag/key/payload)
     */
    void onMessage(DomainEvent event);
}
