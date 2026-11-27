package com.yoyi.pilates.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.system.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 经营账本总览接口测试: /api/admin/statistics/studio-ledger.
 * 口径: 营业额=已支付订单实付金额(PAID/REFUNDED); 已上课=账本表 ATTENDED;
 * 剩余课次=ACTIVE 课包 remaining_credits; 周从周一开始.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "yoyi.studio-ledger.enabled=true")
class StatsOverviewHttpTest extends BookingFixture {

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    private final HttpClient client = HttpClient.newHttpClient();

    /** 共享上下文中 reset-h2.sql 不清账本表, 先清掉其他测试留下的记录. */
    @BeforeEach
    void clearLedgerTables() {
        jdbc.update("DELETE FROM studio_booking_terms");
        jdbc.update("DELETE FROM studio_member_lesson");
        jdbc.update("DELETE FROM studio_lesson_session");
    }

    private record Actor(long id, String token) {}

    private Actor actor(String role) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO sys_user(username,password,name,status,auth_version) VALUES(?,?,'经营账本测试','ACTIVE',0)",
                    new String[]{"id"});
            ps.setString(1, "test_" + UUID.randomUUID().toString().substring(0, 12));
            ps.setString(2, "unused-test-password");
            return ps;
        }, holder);
        long id = holder.getKey().longValue();
        Long roleId = jdbc.queryForList("SELECT id FROM sys_role WHERE code=?", role).stream()
                .map(r -> ((Number) r.get("id")).longValue()).findFirst().orElse(null);
        if (roleId == null) {
            var roleHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("INSERT INTO sys_role(code,name,status) VALUES(?,?,'ACTIVE')",
                        new String[]{"id"});
                ps.setString(1, role);
                ps.setString(2, role);
                return ps;
            }, roleHolder);
            roleId = roleHolder.getKey().longValue();
        }
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, roleId);
        String token = jwt.createToken(id, "ADMIN", 0L);
        redis.opsForValue().set(RedisKeys.loginToken(token), Long.toString(id));
        return new Actor(id, token);
    }

    private JsonNode get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }

    private JsonNode ok(String path, String token) throws Exception {
        var node = get(path, token);
        assertEquals(0, node.path("code").asInt(), () -> node.path("message").asText());
        return node.path("data");
    }

    private void order(LocalDateTime paidAt, String amount) {
        jdbc.update("INSERT INTO `order`(order_no,user_id,order_type,total_amount,discount_amount,points_amount,"
                        + "pay_amount,status,paid_at,created_at) VALUES(?,?,'PACKAGE',?,0,0,?,'PAID',?,?)",
                "ledger-" + UUID.randomUUID().toString().substring(0, 20), userId, amount, amount, paidAt, paidAt);
    }

    private void lesson(LocalDateTime startAt, String outcome, int seq) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO studio_lesson_session(schedule_id,source_ref,course_name,"
                            + "coach_name,course_type,start_at,end_at,price_fen) VALUES(NULL,?,?,?,'GROUP',?,?,0)",
                    new String[]{"id"});
            ps.setString(1, "overview-" + seq + "-" + UUID.randomUUID().toString().substring(0, 8));
            ps.setString(2, "经营账本测试课");
            ps.setString(3, "测试教练");
            ps.setObject(4, startAt);
            ps.setObject(5, startAt.plusHours(1));
            return ps;
        }, holder);
        long sessionId = holder.getKey().longValue();
        jdbc.update("INSERT INTO studio_member_lesson(member_id,session_id,outcome,decision,reason,recorded_at) "
                        + "VALUES(?,?,?,?,?,?)",
                userId, sessionId, outcome, "RULE", "经营账本测试", startAt);
    }

    private void pack(String type, int remaining, String status, int seq) {
        jdbc.update("INSERT INTO user_package(user_id,package_id,order_id,name,type,total_credits,remaining_credits,"
                        + "used_credits,valid_from,valid_until,status,version) VALUES(?,?,?,?,?,?,?,0,?,?,?,0)",
                userId, seq, seq, "测试课包", type, remaining, remaining, LocalDate.now(), LocalDate.now().plusYears(1), status);
    }

    private void assertAmount(String expected, JsonNode node) {
        assertEquals(0, new java.math.BigDecimal(expected).compareTo(node.decimalValue()),
                () -> "金额应为 " + expected + ", 实际 " + node);
    }

    @Test
    void overviewAggregatesRevenueAttendedAndRemainingCredits() throws Exception {
        var admin = actor("ADMIN");
        LocalDate today = StudioTime.today();
        boolean monthSeed = today.getDayOfMonth() > 10;

        // 营业额: 今日 100、昨日 50(本周)、10天前 25(当月桶, 仅当月已过10号)、40天前 30(仅累计)
        order(today.atTime(9, 0), "100.00");
        order(today.minusDays(1).atTime(9, 0), "50.00");
        order(today.minusDays(10).atTime(12, 0), "25.00");
        order(today.minusDays(40).atTime(12, 0), "30.00");

        // 已上课: 今日 1、昨日 1(本周)、10天前 1(当月桶, 仅当月已过10号)、40天前 1(仅累计)、今日爽约 1(不计)
        lesson(today.atTime(15, 0), "ATTENDED", 1);
        lesson(today.minusDays(1).atTime(15, 0), "ATTENDED", 2);
        lesson(today.minusDays(10).atTime(15, 0), "ATTENDED", 3);
        lesson(today.minusDays(40).atTime(15, 0), "ATTENDED", 4);
        lesson(today.atTime(16, 0), "NO_SHOW", 5);

        // 剩余课次: 小班 5 + 私教 3 有效, 过期课包 10 不计
        pack("GROUP", 5, "ACTIVE", 1);
        pack("PRIVATE", 3, "ACTIVE", 2);
        pack("GROUP", 10, "EXPIRED", 3);

        // 待上课预约: 1 个 BOOKED
        bookings.book(userId, scheduleId, null, null);

        var data = ok("/api/admin/statistics/studio-ledger", admin.token());
        assertAmount("100.00", data.path("revenue").path("today"));
        assertAmount("150.00", data.path("revenue").path("week"));
        assertAmount(monthSeed ? "175.00" : "150.00", data.path("revenue").path("month"));
        assertAmount("205.00", data.path("revenue").path("total"));
        assertEquals(1, data.path("attended").path("today").asLong());
        assertEquals(2, data.path("attended").path("week").asLong());
        assertEquals(monthSeed ? 3 : 2, data.path("attended").path("month").asLong());
        assertEquals(4, data.path("attended").path("total").asLong());
        assertEquals(8, data.path("remainingCredits").path("total").asLong());
        assertEquals(5, data.path("remainingCredits").path("group").asLong());
        assertEquals(3, data.path("remainingCredits").path("private").asLong());
        assertEquals(1, data.path("upcomingBookings").asLong());
    }

    @Test
    void nonAdminWithStatisticsPermGetsOverviewWithoutRevenue() throws Exception {
        var staff = actor("STAFF");
        jdbc.update("INSERT INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'statistics:view','统计查看','API',100)");
        Long permId = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='statistics:view'", Long.class);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) SELECT id,? FROM sys_role WHERE code='STAFF'", permId);

        var data = ok("/api/admin/statistics/studio-ledger", staff.token());
        assertFalse(data.has("revenue"), () -> "非管理员/店长不应看到营业额: " + data);
        assertTrue(data.has("attended"));
        assertTrue(data.has("remainingCredits"));
    }
}
