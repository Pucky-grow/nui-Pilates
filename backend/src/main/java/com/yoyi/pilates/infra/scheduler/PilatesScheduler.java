package com.yoyi.pilates.infra.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.application.StockService;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.notify.application.NotificationService;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.product.infrastructure.mapper.UserPackageMapper;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.trade.application.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务: 关单/课程完成/开课提醒/到期提醒/过期处理/库存对账.
 * (生产可替换为 XXL-JOB, 任务逻辑保持不变)
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "yoyi.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PilatesScheduler {

    private final OrderService orderService;
    private final BookingService bookingService;
    private final BookingMapper bookingMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseMapper courseMapper;
    private final MemberMapper memberMapper;
    private final UserPackageMapper userPackageMapper;
    private final StockService stockService;
    private final NotificationService notificationService;
    private final SystemConfigService configService;

    /** 关闭超时未支付订单(每5分钟) */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 30 * 1000)
    public void closeTimeoutOrders() {
        int closed = orderService.closeTimeoutOrders(30);
        if (closed > 0) {
            log.info("[定时] 关闭超时订单 {} 单", closed);
        }
    }

    /** 课程结束自动完成: 已签到→已完成, 未签到→爽约(每5分钟) */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void finishPassedSchedules() {
        LocalDateTime now = com.yoyi.pilates.common.util.StudioTime.now();
        long cursor=0;
        while(true) {
        List<CourseScheduleEntity> passed = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getStatus, "SCHEDULED")
                        .le(CourseScheduleEntity::getCourseDate, now.toLocalDate())
                        .gt(CourseScheduleEntity::getId,cursor).orderByAsc(CourseScheduleEntity::getId)
                        .last("LIMIT 200"));
        if(passed.isEmpty()) break;
        cursor=passed.get(passed.size()-1).getId();
        int done = 0;
        for (CourseScheduleEntity schedule : passed) {
            if (LocalDateTime.of(schedule.getCourseDate(), schedule.getEndTime()).isBefore(now)) {
                int[] result = bookingService.finishSchedule(schedule.getId());
                if (result[0] + result[1] > 0) {
                    done++;
                    log.info("[定时] 课程完成 scheduleId={} 完成{} 爽约{}",
                            schedule.getId(), result[0], result[1]);
                }
            }
        }
    }

        }
    /** 开课提醒(每10分钟): 未来约2小时内开课的预约 */
    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 90 * 1000)
    public void courseRemind() {
        LocalDateTime now = com.yoyi.pilates.common.util.StudioTime.now();
        LocalDateTime horizon = now.plusMinutes(120);
        long cursor=0;
        while(true) {
        List<CourseScheduleEntity> upcoming = scheduleMapper.selectList(
                new LambdaQueryWrapper<CourseScheduleEntity>()
                        .eq(CourseScheduleEntity::getStatus, "SCHEDULED")
                        .le(CourseScheduleEntity::getCourseDate, horizon.toLocalDate())
                        .gt(CourseScheduleEntity::getId,cursor).orderByAsc(CourseScheduleEntity::getId)
                        .last("LIMIT 500"));
        if(upcoming.isEmpty()) break;
        cursor=upcoming.get(upcoming.size()-1).getId();
        for (CourseScheduleEntity schedule : upcoming) {
            LocalDateTime start = LocalDateTime.of(schedule.getCourseDate(), schedule.getStartTime());
            if (start.isAfter(now) && start.isBefore(horizon)) {
                List<BookingEntity> bookings = bookingMapper.selectList(
                        new LambdaQueryWrapper<BookingEntity>()
                                .eq(BookingEntity::getScheduleId, schedule.getId())
                                .eq(BookingEntity::getStatus, "BOOKED"));
                for (BookingEntity booking : bookings) {
                    Map<String, String> vars = new HashMap<>();
                    vars.put("courseName", courseName(schedule.getCourseId()));
                    vars.put("startTime", schedule.getStartTime().toString());
                    vars.put("courseDate", schedule.getCourseDate().toString());
                    notificationService.send(booking.getUserId(), "COURSE_REMIND", vars,
                            "COURSE_REMIND", String.valueOf(booking.getId()));
                }
            }
        }
    }

        }
    /** 会员到期 / 课时不足提醒(每天10:07) */
    @Scheduled(cron = "0 7 10 * * ?", zone="Asia/Shanghai")
    public void expireReminds() {
        for (int days : configService.getIntList("member.expire_remind_days")) {
            LocalDate target = com.yoyi.pilates.common.util.StudioTime.today().plusDays(days);
            List<MemberEntity> members = memberMapper.selectList(
                    new LambdaQueryWrapper<MemberEntity>().eq(MemberEntity::getValidUntil, target));
            for (MemberEntity member : members) {
                Map<String, String> vars = Map.of("expireDate", member.getValidUntil().toString());
                notificationService.send(member.getUserId(), "MEMBER_EXPIRE", vars,
                        "MEMBER_EXPIRE", member.getUserId() + ":" + target + ":" + days);
            }
        }
        for (int threshold : configService.getIntList("credits.low_remind")) {
            long cursor=0;
            while(true) {
            List<UserPackageEntity> lowPackages = userPackageMapper.selectList(
                    new LambdaQueryWrapper<UserPackageEntity>()
                            .eq(UserPackageEntity::getStatus, "ACTIVE")
                            .eq(UserPackageEntity::getRemainingCredits, threshold)
                            .gt(UserPackageEntity::getId,cursor).orderByAsc(UserPackageEntity::getId)
                            .last("LIMIT 500"));
            if(lowPackages.isEmpty()) break;
            cursor=lowPackages.get(lowPackages.size()-1).getId();
            for (UserPackageEntity up : lowPackages) {
                Map<String, String> vars = Map.of(
                        "packageName", up.getName(), "remaining", String.valueOf(up.getRemainingCredits()));
                notificationService.send(up.getUserId(), "CREDITS_LOW", vars,
                        "CREDITS_LOW", up.getId() + ":" + threshold);
            }
        }
    }

        }
    /** 过期处理(每天03:00): 优惠券/课包状态推进 */
    @Scheduled(cron = "0 0 3 * * ?", zone="Asia/Shanghai")
    public void expireEntities() {
        int coupons = userPackageMapper.expirePackages(com.yoyi.pilates.common.util.StudioTime.today());
        log.info("[定时] 过期课包 {} 个", coupons);
    }

    /** 库存对账(每小时): Redis 库存校准为 DB(max - booked) */
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void reconcileStock() {
        log.info("[定时] 库存对账完成, 校准 {} 条排课", stockService.reconcileAll());
    }

    private String courseName(Long courseId) {
        CourseEntity course = courseMapper.selectById(courseId);
        return course != null ? course.getName() : "课程";
    }
}
