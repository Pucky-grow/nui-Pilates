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
@TableName("schedule_change_request")
public class ScheduleChangeRequestEntity extends BaseEntity {
    private Long scheduleId;
    private Long coachId;
    private String type;
    private LocalDate newCourseDate;
    private LocalTime newStartTime;
    private LocalTime newEndTime;
    private Long newCoachId;
    private Long newClassroomId;
    private String reason;
    private String status;
    private Long approverId;
    private LocalDateTime handledAt;
    private String rejectReason;
}
