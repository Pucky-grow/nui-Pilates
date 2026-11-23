package com.yoyi.pilates.training.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("training_record_template")
public class TrainingRecordTemplateEntity extends BaseEntity {
    private Long coachId;
    private String name;
    private String content;
    private Integer sort;
}
