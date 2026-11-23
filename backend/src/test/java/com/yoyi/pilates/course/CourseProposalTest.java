package com.yoyi.pilates.course;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.course.application.CourseCoachService;
import com.yoyi.pilates.course.application.CourseProposalService;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseProposalEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseProposalMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 5 课程体系测试: 课程方案审批流 + 授课资格 + 数据权限.
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class CourseProposalTest {

    @Autowired private CourseProposalService proposalService;
    @Autowired private CourseCoachService courseCoachService;
    @Autowired private CoachMapper coachMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseProposalMapper proposalMapper;
    @Autowired private CourseCoachMapper courseCoachMapper;
    @Autowired private StringRedisTemplate redisTemplate;

    private Long coachA;
    private Long coachB;
    private Long categoryId;
    private Long existingCourseId;

    @BeforeEach
    void setUp() {
        coachA = insertCoach("教练A");
        coachB = insertCoach("教练B");
        CourseCategoryEntity category = new CourseCategoryEntity();
        category.setCode("CAT_" + System.nanoTime());
        category.setName("测试分类");
        category.setStatus("ACTIVE");
        categoryMapper.insert(category);
        categoryId = category.getId();

        CourseEntity course = new CourseEntity();
        course.setCategoryId(categoryId);
        course.setName("已有核心训练课");
        course.setDurationMinutes(60);
        course.setType("GROUP");
        course.setDifficulty("JUNIOR");
        course.setCreditsCost(1);
        course.setStatus("ON_SHELF");
        courseMapper.insert(course);
        existingCourseId = course.getId();
    }

    @Test
    void proposalApprove_createsCourseAndQualification() {
        // 教练A创建方案
        CourseProposalEntity proposal = new CourseProposalEntity();
        proposal.setCategoryId(categoryId);
        proposal.setCourseName("肩颈体态改善");
        proposal.setCourseType("GROUP");
        proposal.setDifficulty("BEGINNER");
        proposal.setDuration(60);
        proposal.setSuggestedCapacity(8);
        proposal.setCreditCost(1);
        proposal.setDescription("肩颈体态改善普拉提");
        Long proposalId = proposalService.create(coachA, proposal);

        assertEquals("DRAFT", proposalMapper.selectById(proposalId).getStatus());
        proposalService.submit(coachA, proposalId);
        assertEquals("PENDING_REVIEW", proposalMapper.selectById(proposalId).getStatus());

        // 管理员审核通过 → 生成正式课程 + 授课资格
        proposalService.approve(proposalId, 1L);
        CourseProposalEntity approved = proposalMapper.selectById(proposalId);
        assertEquals("APPROVED", approved.getStatus());
        assertNotNull(approved.getCourseId());

        CourseEntity created = courseMapper.selectById(approved.getCourseId());
        assertEquals("肩颈体态改善", created.getName());
        // 授课资格已建立
        CourseCoachEntity qualification = courseCoachMapper.selectOne(
                new LambdaQueryWrapper<CourseCoachEntity>()
                        .eq(CourseCoachEntity::getCourseId, approved.getCourseId())
                        .eq(CourseCoachEntity::getCoachId, coachA));
        assertNotNull(qualification);
        assertEquals("APPROVED", qualification.getStatus());
    }

    @Test
    void coachCannotModifyOtherCoachesProposal() {
        Long proposalId = proposalService.create(coachA, proposal("A的课程"));
        // 教练B 不能修改教练A的方案
        assertThrows(BusinessException.class,
                () -> proposalService.update(coachB, proposalId, proposal("篡改")));
        assertThrows(BusinessException.class,
                () -> proposalService.submit(coachB, proposalId));
    }

    @Test
    void applyToExistingCourse_createsPendingQualification() {
        Long relationId = courseCoachService.applyToCourse(coachB, existingCourseId);
        CourseCoachEntity relation = courseCoachMapper.selectById(relationId);
        assertEquals("PENDING", relation.getStatus());
        assertEquals(coachB, relation.getCoachId());

        // 审核通过
        courseCoachService.approveApplication(existingCourseId, coachB, 1L, true);
        CourseCoachEntity approved = courseCoachMapper.selectById(relationId);
        assertEquals("APPROVED", approved.getStatus());
    }

    private CourseProposalEntity proposal(String name) {
        CourseProposalEntity p = new CourseProposalEntity();
        p.setCategoryId(categoryId);
        p.setCourseName(name);
        p.setCourseType("GROUP");
        p.setDifficulty("BEGINNER");
        p.setDuration(60);
        p.setSuggestedCapacity(8);
        p.setCreditCost(1);
        return p;
    }

    private Long insertCoach(String name) {
        CoachEntity coach = new CoachEntity();
        coach.setName(name);
        coach.setYears(1);
        coach.setStatus("ACTIVE");
        coachMapper.insert(coach);
        return coach.getId();
    }
}
