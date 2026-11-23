package com.yoyi.pilates.course.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_version")
public class CourseVersionEntity extends BaseEntity {
    private Long courseId;
    private Integer versionNo;
    private Long operatorId;
    private String operatorName;
    private String changeContent;
    private String reason;
}
