package com.yoyi.pilates.notify.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 站内消息(notification 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification")
public class NotificationEntity extends BaseEntity {

    private Long userId;
    private String templateCode;
    private String title;
    private String content;
    private String bizType;
    private String bizId;
    private Integer isRead;
    private LocalDateTime readAt;
}
