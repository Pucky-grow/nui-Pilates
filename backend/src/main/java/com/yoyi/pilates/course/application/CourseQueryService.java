package com.yoyi.pilates.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachCertificateEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.ClassroomMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CoachCertificateMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.course.interfaces.dto.CoachView;
import com.yoyi.pilates.course.interfaces.dto.CourseView;
import com.yoyi.pilates.course.interfaces.dto.ScheduleView;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 课程查询服务(小程序公开接口 + 后台共用聚合).
 */
@Service
@RequiredArgsConstructor
public class CourseQueryService {

    private final BookingPolicy bookingPolicy;
    private final CourseMapper courseMapper;
    private final CourseCategoryMapper categoryMapper;
    private final CoachMapper coachMapper;
    private final CoachCertificateMapper certificateMapper;
    private final ClassroomMapper classroomMapper;
    private final CourseScheduleMapper scheduleMapper;

    // ---------------- 分类 ----------------

    public List<CourseCategoryEntity> listActiveCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<CourseCategoryEntity>()
                .eq(CourseCategoryEntity::getStatus, "ACTIVE")
                .orderByAsc(CourseCategoryEntity::getSort));
    }

    /** 启用的教室列表(创建排课选择) */
    public List<ClassroomEntity> listActiveClassrooms() {
        return classroomMapper.selectList(new LambdaQueryWrapper<ClassroomEntity>()
                .eq(ClassroomEntity::getStatus, "ACTIVE")
                .orderByAsc(ClassroomEntity::getId));
    }

    // ---------------- 课程 ----------------

    public List<CourseView> listCourses(Long categoryId, String type, String keyword) {
        LambdaQueryWrapper<CourseEntity> qw = new LambdaQueryWrapper<CourseEntity>()
                .eq(CourseEntity::getStatus, "ON_SHELF")
                .eq(categoryId != null, CourseEntity::getCategoryId, categoryId)
                .eq(StringUtils.isNotBlank(type), CourseEntity::getType, type)
                .like(StringUtils.isNotBlank(keyword), CourseEntity::getName, keyword)
                .orderByAsc(CourseEntity::getSort);
        List<CourseEntity> courses = courseMapper.selectList(qw);
        Map<Long, String> categoryNames = categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(CourseCategoryEntity::getId, CourseCategoryEntity::getName));
        return courses.stream().map(c -> toCourseView(c, categoryNames.get(c.getCategoryId()))).toList();
    }

    public CourseView courseDetail(Long courseId) {
        CourseEntity course = requireCourse(courseId);
        CourseCategoryEntity category = categoryMapper.selectById(course.getCategoryId());
        return toCourseView(course, category != null ? category.getName() : null);
    }

    public CourseEntity requireCourse(Long courseId) {
        CourseEntity course = courseMapper.selectById(courseId);
        if (course == null) {
            throw BusinessException.of(ResultCode.COURSE_NOT_FOUND);
        }
        return course;
    }

    // ---------------- 教练 ----------------

    public List<CoachView> listCoaches() {
        return coachMapper.selectList(new LambdaQueryWrapper<CoachEntity>()
                        .eq(CoachEntity::getStatus, "ACTIVE")
                        .orderByAsc(CoachEntity::getSort))
                .stream().map(c -> toCoachView(c, false)).toList();
    }

    public CoachView coachDetail(Long coachId) {
        CoachEntity coach = coachMapper.selectById(coachId);
        if (coach == null || !"ACTIVE".equals(coach.getStatus())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教练不存在");
        }
        return toCoachView(coach, true);
    }

    public CoachEntity requireCoach(Long coachId) {
        CoachEntity coach = coachMapper.selectById(coachId);
        if (coach == null || !"ACTIVE".equals(coach.getStatus())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "教练不存在或已停用");
        }
        return coach;
    }

    // ---------------- 排课(课程卡片流) ----------------

    /**
     * 排课查询: 日期范围 + 时段 + 教练 + 分类 + 难度 + 类型筛选.
     *
     * @param fromDate  开始日期(默认今天)
     * @param toDate    结束日期(默认+7天)
     * @param timeOfDay MORNING/AFTERNOON/EVENING, 可空
     * @param onlyAvailable true 时仅返回有余票的排期
     */
    public List<ScheduleView> listSchedules(LocalDate fromDate, LocalDate toDate, String timeOfDay,
                                            Long coachId, Long categoryId, String difficulty,
                                            String courseType, boolean onlyAvailable) {
        LocalDate from = fromDate != null ? fromDate : com.yoyi.pilates.common.util.StudioTime.today();
        LocalDate to = toDate != null ? toDate : from.plusDays(bookingPolicy.advanceDays());

        LambdaQueryWrapper<CourseScheduleEntity> qw = new LambdaQueryWrapper<CourseScheduleEntity>()
                .ge(CourseScheduleEntity::getCourseDate, from)
                .le(CourseScheduleEntity::getCourseDate, to)
                .eq(coachId != null, CourseScheduleEntity::getCoachId, coachId)
                .orderByAsc(CourseScheduleEntity::getCourseDate)
                .orderByAsc(CourseScheduleEntity::getStartTime);
        List<CourseScheduleEntity> schedules = scheduleMapper.selectList(qw);
        if (schedules.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量取关联数据, 避免 N+1
        Map<Long, CourseEntity> courseMap = batchCourses(schedules);
        Map<Long, CoachEntity> coachMap = batchCoaches(schedules);
        Map<Long, ClassroomEntity> classroomMap = batchClassrooms(schedules);
        Map<Long, String> categoryNames = categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(CourseCategoryEntity::getId, CourseCategoryEntity::getName));

        return schedules.stream()
                .map(s -> toScheduleView(s, courseMap, coachMap, classroomMap, categoryNames))
                .filter(v -> v.getCourseName() != null)
                .filter(v -> categoryId == null || categoryId.equals(v.getCategoryId()))
                .filter(v -> StringUtils.isBlank(difficulty) || difficulty.equals(v.getDifficulty()))
                .filter(v -> StringUtils.isBlank(courseType) || courseType.equals(v.getCourseType()))
                .filter(v -> matchTimeOfDay(v.getStartTime(), timeOfDay))
                .filter(v -> !onlyAvailable || v.isBookable())
                .toList();
    }

    /** 课程详情页: 该课程未来7天排期 */
    public List<ScheduleView> schedulesOfCourse(Long courseId) {
        List<CourseScheduleEntity> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getCourseId, courseId)
                                .ge(CourseScheduleEntity::getCourseDate, com.yoyi.pilates.common.util.StudioTime.today())
                        .le(CourseScheduleEntity::getCourseDate, com.yoyi.pilates.common.util.StudioTime.today().plusDays(bookingPolicy.advanceDays()))
                        .orderByAsc(CourseScheduleEntity::getCourseDate)
                        .orderByAsc(CourseScheduleEntity::getStartTime));
        if (schedules.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, CourseEntity> courseMap = batchCourses(schedules);
        Map<Long, CoachEntity> coachMap = batchCoaches(schedules);
        Map<Long, ClassroomEntity> classroomMap = batchClassrooms(schedules);
        Map<Long, String> categoryNames = categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(CourseCategoryEntity::getId, CourseCategoryEntity::getName));
        return schedules.stream()
                .map(s -> toScheduleView(s, courseMap, coachMap, classroomMap, categoryNames))
                .toList();
    }

    public ScheduleView scheduleDetail(Long scheduleId) {
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        }
        Map<Long, CourseEntity> courseMap = batchCourses(List.of(schedule));
        Map<Long, CoachEntity> coachMap = batchCoaches(List.of(schedule));
        Map<Long, ClassroomEntity> classroomMap = batchClassrooms(List.of(schedule));
        Map<Long, String> categoryNames = categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(CourseCategoryEntity::getId, CourseCategoryEntity::getName));
        return toScheduleView(schedule, courseMap, coachMap, classroomMap, categoryNames);
    }

    // ---------------- 转换 ----------------

    private CourseView toCourseView(CourseEntity c, String categoryName) {
        CourseView v = new CourseView();
        BeanUtils.copyProperties(c, v);
        v.setCategoryName(categoryName);
        return v;
    }

    private CoachView toCoachView(CoachEntity c, boolean withCertificates) {
        CoachView v = new CoachView();
        BeanUtils.copyProperties(c, v);
        if (withCertificates) {
            v.setCertificates(certificateMapper.selectList(
                            new LambdaQueryWrapper<CoachCertificateEntity>()
                                    .eq(CoachCertificateEntity::getCoachId, c.getId()))
                    .stream().map(cert -> {
                        CoachView.CertificateItem item = new CoachView.CertificateItem();
                        item.setName(cert.getName());
                        item.setIssuer(cert.getIssuer());
                        item.setImageUrl(cert.getImageUrl());
                        return item;
                    }).toList());
        }
        return v;
    }

    private ScheduleView toScheduleView(CourseScheduleEntity s,
                                        Map<Long, CourseEntity> courseMap,
                                        Map<Long, CoachEntity> coachMap,
                                        Map<Long, ClassroomEntity> classroomMap,
                                        Map<Long, String> categoryNames) {
        ScheduleView v = new ScheduleView();
        BeanUtils.copyProperties(s, v);
        CourseEntity course = courseMap.get(s.getCourseId());
        if (course != null) {
            v.setCourseName(course.getName());
            v.setCourseCover(course.getCover());
            v.setCourseType(course.getType());
            v.setDifficulty(course.getDifficulty());
            v.setNotices(course.getNotices());
            v.setTargetUsers(course.getTargetUsers());
            v.setTrainingFocus(course.getTrainingFocus());
            v.setDurationMinutes(course.getDurationMinutes());
            v.setCreditsCost(course.getCreditsCost());
            v.setCategoryId(course.getCategoryId());
            v.setCategoryName(categoryNames.get(course.getCategoryId()));
        }
        CoachEntity coach = coachMap.get(s.getCoachId());
        if (coach != null) {
            v.setCoachName(coach.getName());
            v.setCoachAvatar(coach.getAvatar());
        }
        ClassroomEntity classroom = classroomMap.get(s.getClassroomId());
        if (classroom != null) {
            v.setClassroomName(classroom.getName());
            v.setClassroomType(classroom.getType());
            v.setClassroomEquipment(classroom.getEquipment());
        }
        int capacity = BookingPolicy.capacity(s, course);
        v.setMaxCapacity(capacity);
        int remaining = Math.max(0, capacity - s.getBookedCount());
        v.setRemaining(remaining);
        v.setAvailability(availability(remaining, capacity));
        BookingPolicy.Decision decision = bookingPolicy.evaluate(s, course, coach, classroom);
        v.setBookable(decision.bookable()); v.setBookingStatus(decision.code());
        v.setUnbookableReason(decision.bookable() ? "" : decision.reason());
        v.setBookingMode(bookingPolicy.mode());
        return v;
    }

    private String availability(int remaining, int max) {
        if (remaining <= 0) {
            return "FULL";
        }
        if (remaining <= 2) {
            return "ALMOST_FULL";
        }
        return remaining <= max / 3 ? "FEW" : "PLENTY";
    }

    private boolean matchTimeOfDay(LocalTime startTime, String timeOfDay) {
        if (StringUtils.isBlank(timeOfDay) || startTime == null) {
            return true;
        }
        int hour = startTime.getHour();
        return switch (timeOfDay) {
            case "MORNING" -> hour < 12;
            case "AFTERNOON" -> hour >= 12 && hour < 18;
            case "EVENING" -> hour >= 18;
            default -> true;
        };
    }

    private Map<Long, CourseEntity> batchCourses(List<CourseScheduleEntity> schedules) {
        List<Long> ids = schedules.stream().map(CourseScheduleEntity::getCourseId).distinct().toList();
        return ids.isEmpty() ? Collections.emptyMap()
                : courseMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(CourseEntity::getId, Function.identity()));
    }

    private Map<Long, CoachEntity> batchCoaches(List<CourseScheduleEntity> schedules) {
        List<Long> ids = schedules.stream().map(CourseScheduleEntity::getCoachId).distinct().toList();
        return ids.isEmpty() ? Collections.emptyMap()
                : coachMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(CoachEntity::getId, Function.identity()));
    }

    private Map<Long, ClassroomEntity> batchClassrooms(List<CourseScheduleEntity> schedules) {
        List<Long> ids = schedules.stream().map(CourseScheduleEntity::getClassroomId).distinct().toList();
        return ids.isEmpty() ? Collections.emptyMap()
                : classroomMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(ClassroomEntity::getId, Function.identity()));
    }
}
