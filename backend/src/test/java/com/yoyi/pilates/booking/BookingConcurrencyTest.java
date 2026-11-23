package com.yoyi.pilates.booking;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.constant.RedisKeys;
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
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageItemEntity;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.product.infrastructure.mapper.PackageItemMapper;
import com.yoyi.pilates.product.infrastructure.mapper.PackageMapper;
import com.yoyi.pilates.product.infrastructure.mapper.UserPackageMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预约并发测试(P6 验收标准):
 * 100 个用户同时抢 8 个名额:
 * - 最终预约成功数 &lt;= 8
 * - 库存不为负
 * - 无重复预约
 * - 课时不重复扣减
 */
@org.springframework.test.context.jdbc.Sql("classpath:reset-h2.sql")
@SpringBootTest
@ActiveProfiles("junit")
class BookingConcurrencyTest {

    private static final int USER_COUNT = 100;
    private static final int CAPACITY = 8;

    @Autowired private com.yoyi.pilates.course.infrastructure.mapper.CourseCoachMapper qualificationMapper;
    @Autowired private BookingService bookingService;
    @Autowired private UserMapper userMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseCategoryMapper categoryMapper;
    @Autowired private CourseScheduleMapper scheduleMapper;
    @Autowired private CoachMapper coachMapper;
    @Autowired private ClassroomMapper classroomMapper;
    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageItemMapper packageItemMapper;
    @Autowired private UserPackageMapper userPackageMapper;
    @Autowired private BookingMapper bookingMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.yoyi.pilates.system.application.SystemConfigService configService;

    private Long scheduleId;
    private Long packageId;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        flushTestRedis();
        // 本测试验证 PACKAGE 模式扣课时行为, 显式声明模式
        configService.set("booking.paymentMode", "PACKAGE");
        prepareCourseData();
        prepareUsersAndPackages();
    }

    @Test
    void concurrentBooking_shouldNeverExceedCapacity() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(USER_COUNT);
        CountDownLatch ready = new CountDownLatch(USER_COUNT);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        Map<Long, Integer> perUserSuccess = new ConcurrentHashMap<>();

        for (Long userId : userIds) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    fire.await();
                    bookingService.book(userId, scheduleId, null, null);
                    success.incrementAndGet();
                    perUserSuccess.merge(userId, 1, Integer::sum);
                } catch (BusinessException e) {
                    if (e.getMessage().contains("名额已满")) {
                        full.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 记录但不断言失败类型, 最终状态以 DB 断言为准
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        fire.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // ---- 核心断言 ----
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        Long bookedRows = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getScheduleId, scheduleId)
                .eq(BookingEntity::getStatus, "BOOKED"));

        assertEquals(CAPACITY, success.get(), "成功预约数必须等于名额数");
        assertEquals(CAPACITY, schedule.getBookedCount(), "排课已约人数必须等于名额数");
        assertEquals(CAPACITY, bookedRows.intValue(), "预约记录数必须等于名额数");
        assertTrue(perUserSuccess.values().stream().allMatch(c -> c == 1), "同一用户不能重复预约");

        // Redis 库存不能为负
        String stock = redisTemplate.opsForValue().get(RedisKeys.stock(scheduleId));
        assertEquals("0", stock, "库存应恰好扣到0");

        // 课时扣减: 每个成功用户恰好扣1
        for (Long userId : perUserSuccess.keySet()) {
            UserPackageEntity up = userPackageMapper.selectOne(new LambdaQueryWrapper<UserPackageEntity>()
                    .eq(UserPackageEntity::getUserId, userId));
            assertEquals(9, up.getRemainingCredits(), "成功用户应恰好扣1课时");
            assertEquals(1, up.getUsedCredits());
        }
    }

    @Test
    void duplicateBooking_shouldBeRejected() {
        Long userId = userIds.get(0);
        bookingService.book(userId, scheduleId, null, null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> bookingService.book(userId, scheduleId, null, null));
        assertTrue(e.getMessage().contains("已预约"));
    }

    @Test
    void cancel_shouldRestoreStockAndCredits() {
        Long userId = userIds.get(0);
        Long bookingId = bookingService.book(userId, scheduleId, null, null);
        bookingService.cancel(userId, bookingId);

        BookingEntity booking = bookingMapper.selectById(bookingId);
        assertEquals("CANCELLED", booking.getStatus());
        assertEquals(0, scheduleMapper.selectById(scheduleId).getBookedCount());
        UserPackageEntity up = userPackageMapper.selectOne(new LambdaQueryWrapper<UserPackageEntity>()
                .eq(UserPackageEntity::getUserId, userId));
        assertEquals(10, up.getRemainingCredits(), "免费窗口取消应全额返还课时");
        assertEquals(String.valueOf(CAPACITY),
                redisTemplate.opsForValue().get(RedisKeys.stock(scheduleId)), "取消后库存应恢复");
    }

    // ==================== 测试数据 ====================

    private void prepareCourseData() {
        CourseCategoryEntity category = new CourseCategoryEntity();
        category.setCode("TEST_" + System.nanoTime());
        category.setName("测试分类");
        category.setSort(0);
        category.setStatus("ACTIVE");
        categoryMapper.insert(category);

        CoachEntity coach = new CoachEntity();
        coach.setName("测试教练");
        coach.setYears(1);
        coach.setRating(BigDecimal.valueOf(5.0));
        coach.setSort(0);
        coach.setStatus("ACTIVE");
        coachMapper.insert(coach);

        ClassroomEntity room = new ClassroomEntity();
        room.setName("测试教室");
        room.setType("GROUP");
        room.setCapacity(CAPACITY);
        room.setStatus("ACTIVE");
        classroomMapper.insert(room);

        CourseEntity course = new CourseEntity();
        course.setCategoryId(category.getId());
        course.setName("测试器械课");
        course.setDurationMinutes(60);
        course.setType("GROUP");
        course.setDifficulty("JUNIOR");
        course.setCreditsCost(1);
        course.setPrice(BigDecimal.valueOf(100));
        course.setSort(0);
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
        schedule.setMaxCapacity(CAPACITY);
        schedule.setBookedCount(0);
        schedule.setStatus("SCHEDULED");
        scheduleMapper.insert(schedule);
        this.scheduleId = schedule.getId();

        PackageEntity pkg = new PackageEntity();
        pkg.setName("测试10次卡");
        pkg.setType("TIMES");
        pkg.setOriginalPrice(BigDecimal.valueOf(1000));
        pkg.setSalePrice(BigDecimal.valueOf(800));
        pkg.setTotalCredits(10);
        pkg.setValidDays(180);
        pkg.setIsNewUserOnly(0);
        pkg.setAllowTransfer(0);
        pkg.setAllowRefund(1);
        pkg.setPurchaseLimit(0);
        pkg.setSort(0);
        pkg.setStatus("ON_SHELF");
        packageMapper.insert(pkg);
        this.packageId = pkg.getId();

        PackageItemEntity item = new PackageItemEntity();
        item.setPackageId(pkg.getId());
        item.setItemType("ALL");
        packageItemMapper.insert(item);
    }

    private void prepareUsersAndPackages() {
        userIds = new ArrayList<>();
        for (int i = 0; i < USER_COUNT; i++) {
            UserEntity user = new UserEntity();
            user.setOpenid("test_user_" + i + "_" + System.nanoTime());
            user.setNickname("测试用户" + i);
            user.setGender(0);
            user.setStatus("ACTIVE");
            userMapper.insert(user);
            userIds.add(user.getId());

            UserPackageEntity up = new UserPackageEntity();
            up.setUserId(user.getId());
            up.setPackageId(packageId);
            up.setOrderId(1000000L + i);
            up.setName("测试10次卡");
            up.setType("TIMES");
            up.setTotalCredits(10);
            up.setRemainingCredits(10);
            up.setUsedCredits(0);
            up.setValidFrom(LocalDate.now());
            up.setValidUntil(LocalDate.now().plusDays(180));
            up.setStatus("ACTIVE");
            userPackageMapper.insert(up);
        }
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
