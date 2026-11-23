package com.yoyi.pilates.home.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("home_hot_course")
public class HomeHotCourseEntity extends BaseEntity {
    private Long courseId;
    private Integer sort;
    private Integer enabled;
}
