package com.yoyi.pilates.training;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.coach.application.CoachStudentService;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.ClassroomMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseCategoryMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.training.application.TrainingService;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import com.yoyi.pilates.training.infrastructure.mapper.TrainingRecordMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 训练记录: 重复/并发唯一性 + 师生关系(CoachStudentService.hasServed).
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class TrainingRecordTest {

    @Autowired private com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper qualificationMapper;
    @Autowired private TrainingService trainingService;
    @Autowired private CoachStudentService studentService;
    @Autowired private BookingService bookingService;
    @Autowired private UserMapper userMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CourseScheduleMapper scheduleMapper;
    @Autowired private CoachMapper coachMapper;
    @Autowired private ClassroomMapper classroomMapper;
    @Autowired private TrainingRecordMapper trainingRecordMapper;
    @Autowired private SystemConfigService configService;
    @Autowired private StringRedisTemplate redisTemplate;

    private Long coachId;
    private Long userId;
    private Long bookingId;

    @BeforeEach
    void setUp() {
        flushTestRedis();
        configService.set("booking.paymentMode", "FREE");

        CourseCategoryEntity category = new CourseCategoryEntity();
        category.setCode("CAT_" + System.nanoTime());
        category.setName("测试");
        category.setStatus("ACTIVE");
        categoryMapper.insert(category);

        CoachEntity coach = new CoachEntity();
        coach.setName("教练");
        coach.setYears(1);
        coach.setStatus("ACTIVE");
        coachMapper.insert(coach);
        coachId = coach.getId();

        ClassroomEntity room = new ClassroomEntity();
        room.setName("教室");
        room.setType("GROUP");
        room.setCapacity(8);
        room.setStatus("ACTIVE");
        classroomMapper.insert(room);

        CourseEntity course = new CourseEntity();
        course.setCategoryId(category.getId());
        course.setName("测试课");
        course.setDurationMinutes(60);
        course.setType("GROUP");
        course.setDifficulty("JUNIOR");
        course.setCreditsCost(1);
        course.setStatus("ON_SHELF");
        courseMapper.insert(course);
        com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity qualified = new com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity();
        qualified.setCourseId(course.getId()); qualified.setCoachId(coach.getId()); qualified.setStatus("APPROVED");
        qualificationMapper.insert(qualified);

        CourseScheduleEntity schedule = new CourseScheduleEntity();
        schedule.setCourseId(course.getId());
        schedule.setCoachId(coachId);
        schedule.setClassroomId(room.getId());
        schedule.setCourseDate(LocalDate.now().plusDays(1));
        schedule.setStartTime(LocalTime.of(10, 0));
        schedule.setEndTime(LocalTime.of(11, 0));
        schedule.setMaxCapacity(8);
        schedule.setBookedCount(0);
        schedule.setStatus("SCHEDULED");
        scheduleMapper.insert(schedule);

        UserEntity user = new UserEntity();
        user.setOpenid("tr_user_" + System.nanoTime());
        user.setNickname("学员");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();

        bookingId = bookingService.book(userId, schedule.getId(), null, null);
    }

    @Test
    void createRecord_duplicate_shouldBeRejected() {
        TrainingRecordEntity record = new TrainingRecordEntity();
        record.setBookingId(bookingId);
        record.setGoal("测试目标");
        Long id = trainingService.createRecord(coachId, false, record);
        assertNotNull(id);

        TrainingRecordEntity dup = new TrainingRecordEntity();
        dup.setBookingId(bookingId);
        dup.setGoal("重复");
        BusinessException e = assertThrows(BusinessException.class,
                () -> trainingService.createRecord(coachId, false, dup));
        assertTrue(e.getMessage().contains("已填写"));
    }

    @Test
    void createRecord_concurrent_shouldInsertOnlyOne() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    fire.await();
                    TrainingRecordEntity r = new TrainingRecordEntity();
                    r.setBookingId(bookingId);
                    r.setGoal("并发");
                    trainingService.createRecord(coachId, false, r);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getMessage().contains("已填写")) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }
        fire.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "并发提交只应成功一次");
        assertEquals(1, conflict.get(), "并发重复应被拒绝一次");
        assertEquals(1, trainingRecordMapper.selectCount(new LambdaQueryWrapper<TrainingRecordEntity>()
                .eq(TrainingRecordEntity::getBookingId, bookingId)), "最终应只有一条有效记录");
    }

    @Test
    void hasServed_trueWhenCoachHasBooking() {
        assertTrue(studentService.hasServed(coachId, userId));
    }

    @Test
    void hasServed_falseWhenNoRelation() {
        UserEntity other = new UserEntity();
        other.setOpenid("tr_other_" + System.nanoTime());
        other.setNickname("无关系");
        other.setStatus("ACTIVE");
        userMapper.insert(other);
        assertFalse(studentService.hasServed(coachId, other.getId()));
    }

    private void flushTestRedis() {
        var factory = redisTemplate.getConnectionFactory();
        if (factory != null) {
            try (var conn = factory.getConnection()) {
                conn.serverCommands().flushDb();
            }
        }
    }
}
