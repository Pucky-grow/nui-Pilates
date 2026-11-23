package com.yoyi.pilates.user.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_privacy")
public class UserPrivacyEntity extends BaseEntity {
    private Long userId;
    private Integer allowAssessmentView;
    private Integer allowTrainingView;
    private Integer allowPhoto;
    private Integer allowShowNickname;
    private Integer allowMarketing;
}
