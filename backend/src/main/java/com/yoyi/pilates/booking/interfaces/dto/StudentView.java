package com.yoyi.pilates.booking.interfaces.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 教练端学员视图(某节课的预约学员).
 */
@Data
public class StudentView {

    private Long bookingId;
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private String levelName;
    private String bookingStatus;
    private String attendanceSource;
    private boolean canCheckin;
    private Integer remainingCredits;
    private String lastTrainingAt;
    private String remark;

    // ---- 课程信息(教练课表用) ----
    private Long scheduleId;
    private String courseName;
    private LocalDate courseDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String classroomName;
    private Integer bookedCount;
    private Integer maxCapacity;
}
