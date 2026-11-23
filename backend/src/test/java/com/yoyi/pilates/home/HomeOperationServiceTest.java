package com.yoyi.pilates.home;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.home.application.HomeOperationService;
import com.yoyi.pilates.home.infrastructure.entity.HomeHotCourseEntity;
import com.yoyi.pilates.home.infrastructure.entity.HomeTeamCoachEntity;
import com.yoyi.pilates.home.infrastructure.mapper.HomeHotCourseMapper;
import com.yoyi.pilates.home.infrastructure.mapper.HomeTeamCoachMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 热门课程/教练团队全量替换的数据安全校验.
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class HomeOperationServiceTest {

    @Autowired private HomeOperationService homeOperationService;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CoachMapper coachMapper;
    @Autowired private HomeHotCourseMapper hotCourseMapper;
    @Autowired private HomeTeamCoachMapper teamCoachMapper;

    @Test
    void setHotCourses_rejectsInvalidCourseId() {
        HomeHotCourseEntity item = new HomeHotCourseEntity();
        item.setCourseId(999999L);
        assertThrows(BusinessException.class, () -> homeOperationService.setHotCourses(List.of(item)));
    }

    @Test
    void setHotCourses_rejectsDuplicate() {
        Long courseId = createCourse();
        HomeHotCourseEntity a = new HomeHotCourseEntity();
        a.setCourseId(courseId);
        HomeHotCourseEntity b = new HomeHotCourseEntity();
        b.setCourseId(courseId);
        assertThrows(BusinessException.class, () -> homeOperationService.setHotCourses(List.of(a, b)));
    }

    @Test
    void setHotCourses_acceptsValid() {
        Long courseId = createCourse();
        HomeHotCourseEntity item = new HomeHotCourseEntity();
        item.setCourseId(courseId);
        homeOperationService.setHotCourses(List.of(item));
        assertEquals(1, hotCourseMapper.selectCount(null));
    }

    @Test
    void setHotCourses_emptyClears() {
        homeOperationService.setHotCourses(List.of());
        assertEquals(0, hotCourseMapper.selectCount(null));
    }

    @Test
    void setTeamCoaches_rejectsInvalidCoach() {
        HomeTeamCoachEntity item = new HomeTeamCoachEntity();
        item.setCoachId(999999L);
        assertThrows(BusinessException.class, () -> homeOperationService.setTeamCoaches(List.of(item)));
    }

    @Test
    void setTeamCoaches_acceptsValid() {
        Long coachId = createCoach();
        HomeTeamCoachEntity item = new HomeTeamCoachEntity();
        item.setCoachId(coachId);
        homeOperationService.setTeamCoaches(List.of(item));
        assertEquals(1, teamCoachMapper.selectCount(null));
    }

    private Long createCourse() {
        CourseCategoryEntity cat = new CourseCategoryEntity();
        cat.setCode("HOME_CAT_" + System.nanoTime());
        cat.setName("测试");
        cat.setStatus("ACTIVE");
        categoryMapper.insert(cat);
        CourseEntity c = new CourseEntity();
        c.setCategoryId(cat.getId());
        c.setName("测试课" + System.nanoTime());
        c.setDurationMinutes(60);
        c.setType("GROUP");
        c.setDifficulty("JUNIOR");
        c.setCreditsCost(1);
        c.setStatus("ON_SHELF");
        courseMapper.insert(c);
        return c.getId();
    }

    private Long createCoach() {
        CoachEntity coach = new CoachEntity();
        coach.setName("教练" + System.nanoTime());
        coach.setStatus("ACTIVE");
        coach.setYears(1);
        coachMapper.insert(coach);
        return coach.getId();
    }
}
