package com.yoyi.pilates.booking.interfaces.dto;

import com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ScheduledBookingRequest(@NotNull @Valid ScheduleCreateRequest schedule,
                                      @Positive Long userId, @Size(max = 32) String memberReference,
                                      @NotBlank @Size(max = 255) String reason, @Size(max = 255) String remark) {
}
