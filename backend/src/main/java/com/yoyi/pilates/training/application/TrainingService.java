package com.yoyi.pilates.training.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import com.yoyi.pilates.training.infrastructure.mapper.TrainingRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练记录服务: 教练填写(数据权限校验) / 会员查看 / 训练统计.
 */
@Service
@RequiredArgsConstructor
public class TrainingService {

    private final com.yoyi.pilates.coach.application.CoachStudentService studentService;
    private final TrainingRecordMapper trainingRecordMapper;
    private final BookingMapper bookingMapper;
    private final CourseScheduleMapper scheduleMapper;

    /**
     * 教练填写训练记录.
     *
     * @param coachId  教练ID(数据权限: 必须是该预约课程的授课教练, 超管除外)
     * @param isAdmin  是否超管(跳过归属校验)
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createRecord(Long coachId, boolean isAdmin, TrainingRecordEntity record) {
        if (record.getBookingId() != null) {
            BookingEntity booking = bookingMapper.selectById(record.getBookingId());
            if (booking == null) {
                throw BusinessException.of(ResultCode.BOOKING_NOT_FOUND);
            }
            if (!List.of("BOOKED", "SIGNED_IN", "COMPLETED").contains(booking.getStatus())
                    || (record.getUserId() != null && !record.getUserId().equals(booking.getUserId()))
                    || (record.getScheduleId() != null && !record.getScheduleId().equals(booking.getScheduleId()))) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "预约、学员或排课归属不匹配");
            }
            CourseScheduleEntity schedule = scheduleMapper.selectById(booking.getScheduleId());
            if (schedule == null || "CANCELLED".equals(schedule.getStatus())
                    || (!isAdmin && !schedule.getCoachId().equals(coachId))) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "只能为本人授课的学员填写记录");
            }
            // 同一预约只允许一份训练记录(防重复提交; DB 层建议补唯一索引兜底)
            Long exists = trainingRecordMapper.selectCount(new LambdaQueryWrapper<TrainingRecordEntity>()
                    .eq(TrainingRecordEntity::getBookingId, record.getBookingId()));
            if (exists > 0) {
                throw BusinessException.of(ResultCode.CONFLICT, "该预约已填写过训练记录");
            }
            record.setScheduleId(booking.getScheduleId());
            record.setUserId(booking.getUserId());
        }
        if (record.getBookingId() == null && !studentService.hasServed(coachId, record.getUserId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无预约记录时必须已有可信授课关系");
        }
        if (!isAdmin) studentService.requireAccess(coachId, record.getUserId(), false);
        record.setId(null);
        record.setCoachId(coachId);
        if (record.getTrainingDate() == null) {
            record.setTrainingDate(LocalDate.now());
        }
        try {
            trainingRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发重复提交时 DB 唯一索引兜底
            throw BusinessException.of(ResultCode.CONFLICT, "该预约已填写过训练记录");
        }
        return record.getId();
    }

    public Page<TrainingRecordEntity> pageMyRecords(Long userId, long page, long size) {
        return trainingRecordMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<TrainingRecordEntity>()
                        .eq(TrainingRecordEntity::getUserId, userId)
                        .orderByDesc(TrainingRecordEntity::getTrainingDate)
                        .orderByDesc(TrainingRecordEntity::getId));
    }

    public Page<TrainingRecordEntity> pageStudentRecords(Long coachId, Long userId, long page, long size) {
        studentService.requireAccess(coachId, userId, false);
        return trainingRecordMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<TrainingRecordEntity>()
                        .eq(TrainingRecordEntity::getCoachId, coachId)
                        .eq(TrainingRecordEntity::getUserId, userId)
                        .orderByDesc(TrainingRecordEntity::getTrainingDate));
    }

    /** 待填写记录的签到学员(教练工作台) */
    public List<BookingEntity> pendingRecords(Long coachId) {
        // 已签到/已完成 但还没有训练记录的预约
        return bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .in(BookingEntity::getStatus, List.of("SIGNED_IN", "COMPLETED"))
                .exists("SELECT 1 FROM course_schedule cs WHERE cs.id = booking.schedule_id "
                        + "AND cs.coach_id = " + coachId + " AND cs.deleted = 0")
                .notExists("SELECT 1 FROM training_record tr WHERE tr.booking_id = booking.id "
                        + "AND tr.deleted = 0")
                .orderByDesc(BookingEntity::getId)
                .last("LIMIT 50"));
    }

    /**
     * 训练统计(个人中心图表):
     * 累计次数/累计时长/本月次数/连续天数 + 近8周每周次数 + 近6月每月次数.
     */
    public Map<String, Object> stats(Long userId) {
        List<TrainingRecordEntity> records = trainingRecordMapper.selectList(
                new LambdaQueryWrapper<TrainingRecordEntity>()
                        .eq(TrainingRecordEntity::getUserId, userId)
                        .orderByDesc(TrainingRecordEntity::getTrainingDate));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", records.size());

        LocalDate now = LocalDate.now();
        long monthCount = records.stream()
                .filter(r -> r.getTrainingDate().getYear() == now.getYear()
                        && r.getTrainingDate().getMonth() == now.getMonth())
                .count();
        stats.put("monthCount", monthCount);
        stats.put("totalMinutes", records.size() * 60);
        stats.put("streakDays", streak(records));

        // 近8周
        Map<String, Long> weekly = new java.util.LinkedHashMap<>();
        for (int i = 7; i >= 0; i--) {
            LocalDate weekStart = now.minusWeeks(i).with(java.time.DayOfWeek.MONDAY);
            LocalDate weekEnd = weekStart.plusDays(6);
            long count = records.stream()
                    .filter(r -> !r.getTrainingDate().isBefore(weekStart)
                            && !r.getTrainingDate().isAfter(weekEnd))
                    .count();
            weekly.put(weekStart.toString(), count);
        }
        stats.put("weekly", weekly);

        // 近6月
        Map<String, Long> monthly = new java.util.LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) {
            java.time.YearMonth ym = java.time.YearMonth.from(now.minusMonths(i));
            long count = records.stream()
                    .filter(r -> java.time.YearMonth.from(r.getTrainingDate()).equals(ym))
                    .count();
            monthly.put(ym.toString(), count);
        }
        stats.put("monthly", monthly);
        return stats;
    }

    /** 连续训练天数(按有记录的日期连续计算) */
    private int streak(List<TrainingRecordEntity> recordsDesc) {
        if (recordsDesc.isEmpty()) {
            return 0;
        }
        var dates = recordsDesc.stream().map(TrainingRecordEntity::getTrainingDate)
                .distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        LocalDate cursor = LocalDate.now();
        // 今天没训练则从昨天起算连续
        if (!dates.get(0).equals(cursor)) {
            cursor = cursor.minusDays(1);
        }
        int streak = 0;
        for (LocalDate d : dates) {
            if (d.equals(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (d.isBefore(cursor)) {
                break;
            }
        }
        return streak;
    }
}
