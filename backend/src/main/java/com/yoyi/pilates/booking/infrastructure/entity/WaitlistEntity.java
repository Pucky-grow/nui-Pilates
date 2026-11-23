package com.yoyi.pilates.booking.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("waitlist")
public class WaitlistEntity extends BaseEntity {
    private Long scheduleId;
    private Long userId;
    @com.baomidou.mybatisplus.annotation.TableField("`rank`") // MySQL 8 RANK 是保留字。
    private Integer rank;
    private String status;
    private LocalDateTime convertedAt;
    private LocalDateTime expireAt;
}
