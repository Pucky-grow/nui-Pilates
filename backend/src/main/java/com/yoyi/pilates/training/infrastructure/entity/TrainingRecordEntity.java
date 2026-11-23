package com.yoyi.pilates.training.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 训练记录(training_record 表, 教练填写).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("training_record")
public class TrainingRecordEntity extends BaseEntity {

    private Long userId;
    private Long coachId;
    private Long bookingId;
    private Long scheduleId;
    private LocalDate trainingDate;
    private String goal;
    private String content;
    private String bodyParts;
    private String movements;
    /** LOW / MEDIUM / HIGH */
    private String intensity;
    private String completion;
    private String feedback;
    private String suggestion;
    private String remark;
}
