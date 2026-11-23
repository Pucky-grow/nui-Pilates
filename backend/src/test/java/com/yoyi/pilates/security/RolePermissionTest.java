package com.yoyi.pilates.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseProposalEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseProposalMapper;
import com.yoyi.pilates.course.interfaces.AdminCourseProposalController;
import com.yoyi.pilates.home.interfaces.CoachHomeController;
import com.yoyi.pilates.system.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * STORE_MANAGER / STAFF / COACH 权限边界测试(控制器层).
 *
 * 真实权限映射(以 migration + init_data 为准):
 * - STORE_MANAGER: 有 manager:course:proposal:* (审核), 无 coach:home:*
 * - STAFF: 仅有前台查询/签到, 无 manager:*, 无 coach:home:*
 * - COACH: 有 coach:home:* (门店运营), 有 coach:schedule:*, 无 manager:*
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class RolePermissionTest {

    @Autowired private CoachHomeController coachHomeController;
    @Autowired private AdminCourseProposalController adminCourseProposalController;
    @Autowired private CourseProposalMapper proposalMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CoachMapper coachMapper;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ==================== STORE_MANAGER ====================

    @Test
    void storeManager_can_review_proposals() {
        setContext(Set.of("STORE_MANAGER"),
                Set.of("manager:course:proposal:view", "manager:course:proposal:approve", "manager:course:proposal:reject"),
                null);
        assertDoesNotThrow(() -> adminCourseProposalController.proposals(null));
    }

    @Test
    void storeManager_cannot_manage_home_content() {
        setContext(Set.of("STORE_MANAGER"),
                Set.of("manager:course:proposal:view", "course:view", "content:manage"),
                null);
        BusinessException e = assertThrows(BusinessException.class, coachHomeController::banners);
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode(), "STORE_MANAGER 无 coach:home:view 应拒绝");
    }

    @Test
    void storeManager_approve_proposal_creates_course() {
        Long coachId = createCoach();
        Long categoryId = createCategory();
        Long proposalId = createProposal(coachId, categoryId);

        setContext(Set.of("STORE_MANAGER"),
                Set.of("manager:course:proposal:view", "manager:course:proposal:approve"),
                null);

        adminCourseProposalController.approve(proposalId);

        assertEquals("APPROVED", proposalMapper.selectById(proposalId).getStatus());
        assertEquals(1, courseMapper.selectCount(new LambdaQueryWrapper<CourseEntity>()
                .eq(CourseEntity::getName, "店长审核课")), "审核通过应生成正式课程");
    }

    // ==================== STAFF ====================

    @Test
    void staff_cannot_manage_home_content() {
        setContext(Set.of("STAFF"), Set.of("staff:attendance:create", "member:view"), null);
        BusinessException e = assertThrows(BusinessException.class, coachHomeController::banners);
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void staff_cannot_review_proposals() {
        setContext(Set.of("STAFF"), Set.of("staff:attendance:create", "member:view"), null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCourseProposalController.proposals(null));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    // ==================== COACH(澄清 coach:home:* 归属) ====================

    @Test
    void coach_can_read_home_content() {
        setContext(Set.of("COACH"), Set.of("coach:home:view", "coach:schedule:create"), 1L);
        assertDoesNotThrow(() -> coachHomeController.banners(),
                "COACH 被授予 coach:home:view, 可管理首页运营内容");
    }

    @Test
    void coach_cannot_review_proposals() {
        setContext(Set.of("COACH"), Set.of("coach:home:view", "coach:schedule:create"), 1L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCourseProposalController.proposals(null));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode(), "COACH 无 manager:course:proposal:view");
    }

    // ==================== 辅助 ====================

    private void setContext(Set<String> roles, Set<String> perms, Long coachId) {
        LoginUser user = new LoginUser();
        user.setId(1L);
        user.setType("ADMIN");
        user.setRoles(roles);
        user.setPermissions(perms);
        user.setCoachId(coachId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private Long createCoach() {
        CoachEntity coach = new CoachEntity();
        coach.setName("教练" + System.nanoTime());
        coach.setStatus("ACTIVE");
        coach.setYears(1);
        coachMapper.insert(coach);
        return coach.getId();
    }

    private Long createCategory() {
        CourseCategoryEntity c = new CourseCategoryEntity();
        c.setCode("ROLE_CAT_" + System.nanoTime());
        c.setName("测试分类");
        c.setStatus("ACTIVE");
        categoryMapper.insert(c);
        return c.getId();
    }

    private Long createProposal(Long coachId, Long categoryId) {
        CourseProposalEntity p = new CourseProposalEntity();
        p.setProposalNo("RP" + System.nanoTime());
        p.setCoachId(coachId);
        p.setCategoryId(categoryId);
        p.setCourseName("店长审核课");
        p.setCourseType("GROUP");
        p.setDifficulty("JUNIOR");
        p.setDuration(60);
        p.setCreditCost(1);
        p.setStatus("PENDING_REVIEW");
        proposalMapper.insert(p);
        return p.getId();
    }
}
