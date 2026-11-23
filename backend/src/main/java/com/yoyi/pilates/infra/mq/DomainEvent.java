package com.yoyi.pilates.infra.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 领域事件统一信封: topic 内按 tag 区分子事件, key 用于消费幂等.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DomainEvent implements Serializable {

    /** 子事件类型, 如 BOOKING_CREATED / ORDER_PAID */
    private String tag;

    /** 业务唯一键(消费幂等依据), 如 bookingNo / orderNo */
    private String key;

    /** 业务负载(JSON 可序列化对象) */
    private Object payload;

    private LocalDateTime occurredAt = LocalDateTime.now();

    public static DomainEvent of(String tag, String key, Object payload) {
        return new DomainEvent(tag, key, payload, LocalDateTime.now());
    }
}
