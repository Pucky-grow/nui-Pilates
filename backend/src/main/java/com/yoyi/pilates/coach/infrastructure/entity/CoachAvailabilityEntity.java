package com.yoyi.pilates.coach.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coach_availability")
public class CoachAvailabilityEntity extends BaseEntity {
    private Long coachId;
    private Integer weekday;
    private LocalTime startTime;
    private LocalTime endTime;
}
