package com.yoyi.pilates.booking.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 签到出勤(attendance 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("attendance")
public class AttendanceEntity extends BaseEntity {

    private Long bookingId;
    private Long userId;
    private Long scheduleId;
    /** USER_SCAN / COACH / ADMIN */
    private String checkinType;
    /** PRESENT / ABSENT */
    private String status;
    private Long operatorId;
    private LocalDateTime checkinAt;
}
