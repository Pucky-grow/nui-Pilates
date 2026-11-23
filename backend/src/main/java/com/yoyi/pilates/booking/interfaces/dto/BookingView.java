package com.yoyi.pilates.booking.interfaces.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约视图(列表/详情).
 */
@Data
public class BookingView {

    private Long id;
    private String bookingNo;
    private String status;
    private Integer creditsUsed;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    private String publicCancelReason;
    private String attendanceSource;

    // ---- 课程信息 ----
    private Long scheduleId;
    private Long courseId;
    private String courseName;
    private String courseCover;
    private String courseType;
    private LocalDate courseDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String coachName;
    private String classroomName;

    // ---- 课包信息 ----
    private Long userPackageId;
    private String packageName;

    // ---- 可操作标记(前端按钮控制) ----
    private boolean canCancel;
    private boolean canCheckin;
    private boolean canReview;
    private com.yoyi.pilates.ledger.StudioCancellationCapture.MemberTerms cancellationTerms;
}
