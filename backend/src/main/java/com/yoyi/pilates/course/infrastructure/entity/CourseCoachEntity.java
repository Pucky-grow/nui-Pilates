package com.yoyi.pilates.course.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_coach")
public class CourseCoachEntity extends BaseEntity {
    private Long courseId;
    private Long coachId;
    private String status;
    private String qualificationSource;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String remark;
}
