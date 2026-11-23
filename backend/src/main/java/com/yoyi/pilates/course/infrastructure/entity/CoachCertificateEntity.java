package com.yoyi.pilates.course.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 教练证书(coach_certificate 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coach_certificate")
public class CoachCertificateEntity extends BaseEntity {

    private Long coachId;
    private String name;
    private String issuer;
    private LocalDate issuedAt;
    private String imageUrl;
}
