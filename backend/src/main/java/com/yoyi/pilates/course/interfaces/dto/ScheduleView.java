package com.yoyi.pilates.course.interfaces.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 排课视图(课程卡片): 聚合课程/教练/教室信息.
 */
@Data
public class ScheduleView {

    private Long id;
    private Long courseId;
    private String courseName;
    private String courseCover;
    private String courseType;
    private String difficulty;
    private String targetUsers;
    private String notices;
    private String trainingFocus;
    private Integer durationMinutes;
    private Integer creditsCost;
    private Long categoryId;
    private String categoryName;

    private Long coachId;
    private String coachName;
    private String coachAvatar;

    private Long classroomId;
    private String classroomName;
    private String classroomType;
    private String classroomEquipment;

    private LocalDate courseDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer maxCapacity;
    private Integer version;
    private Integer bookedCount;
    /** 剩余名额 */
    private Integer remaining;
    /** 名额状态: PLENTY充足/FEW较少/ALMOST_FULL即将满员/FULL已满 */
    private String availability;
    private String status;
    private boolean bookable;
    private String bookingStatus;
    private String unbookableReason;
    private String bookingMode;
    private boolean editable;
    private boolean cancellable;
    private boolean hasBookingHistory;
    private String managementReason;
}
