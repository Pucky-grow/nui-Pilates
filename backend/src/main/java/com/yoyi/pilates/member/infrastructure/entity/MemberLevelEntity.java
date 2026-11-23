package com.yoyi.pilates.member.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 会员等级(member_level 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("member_level")
public class MemberLevelEntity extends BaseEntity {

    private String code;
    private String name;
    private BigDecimal discount;
    private Integer sort;
    private String status;
}
