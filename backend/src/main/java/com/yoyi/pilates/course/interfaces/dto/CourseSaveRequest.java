package com.yoyi.pilates.course.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 课程保存请求(新增/编辑).
 */
@Data
public class CourseSaveRequest {

    /** 新增时省略则使用默认分类，编辑时省略则保留原分类。 */
    private Long categoryId;

    @NotBlank(message = "课程名称不能为空")
    @Size(max = 64)
    private String name;

    @jakarta.validation.constraints.Size(max=512)
    private String cover;
    private String description;

    @NotNull(message = "课程时长不能为空")
    @Min(value = 15, message = "课程时长至少15分钟")
    private Integer durationMinutes;

    @NotBlank(message = "课程类型不能为空")
    @jakarta.validation.constraints.Pattern(regexp="GROUP|PRIVATE|EXPERIENCE", message="状态或类型无效")
    private String type;

    @NotBlank(message = "课程难度不能为空")
    @jakarta.validation.constraints.Pattern(regexp="BEGINNER|JUNIOR|INTERMEDIATE|ADVANCED", message="状态或类型无效")
    private String difficulty;

    @NotNull(message = "消耗课时不能为空")
    @Min(value = 1, message = "消耗课时至少为1")
    private Integer creditsCost;

    @DecimalMin(value = "0.00", message = "价格不能为负")
    private BigDecimal price;

    @jakarta.validation.constraints.Size(max=512)
    private String targetUsers;
    @jakarta.validation.constraints.Size(max=512)
    private String trainingFocus;
    @jakarta.validation.constraints.Size(max=512)
    private String notices;
    private Integer sort;
    @jakarta.validation.constraints.Pattern(regexp="ON_SHELF|OFF_SHELF", message="状态或类型无效")
    private String status;
}
