package com.yoyi.pilates.common.result;

import lombok.Getter;

/**
 * 统一业务状态码.
 * 0=成功; 4xxxx=业务/客户端错误; 5xxxx=服务端错误.
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "success"),

    // ---- 通用 40xxx ----
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权限访问"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "请求冲突, 请勿重复提交"),
    RATE_LIMITED(42900, "请求过于频繁, 请稍后再试"),

    // ---- 课程/预约 41xxx ----
    COURSE_NOT_FOUND(41001, "课程不存在"),
    SCHEDULE_NOT_FOUND(41002, "课程排期不存在"),
    SCHEDULE_FINISHED(41003, "课程已结束"),
    SCHEDULE_FULL(41004, "课程名额已满"),
    BOOKING_DUPLICATED(41005, "您已预约该课程, 请勿重复预约"),
    BOOKING_NOT_FOUND(41006, "预约记录不存在"),
    BOOKING_STATUS_ILLEGAL(41007, "当前预约状态不允许该操作"),
    BOOKING_DEADLINE_PASSED(41008, "已超过预约截止时间"),
    BOOKING_CANCEL_FORBIDDEN(41009, "已超过可取消时间, 请联系客服处理"),
    BOOKING_LIMITED(41010, "您的账号当前被限制预约"),

    // ---- 课包/课时 42xxx ----
    PACKAGE_NOT_FOUND(42001, "课包不存在"),
    PACKAGE_OFF_SHELF(42002, "课包已下架"),
    PACKAGE_NEW_USER_ONLY(42003, "该课包仅限新会员购买"),
    PACKAGE_PURCHASE_LIMIT(42004, "已达到该课包限购数量"),
    USER_PACKAGE_NOT_FOUND(42005, "无可用课包, 请先购买课包"),
    USER_PACKAGE_EXPIRED(42006, "课包已过期"),
    CREDITS_NOT_ENOUGH(42007, "当前课时不足, 请购买或充值课包"),
    PACKAGE_NOT_APPLICABLE(42008, "课包不适用于该课程"),

    // ---- 交易 43xxx ----
    ORDER_NOT_FOUND(43001, "订单不存在"),
    ORDER_STATUS_ILLEGAL(43002, "订单状态不允许该操作"),
    PAY_FAILED(43003, "支付失败"),
    PAY_SIGN_INVALID(43004, "支付回调验签失败"),
    REFUND_NOT_ALLOWED(43005, "该订单不支持退款"),

    // ---- 营销 44xxx ----
    COUPON_NOT_FOUND(44001, "优惠券不存在"),
    COUPON_UNAVAILABLE(44002, "优惠券不可用"),
    POINTS_NOT_ENOUGH(44003, "积分不足"),

    // ---- 系统 50xxx ----
    SYSTEM_ERROR(50000, "系统繁忙, 请稍后再试"),
    CONCURRENT_MODIFY(50001, "数据已被他人修改, 请刷新后重试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
