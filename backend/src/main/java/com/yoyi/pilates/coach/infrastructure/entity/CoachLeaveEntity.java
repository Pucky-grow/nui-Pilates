package com.yoyi.pilates.coach.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coach_leave")
public class CoachLeaveEntity extends BaseEntity {
    private Long coachId;
    private LocalDate leaveDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String reason;
    private String status;
    private Long approverId;
    private LocalDateTime approvedAt;
    private String rejectReason;
}
