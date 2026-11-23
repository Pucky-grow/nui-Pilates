package com.yoyi.pilates.content.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文章(article 表, 优仪课堂).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("article")
public class ArticleEntity extends BaseEntity {

    private Long categoryId;
    private String title;
    private String cover;
    private String content;
    private String videoUrl;
    private String author;
    private String tags;
    private Integer views;
    private Integer likes;
    /** DRAFT / PUBLISHED / OFFLINE */
    private String status;
    private LocalDateTime publishedAt;
}
