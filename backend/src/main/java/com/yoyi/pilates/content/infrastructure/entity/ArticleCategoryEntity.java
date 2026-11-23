package com.yoyi.pilates.content.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章分类(article_category 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("article_category")
public class ArticleCategoryEntity extends BaseEntity {

    private String code;
    private String name;
    private Integer sort;
    private String status;
}
