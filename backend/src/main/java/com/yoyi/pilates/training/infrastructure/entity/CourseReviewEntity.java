package com.yoyi.pilates.training.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课程评价(course_review 表, booking_id 唯一 = 一节课只能评价一次).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_review")
public class CourseReviewEntity extends BaseEntity {

    private Long bookingId;
    private Long userId;
    private Long scheduleId;
    private Long courseId;
    private Long coachId;
    private Integer courseScore;
    private Integer coachScore;
    private Integer envScore;
    private String content;
    /** 图片 JSON 数组字符串 */
    private String images;
    /** 标签, 逗号分隔 */
    private String tags;
    /** VISIBLE / HIDDEN */
    private String status;
}
