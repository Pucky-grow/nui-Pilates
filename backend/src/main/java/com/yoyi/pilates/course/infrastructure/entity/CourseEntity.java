package com.yoyi.pilates.course.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 课程(course 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course")
public class CourseEntity extends BaseEntity {

    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Long categoryId;
    private String name;
    private String cover;
    private String description;
    private Integer durationMinutes;
    /** GROUP / PRIVATE / EXPERIENCE */
    private String type;
    /** BEGINNER / JUNIOR / INTERMEDIATE / ADVANCED */
    private String difficulty;
    private Integer creditsCost;
    private BigDecimal price;
    private String targetUsers;
    private String trainingFocus;
    private String notices;
    private Integer sort;
    /** ON_SHELF / OFF_SHELF */
    private String status;
}
