package com.yoyi.pilates.support;

import com.yoyi.pilates.booking.application.*;
import com.yoyi.pilates.booking.infrastructure.entity.*;
import com.yoyi.pilates.booking.infrastructure.mapper.*;
import com.yoyi.pilates.course.infrastructure.entity.*;
import com.yoyi.pilates.course.infrastructure.mapper.*;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import com.yoyi.pilates.common.util.StudioTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("junit")

public abstract class BookingFixture {
    @Autowired protected BookingService bookings;
    @Autowired protected BookingMapper bookingMapper;
    @Autowired protected AttendanceMapper attendanceMapper;
    @Autowired protected CourseScheduleMapper schedules;
    @Autowired protected CourseMapper courses;
    @Autowired protected CoachMapper coaches;
    @Autowired protected ClassroomMapper rooms;
    @Autowired protected CourseCategoryMapper categories;
    @Autowired protected CourseCoachMapper qualifications;
    @Autowired protected UserMapper users;
    @Autowired protected SystemConfigService config;
    @Autowired protected StringRedisTemplate redis;
    protected Long userId, courseId, coachId, roomId, scheduleId;
    private final java.util.List<java.util.Map<String,Object>> concurrentRuns = new java.util.ArrayList<>();

    @Autowired protected javax.sql.DataSource dataSource;
    @BeforeEach void fixture() throws Exception {
        try (var connection = dataSource.getConnection()) {
            boolean mysql = connection.getMetaData().getDatabaseProductName().equals("MySQL");
            if (mysql && (System.getenv("PILATES_TEST_MYSQL_URL") == null || !connection.getMetaData().getURL().equals(System.getenv("PILATES_TEST_MYSQL_URL"))))
                throw new IllegalStateException("MySQL tests require the owned isolated runner URL");
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(mysql ? "reset-mysql.sql" : "reset-h2.sql")).execute(dataSource);
        }
        concurrentRuns.clear();
        SecurityContextHolder.clearContext();
        // Never fall back to an existing Redis service. Runner owns this password-protected temporary instance.
        if (System.getenv("PILATES_TEST_REDIS_PORT") == null || System.getenv("PILATES_TEST_REDIS_PASSWORD") == null)
            throw new IllegalStateException("必须使用隔离测试运行器");
        try (var connection=redis.getConnectionFactory().getConnection()) {
            var info=connection.serverCommands().info("server");
            if (info==null || !System.getenv("PILATES_TEST_REDIS_PORT").equals(info.getProperty("tcp_port")))
                throw new IllegalStateException("Refusing to reset a Redis instance not owned by the test runner");
            connection.serverCommands().flushDb();
        }
        config.set("booking.paymentMode", "FREE");
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("INSERT INTO member_level(code,name,discount,sort,status) VALUES('NORMAL','普通会员',1,0,'ACTIVE')");
        CourseCategoryEntity category = new CourseCategoryEntity();
        category.setCode("TEST"); category.setName("测试分类"); category.setStatus("ACTIVE"); categories.insert(category);
        CoachEntity coach = new CoachEntity(); coach.setName("教练"); coach.setStatus("ACTIVE"); coach.setYears(1); coaches.insert(coach); coachId = coach.getId();
        ClassroomEntity room = new ClassroomEntity(); room.setName("教室"); room.setType("GROUP"); room.setCapacity(20); room.setStatus("ACTIVE"); rooms.insert(room); roomId = room.getId();
        CourseEntity course = new CourseEntity(); course.setName("测试课"); course.setCategoryId(category.getId()); course.setDurationMinutes(60); course.setType("GROUP"); course.setDifficulty("BEGINNER"); course.setCreditsCost(1); course.setStatus("ON_SHELF"); courses.insert(course); courseId = course.getId();
        CourseCoachEntity qualification = new CourseCoachEntity(); qualification.setCourseId(courseId); qualification.setCoachId(coachId); qualification.setStatus("APPROVED"); qualifications.insert(qualification);
        userId = newUser(); scheduleId = newSchedule(8, 1, 10);
    }
    protected Long newUser() { UserEntity u = new UserEntity(); u.setOpenid("u_" + UUID.randomUUID()); u.setNickname("会员"); u.setStatus("ACTIVE"); users.insert(u); return u.getId(); }
    protected Long newSchedule(int capacity, int days, int hour) {
        CourseScheduleEntity s = new CourseScheduleEntity(); s.setCourseId(courseId); s.setCoachId(coachId); s.setClassroomId(roomId); s.setCourseDate(StudioTime.today().plusDays(days)); s.setStartTime(LocalTime.of(hour,0)); s.setEndTime(LocalTime.of(hour+1,0)); s.setMaxCapacity(capacity); s.setBookedCount(0); s.setStatus("SCHEDULED"); schedules.insert(s); return s.getId();
    }
    protected void openCheckin() {
        var s = schedules.selectById(scheduleId); var start = StudioTime.now().plusMinutes(5);
        // Exercise the real time window without altering production clock configuration.
        s.setCourseDate(start.toLocalDate()); s.setStartTime(start.toLocalTime()); s.setEndTime(start.plusMinutes(20).toLocalTime()); schedules.updateById(s);
    }
    protected int parallel(List<Runnable> work) throws Exception {
        var pool = Executors.newFixedThreadPool(work.size()); var start = new CountDownLatch(1); var ready = new CountDownLatch(work.size()); var successes = new AtomicInteger(); var futures = new ArrayList<Future<?>>(); var failures = new java.util.concurrent.ConcurrentHashMap<String,AtomicInteger>();
        for (Runnable task : work) futures.add(pool.submit(() -> { ready.countDown(); try { start.await(); task.run(); successes.incrementAndGet(); } catch (com.yoyi.pilates.common.exception.BusinessException expected) { failures.computeIfAbsent(expected.getCode()+":"+expected.getMessage(),k->new AtomicInteger()).incrementAndGet(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } }));
        if (!ready.await(30, TimeUnit.SECONDS)) throw new AssertionError("workers not ready"); start.countDown();
        try { for (var f : futures) f.get(90, TimeUnit.SECONDS); } finally { pool.shutdownNow(); }
        concurrentRuns.add(java.util.Map.of("attempts",work.size(),"successes",successes.get(),"failures",failures));
        return successes.get();
    }

    @org.junit.jupiter.api.AfterEach void saveEvidence(org.junit.jupiter.api.TestInfo test) throws Exception {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var rows = jdbc.queryForList("SELECT s.id,s.status,s.max_capacity,s.booked_count,COUNT(b.id) occupied_count FROM course_schedule s LEFT JOIN booking b ON b.schedule_id=s.id AND b.deleted=0 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW') WHERE s.deleted=0 GROUP BY s.id,s.status,s.max_capacity,s.booked_count ORDER BY s.id");
        for (var row : rows) {
            long id=((Number)row.get("id")).longValue();
            row.put("redisStock",redis.opsForValue().get(com.yoyi.pilates.common.constant.RedisKeys.stock(id)));
            row.put("bookingStatuses",jdbc.queryForList("SELECT status,COUNT(*) total FROM booking WHERE schedule_id=? AND deleted=0 GROUP BY status",id));
        }
        var value = new java.util.LinkedHashMap<String,Object>();
        value.put("test",getClass().getSimpleName()+"."+test.getTestMethod().orElseThrow().getName());
        value.put("databaseProduct",jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName()));
        value.put("concurrentRuns",concurrentRuns); value.put("schedules",rows);
        var output=java.nio.file.Path.of(System.getProperty("pilates.build.directory","target"),"case-evidence.jsonl");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value)+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
    }
}
