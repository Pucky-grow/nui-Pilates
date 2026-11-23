package com.yoyi.pilates.common.constant;

/**
 * Redis Key 统一约定(见 docs/01 架构设计第 11 节).
 */
public final class RedisKeys {

    private static final String PREFIX = "pilates:";

    private RedisKeys() {
    }

    /** 课程库存: pilates:stock:{scheduleId} */
    public static String stock(long scheduleId) {
        return PREFIX + "stock:" + scheduleId;
    }

    /** 预约分布式锁: pilates:lock:booking:{scheduleId} */
    public static String bookingLock(long scheduleId) {
        return PREFIX + "lock:booking:" + scheduleId;
    }

    /** 幂等键: pilates:idempotent:{key} */
    public static String idempotent(String key) {
        return PREFIX + "idempotent:" + key;
    }

    /** 登录态标记: pilates:login:token:{token} (JWT 吊销依据) */
    public static String loginToken(String token) {
        return PREFIX + "login:token:" + token;
    }

    /** 热门课程 ZSet */
    public static final String HOT_COURSE = PREFIX + "course:hot";

    /** 首页 Banner 缓存 */
    public static final String HOME_BANNER = PREFIX + "home:banner";

    /** 课程列表缓存前缀 */
    public static String courseList(String queryHash) {
        return PREFIX + "course:list:" + queryHash;
    }

    /** 限流: pilates:ratelimit:{key} */
    public static String rateLimit(String key) {
        return PREFIX + "ratelimit:" + key;
    }

    /** 微信 session_key 缓存: pilates:wx:session:{openid} */
    public static String wxSession(String openid) {
        return PREFIX + "wx:session:" + openid;
    }

    /** 订阅消息授权计数: pilates:notify:subscribe:{userId}:{tplCode} */
    public static String subscribeCount(long userId, String tplCode) {
        return PREFIX + "notify:subscribe:" + userId + ":" + tplCode;
    }

    /** 用户信息缓存 */
    public static String userInfo(long userId) {
        return PREFIX + "user:" + userId;
    }
}
