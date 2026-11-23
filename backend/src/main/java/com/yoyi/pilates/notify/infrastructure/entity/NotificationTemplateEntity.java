package com.yoyi.pilates.notify.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息模板(notification_template 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification_template")
public class NotificationTemplateEntity extends BaseEntity {

    private String code;
    private String name;
    private String titleTpl;
    private String contentTpl;
    private String wxTemplateId;
    private String status;
}
