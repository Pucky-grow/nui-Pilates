package com.yoyi.pilates.coach.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.coach.infrastructure.entity.CoachAvailabilityEntity;
import com.yoyi.pilates.coach.infrastructure.entity.CoachLeaveEntity;
import com.yoyi.pilates.coach.infrastructure.mapper.CoachAvailabilityMapper;
import com.yoyi.pilates.coach.infrastructure.mapper.CoachLeaveMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教练可预约时间服务: 设置/查询/计算可用时段(工作时间 − 已排课 − 请假).
 */
@Service
@RequiredArgsConstructor
public class CoachAvailabilityService {

    private final CoachAvailabilityMapper availabilityMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CoachLeaveMapper leaveMapper;

    /** 全量替换教练可用时间 */
    @Transactional(rollbackFor = Exception.class)
    public void setAvailability(Long coachId, List<CoachAvailabilityEntity> availability) {
        availabilityMapper.delete(new LambdaQueryWrapper<CoachAvailabilityEntity>()
                .eq(CoachAvailabilityEntity::getCoachId, coachId));
        for (CoachAvailabilityEntity item : availability) {
            item.setId(null);
            item.setCoachId(coachId);
            availabilityMapper.insert(item);
        }
    }

    public List<CoachAvailabilityEntity> getAvailability(Long coachId) {
        return availabilityMapper.selectList(new LambdaQueryWrapper<CoachAvailabilityEntity>()
                .eq(CoachAvailabilityEntity::getCoachId, coachId)
                .orderByAsc(CoachAvailabilityEntity::getWeekday)
                .orderByAsc(CoachAvailabilityEntity::getStartTime));
    }

    /**
     * 计算教练某日可预约时段(供私教预约).
     *
     * @return [{"startTime":"09:00","endTime":"10:00"}, ...]
     */
    public List<Map<String, Object>> availableSlots(Long coachId, LocalDate date) {
        int weekday = date.getDayOfWeek().getValue();
        List<CoachAvailabilityEntity> availability = availabilityMapper.selectList(
                new LambdaQueryWrapper<CoachAvailabilityEntity>()
                        .eq(CoachAvailabilityEntity::getCoachId, coachId)
                        .eq(CoachAvailabilityEntity::getWeekday, weekday));
        List<Interval> free = new ArrayList<>();
        for (CoachAvailabilityEntity a : availability) {
            free.add(new Interval(a.getStartTime(), a.getEndTime()));
        }
        if (free.isEmpty()) {
            return List.of();
        }

        // 减去已排课
        List<CourseScheduleEntity> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getCoachId, coachId)
                        .eq(CourseScheduleEntity::getCourseDate, date)
                        .in(CourseScheduleEntity::getStatus, List.of("SCHEDULED")));
        for (CourseScheduleEntity s : schedules) {
            free = subtract(free, new Interval(s.getStartTime(), s.getEndTime()));
        }

        // 减去已通过请假
        List<CoachLeaveEntity> leaves = leaveMapper.selectList(
                new LambdaQueryWrapper<CoachLeaveEntity>()
                        .eq(CoachLeaveEntity::getCoachId, coachId)
                        .eq(CoachLeaveEntity::getLeaveDate, date)
                        .eq(CoachLeaveEntity::getStatus, "APPROVED"));
        for (CoachLeaveEntity l : leaves) {
            free = subtract(free, new Interval(l.getStartTime(), l.getEndTime()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Interval interval : free) {
            Map<String, Object> m = new HashMap<>();
            m.put("startTime", interval.start.toString());
            m.put("endTime", interval.end.toString());
            result.add(m);
        }
        return result;
    }

    // ---------------- 区间运算 ----------------

    private List<Interval> subtract(List<Interval> list, Interval cut) {
        List<Interval> result = new ArrayList<>();
        for (Interval iv : list) {
            // 无重叠
            if (!iv.end.isAfter(cut.start) || !cut.end.isAfter(iv.start)) {
                result.add(iv);
                continue;
            }
            // 左侧剩余
            if (iv.start.isBefore(cut.start)) {
                result.add(new Interval(iv.start, cut.start));
            }
            // 右侧剩余
            if (iv.end.isAfter(cut.end)) {
                result.add(new Interval(cut.end, iv.end));
            }
        }
        return result;
    }

    private static class Interval {
        final LocalTime start;
        final LocalTime end;

        Interval(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }
    }
}
