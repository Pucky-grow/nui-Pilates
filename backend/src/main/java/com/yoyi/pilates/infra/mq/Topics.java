package com.yoyi.pilates.infra.mq;

/**
 * MQ Topic 常量.
 */
public final class Topics {

    private Topics() {
    }

    /** 预约域: 预约成功/取消/签到/完成 */
    public static final String BOOKING_EVENT = "BOOKING_EVENT";
    /** 交易域: 支付成功/退款成功 */
    public static final String ORDER_EVENT = "ORDER_EVENT";
    /** 训练域: 训练记录/评价 */
    public static final String TRAINING_EVENT = "TRAINING_EVENT";
    /** 会员域: 到期/课时不足提醒 */
    public static final String MEMBER_EVENT = "MEMBER_EVENT";
    /** 统计域: 经营数据变更 */
    public static final String STATS_EVENT = "STATS_EVENT";

    public static final String[] ALL = {BOOKING_EVENT, ORDER_EVENT, TRAINING_EVENT, MEMBER_EVENT, STATS_EVENT};
}
