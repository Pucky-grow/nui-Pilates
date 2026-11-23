package com.yoyi.pilates.booking.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.entity.AttendanceEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.AttendanceMapper;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.booking.interfaces.dto.BookingView;
import com.yoyi.pilates.booking.interfaces.dto.StudentView;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.MaskUtils;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.ClassroomMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.entity.MemberLevelEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberLevelMapper;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.product.infrastructure.mapper.UserPackageMapper;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约查询装配服务: BookingView / StudentView 组装(批量取数防 N+1).
 */
@Service
@RequiredArgsConstructor
public class BookingQueryService {

    private final BookingMapper bookingMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseMapper courseMapper;
    private final CoachMapper coachMapper;
    private final ClassroomMapper classroomMapper;
    private final UserPackageMapper userPackageMapper;
    private final UserMapper userMapper;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final SystemConfigService configService;
    private final AttendanceMapper attendanceMapper;

    /** 我的预约列表 */
    public List<BookingView> myBookingViews(Long userId, String status) {
        List<BookingEntity> bookings = bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getUserId, userId)
                .eq(status != null && !status.isBlank(), BookingEntity::getStatus, status)
                .orderByDesc(BookingEntity::getId));
        return toViews(bookings);
    }

    /** 预约详情(校验归属) */
    public BookingView bookingDetail(Long userId, Long bookingId) {
        BookingEntity booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw BusinessException.of(ResultCode.BOOKING_NOT_FOUND);
        }
        if (!booking.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
        List<BookingView> views = toViews(List.of(booking));
        return views.isEmpty() ? null : views.get(0);
    }

    /** 某节课的学员列表(教练端) */
    public List<StudentView> studentsOfSchedule(Long scheduleId) {
        List<BookingEntity> bookings = bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getScheduleId, scheduleId)
                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED", "NO_SHOW"))
                .orderByAsc(BookingEntity::getId));
        if (bookings.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, UserEntity> users = batch(bookings.stream().map(BookingEntity::getUserId).distinct().toList(),
                userMapper::selectBatchIds, UserEntity::getId);
        Map<Long, MemberEntity> members = memberMapper.selectList(new LambdaQueryWrapper<MemberEntity>()
                        .in(MemberEntity::getUserId, users.keySet())).stream()
                .collect(Collectors.toMap(MemberEntity::getUserId, Function.identity()));
        Map<Long, MemberLevelEntity> levels = memberLevelMapper.selectList(null).stream()
                .collect(Collectors.toMap(MemberLevelEntity::getId, Function.identity()));
        Map<Long, UserPackageEntity> packages = batch(
                bookings.stream().map(BookingEntity::getUserPackageId).distinct().toList(),
                userPackageMapper::selectBatchIds, UserPackageEntity::getId);
        Map<Long, String> sources = attendanceSources(bookings);
        var schedule = scheduleMapper.selectById(scheduleId);
        boolean checkinOpen = checkinOpen(schedule, com.yoyi.pilates.common.util.StudioTime.now());

        return bookings.stream().map(b -> {
            StudentView v = new StudentView();
            v.setBookingId(b.getId());
            v.setUserId(b.getUserId());
            v.setBookingStatus(b.getStatus());
            v.setAttendanceSource(sources.get(b.getId()));
            v.setCanCheckin(checkinOpen && "BOOKED".equals(b.getStatus()));
            v.setRemark(b.getRemark());
            UserEntity user = users.get(b.getUserId());
            if (user != null) {
                boolean hidden = user.getOpenid() == null
                        && !com.yoyi.pilates.system.security.SecurityUtils.current().map(u -> u.isAdmin() && u.isAdminRole()).orElse(false);
                v.setNickname(hidden ? "线下会员" : user.getNickname());
                if (!hidden) {
                    v.setAvatarUrl(user.getAvatarUrl());
                    v.setPhone(MaskUtils.maskPhone(user.getPhone()));
                }
            }
            MemberEntity member = members.get(b.getUserId());
            if (member != null) {
                MemberLevelEntity level = levels.get(member.getLevelId());
                v.setLevelName(level != null ? level.getName() : "普通会员");
                v.setLastTrainingAt(member.getLastTrainingAt() == null ? null
                        : member.getLastTrainingAt().toLocalDate().toString());
            }
            UserPackageEntity up = packages.get(b.getUserPackageId());
            if (up != null) {
                v.setRemainingCredits("PERIOD".equals(up.getType()) ? -1 : up.getRemainingCredits());
            }
            return v;
        }).toList();
    }

    private List<BookingView> toViews(List<BookingEntity> bookings) {
        if (bookings.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, CourseScheduleEntity> schedules = batch(
                bookings.stream().map(BookingEntity::getScheduleId).distinct().toList(),
                scheduleMapper::selectBatchIds, CourseScheduleEntity::getId);
        List<Long> courseIds = schedules.values().stream().map(CourseScheduleEntity::getCourseId).distinct().toList();
        List<Long> coachIds = schedules.values().stream().map(CourseScheduleEntity::getCoachId).distinct().toList();
        List<Long> roomIds = schedules.values().stream().map(CourseScheduleEntity::getClassroomId).distinct().toList();
        Map<Long, CourseEntity> courses = batch(courseIds, courseMapper::selectBatchIds, CourseEntity::getId);
        Map<Long, CoachEntity> coaches = batch(coachIds, coachMapper::selectBatchIds, CoachEntity::getId);
        Map<Long, ClassroomEntity> rooms = batch(roomIds, classroomMapper::selectBatchIds, ClassroomEntity::getId);
        Map<Long, UserPackageEntity> packages = batch(
                bookings.stream().map(BookingEntity::getUserPackageId).distinct().toList(),
                userPackageMapper::selectBatchIds, UserPackageEntity::getId);

        int freeHours = configService.getInt("booking.cancel_free_hours", 4);
        LocalDateTime now = com.yoyi.pilates.common.util.StudioTime.now();
        Map<Long, String> sources = attendanceSources(bookings);

        return bookings.stream().map(b -> {
            BookingView v = new BookingView();
            v.setId(b.getId());
            v.setBookingNo(b.getBookingNo());
            v.setStatus(b.getStatus());
            v.setCreditsUsed(b.getCreditsUsed());
            v.setRemark(b.getRemark());
            v.setCreatedAt(b.getCreatedAt());
            v.setCancelledAt(b.getCancelledAt());
            v.setCancelReason(b.getCancelReason());
            v.setAttendanceSource(sources.get(b.getId()));
            if ("CANCELLED".equals(b.getStatus()) && b.getCancelReason() != null
                    && b.getCancelReason().startsWith("课程取消：")) {
                v.setPublicCancelReason(b.getCancelReason().substring("课程取消：".length()));
            }
            v.setUserPackageId(b.getUserPackageId());
            UserPackageEntity up = packages.get(b.getUserPackageId());
            if (up != null) {
                v.setPackageName(up.getName());
            }
            CourseScheduleEntity s = schedules.get(b.getScheduleId());
            if (s != null) {
                v.setScheduleId(s.getId());
                v.setCourseDate(s.getCourseDate());
                v.setStartTime(s.getStartTime());
                v.setEndTime(s.getEndTime());
                CourseEntity course = courses.get(s.getCourseId());
                if (course != null) {
                    v.setCourseId(course.getId());
                    v.setCourseName(course.getName());
                    v.setCourseCover(course.getCover());
                    v.setCourseType(course.getType());
                }
                CoachEntity coach = coaches.get(s.getCoachId());
                if (coach != null) {
                    v.setCoachName(coach.getName());
                }
                ClassroomEntity room = rooms.get(s.getClassroomId());
                if (room != null) {
                    v.setClassroomName(room.getName());
                }
                // 可操作标记
                LocalDateTime classStart = LocalDateTime.of(s.getCourseDate(), s.getStartTime());
                LocalDateTime classEnd = LocalDateTime.of(s.getCourseDate(), s.getEndTime());
                v.setCanCancel("BOOKED".equals(b.getStatus())
                        && "SCHEDULED".equals(s.getStatus()) && now.isBefore(classStart)
                        && (b.getUserPackageId() == null || now.isBefore(classStart.minusHours(freeHours)) || configService.getInt("booking.cancel_late_deduct",1)>0));
                v.setCanCheckin("BOOKED".equals(b.getStatus()) && checkinOpen(s, now));
                v.setCanReview("COMPLETED".equals(b.getStatus()));
            }
            return v;
        }).toList();
    }

    private Map<Long, String> attendanceSources(List<BookingEntity> bookings) {
        return attendanceMapper.selectList(new LambdaQueryWrapper<AttendanceEntity>()
                        .in(AttendanceEntity::getBookingId, bookings.stream().map(BookingEntity::getId).toList()))
                .stream().filter(record -> record.getCheckinType() != null)
                .collect(Collectors.toMap(AttendanceEntity::getBookingId, AttendanceEntity::getCheckinType));
    }

    private boolean checkinOpen(CourseScheduleEntity s, LocalDateTime now) {
        return s != null && "SCHEDULED".equals(s.getStatus())
                && !now.isBefore(LocalDateTime.of(s.getCourseDate(), s.getStartTime()).minusMinutes(30))
                && now.isBefore(LocalDateTime.of(s.getCourseDate(), s.getEndTime()));
    }

    private <E, K> Map<K, E> batch(List<K> ids, Function<List<K>, List<E>> loader,
                                   Function<E, K> keyOf) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return loader.apply(ids).stream().collect(Collectors.toMap(keyOf, Function.identity()));
    }
}
