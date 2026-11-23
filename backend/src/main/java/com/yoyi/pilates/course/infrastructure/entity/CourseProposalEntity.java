package com.yoyi.pilates.course.infrastructure.entity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_proposal")
public class CourseProposalEntity extends BaseEntity {
    private String proposalNo;
    private Long coachId;
    private Long categoryId;
    private String courseName;
    private String courseType;
    private String description;
    private String trainingGoal;
    private String targetAudience;
    private String difficulty;
    private Integer duration;
    private Integer suggestedCapacity;
    private Integer creditCost;
    private String content;
    private String precautions;
    private String status;
    private String rejectReason;
    private Long reviewerId;
    private LocalDateTime reviewedAt;
    private Long courseId;

    /** 提交教练姓名(非表字段, 列表查询时回填用于审核展示) */
    @TableField(exist = false)
    private String coachName;
}
