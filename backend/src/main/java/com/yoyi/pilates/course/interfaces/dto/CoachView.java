package com.yoyi.pilates.course.interfaces.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 教练视图.
 */
@Data
public class CoachView {

    private Long id;
    private String name;
    private String avatar;
    private String title;
    private Integer years;
    /** 专业方向(逗号分隔原样返回, 前端拆分) */
    private String specialties;
    private String bio;
    private BigDecimal rating;
    private List<CertificateItem> certificates;

    @Data
    public static class CertificateItem {
        private String name;
        private String issuer;
        private String imageUrl;
    }
}
