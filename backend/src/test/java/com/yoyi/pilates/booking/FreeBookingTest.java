package com.yoyi.pilates.booking;

import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
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
import com.yoyi.pilates.product.infrastructure.mapper.PackageCreditLogMapper;
import com.yoyi.pilates.product.infrastructure.mapper.UserPackageMapper;
import com.yoyi.pilates.system.infrastructure.entity.SystemConfigEntity;
import com.yoyi.pilates.system.infrastructure.mapper.SystemConfigMapper;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FREE 预约模式测试: 会员无需课包直接预约, 不扣课时, 不创建订单.
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class FreeBookingTest {

    @Autowired private com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper qualificationMapper;
    @Autowired private BookingService bookingService;
    @Autowired private UserMapper userMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CourseScheduleMapper scheduleMapper;
    @Autowired private CoachMapper coachMapper;
    @Autowired private ClassroomMapper classroomMapper;
    @Autowired private BookingMapper bookingMapper;
    @Autowired private UserPackageMapper userPackageMapper;
    @Autowired private PackageCreditLogMapper creditLogMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private SystemConfigMapper configMapper;
    @Autowired private com.yoyi.pilates.system.application.SystemConfigService configService;
    @Autowired private StringRedisTemplate redisTemplate;

    private Long scheduleId;
    private Long userId;

    @BeforeEach
    void setUp() {
        flushTestRedis();
        // FREE 模式
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

        ClassroomEntity room = new ClassroomEntity();
        room.setName("教室");
        room.setType("GROUP");
        room.setCapacity(8);
        room.setStatus("ACTIVE");
        classroomMapper.insert(room);

        CourseEntity course = new CourseEntity();
        course.setCategoryId(category.getId());
        course.setName("免费预约课");
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
        schedule.setCoachId(coach.getId());
        schedule.setClassroomId(room.getId());
        schedule.setCourseDate(LocalDate.now().plusDays(1));
        schedule.setStartTime(LocalTime.of(10, 0));
        schedule.setEndTime(LocalTime.of(11, 0));
        schedule.setMaxCapacity(8);
        schedule.setBookedCount(0);
        schedule.setStatus("SCHEDULED");
        scheduleMapper.insert(schedule);
        scheduleId = schedule.getId();

        UserEntity user = new UserEntity();
        user.setOpenid("free_user_" + System.nanoTime());
        user.setNickname("无课包会员");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();
    }

    @Test
    void freeBooking_noPackageRequired_noCreditsDeducted_noOrderCreated() {
        // 该会员没有任何 user_package
        assertEquals(0, userPackageMapper.selectCount(null));

        Long bookingId = bookingService.book(userId, scheduleId, null, null);
        assertNotNull(bookingId);
        assertEquals("BOOKED", bookingMapper.selectById(bookingId).getStatus());
        // 预约 creditsUsed 应为 0
        assertEquals(0, bookingMapper.selectById(bookingId).getCreditsUsed());
        // 无课时流水
        assertEquals(0, creditLogMapper.selectCount(null));
        // 无订单
        assertEquals(0, orderMapper.selectCount(null));
        // 无课包变化
        assertEquals(0, userPackageMapper.selectCount(null));
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
