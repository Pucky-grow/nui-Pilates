package com.yoyi.pilates.stats.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.trade.infrastructure.entity.OrderEntity;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
        List<OrderEntity> paidOrders = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .in(OrderEntity::getStatus, List.of("PAID", "REFUNDED"))
                .ge(OrderEntity::getPaidAt, from).lt(OrderEntity::getPaidAt, to));
        return paidOrders.stream().map(OrderEntity::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
