package com.yoyi.pilates.booking.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.AttendanceEntity;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.AttendanceMapper;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.course.application.CourseQueryService;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 预约状态机：同会员跨排课限额锁 + 同排课互斥 + 数据库行锁/唯一约束。
 * 锁覆盖事务提交，Redis 只缓存已提交库存；事件同事务写入 outbox；开发内存模式在提交后派发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final com.yoyi.pilates.course.application.BookingPolicy bookingPolicy;
    private final ScheduleExecution execution;
    private final BookingMapper bookingMapper;
    private final AttendanceMapper attendanceMapper;
    private final AttendanceCodeService attendanceCodes;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseQueryService courseQueryService;
    private final PackageService packageService;
    private final BookingTxService bookingTxService;
    private final StockService stockService;
    private final SystemConfigService configService;
    private final UserMapper userMapper;
    private final MemberMapper memberMapper;
    private final RedissonClient redissonClient;
    private final EventPublisher eventPublisher;
    private final com.yoyi.pilates.ledger.StudioCancellationCapture studioCancellation;

    // ==================== 预约 ====================

    /**
     * 创建预约(团课/私教/体验课统一入口).
     *
     * @param userId        用户ID
     * @param scheduleId    排课ID
     * @param userPackageId 指定扣课时课包(null=自动选择)
     * @param remark        私教训练目标等备注
     * @return bookingId
     */
    public Long book(Long userId, Long scheduleId, Long userPackageId, String remark) {
        return bookWithCommand(userId, scheduleId, userPackageId, remark, Supplier::get);
    }

    /** Allows a caller to record its idempotency result and terms within the same booking transaction. */
    public Long bookWithCommand(Long userId, Long scheduleId, Long userPackageId, String remark,
            Function<Supplier<Long>, Long> command) {
        return execution.run(scheduleId, userId, false, () -> command.apply(
                () -> bookLocked(userId, scheduleId, userPackageId, remark, false)));
    }

    /** Create the unpublished schedule and occupy the first place under the same resource/member locks. */
    public Long bookNewSchedule(Long userId, Supplier<Long> createSchedule, String remark,
                                Function<Supplier<Long>, Long> command) {
        return execution.run(null, userId, true, () -> command.apply(
                () -> bookLocked(userId, createSchedule.get(), null, remark, true)));
    }

    private Long bookLocked(Long userId, Long scheduleId, Long userPackageId, String remark, boolean newStaffSchedule) {
        MemberEntity member = requireActiveMember(userId);
        if (newStaffSchedule && member == null) throw BusinessException.of(ResultCode.NOT_FOUND, "会员档案不存在");
        CourseScheduleEntity schedule = lockSchedule(scheduleId);
        if (newStaffSchedule) bookingPolicy.requireStaffBookable(schedule);
        else bookingPolicy.requireBookable(schedule);
        CourseEntity course = courseQueryService.requireCourse(schedule.getCourseId());
        checkNotBookedYet(userId, scheduleId);
        checkDailyLimit(userId, schedule);
        requireNoOverlap(userId, schedule.getId(), schedule.getCourseDate(), schedule.getStartTime(), schedule.getEndTime());
        Long bookingId = bookingTxService.bookInTx(userId, schedule, course, userPackageId, remark, newStaffSchedule);
        execution.changed(scheduleId);
        publishBooking("BOOKING_CREATED", requireBooking(bookingId));
        return bookingId;
    }

    public void cancel(Long userId, Long bookingId) {
        cancel(userId, bookingId, "会员取消", true);
    }

    public void cancelWithReason(Long userId, Long bookingId, String reason) {
        cancel(userId, bookingId, reason, false);
    }

    public void cancelAtMemberRequest(Long userId, Long bookingId, String reason) {
        cancel(userId, bookingId, "代会员取消：" + reason, true);
    }

    private void cancel(Long userId, Long bookingId, String reason, boolean memberRequested) {
        BookingEntity initial = requireBooking(bookingId);
        execution.run(initial.getScheduleId(), userId, false, () -> {
            BookingEntity booking = requireBooking(bookingId);
            if (!booking.getUserId().equals(userId)) throw BusinessException.of(ResultCode.FORBIDDEN);
            requireSameSchedule(initial, booking);
            lockSchedule(booking.getScheduleId());
            if ("CANCELLED".equals(booking.getStatus())) return false;
            cancelLocked(booking, reason);
            BookingEntity cancelled = requireBooking(bookingId);
            if (memberRequested) studioCancellation.memberCancelled(bookingId, cancelled.getCancelledAt());
            publishBooking("BOOKING_CANCELLED", cancelled);
            return true;
        });
    }

    private void cancelLocked(BookingEntity booking, String reason) {
        if (!"BOOKED".equals(booking.getStatus())) throw BusinessException.of(ResultCode.BOOKING_STATUS_ILLEGAL);
        CourseScheduleEntity schedule = lockSchedule(booking.getScheduleId());
        LocalDateTime start = LocalDateTime.of(schedule.getCourseDate(), schedule.getStartTime());
        if (!"SCHEDULED".equals(schedule.getStatus()) || !com.yoyi.pilates.common.util.StudioTime.now().isBefore(start))
            throw BusinessException.of(ResultCode.BOOKING_CANCEL_FORBIDDEN, "已开始或已取消的排课不能退约");
        int freeHours = configService.getInt("booking.cancel_free_hours", 4);
        int lateDeduct = configService.getInt("booking.cancel_late_deduct", 1);
        boolean free = booking.getUserPackageId() == null || com.yoyi.pilates.common.util.StudioTime.now().isBefore(start.minusHours(freeHours));
        if (!free && lateDeduct <= 0) throw BusinessException.of(ResultCode.BOOKING_CANCEL_FORBIDDEN);
        bookingTxService.cancelInTx(booking, free ? 0 : lateDeduct, reason);
        execution.changed(schedule.getId());
    }

    /** Old row remains historical; both seats and any credits change in one transaction. */
    public Long move(Long bookingId, Long targetScheduleId, String reason) {
        BookingEntity initial = requireBooking(bookingId);
        if (initial.getScheduleId().equals(targetScheduleId)) throw BusinessException.of(ResultCode.BAD_REQUEST,"请选择其他排课");
        Long next = execution.runMany(List.of(initial.getScheduleId(), targetScheduleId), initial.getUserId(), false, () -> {
            BookingEntity old = requireBooking(bookingId);
            requireSameSchedule(initial, old);
            requireActiveMember(old.getUserId());
            // Lock both rows in deterministic order, matching the distributed locks.
            for (Long id : List.of(old.getScheduleId(), targetScheduleId).stream().sorted().toList()) lockSchedule(id);
            CourseScheduleEntity target = lockSchedule(targetScheduleId);
            bookingPolicy.requireBookable(target);
            checkNotBookedYet(old.getUserId(), targetScheduleId);
            cancelLocked(old, "管理员调整：" + reason);
            checkDailyLimit(old.getUserId(), target);
            requireNoOverlap(old.getUserId(), target.getId(), target.getCourseDate(), target.getStartTime(), target.getEndTime());
            Long id = bookingTxService.bookInTx(old.getUserId(), target,
                    courseQueryService.requireCourse(target.getCourseId()), old.getUserPackageId(), old.getRemark());
            execution.changed(targetScheduleId);
            publishBooking("BOOKING_CANCELLED", requireBooking(bookingId));
            publishBooking("BOOKING_CREATED", requireBooking(id));
            return id;
        });
        return next;
    }

    private MemberEntity requireActiveMember(Long userId) {
        UserEntity user = userMapper.lockById(userId);
        if (user == null || "DISABLED".equals(user.getStatus())) throw BusinessException.of(ResultCode.UNAUTHORIZED);
        MemberEntity member = memberMapper.selectOne(new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getUserId,userId));
        if ("BANNED".equals(user.getStatus()) || (member != null && "DISABLED".equals(member.getStatus())))
            throw BusinessException.of(ResultCode.BOOKING_LIMITED);
        return member;
    }

    /** 整节取消与全部 BOOKED 退约、返还处于同一事务，不依赖 MQ。 */
    public void cancelSchedule(Long scheduleId, String reason) {
        String publicReason = reason == null || reason.isBlank() ? "门店取消课程" : reason.trim();
        if (("课程取消：" + publicReason).length() > 255)
            throw BusinessException.of(ResultCode.BAD_REQUEST, "门店取消原因最多250字");
        List<BookingEntity> cancelled = execution.run(scheduleId, null, false, () -> {
            CourseScheduleEntity schedule = lockSchedule(scheduleId);
            var actor = com.yoyi.pilates.system.security.SecurityUtils.current().orElse(null);
            if (actor != null && actor.isCoach() && !actor.isAdminRole()
                    && !schedule.getCoachId().equals(actor.getCoachId())) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "只能取消本人排课");
            }
            if ("CANCELLED".equals(schedule.getStatus())) return List.<BookingEntity>of();
            if (!"SCHEDULED".equals(schedule.getStatus()) || !com.yoyi.pilates.common.util.StudioTime.now()
                    .isBefore(LocalDateTime.of(schedule.getCourseDate(), schedule.getStartTime()))) {
                throw BusinessException.of(ResultCode.CONFLICT, "仅未开始的待开课排课可以取消");
            }
            if (bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                    .eq(BookingEntity::getScheduleId, scheduleId).eq(BookingEntity::getStatus, "SIGNED_IN")) > 0) {
                throw BusinessException.of(ResultCode.CONFLICT, "已有会员签到，请先人工核对，不能直接取消整节课");
            }
            List<BookingEntity> rows = bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                    .eq(BookingEntity::getScheduleId, scheduleId).eq(BookingEntity::getStatus, "BOOKED"));
            for (BookingEntity row : rows) bookingTxService.cancelInTx(row, 0, "课程取消：" + publicReason);
            schedule = lockSchedule(scheduleId); // cancellations increment the schedule version
            schedule.setStatus("CANCELLED"); schedule.setCancelReason(publicReason);
            if (scheduleMapper.updateById(schedule) != 1) throw BusinessException.of(ResultCode.CONFLICT, "排课已变化，取消未生效");
            execution.changed(scheduleId);
            for (BookingEntity row : rows) publishBooking("COURSE_CANCELLED_NOTIFY", row);
            return rows;
        });
    }

    /** 旧取消事件的兼容入口：只能核实已经原子取消的课程，绝不退掉仍在开课的预约。 */
    public int cancelBySchedule(Long scheduleId, String reason) {
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || !"CANCELLED".equals(schedule.getStatus())) return 0;
        // New cancellation commits all refunds atomically; redelivery must be a no-op.
        return 0;
    }

    public void checkIn(Long bookingId, String checkinType, Long operatorId) {
        BookingEntity initial = requireBooking(bookingId);
        execution.run(initial.getScheduleId(), null, false,
                () -> {
                    requireSameSchedule(initial, requireBooking(bookingId));
                    boolean result=checkInLocked(bookingId, checkinType, operatorId);
                    if(result) publishBooking("BOOKING_CHECKED_IN", requireBooking(bookingId));
                    return result;
                });
    }

    public void checkInBySchedule(Long userId, Long scheduleId) {
        checkInBySchedule(userId, scheduleId, null, null);
    }

    public void checkInWithCode(Long userId, Long bookingId, String code) {
        checkInBySchedule(userId, attendanceCodes.requireScheduleId(code), bookingId, code);
    }

    private void checkInBySchedule(Long userId, Long scheduleId, Long bookingId, String code) {
        execution.run(scheduleId, null, false, () -> {
            if (code != null) attendanceCodes.requireScheduleId(code);
            lockSchedule(scheduleId);
            BookingEntity booking = bookingMapper.selectOne(new LambdaQueryWrapper<BookingEntity>()
                    .eq(BookingEntity::getUserId, userId).eq(BookingEntity::getScheduleId, scheduleId)
                    .eq(bookingId != null, BookingEntity::getId, bookingId)
                    .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN")));
            if (booking == null) throw BusinessException.of(ResultCode.BOOKING_NOT_FOUND, "未找到本人可签到的预约");
            boolean changed=checkInLocked(booking.getId(), bookingId == null ? "USER_SCAN" : "ONSITE_QR", userId);
            if(changed) publishBooking("BOOKING_CHECKED_IN", requireBooking(booking.getId()));
            return null;
        });
    }

    private boolean checkInLocked(Long id, String type, Long operatorId) {
        BookingEntity booking = requireBooking(id);
        boolean memberCheckin = "USER_SCAN".equals(type) || "ONSITE_QR".equals(type);
        if (memberCheckin && !booking.getUserId().equals(operatorId)) throw BusinessException.of(ResultCode.FORBIDDEN);
        CourseScheduleEntity schedule = lockSchedule(booking.getScheduleId());
        var actor = com.yoyi.pilates.system.security.SecurityUtils.current().orElse(null);
        if (!memberCheckin && (actor == null || (!actor.isAdminRole()
                && !(actor.isAdmin() && "STAFF".equals(type) && actor.hasPerm("staff:attendance:create"))
                && (!actor.isCoach() || !schedule.getCoachId().equals(actor.getCoachId()))))) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能为本人授课的预约签到");
        }
        LocalDateTime now = com.yoyi.pilates.common.util.StudioTime.now();
        AttendanceCodeService.requireWindow(schedule, now);
        if ("SIGNED_IN".equals(booking.getStatus()) && attendanceMapper.selectCount(new LambdaQueryWrapper<AttendanceEntity>()
                .eq(AttendanceEntity::getBookingId, id)) == 1) return false;
        if (!"BOOKED".equals(booking.getStatus())) throw BusinessException.of(ResultCode.BOOKING_STATUS_ILLEGAL);
        int changed = bookingMapper.update(null, new LambdaUpdateWrapper<BookingEntity>()
                .eq(BookingEntity::getId, id).eq(BookingEntity::getStatus, "BOOKED").set(BookingEntity::getStatus, "SIGNED_IN"));
        if (changed != 1) throw BusinessException.of(ResultCode.CONFLICT, "预约状态已变化");
        AttendanceEntity attendance = new AttendanceEntity();
        attendance.setBookingId(id); attendance.setUserId(booking.getUserId()); attendance.setScheduleId(schedule.getId());
        attendance.setCheckinType(type); attendance.setStatus("PRESENT"); attendance.setOperatorId(operatorId); attendance.setCheckinAt(now);
        if (attendanceMapper.insert(attendance) != 1) throw BusinessException.of(ResultCode.CONFLICT, "签到写入失败");
        return true;
    }

    private CourseScheduleEntity lockSchedule(Long id) {
        CourseScheduleEntity schedule = scheduleMapper.lockById(id);
        if (schedule == null) throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        return schedule;
    }

    private void requireSameSchedule(BookingEntity initial, BookingEntity current) {
        if (!initial.getScheduleId().equals(current.getScheduleId())) {
            throw BusinessException.of(ResultCode.CONFLICT, "预约已调课，请刷新后重试");
        }
    }

    private void publishBooking(String tag, BookingEntity booking) {
        try {
            eventPublisher.publish(Topics.BOOKING_EVENT, DomainEvent.of(tag, booking.getBookingNo(),
                    Map.of("bookingId", booking.getId(), "userId", booking.getUserId(), "scheduleId", booking.getScheduleId())));
        } catch (RuntimeException error) {
            if (eventPublisher instanceof com.yoyi.pilates.infra.delivery.OutboxPublisher) throw error;
            log.error("开发事件派发失败 bookingId={} tag={} errorType={}", booking.getId(), tag, error.getClass().getSimpleName());
        }
    }

    // ==================== 课程完成/爽约(定时任务调用) ====================

    /**
     * 课程结束后批量处理: 已签到→已完成(累计课时); 未签到→爽约(课时不退).
     *
     * @return int[]{completed, noShow}
     */
    public int[] finishSchedule(Long scheduleId) {
        return execution.run(scheduleId, null, false, () -> {
        CourseScheduleEntity locked = lockSchedule(scheduleId);
        LocalDateTime finishedAt = com.yoyi.pilates.common.util.StudioTime.now();
        if (!"SCHEDULED".equals(locked.getStatus()) || finishedAt.isBefore(
                LocalDateTime.of(locked.getCourseDate(), locked.getEndTime()))) return new int[]{0, 0};
        List<BookingEntity> signedIns = bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getScheduleId, scheduleId).eq(BookingEntity::getStatus, "SIGNED_IN")
                .orderByAsc(BookingEntity::getUserId));
        for (BookingEntity b : signedIns) {
            b.setStatus("COMPLETED");
            if (bookingMapper.updateById(b) != 1) throw BusinessException.of(ResultCode.CONFLICT);
            memberMapper.recordCompletedSession(b.getUserId(), finishedAt);
        }
        List<BookingEntity> noShows = bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getScheduleId, scheduleId).eq(BookingEntity::getStatus, "BOOKED"));
        for (BookingEntity b : noShows) {
            b.setStatus("NO_SHOW");
            if (bookingMapper.updateById(b) != 1) throw BusinessException.of(ResultCode.CONFLICT);
        }
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule != null && "SCHEDULED".equals(schedule.getStatus())) {
            schedule.setStatus("FINISHED");
            if (scheduleMapper.updateById(schedule) != 1) throw BusinessException.of(ResultCode.CONFLICT);
            execution.changed(scheduleId);
        }
        return new int[]{signedIns.size(), noShows.size()};
        });
    }

    // ==================== 查询 ====================

    public BookingEntity requireBooking(Long bookingId) {
        BookingEntity booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw BusinessException.of(ResultCode.BOOKING_NOT_FOUND);
        }
        return booking;
    }

    public List<BookingEntity> myBookings(Long userId, String status) {
        return bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getUserId, userId)
                .eq(status != null && !status.isBlank(), BookingEntity::getStatus, status)
                .orderByDesc(BookingEntity::getId));
    }

    public List<BookingEntity> bookingsOfSchedule(Long scheduleId) {
        return bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getScheduleId, scheduleId)
                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED", "NO_SHOW"))
                .orderByAsc(BookingEntity::getId));
    }

    // ==================== 规则校验 ====================

    private void checkNotBookedYet(Long userId, Long scheduleId) {
        Long count = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getUserId, userId)
                .eq(BookingEntity::getScheduleId, scheduleId)
                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED", "NO_SHOW")));
        if (count > 0) {
            throw BusinessException.of(ResultCode.BOOKING_DUPLICATED);
        }
    }

    public void requireNoOverlap(Long userId, Long excludedScheduleId, java.time.LocalDate date,
                                 java.time.LocalTime start, java.time.LocalTime end) {
        if (bookingMapper.countOverlaps(userId, excludedScheduleId, date, start, end) > 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "会员在该时段已有预约，请选择其他时间");
        }
    }

    private void checkDailyLimit(Long userId, CourseScheduleEntity schedule) {
        requireDailyLimit(userId, schedule.getCourseDate(), null);
    }

    public void requireDailyLimit(Long userId, java.time.LocalDate date, Long excludedScheduleId) {
        int maxPerDay = configService.getInt("booking.max_per_day", 3);
        // 按"排课日期"统计当日已约节数(而非预约创建日期)
        List<Long> dateScheduleIds = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getCourseDate, date)
                        .ne(excludedScheduleId != null, CourseScheduleEntity::getId, excludedScheduleId)
                        .select(CourseScheduleEntity::getId))
                .stream().map(CourseScheduleEntity::getId).toList();
        if (dateScheduleIds.isEmpty()) {
            return;
        }
        Long count = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getUserId, userId)
                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED", "NO_SHOW"))
                .in(BookingEntity::getScheduleId, dateScheduleIds));
        if (count >= maxPerDay) {
            throw BusinessException.of(ResultCode.BOOKING_LIMITED, "当日预约已达上限(" + maxPerDay + "节)");
        }
    }
}
