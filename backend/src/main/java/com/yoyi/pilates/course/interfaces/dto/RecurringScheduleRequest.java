package com.yoyi.pilates.course.interfaces.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * 周期排课请求: 在 [fromDate, toDate] 范围内, 按 weekdays 批量生成.
 * 例: 每周一三五 18:30 器械普拉提.
 */
@Data
public class RecurringScheduleRequest {

    @NotNull(message = "课程不能为空")
    private Long courseId;

    @NotNull(message = "教练不能为空")
    private Long coachId;

    @NotNull(message = "教室不能为空")
    private Long classroomId;

    @NotNull(message = "开始日期不能为空")
    private LocalDate fromDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate toDate;

    /** 星期几上课: 1=周一 ... 7=周日 */
    @NotEmpty(message = "上课星期不能为空")
    private Set<Integer> weekdays;

    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    @NotNull(message = "人数上限不能为空")
    @Min(value = 1, message = "人数上限至少为1")
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = CapacityDeserializer.class)
    private Integer maxCapacity;
}
