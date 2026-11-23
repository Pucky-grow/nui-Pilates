package com.yoyi.pilates.booking.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建预约请求.
 */
@Data
public class BookingCreateRequest {

    @NotNull(message = "排课不能为空")
    private Long scheduleId;

    /** 指定扣课时的课包(可选, 不传自动选择) */
    private Long userPackageId;

    /** 私教训练目标等备注 */
    @Size(max = 255)
    private String remark;
}
