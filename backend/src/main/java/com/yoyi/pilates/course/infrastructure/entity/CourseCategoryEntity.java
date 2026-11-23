package com.yoyi.pilates.course.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课程分类(course_category 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_category")
public class CourseCategoryEntity extends BaseEntity {

    private String code;
    private String name;
    private Integer sort;
    private String status;
}
