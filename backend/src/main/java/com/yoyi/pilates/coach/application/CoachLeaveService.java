package com.yoyi.pilates.coach.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.coach.infrastructure.entity.CoachLeaveEntity;
import com.yoyi.pilates.coach.infrastructure.mapper.CoachLeaveMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 教练请假服务: 提交(冲突校验)/查看/取消/审批.
 */
@Service
@RequiredArgsConstructor
public class CoachLeaveService {

    private final com.yoyi.pilates.booking.application.ScheduleExecution execution;
    private final CoachLeaveMapper leaveMapper;
    private final CourseScheduleMapper scheduleMapper;

    /**
     * 提交请假: 校验该时段是否已有排课.
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long coachId, CoachLeaveEntity leave) {
        leave.setCoachId(coachId);
        leave.setStatus("PENDING");
        // 冲突校验: 请假时段与已有 SCHEDULED 排课重叠
        boolean conflict = scheduleMapper.exists(new LambdaQueryWrapper<CourseScheduleEntity>()
                .eq(CourseScheduleEntity::getCoachId, coachId)
                .eq(CourseScheduleEntity::getCourseDate, leave.getLeaveDate())
                .eq(CourseScheduleEntity::getStatus, "SCHEDULED")
                .lt(CourseScheduleEntity::getStartTime, leave.getEndTime())
                .gt(CourseScheduleEntity::getEndTime, leave.getStartTime()));
        if (conflict) {
            throw BusinessException.of(ResultCode.CONFLICT, "当前时间存在已排课程，请先处理相关课程");
        }
        leaveMapper.insert(leave);
        return leave.getId();
    }

    public List<CoachLeaveEntity> myLeaves(Long coachId) {
        return leaveMapper.selectList(new LambdaQueryWrapper<CoachLeaveEntity>()
                .eq(CoachLeaveEntity::getCoachId, coachId)
                .orderByDesc(CoachLeaveEntity::getLeaveDate)
                .orderByDesc(CoachLeaveEntity::getId));
    }

    /** 教练取消待审批的请假 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long coachId, Long leaveId) {
        CoachLeaveEntity leave = requireLeave(leaveId);
        if (!leave.getCoachId().equals(coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        if (!"PENDING".equals(leave.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "仅待审批状态可取消");
        }
        leave.setStatus("CANCELLED");
        leaveMapper.updateById(leave);
    }

    /** 管理员审批 */
    public void approve(Long leaveId, Long approverId, boolean approve, String rejectReason) {
        execution.run(null, null, true, () -> {
        CoachLeaveEntity leave = requireLeave(leaveId);
        if (!"PENDING".equals(leave.getStatus())) {
            throw BusinessException.of(ResultCode.CONFLICT, "该申请已处理");
        }
        if (approve && scheduleMapper.exists(new LambdaQueryWrapper<CourseScheduleEntity>()
                .eq(CourseScheduleEntity::getCoachId, leave.getCoachId()).eq(CourseScheduleEntity::getCourseDate, leave.getLeaveDate())
                .eq(CourseScheduleEntity::getStatus, "SCHEDULED").lt(CourseScheduleEntity::getStartTime, leave.getEndTime())
                .gt(CourseScheduleEntity::getEndTime, leave.getStartTime()))) {
            throw BusinessException.of(ResultCode.CONFLICT, "已有重叠排课，不能批准请假");
        }
        leave.setStatus(approve ? "APPROVED" : "REJECTED");
        leave.setApproverId(approverId);
        leave.setApprovedAt(LocalDateTime.now());
        leave.setRejectReason(rejectReason);
        if (leaveMapper.updateById(leave) != 1) throw BusinessException.of(ResultCode.CONFLICT);
        return null;
        });
    }

    public CoachLeaveEntity requireLeave(Long leaveId) {
        CoachLeaveEntity leave = leaveMapper.selectById(leaveId);
        if (leave == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "请假记录不存在");
        }
        return leave;
    }

    /** 待审批请假列表(管理员) */
    public List<CoachLeaveEntity> pendingLeaves() {
        return leaveMapper.selectList(new LambdaQueryWrapper<CoachLeaveEntity>()
                .eq(CoachLeaveEntity::getStatus, "PENDING")
                .orderByAsc(CoachLeaveEntity::getLeaveDate));
    }

    /** 判断教练某日是否请假中(供私教预约校验) */
    public boolean isOnLeave(Long coachId, LocalDate date, java.time.LocalTime start, java.time.LocalTime end) {
        return leaveMapper.exists(new LambdaQueryWrapper<CoachLeaveEntity>()
                .eq(CoachLeaveEntity::getCoachId, coachId)
                .eq(CoachLeaveEntity::getLeaveDate, date)
                .eq(CoachLeaveEntity::getStatus, "APPROVED")
                .lt(CoachLeaveEntity::getStartTime, end)
                .gt(CoachLeaveEntity::getEndTime, start));
    }
}
