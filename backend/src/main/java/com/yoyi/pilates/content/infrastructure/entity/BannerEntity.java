package com.yoyi.pilates.content.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 首页 Banner(banner 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("banner")
public class BannerEntity extends BaseEntity {

    private String imageUrl;
    private String title;
    private String subtitle;
    /** NONE / COURSE / PACKAGE / ACTIVITY / ARTICLE / URL */
    private String linkType;
    private String linkValue;
    private Integer sort;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;
}
