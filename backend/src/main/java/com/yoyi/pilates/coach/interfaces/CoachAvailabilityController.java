package com.yoyi.pilates.coach.interfaces;

import com.yoyi.pilates.coach.application.CoachAvailabilityService;
import com.yoyi.pilates.coach.infrastructure.entity.CoachAvailabilityEntity;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 教练可预约时间接口.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoachAvailabilityController {

    private final CoachAvailabilityService availabilityService;

    /** 教练设置自己的可预约时间 */
    @PutMapping("/coach-end/availability")
    public Result<Void> setAvailability(@RequestBody List<CoachAvailabilityEntity> availability) {
        availabilityService.setAvailability(requireCoach().getCoachId(), availability);
        return Result.ok();
    }

    /** 教练查看自己的可预约时间 */
    @GetMapping("/coach-end/availability")
    public Result<List<CoachAvailabilityEntity>> getAvailability() {
        return Result.ok(availabilityService.getAvailability(requireCoach().getCoachId()));
    }

    /** 学员查看教练某日可预约时段(私教预约选时间) */
    @GetMapping("/coaches/{coachId}/available-slots")
    public Result<List<Map<String, Object>>> availableSlots(
            @PathVariable Long coachId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(availabilityService.availableSlots(coachId, date));
    }

    private LoginUser requireCoach() {
        LoginUser user = SecurityUtils.requireUser();
        if (!user.isCoach() && !user.isAdminRole()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "仅教练账号可访问");
        }
        if (user.getCoachId() == null) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "账号未关联教练档案");
        }
        return user;
    }
}
