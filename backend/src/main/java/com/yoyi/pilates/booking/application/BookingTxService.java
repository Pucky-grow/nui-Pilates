package com.yoyi.pilates.booking.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.NoGenerator;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.product.application.PackageService;
import com.yoyi.pilates.product.infrastructure.entity.UserPackageEntity;
import com.yoyi.pilates.system.application.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 预约事务执行器(独立 Bean, 保证 @Transactional 经代理生效,
 * 避免 Service 内部自调用导致事务失效).
 */
@Service
@RequiredArgsConstructor
public class BookingTxService {

    private final BookingMapper bookingMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final PackageService packageService;
    private final EventPublisher eventPublisher;
    private final SystemConfigService configService;

    /** 是否 FREE 预约模式(无需课包/不扣课时) */
    private boolean freeMode() {
        return "FREE".equals(configService.get("booking.paymentMode", "FREE"));
    }

    /**
     * 预约事务: 占座 → (扣课时, PACKAGE 模式) → 建单. 任一失败整体回滚(调用方回补 Redis 库存).
     */
    @Transactional(rollbackFor = Exception.class)
    public Long bookInTx(Long userId, CourseScheduleEntity schedule, CourseEntity course,
                         Long userPackageId, String remark) {
        // 1. 排课占座(DB 条件更新, 防超卖最后防线)
        if (scheduleMapper.incrBooked(schedule.getId()) == 0) {
            throw BusinessException.of(ResultCode.SCHEDULE_FULL);
        }
        // 2. 课时处理: FREE 模式跳过课包校验与扣课时; PACKAGE 模式选课包扣课时
        BookingEntity booking = new BookingEntity();
        booking.setBookingNo(NoGenerator.bookingNo());
        booking.setUserId(userId);
        booking.setScheduleId(schedule.getId());
        if (freeMode()) {
            booking.setUserPackageId(null);
            booking.setCreditsUsed(0);
        } else {
            UserPackageEntity up = packageService.pickApplicablePackage(userId, course, userPackageId);
            int cost = course.getCreditsCost() == null ? 1 : course.getCreditsCost();
            packageService.deduct(up, cost, "BOOKING", String.valueOf(schedule.getId()));
            booking.setUserPackageId(up.getId());
            booking.setCreditsUsed("PERIOD".equals(up.getType()) ? 0 : cost);
        }
        // 3. 创建预约(唯一索引兜底防重复)
        booking.setStatus("BOOKED");
        booking.setRemark(remark);
        try {
            bookingMapper.insert(booking);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of(ResultCode.BOOKING_DUPLICATED);
        }

        return booking.getId();
    }

    /**
     * 取消事务: 乐观状态推进 → 释放座位 → 课时返还(或按规则扣课时).
     *
     * @param deductOnCancel 惩罚性扣除课时(0=全额返还)
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelInTx(BookingEntity booking, int deductOnCancel, String reason) {
        // 原子条件更新: WHERE status='BOOKED', 防并发重复取消, 与签到/完成互斥
        int updated = bookingMapper.update(null, new LambdaUpdateWrapper<BookingEntity>()
                .eq(BookingEntity::getId, booking.getId())
                .eq(BookingEntity::getStatus, "BOOKED")
                .set(BookingEntity::getStatus, "CANCELLED")
                .set(BookingEntity::getCancelReason, reason)
                .set(BookingEntity::getCancelledAt, com.yoyi.pilates.common.util.StudioTime.now()));
        if (updated == 0) {
            throw BusinessException.of(ResultCode.BOOKING_STATUS_ILLEGAL, "预约已被处理");
        }

        if (scheduleMapper.decrBooked(booking.getScheduleId()) != 1) {
            throw BusinessException.of(ResultCode.CONFLICT, "预约人数异常，取消未生效，请核对排课");
        }

        // 课时返还: FREE 模式(userPackageId 为空/未扣课时)跳过
        if (booking.getUserPackageId() != null && booking.getCreditsUsed() != null && booking.getCreditsUsed() > 0) {
            UserPackageEntity up = packageService.requireUserPackage(booking.getUserPackageId());
            int refundAmount = Math.max(0, booking.getCreditsUsed() - deductOnCancel);
            if (refundAmount > 0) {
                packageService.refund(up, refundAmount, "BOOKING", booking.getBookingNo(), reason);
            }
        }
    }
}
