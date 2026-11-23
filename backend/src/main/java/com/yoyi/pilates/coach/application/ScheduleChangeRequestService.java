package com.yoyi.pilates.coach.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.coach.infrastructure.entity.ScheduleChangeRequestEntity;
import com.yoyi.pilates.coach.infrastructure.mapper.ScheduleChangeRequestMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.course.application.CourseQueryService;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest;
import com.yoyi.pilates.course.interfaces.dto.ScheduleView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调课申请服务: 提交/查看/审批(审批通过后应用到排课).
 */
@Service
@RequiredArgsConstructor
public class ScheduleChangeRequestService {

    private final ScheduleChangeRequestMapper requestMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final com.yoyi.pilates.course.application.ScheduleService scheduleService;
    private final CourseQueryService courseQueries;

    public record Preview(ScheduleView current, ScheduleCreateRequest proposed, int affectedCount) {}

    /** 教练提交调课申请(校验排课归属本人) */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long coachId, ScheduleChangeRequestEntity request) {
        CourseScheduleEntity schedule = scheduleMapper.selectById(request.getScheduleId());
        if (schedule == null) {
            throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        }
        if (!schedule.getCoachId().equals(coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能对本人授课的课程申请调课");
        }
        if (!"SCHEDULED".equals(schedule.getStatus())
                || !StudioTime.now().isBefore(LocalDateTime.of(schedule.getCourseDate(), schedule.getStartTime()))) {
            throw BusinessException.of(ResultCode.CONFLICT, "仅可申请调整尚未开始的排课");
        }
        var proposed = proposed(schedule, request);
        if (proposed.getCourseDate().isBefore(StudioTime.today())
                || !proposed.getEndTime().isAfter(proposed.getStartTime())
                || !LocalDateTime.of(proposed.getCourseDate(), proposed.getStartTime()).isAfter(StudioTime.now())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择尚未开始且时间范围有效的排课");
        }
        if (request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 255)
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请填写1至255字的调课原因");
        request.setId(null);
        request.setCoachId(coachId);
        request.setStatus("PENDING");
        request.setApproverId(null);
        request.setHandledAt(null);
        request.setRejectReason(null);
        requestMapper.insert(request);
        return request.getId();
    }

    public List<ScheduleChangeRequestEntity> myRequests(Long coachId) {
        return requestMapper.selectList(new LambdaQueryWrapper<ScheduleChangeRequestEntity>()
                .eq(ScheduleChangeRequestEntity::getCoachId, coachId)
                .orderByDesc(ScheduleChangeRequestEntity::getId));
    }

    public List<ScheduleChangeRequestEntity> pendingRequests() {
        return requestMapper.selectList(new LambdaQueryWrapper<ScheduleChangeRequestEntity>()
                .eq(ScheduleChangeRequestEntity::getStatus, "PENDING")
                .orderByAsc(ScheduleChangeRequestEntity::getId));
    }

    public Preview preview(Long requestId) {
        var request = pending(requestId);
        var current = scheduleService.requireSchedule(request.getScheduleId());
        var view = courseQueries.scheduleDetail(current.getId());
        return new Preview(view, proposed(current, request), scheduleService.booked(current.getId()).size());
    }

    public void approve(Long requestId, Long approverId, boolean approve, String rejectReason,
                        Integer scheduleVersion, Integer affectedCount) {
        ScheduleChangeRequestEntity request = pending(requestId);
        Runnable mark = () -> {
            int changed = requestMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ScheduleChangeRequestEntity>()
                    .eq(ScheduleChangeRequestEntity::getId, requestId).eq(ScheduleChangeRequestEntity::getStatus, "PENDING")
                    .set(ScheduleChangeRequestEntity::getStatus, approve ? "APPROVED" : "REJECTED")
                    .set(ScheduleChangeRequestEntity::getApproverId, approverId).set(ScheduleChangeRequestEntity::getHandledAt, StudioTime.now())
                    .set(ScheduleChangeRequestEntity::getRejectReason, rejectReason));
            if (changed != 1) throw BusinessException.of(ResultCode.CONFLICT, "该申请已处理");
        };
        if (!approve) { mark.run(); return; }
        CourseScheduleEntity schedule = scheduleService.requireSchedule(request.getScheduleId());
        if (!schedule.getCoachId().equals(request.getCoachId())) {
            throw BusinessException.of(ResultCode.CONFLICT, "授课教练已变更，请重新提交调课申请");
        }
        scheduleService.approveChange(schedule.getId(), proposed(schedule, request), scheduleVersion, affectedCount, mark);
    }

    private ScheduleChangeRequestEntity pending(Long requestId) {
        ScheduleChangeRequestEntity request = requireRequest(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "该申请已处理");
        }
        return request;
    }

    private ScheduleCreateRequest proposed(CourseScheduleEntity schedule, ScheduleChangeRequestEntity request) {
        var edit = new ScheduleCreateRequest();
        org.springframework.beans.BeanUtils.copyProperties(schedule, edit);
        if (request.getType() == null) throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择调课类型");
        switch (request.getType()) {
            case "TIME" -> {
                if (request.getNewCourseDate() == null || request.getNewStartTime() == null || request.getNewEndTime() == null)
                    throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择完整的新日期和时间");
                edit.setCourseDate(request.getNewCourseDate());
                edit.setStartTime(request.getNewStartTime());
                edit.setEndTime(request.getNewEndTime());
            }
            case "COACH" -> {
                if (request.getNewCoachId() == null) throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择新教练");
                edit.setCoachId(request.getNewCoachId());
            }
            case "CLASSROOM" -> {
                if (request.getNewClassroomId() == null) throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择新教室");
                edit.setClassroomId(request.getNewClassroomId());
            }
            default -> throw BusinessException.of(ResultCode.BAD_REQUEST, "未知调课类型");
        }
        if ("PRIVATE".equals(courseQueries.requireCourse(schedule.getCourseId()).getType())) {
            edit.setMaxCapacity(1);
        }
        return edit;
    }

    public ScheduleChangeRequestEntity requireRequest(Long requestId) {
        ScheduleChangeRequestEntity request = requestMapper.selectById(requestId);
        if (request == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "调课申请不存在");
        }
        return request;
    }
}
