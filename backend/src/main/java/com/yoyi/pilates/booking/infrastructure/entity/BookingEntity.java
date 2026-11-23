package com.yoyi.pilates.booking.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 预约(booking 表).
 * (active_user_id, schedule_id) 生成列唯一索引 = 防重复预约最终兜底.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("booking")
public class BookingEntity extends BaseEntity {

    private String bookingNo;
    private Long userId;
    private Long scheduleId;
    private Long userPackageId;
    private Integer creditsUsed;
    /** PENDING待确认 / BOOKED已预约 / SIGNED_IN已签到 / COMPLETED已完成 / CANCELLED已取消 / NO_SHOW已爽约 / REFUNDED已退款 */
    private String status;
    private String cancelReason;
    private LocalDateTime cancelledAt;
    /** 私教预约: 训练目标 */
    private String remark;
}
