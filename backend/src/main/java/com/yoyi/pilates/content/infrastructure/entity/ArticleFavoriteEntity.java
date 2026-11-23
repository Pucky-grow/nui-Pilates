package com.yoyi.pilates.content.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章收藏(article_favorite 表, article+user 唯一).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("article_favorite")
public class ArticleFavoriteEntity extends BaseEntity {

    private Long articleId;
    private Long userId;
}
