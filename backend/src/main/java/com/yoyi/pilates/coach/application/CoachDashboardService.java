package com.yoyi.pilates.coach.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import com.yoyi.pilates.training.infrastructure.mapper.TrainingRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教练工作台今日概览统计.
 */
@Service
@RequiredArgsConstructor
public class CoachDashboardService {

    private final CourseScheduleMapper scheduleMapper;
    private final BookingMapper bookingMapper;
    private final TrainingRecordMapper trainingRecordMapper;

    public Map<String, Object> dashboard(Long coachId) {
        LocalDate today = com.yoyi.pilates.common.util.StudioTime.today();
        List<CourseScheduleEntity> todaySchedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getCoachId, coachId)
                        .eq(CourseScheduleEntity::getCourseDate, today)
                        .ne(CourseScheduleEntity::getStatus, "CANCELLED"));

        long total = todaySchedules.size();
        long finished = todaySchedules.stream().filter(s -> "FINISHED".equals(s.getStatus())).count();
        long upcoming = todaySchedules.stream().filter(s -> "SCHEDULED".equals(s.getStatus())).count();

        // 今日学员数(去重)
        List<Long> scheduleIds = todaySchedules.stream().map(CourseScheduleEntity::getId).toList();
        long studentCount = scheduleIds.isEmpty() ? 0
                : bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                        .in(BookingEntity::getScheduleId, scheduleIds)
                        .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED")));

        // 待填写训练记录数(已签到/已完成但未填写)
        long pendingRecords = scheduleIds.isEmpty() ? 0 : bookingMapper.selectCount(
                new LambdaQueryWrapper<BookingEntity>()
                        .in(BookingEntity::getScheduleId, scheduleIds)
                        .in(BookingEntity::getStatus, List.of("SIGNED_IN", "COMPLETED"))
                        .notExists("SELECT 1 FROM training_record tr WHERE tr.booking_id = booking.id AND tr.deleted = 0"));

        Map<String, Object> view = new HashMap<>();
        view.put("todayCourseCount", total);
        view.put("todayFinished", finished);
        view.put("todayUpcoming", upcoming);
        view.put("todayCancelled", todaySchedules.stream().filter(s -> "CANCELLED".equals(s.getStatus())).count());
        view.put("todayStudentCount", studentCount);
        view.put("pendingRecords", pendingRecords);

        // 今日每节课的预约/已签到/待签到明细
        List<Map<String, Object>> todayCourses = new ArrayList<>();
        for (CourseScheduleEntity s : todaySchedules) {
            List<BookingEntity> bookings = bookingMapper.selectList(
                    new LambdaQueryWrapper<BookingEntity>()
                            .eq(BookingEntity::getScheduleId, s.getId())
                            .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED")));
            long signedIn = bookings.stream().filter(b -> "SIGNED_IN".equals(b.getStatus())
                    || "COMPLETED".equals(b.getStatus())).count();
            Map<String, Object> m = new HashMap<>();
            m.put("scheduleId", s.getId());
            m.put("startTime", s.getStartTime().toString());
            m.put("endTime", s.getEndTime().toString());
            m.put("bookedCount", bookings.size());
            m.put("signedInCount", signedIn);
            m.put("pendingSignin", bookings.size() - signedIn);
            m.put("status", s.getStatus());
            todayCourses.add(m);
        }
        view.put("todayCourses", todayCourses);
        return view;
    }
}
