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
 * 金额、上课人次、课包与预约的总览，以及经营金额的查看权限。
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
        jdbc.update("DELETE FROM studio_wallet_entry");
        jdbc.update("DELETE FROM studio_receipt");
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

    private long walletEntry(String kind, String source, int sequence, Long reversalOf,
                             long delta, LocalDateTime businessAt) {
        long before = Math.max(0, -delta);
        jdbc.update("""
                INSERT INTO studio_wallet_entry(member_id,bucket,sequence_no,kind,delta_fen,before_fen,after_fen,
                    source_kind,source_id,reversal_of,business_at,posted_at,operator_id,reason)
                VALUES(?,'GENERAL',?,?,?,?,?,?,?,?,?,?,1,'经营账本测试')
                """, userId, sequence, kind, delta, before, before + delta, source, sequence, reversalOf,
                businessAt, StudioTime.now());
        return jdbc.queryForObject(
                "SELECT id FROM studio_wallet_entry WHERE member_id=? AND sequence_no=?", Long.class, userId, sequence);
    }

    private void receipt(String kind, String state, long amount, LocalDateTime businessAt) {
        jdbc.update("""
                INSERT INTO studio_receipt(member_id,kind,state,amount_fen,general_fen,group_fen,private_fen,
                    channel,business_at,reason,recorded_at)
                VALUES(?,?,?,?,?,0,0,'现金',?,'经营账本测试',?)
                """, userId, kind, state, amount, amount, businessAt, StudioTime.now());
    }

    private void lesson(LocalDateTime startAt, String outcome, int seq) {
        lesson(startAt, outcome, seq, "VERIFIED");
    }

    private void lesson(LocalDateTime startAt, String outcome, int seq, String state) {
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
        jdbc.update("INSERT INTO studio_member_lesson(member_id,session_id,outcome,decision,reason,recorded_at,state) "
                        + "VALUES(?,?,?,?,?,?,?)",
                userId, sessionId, outcome, "RULE", "经营账本测试", startAt, state);
    }

    private void pack(String type, int remaining, String status, int seq) {
        jdbc.update("INSERT INTO user_package(user_id,package_id,order_id,name,type,total_credits,remaining_credits,"
                        + "used_credits,valid_from,valid_until,status,version) VALUES(?,?,?,?,?,?,?,0,?,?,?,0)",
                userId, seq, seq, "测试课包", type, remaining, remaining, LocalDate.now(), LocalDate.now().plusYears(1), status);
    }

    private void assertAmount(String expected, JsonNode node) {
        assertTrue(node.isNumber(), () -> "预期金额字段，实际 " + node);
        assertEquals(0, new java.math.BigDecimal(expected).compareTo(node.decimalValue()),
                () -> "金额应为 " + expected + ", 实际 " + node);
    }

    @Test
    void overviewAggregatesRevenueAttendedAndRemainingCredits() throws Exception {
        var admin = actor("ADMIN");
        LocalDate today = StudioTime.today();
        boolean yesterdayThisWeek = today.getDayOfWeek().getValue() > 1;
        boolean yesterdayThisMonth = today.getDayOfMonth() > 1;
        boolean tenDaysAgoThisMonth = today.getDayOfMonth() > 10;

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
        // 待核实与已冲正的已上记录不计入人次
        lesson(today.atTime(17, 0), "ATTENDED", 6, "PENDING_VERIFY");
        lesson(today.atTime(18, 0), "ATTENDED", 7, "REVERSED");

        // 剩余课次: 小班 5 + 私教 3 有效, 过期课包 10 不计
        pack("GROUP", 5, "ACTIVE", 1);
        pack("PRIVATE", 3, "ACTIVE", 2);
        pack("GROUP", 10, "EXPIRED", 3);

        // 待上课预约: 1 个 BOOKED; 已删除的预约不计
        bookings.book(userId, scheduleId, null, null);
        jdbc.update("INSERT INTO booking(booking_no,user_id,schedule_id,status,deleted) VALUES(?,?,?,'BOOKED',1)",
                "deleted-" + UUID.randomUUID().toString().substring(0, 20), userId, scheduleId);

        var data = ok("/api/admin/statistics/studio-ledger", admin.token());
        assertAmount("100.00", data.path("revenue").path("today"));
        assertAmount(yesterdayThisWeek ? "150.00" : "100.00", data.path("revenue").path("week"));
        assertAmount(tenDaysAgoThisMonth ? "175.00" : yesterdayThisMonth ? "150.00" : "100.00",
                data.path("revenue").path("month"));
        assertAmount("205.00", data.path("revenue").path("total"));
        assertEquals(1, data.path("attended").path("today").asLong());
        assertEquals(yesterdayThisWeek ? 2 : 1, data.path("attended").path("week").asLong());
        assertEquals(tenDaysAgoThisMonth ? 3 : yesterdayThisMonth ? 2 : 1, data.path("attended").path("month").asLong());
        assertEquals(4, data.path("attended").path("total").asLong());
        assertEquals(8, data.path("remainingCredits").path("total").asLong());
        assertEquals(5, data.path("remainingCredits").path("group").asLong());
        assertEquals(3, data.path("remainingCredits").path("private").asLong());
        assertEquals(1, data.path("upcomingBookings").asLong());
    }

    @Test
    void courseConsumptionAndNetReceiptsUseTheirOwnTransactionsAndBusinessDates() throws Exception {
        var admin = actor("ADMIN");
        var today = StudioTime.today().atStartOfDay();
        var past = today.minusDays(60);
        var tomorrow = today.plusDays(1);
        var yesterday = today.minusDays(1);
        walletEntry("COURSE", "LESSON", 1, null, -12025, today);
        walletEntry("COURSE", "LESSON", 2, null, -200, yesterday);
        walletEntry("CANCELLATION", "BOOKING", 3, null, -3500, past);
        long reversed = walletEntry("COURSE", "LESSON", 4, null, -9000, past);
        walletEntry("REVERSAL", "REVERSAL", 5, reversed, 9000, past);
        walletEntry("TRANSFER", "MODE", 6, null, -1000, today);
        walletEntry("COURSE", "LESSON", 7, null, -7000, tomorrow);
        walletEntry("TOPUP", "RECEIPT", 8, null, 100000, past);

        order(past, "80.00");
        Long orderId = jdbc.queryForObject("SELECT id FROM `order` WHERE user_id=?", Long.class, userId);
        jdbc.update("UPDATE `order` SET status='REFUNDED' WHERE id=?", orderId);
        jdbc.update("""
                INSERT INTO refund(refund_no,order_id,payment_id,amount,status,refunded_at)
                VALUES('overview-refund',?,1,80,'SUCCESS',?)
                """, orderId, today);
        jdbc.update("""
                INSERT INTO refund(refund_no,order_id,payment_id,amount,status,refunded_at)
                VALUES('overview-pending',?,1,10,'PENDING',?)
                """, orderId, today);
        receipt("TOPUP", "VERIFIED_COVERED", 100000, past);
        receipt("TOPUP", "VERIFIED", 1005, today);
        receipt("REFUND", "VERIFIED", 5000, today);
        receipt("OPENING", "VERIFIED", 90000, past);
        receipt("TOPUP", "PENDING_VERIFY", 6000, today);
        receipt("TOPUP", "REVERSED", 3000, today);
        receipt("TOPUP", "VERIFIED", 9000, tomorrow);

        var data = ok("/api/admin/statistics/studio-ledger", admin.token());
        var consumption = data.path("courseConsumption");
        assertAmount("120.25", consumption.path("today"));
        assertAmount(today.getDayOfWeek().getValue() > 1 ? "122.25" : "120.25", consumption.path("week"));
        assertAmount(today.getDayOfMonth() > 1 ? "122.25" : "120.25", consumption.path("month"));
        assertAmount("157.25", consumption.path("total"));

        var receipts = data.path("netReceipts");
        assertAmount("-119.95", receipts.path("today"));
        assertAmount("-119.95", receipts.path("week"));
        assertAmount("-119.95", receipts.path("month"));
        assertAmount("960.05", receipts.path("total"));
        assertAmount("80.00", data.path("revenue").path("total"));
    }

    @Test
    void onlyAdminAndStoreManagerCanReadFinancials() throws Exception {
        var staff = actor("STAFF");
        var manager = actor("STORE_MANAGER");
        jdbc.update("INSERT INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'statistics:view','统计查看','API',100)");
        Long permId = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='statistics:view'", Long.class);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) SELECT id,? FROM sys_role WHERE code IN ('STAFF','STORE_MANAGER')", permId);

        var data = ok("/api/admin/statistics/studio-ledger", staff.token());
        assertFalse(data.has("revenue"), () -> "非管理员/店长不应看到营业额: " + data);
        assertTrue(data.has("attended"));
        assertTrue(data.has("remainingCredits"));
        assertFalse(data.has("courseConsumption"));
        assertFalse(data.has("netReceipts"));

        var managerView = ok("/api/admin/statistics/studio-ledger", manager.token());
        assertAmount("0.00", managerView.at("/courseConsumption/total"));
        assertAmount("0.00", managerView.at("/netReceipts/total"));
    }
}
