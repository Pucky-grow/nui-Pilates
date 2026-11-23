package com.yoyi.pilates.course.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 教练保存请求.
 */
@Data
public class CoachSaveRequest {

    /** 关联后台账号(教练登录用), 可选 */
    private Long sysUserId;

    @NotBlank(message = "教练姓名不能为空")
    @Size(max = 32)
    private String name;

    private String avatar;

    @Size(max = 64)
    private String title;

    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(100)
    private Integer years;

    @Size(max = 512)
    private String specialties;

    private String bio;
    private Integer sort;
    @jakarta.validation.constraints.Pattern(regexp="ACTIVE|DISABLED", message="状态或类型无效")
    private String status;
}
