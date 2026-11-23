package com.yoyi.pilates.course.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 排课(course_schedule 表) —— 预约库存的权威来源.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_schedule")
public class CourseScheduleEntity extends BaseEntity {

    private Long courseId;
    private Long coachId;
    private Long classroomId;
    private LocalDate courseDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer maxCapacity;
    private Integer bookedCount;
    /** SCHEDULED / CANCELLED / FINISHED */
    private String status;
    private String cancelReason;

    @Version
    private Integer version;
}
