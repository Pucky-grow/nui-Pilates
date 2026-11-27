package com.yoyi.pilates.stats.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据看板统计服务(后台 Dashboard / 财务).
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final OrderMapper orderMapper;
    private final BookingMapper bookingMapper;
    private final MemberMapper memberMapper;
    private final UserMapper userMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseMapper courseMapper;
    private final JdbcTemplate jdbc;

    /** 看板总览 */
    public Map<String, Object> dashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();
        YearMonth month = YearMonth.from(today);
        LocalDateTime monthStart = month.atDay(1).atStartOfDay();

        // 今日/本月营业额(已支付订单实付金额)
        BigDecimal todayRevenue = sumPaid(todayStart, todayEnd);
        BigDecimal monthRevenue = sumPaid(monthStart, LocalDateTime.now().plusDays(1));

        // 今日预约/上课/新增会员
        long todayBookings = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .ge(BookingEntity::getCreatedAt, todayStart).lt(BookingEntity::getCreatedAt, todayEnd));
        long todayFinished = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getStatus, "COMPLETED")
                .ge(BookingEntity::getUpdatedAt, todayStart).lt(BookingEntity::getUpdatedAt, todayEnd));
        long todayNewMembers = memberMapper.selectCount(new LambdaQueryWrapper<com.yoyi.pilates.member.infrastructure.entity.MemberEntity>()
                .ge(com.yoyi.pilates.member.infrastructure.entity.MemberEntity::getCreatedAt, todayStart)
                .lt(com.yoyi.pilates.member.infrastructure.entity.MemberEntity::getCreatedAt, todayEnd));

        long monthNewMembers = memberMapper.selectCount(new LambdaQueryWrapper<com.yoyi.pilates.member.infrastructure.entity.MemberEntity>()
                .ge(com.yoyi.pilates.member.infrastructure.entity.MemberEntity::getCreatedAt, monthStart));
        long monthBookings = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .ge(BookingEntity::getCreatedAt, monthStart));
        long monthFinished = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getStatus, "COMPLETED")
                .ge(BookingEntity::getUpdatedAt, monthStart));

        Map<String, Object> view = new HashMap<>();
        view.put("todayRevenue", todayRevenue);
        view.put("todayBookings", todayBookings);
        view.put("todayFinished", todayFinished);
        view.put("todayNewMembers", todayNewMembers);
        view.put("monthRevenue", monthRevenue);
        view.put("monthNewMembers", monthNewMembers);
        view.put("monthBookings", monthBookings);
        view.put("monthFinished", monthFinished);
        view.put("totalMembers", memberMapper.selectCount(null));
        return view;
    }

    /** 近 N 日营业额趋势(折线图) */
    public Map<String, Object> revenueTrend(int days) {
        Map<String, BigDecimal> series = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            BigDecimal revenue = sumPaid(d.atStartOfDay(), d.plusDays(1).atStartOfDay());
            series.put(d.toString(), revenue);
        }
        return Map.of("days", series.keySet(), "values", series.values());
    }

    /** 热门课程(按预约数) */
    public List<Map<String, Object>> topCourses(int limit) {
        List<CourseScheduleEntity> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .ge(CourseScheduleEntity::getCourseDate, LocalDate.now().minusDays(30)));
        Map<Long, Long> courseBookingCount = bookingMapper.selectList(
                        new LambdaQueryWrapper<BookingEntity>()
                                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN", "COMPLETED")))
                .stream().collect(Collectors.groupingBy(b -> scheduleIdToCourse(b.getScheduleId(), schedules),
                        Collectors.counting()));
        return courseBookingCount.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("courseId", e.getKey());
                    m.put("courseName", courseMapper.selectById(e.getKey()) != null
                            ? courseMapper.selectById(e.getKey()).getName() : "未知课程");
                    m.put("count", e.getValue());
                    return m;
                }).toList();
    }

    /** 教练授课统计 */
    public List<Map<String, Object>> coachStats() {
        List<CourseScheduleEntity> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .ge(CourseScheduleEntity::getCourseDate, LocalDate.now().minusDays(30)));
        Map<Long, Long> byCoach = schedules.stream()
                .collect(Collectors.groupingBy(CourseScheduleEntity::getCoachId, Collectors.counting()));
        return byCoach.entrySet().stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("coachId", e.getKey());
            m.put("count", e.getValue());
            return m;
        }).toList();
    }

    private BigDecimal sumPaid(LocalDateTime from, LocalDateTime to) {
        var query = new LambdaQueryWrapper<OrderEntity>()
                .in(OrderEntity::getStatus, List.of("PAID", "REFUNDED"));
        if (from != null) query.ge(OrderEntity::getPaidAt, from);
        if (to != null) query.lt(OrderEntity::getPaidAt, to);
        return orderMapper.selectList(query).stream().map(OrderEntity::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 经营账本总览，金额及上课人次按门店日期统计，每周从周一开始。 */
    public Map<String, Object> studioLedgerOverview(boolean canViewFinancials) {
        LocalDate today = StudioTime.today();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L).atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        Map<String, Object> attended = new LinkedHashMap<>();
        attended.put("today", countAttended(todayStart, todayStart.plusDays(1)));
        attended.put("week", countAttended(weekStart, null));
        attended.put("month", countAttended(monthStart, null));
        attended.put("total", countAttended(null, null));

        long groupCredits = 0;
        long privateCredits = 0;
        for (var row : jdbc.queryForList(
                "SELECT type, COALESCE(SUM(remaining_credits),0) AS credits FROM user_package "
                        + "WHERE status='ACTIVE' AND deleted=0 GROUP BY type")) {
            long credits = ((Number) row.get("credits")).longValue();
            if ("PRIVATE".equals(row.get("type"))) privateCredits += credits;
            else groupCredits += credits;
        }
        Map<String, Object> remaining = new LinkedHashMap<>();
        remaining.put("total", groupCredits + privateCredits);
        remaining.put("group", groupCredits);
        remaining.put("private", privateCredits);

        long upcomingBookings = bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>()
                .in(BookingEntity::getStatus, List.of("BOOKED", "SIGNED_IN")));

        Map<String, Object> view = new LinkedHashMap<>();
        if (canViewFinancials) {
            // 保留旧版小程序使用的订单实付字段。
            view.put("revenue", Map.of(
                    "today", sumPaid(todayStart, todayStart.plusDays(1)),
                    "week", sumPaid(weekStart, null),
                    "month", sumPaid(monthStart, null),
                    "total", sumPaid(null, null)));
            view.put("courseConsumption", courseConsumption(today));
            view.put("netReceipts", netReceipts(today));
        }
        view.put("attended", attended);
        view.put("remainingCredits", remaining);
        view.put("upcomingBookings", upcomingBookings);
        return view;
    }

    private Map<String, BigDecimal> courseConsumption(LocalDate today) {
        return sumLedgerAmounts(today, """
                SELECT e.business_at, -e.delta_fen AS amount_fen
                FROM studio_wallet_entry e
                WHERE e.kind IN ('COURSE', 'CANCELLATION')
                  AND NOT EXISTS (SELECT 1 FROM studio_wallet_entry r WHERE r.reversal_of = e.id)
                """);
    }

    private Map<String, BigDecimal> netReceipts(LocalDate today) {
        return sumLedgerAmounts(today, """
                SELECT paid_at AS business_at, pay_amount * 100 AS amount_fen
                FROM `order`
                WHERE status IN ('PAID', 'REFUNDING', 'REFUNDED') AND deleted = 0
                UNION ALL
                SELECT refunded_at, -amount * 100
                FROM refund
                WHERE status = 'SUCCESS' AND deleted = 0
                UNION ALL
                SELECT business_at, CASE WHEN kind = 'REFUND' THEN -amount_fen ELSE amount_fen END
                FROM studio_receipt
                WHERE kind IN ('TOPUP', 'REFUND') AND state IN ('VERIFIED', 'VERIFIED_COVERED')
                """);
    }

    private Map<String, BigDecimal> sumLedgerAmounts(LocalDate today, String transactions) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN business_at >= ? THEN amount_fen END), 0) AS today_fen,
                       COALESCE(SUM(CASE WHEN business_at >= ? THEN amount_fen END), 0) AS week_fen,
                       COALESCE(SUM(CASE WHEN business_at >= ? THEN amount_fen END), 0) AS month_fen,
                       COALESCE(SUM(amount_fen), 0) AS total_fen
                FROM (%s) AS transactions
                WHERE business_at < ?
                """.formatted(transactions), (rs, row) -> Map.of(
                "today", rs.getBigDecimal("today_fen").movePointLeft(2),
                "week", rs.getBigDecimal("week_fen").movePointLeft(2),
                "month", rs.getBigDecimal("month_fen").movePointLeft(2),
                "total", rs.getBigDecimal("total_fen").movePointLeft(2)),
                Timestamp.valueOf(today.atStartOfDay()), Timestamp.valueOf(monday.atStartOfDay()),
                Timestamp.valueOf(today.withDayOfMonth(1).atStartOfDay()), Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
    }

    private long countAttended(LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM studio_member_lesson l "
                + "JOIN studio_lesson_session s ON l.session_id = s.id WHERE l.outcome = 'ATTENDED'");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND s.start_at >= ?");
            args.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND s.start_at < ?");
            args.add(Timestamp.valueOf(to));
        }
        return jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    private Long scheduleIdToCourse(Long scheduleId, List<CourseScheduleEntity> schedules) {
        return schedules.stream().filter(s -> s.getId().equals(scheduleId))
                .map(CourseScheduleEntity::getCourseId).findFirst().orElse(0L);
    }

    /**
     * 经营分析: 到课率/爽约率/上座率/复购率(近30天).
     */
    public Map<String, Object> businessAnalysis() {
        List<BookingEntity> bookings = bookingMapper.selectList(
                new LambdaQueryWrapper<BookingEntity>()
                        .ge(BookingEntity::getCreatedAt, LocalDate.now().minusDays(30).atStartOfDay()));
        long total = bookings.size();
        long signedIn = bookings.stream().filter(b -> "SIGNED_IN".equals(b.getStatus())
                || "COMPLETED".equals(b.getStatus())).count();
        long noShow = bookings.stream().filter(b -> "NO_SHOW".equals(b.getStatus())).count();

        // 到课率 = 签到/完成 / 总数; 爽约率 = 爽约 / 总数
        double attendanceRate = total == 0 ? 0 : Math.round(signedIn * 1000.0 / total) / 10.0;
        double noShowRate = total == 0 ? 0 : Math.round(noShow * 1000.0 / total) / 10.0;

        // 上座率 = 平均(签到人数 / 课程容量)
        List<CourseScheduleEntity> finished = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .ge(CourseScheduleEntity::getCourseDate, LocalDate.now().minusDays(30))
                        .in(CourseScheduleEntity::getStatus, List.of("FINISHED", "SCHEDULED")));
        double occupancy = 0;
        if (!finished.isEmpty()) {
            double sum = finished.stream().mapToDouble(s ->
                    s.getMaxCapacity() == 0 ? 0 : s.getBookedCount() * 1.0 / s.getMaxCapacity()).sum();
            occupancy = Math.round(sum * 1000.0 / finished.size()) / 10.0;
        }

        // 复购率 = 有 >=2 笔支付订单的用户 / 有 >=1 笔支付订单的用户
        List<OrderEntity> paidOrders = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getStatus, "PAID"));
        Map<Long, Long> orderCountByUser = paidOrders.stream()
                .collect(Collectors.groupingBy(OrderEntity::getUserId, Collectors.counting()));
        long paidUsers = orderCountByUser.size();
        long repeatUsers = orderCountByUser.values().stream().filter(c -> c >= 2).count();
        double retentionRate = paidUsers == 0 ? 0 : Math.round(repeatUsers * 1000.0 / paidUsers) / 10.0;

        Map<String, Object> view = new HashMap<>();
        view.put("attendanceRate", attendanceRate);
        view.put("noShowRate", noShowRate);
        view.put("occupancyRate", occupancy);
        view.put("retentionRate", retentionRate);
        view.put("totalBookings", total);
        view.put("signedIn", signedIn);
        view.put("noShow", noShow);
        return view;
    }
}
