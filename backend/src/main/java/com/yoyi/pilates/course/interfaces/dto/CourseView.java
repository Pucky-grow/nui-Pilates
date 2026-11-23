package com.yoyi.pilates.course.interfaces.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 课程视图(列表/详情共用).
 */
@Data
public class CourseView {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String cover;
    private String description;
    private Integer durationMinutes;
    private String type;
    private String difficulty;
    private Integer creditsCost;
    private BigDecimal price;
    private String targetUsers;
    private String trainingFocus;
    private String notices;
    private Integer sort;
    private String status;
}
