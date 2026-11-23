package com.yoyi.pilates.course.interfaces.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 教室保存请求.
 */
@Data
public class ClassroomSaveRequest {

    @NotBlank(message = "教室名称不能为空")
    @jakarta.validation.constraints.Size(max=32)
    private String name;

    @NotBlank(message = "教室类型不能为空")
    @jakarta.validation.constraints.Pattern(regexp="GROUP|PRIVATE|REFORMER|MAT",message="教室类型无效")
    private String type;

    @NotNull(message = "容纳人数不能为空")
    @Min(value = 1, message = "容纳人数至少为1")
    private Integer capacity;

    @jakarta.validation.constraints.Size(max=512)
    private String equipment;
    @jakarta.validation.constraints.Pattern(regexp="ACTIVE|DISABLED", message="状态或类型无效")
    private String status;
    @jakarta.validation.constraints.Size(max=512)
    private String remark;
}
