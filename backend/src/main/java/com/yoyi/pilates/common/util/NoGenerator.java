package com.yoyi.pilates.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务单号生成器: 前缀 + 时间戳 + 序列 + 随机数, 如 BK20260923203100123.
 * 单实例内有序唯一; 分布式场景追加随机后缀防碰撞, 最终由数据库唯一索引兜底.
 */
public final class NoGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private NoGenerator() {
    }

    public static String next(String prefix) {
        int seq = Math.abs(SEQ.getAndIncrement() % 1000);
        int rand = ThreadLocalRandom.current().nextInt(100, 999);
        return prefix + FMT.format(LocalDateTime.now()) + String.format("%03d", seq) + rand;
    }

    /** 预约单号 */
    public static String bookingNo() {
        return next("BK");
    }

    /** 订单号 */
    public static String orderNo() {
        return next("OD");
    }

    /** 支付单号 */
    public static String paymentNo() {
        return next("PY");
    }

    /** 退款单号 */
    public static String refundNo() {
        return next("RF");
    }

    /** 会员编号 */
    public static String memberNo() {
        return next("MB");
    }
}
