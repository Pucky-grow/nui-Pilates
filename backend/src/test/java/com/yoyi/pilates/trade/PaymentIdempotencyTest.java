package com.yoyi.pilates.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.marketing.application.PointsService;
import com.yoyi.pilates.product.infrastructure.entity.PackageEntity;
import com.yoyi.pilates.product.infrastructure.entity.PackageItemEntity;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.product.infrastructure.mapper.PackageItemMapper;
import com.yoyi.pilates.product.infrastructure.mapper.PackageMapper;
import com.yoyi.pilates.product.infrastructure.mapper.UserPackageMapper;
import com.yoyi.pilates.trade.application.OrderService;
import com.yoyi.pilates.trade.application.PaymentService;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.entity.PaymentEntity;
import com.yoyi.pilates.trade.infrastructure.mapper.PaymentMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 支付回调幂等测试(P7 验收标准):
 * 模拟微信重复回调/并发回调, 验证:
 * - 课包只发放一次
 * - 订单只推进一次
 * - 积分只入账一次
 */
@SpringBootTest(properties="yoyi.sales.package-enabled=true")
@ActiveProfiles("junit")
class PaymentIdempotencyTest {

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private PointsService pointsService;
    @Autowired private UserMapper userMapper;
    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageItemMapper packageItemMapper;
    @Autowired private UserPackageMapper userPackageMapper;
    @Autowired private PaymentMapper paymentMapper;
    @Autowired private com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper orderMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private javax.sql.DataSource dataSource;

    private Long userId;
    private Long orderId;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = dataSource.getConnection()) {
            boolean mysql = connection.getMetaData().getDatabaseProductName().equals("MySQL");
            if (mysql && (System.getenv("PILATES_TEST_MYSQL_URL") == null || !connection.getMetaData().getURL().equals(System.getenv("PILATES_TEST_MYSQL_URL"))))
                throw new IllegalStateException("MySQL tests require the owned isolated runner URL");
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(mysql ? "reset-mysql.sql" : "reset-h2.sql")).execute(dataSource);
        }
        flushTestRedis();
        // 用户
        UserEntity user = new UserEntity();
        user.setOpenid("pay_test_" + System.nanoTime());
        user.setNickname("支付测试");
        user.setGender(0);
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userId = user.getId();

        // 课包
        PackageEntity pkg = new PackageEntity();
        pkg.setName("幂等测试卡");
        pkg.setType("TIMES");
        pkg.setOriginalPrice(BigDecimal.valueOf(500));
        pkg.setSalePrice(BigDecimal.valueOf(300));
        pkg.setTotalCredits(5);
        pkg.setValidDays(90);
        pkg.setIsNewUserOnly(0);
        pkg.setAllowTransfer(0);
        pkg.setAllowRefund(1);
        pkg.setPurchaseLimit(0);
        pkg.setSort(0);
        pkg.setStatus("ON_SHELF");
        packageMapper.insert(pkg);
        PackageItemEntity item = new PackageItemEntity();
        item.setPackageId(pkg.getId());
        item.setItemType("ALL");
        packageItemMapper.insert(item);

        // 下单 + 创建支付参数(生成支付单)
        orderId = orderService.createOrder(userId, pkg.getId(), null, null);
        paymentService.createPayParams(userId, orderId);
    }

    @Test
    void repeatedCallback_shouldGrantPackageOnlyOnce() {
        // 同一回调重复 3 次
        paymentService.mockPay(userId, orderId);
        paymentService.mockPay(userId, orderId);
        paymentService.mockPay(userId, orderId);

        assertGrantOnlyOnce();
    }

    @Test
    void concurrentCallback_shouldGrantPackageOnlyOnce() throws InterruptedException {
        int concurrency = 10;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch fire = new CountDownLatch(1);
        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                try {
                    fire.await();
                    paymentService.mockPay(userId, orderId);
                } catch (Exception ignored) {
                    // 并发下重复结算抛出的异常可接受, 以最终状态断言为准
                }
            });
        }
        fire.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertGrantOnlyOnce();
    }

    @Test
    void historicalZeroAmountOrderSettlesWithoutOpeningProductionMockEndpoint() {
        var order=orderService.requireOrder(orderId);order.setPayAmount(java.math.BigDecimal.ZERO);orderMapper.updateById(order);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService,"mockEnabled",false);
        try { var result=paymentService.createPayParams(userId,orderId);assertEquals(true,result.get("settled"));assertEquals(false,result.get("mock"));assertGrantOnlyOnce(0); }
        finally {org.springframework.test.util.ReflectionTestUtils.setField(paymentService,"mockEnabled",true);}
    }

    private void assertGrantOnlyOnce() { assertGrantOnlyOnce(300); }
    private void assertGrantOnlyOnce(int expectedPoints) {
        // 课包只发一次
        Long packageCount = userPackageMapper.selectCount(new LambdaQueryWrapper<UserPackageEntity>()
                .eq(UserPackageEntity::getUserId, userId)
                .eq(UserPackageEntity::getOrderId, orderId));
        assertEquals(1, packageCount, "重复回调不得重复发课包");

        UserPackageEntity up = userPackageMapper.selectOne(new LambdaQueryWrapper<UserPackageEntity>()
                .eq(UserPackageEntity::getUserId, userId));
        assertEquals(5, up.getRemainingCredits(), "课时不得重复到账");

        // 订单已支付
        OrderEntity order = orderService.requireOrder(orderId);
        assertEquals("PAID", order.getStatus());

        // 支付单只成功一次
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, orderId));
        assertEquals("SUCCESS", payment.getStatus());

        // 积分只入账一次(消费300元 × 1积分/元 = 300)
        assertEquals(expectedPoints, pointsService.balanceOf(userId), "积分不得重复入账");
    }

    private void flushTestRedis() {
        if (System.getenv("PILATES_TEST_REDIS_PORT") == null || System.getenv("PILATES_TEST_REDIS_PASSWORD") == null)
            throw new IllegalStateException("Use the isolated test runner");
        var factory = redisTemplate.getConnectionFactory();
        if (factory != null) {
            try (var conn = factory.getConnection()) {
                var info = conn.serverCommands().info("server");
                if (info == null || !System.getenv("PILATES_TEST_REDIS_PORT").equals(info.getProperty("tcp_port")))
                    throw new IllegalStateException("Refusing to reset Redis not owned by the test runner");
                conn.serverCommands().flushDb();
            }
        }
    }
}
