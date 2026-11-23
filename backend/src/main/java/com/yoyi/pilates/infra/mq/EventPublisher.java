package com.yoyi.pilates.infra.mq;

/**
 * 事件发布抽象: 业务代码只依赖此接口.
 * dev=内存事件总线；prod/staging=同事务 outbox 持久化派发。
 */
public interface EventPublisher {

    /**
     * 生产模式在当前业务事务中持久化事件；开发模式在提交后同步派发。
     *
     * @param topic Topic, 见 {@link Topics}
     * @param event 事件信封
     */
    void publish(String topic, DomainEvent event);
}
