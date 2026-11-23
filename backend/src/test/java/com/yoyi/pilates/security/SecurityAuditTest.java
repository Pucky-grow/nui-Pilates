package com.yoyi.pilates.security;

import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.content.infrastructure.entity.BannerEntity;
import com.yoyi.pilates.content.infrastructure.mapper.BannerMapper;
import com.yoyi.pilates.course.application.ScheduleService;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.ClassroomMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.course.interfaces.AdminCourseProposalController;
import com.yoyi.pilates.course.interfaces.CoachCourseController;
import com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest;
import com.yoyi.pilates.home.interfaces.CoachHomeController;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 后端权限专项审计测试(控制器层集成, @RequiresPerm 切面经 AOP 生效):
 * 1) 未登录 / MEMBER 越权调用管理接口 → 40100 / 40300
 * 2) 教练越权取消他人排课 / 编辑他人课程 → FORBIDDEN 且 DB 不变
 * 3) 伪造 coachId 创建排课 → 后端强制为当前教练本人
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class SecurityAuditTest {

    @Autowired private CoachHomeController coachHomeController;
    @Autowired private AdminCourseProposalController adminCourseProposalController;
    @Autowired private ScheduleService scheduleService;
    @Autowired private CoachCourseController coachCourseController;

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserMapper userMapper;
    @Autowired private BannerMapper bannerMapper;
    @Autowired private CoachMapper coachMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CourseScheduleMapper scheduleMapper;
    @Autowired private CourseCoachMapper courseCoachMapper;
    @Autowired private ClassroomMapper classroomMapper;

    @BeforeEach
    void flushRedis() {
        var factory = redisTemplate.getConnectionFactory();
        if (factory != null) {
            try (var conn = factory.getConnection()) {
                conn.serverCommands().flushDb();
            }
        }
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 越权(控制器层) ====================

    @Test
    void unauthenticated_rejected() {
        BusinessException e = assertThrows(BusinessException.class, coachHomeController::banners);
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    @Test
    void member_cannot_read_admin_content() {
        setMemberContext();
        BusinessException e = assertThrows(BusinessException.class, coachHomeController::banners);
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void member_cannot_write_banner() {
        setMemberContext();
        BusinessException e = assertThrows(BusinessException.class,
                () -> coachHomeController.createBanner(new BannerEntity()));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        assertEquals(0, bannerMapper.selectCount(null), "Banner 不得被越权写入");
    }

    @Test
    void member_cannot_review_proposals() {
        setMemberContext();
        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCourseProposalController.proposals(null));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    // ==================== 教练归属越权 ====================

    @Test
    void coach_cannot_cancel_others_schedule() {
        Long coachA = createCoach();
        Long coachB = createCoach();
        Long courseId = createCourse();
        Long scheduleId = createSchedule(courseId, coachB);

        setCoachContext(coachA);

        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.cancel(scheduleId, "test"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        assertEquals("SCHEDULED", scheduleMapper.selectById(scheduleId).getStatus(), "排课状态不得被他人取消改变");
    }

    @Test
    void coach_cannot_edit_others_course() {
        Long coachA = createCoach();
        Long coachB = createCoach();
        Long courseId = createCourse();
        giveQualification(courseId, coachB); // 仅 B 有授课资格

        setCoachContext(coachA);

        BusinessException e = assertThrows(BusinessException.class,
                () -> coachCourseController.updateCourseDisplay(courseId, Map.of("description", "越权")));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void create_schedule_forces_coachId_to_self() {
        Long coachA = createCoach();
        Long courseId = createCourse();
        giveQualification(courseId, coachA);

        setCoachContext(coachA);

        ScheduleCreateRequest req = new ScheduleCreateRequest();
        req.setCourseId(courseId);
        req.setCoachId(999999L); // 伪造他人教练
        req.setClassroomId(createClassroom());
        req.setCourseDate(LocalDate.now().plusDays(2));
        req.setStartTime(LocalTime.of(10, 0));
        req.setEndTime(LocalTime.of(11, 0));
        req.setMaxCapacity(8);

        Long scheduleId = scheduleService.create(req);
        assertEquals(coachA, scheduleMapper.selectById(scheduleId).getCoachId(), "教练归属应强制为当前登录教练");
    }

    // ==================== 辅助 ====================

    private void setMemberContext() {
        UserEntity user = new UserEntity();
        user.setOpenid("sec_member_" + System.nanoTime());
        user.setNickname("会员");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setType("MP_USER");
        loginUser.setRoles(Set.of("MEMBER"));
        loginUser.setPermissions(Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    private void setCoachContext(Long coachId) {
        LoginUser user = new LoginUser();
        user.setId(coachId);
        user.setType("ADMIN");
        user.setRoles(Set.of("COACH"));
        user.setPermissions(Set.of("coach:schedule:create", "coach:schedule:cancel"));
        user.setCoachId(coachId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private Long createCoach() {
        CoachEntity coach = new CoachEntity();
        coach.setName("教练" + System.nanoTime());
        coach.setYears(1);
        coach.setStatus("ACTIVE");
        coachMapper.insert(coach);
        return coach.getId();
    }

    private Long createCourse() {
        CourseCategoryEntity cat = new CourseCategoryEntity();
        cat.setCode("SEC_CAT_" + System.nanoTime());
        cat.setName("测试");
        cat.setStatus("ACTIVE");
        categoryMapper.insert(cat);

        CourseEntity c = new CourseEntity();
        c.setCategoryId(cat.getId());
        c.setName("测试课");
        c.setDurationMinutes(60);
        c.setType("GROUP");
        c.setDifficulty("JUNIOR");
        c.setCreditsCost(1);
        c.setStatus("ON_SHELF");
        courseMapper.insert(c);
        return c.getId();
    }

    private Long createClassroom() {
        ClassroomEntity r = new ClassroomEntity();
        r.setName("教室");
        r.setType("GROUP");
        r.setCapacity(10);
        r.setStatus("ACTIVE");
        classroomMapper.insert(r);
        return r.getId();
    }

    private Long createSchedule(Long courseId, Long coachId) {
        CourseScheduleEntity s = new CourseScheduleEntity();
        s.setCourseId(courseId);
        s.setCoachId(coachId);
        s.setClassroomId(createClassroom());
        s.setCourseDate(LocalDate.now().plusDays(2));
        s.setStartTime(LocalTime.of(10, 0));
        s.setEndTime(LocalTime.of(11, 0));
        s.setMaxCapacity(8);
        s.setBookedCount(0);
        s.setStatus("SCHEDULED");
        scheduleMapper.insert(s);
        return s.getId();
    }

    private void giveQualification(Long courseId, Long coachId) {
        CourseCoachEntity cc = new CourseCoachEntity();
        cc.setCourseId(courseId);
        cc.setCoachId(coachId);
        cc.setStatus("APPROVED");
        cc.setQualificationSource("ADMIN");
        courseCoachMapper.insert(cc);
    }
}
