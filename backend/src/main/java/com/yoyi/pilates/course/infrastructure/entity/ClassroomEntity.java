package com.yoyi.pilates.course.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 教室(classroom 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("classroom")
public class ClassroomEntity extends BaseEntity {

    private String name;
    /** GROUP / PRIVATE / REFORMER / MAT */
    private String type;
    private Integer capacity;
    private String equipment;
    private String status;
    private String remark;
}
