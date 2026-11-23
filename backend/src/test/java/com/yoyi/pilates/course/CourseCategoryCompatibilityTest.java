package com.yoyi.pilates.course;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.course.application.CourseAdminService;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.interfaces.dto.CourseSaveRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CourseCategoryCompatibilityTest {
    @Test
    void omittedCategoryUsesOneDefaultOnCreateAndLeavesLegacyCategoryOnEdit() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var schema = getClass().getResourceAsStream("/schema-h2.sql");
             var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            var courseTables = Pattern.compile("(?s)CREATE TABLE `course(?:_category)?` \\(.*?\\);")
                    .matcher(new String(schema.readAllBytes(), StandardCharsets.UTF_8))
                    .results().map(MatchResult::group).toList();
            assertEquals(2, courseTables.size());
            for (var table : courseTables) {
                statement.execute(table);
            }
        }

        var configuration = new MybatisConfiguration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CourseMapper.class);
        configuration.addMapper(CourseCategoryMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession()) {
            var courses = session.getMapper(CourseMapper.class);
            var categories = session.getMapper(CourseCategoryMapper.class);
            var service = new CourseAdminService(courses, categories, null, null, null, null);
            var request = new CourseSaveRequest();
            request.setName("回归测试课程");
            request.setType("PRIVATE");
            request.setDifficulty("BEGINNER");
            request.setDurationMinutes(60);
            request.setCreditsCost(1);
            request.setStatus("ON_SHELF");

            var legacyCategory = service.createCategory("LEGACY", "历史分类", 1);
            request.setCategoryId(legacyCategory);
            var legacyCourse = service.createCourse(request);
            request.setCategoryId(null);
            request.setName("更新后的课程");
            service.updateCourse(legacyCourse, request);
            assertEquals(legacyCategory, service.courseDetail(legacyCourse).getCategoryId());
            assertEquals("更新后的课程", service.courseDetail(legacyCourse).getName());

            var first = service.createCourse(request);
            var second = service.createCourse(request);
            var defaultCategory = categories.lockDefaultCategoryId();
            assertEquals(defaultCategory, service.courseDetail(first).getCategoryId());
            assertEquals(defaultCategory, service.courseDetail(second).getCategoryId());
            assertEquals(2L, categories.selectCount(null));

            request.setCategoryId(-1L);
            assertThrows(BusinessException.class, () -> service.updateCourse(first, request));
        }
    }
}
