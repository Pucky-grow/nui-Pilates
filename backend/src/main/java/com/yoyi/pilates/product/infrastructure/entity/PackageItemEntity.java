package com.yoyi.pilates.product.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课包适用范围(package_item 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("package_item")
public class PackageItemEntity extends BaseEntity {

    private Long packageId;
    /** ALL / CATEGORY / COURSE / COACH */
    private String itemType;
    private Long itemId;
}
