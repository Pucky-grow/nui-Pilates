package com.yoyi.pilates.course.interfaces.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 单次排课请求.
 */
@Data
public class ScheduleCreateRequest {

    @NotNull(message = "课程不能为空")
    private Long courseId;

    @NotNull(message = "教练不能为空")
    private Long coachId;

    @NotNull(message = "教室不能为空")
    private Long classroomId;

    @NotNull(message = "上课日期不能为空")
    private LocalDate courseDate;

    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    @NotNull(message = "人数上限不能为空")
    @Min(value = 1, message = "人数上限至少为1")
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = CapacityDeserializer.class)
    private Integer maxCapacity;
}
