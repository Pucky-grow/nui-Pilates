package com.yoyi.pilates.booking.interfaces;

import com.yoyi.pilates.booking.application.BookingQueryService;
import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.interfaces.dto.BookingCreateRequest;
import com.yoyi.pilates.booking.interfaces.dto.BookingView;
import com.yoyi.pilates.common.annotation.Idempotent;
import com.yoyi.pilates.common.annotation.RateLimit;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 预约接口(小程序用户端).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final BookingQueryService bookingQueryService;
    private final com.yoyi.pilates.ledger.StudioCancellationCapture cancellation;

    /**
     * 创建预约：数据库有效预约唯一键防重复，前端防连点；允许取消后立即重约。
     */
    @PostMapping("/bookings")
    @RateLimit(key = "T(com.yoyi.pilates.system.security.SecurityUtils).requireMemberId()", limit = 20, windowSeconds = 60)
    public Result<Map<String, Long>> book(@Valid @RequestBody BookingCreateRequest request) {
        Long userId = SecurityUtils.requireMemberId();
        Long bookingId = bookingService.book(userId, request.getScheduleId(),
                request.getUserPackageId(), request.getRemark());
        return Result.ok(Map.of("bookingId", bookingId));
    }

    /** 取消预约 */
    @DeleteMapping("/bookings/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        bookingService.cancel(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    /** 预约详情 */
    @GetMapping("/bookings/{id}")
    public Result<BookingView> detail(@PathVariable Long id) {
        long userId = SecurityUtils.requireMemberId();
        BookingView view = bookingQueryService.bookingDetail(userId, id);
        view.setCancellationTerms(cancellation.termsForUser(userId).get(id));
        view.setCancelReason(null);
        return Result.ok(view);
    }

    /** 我的预约 */
    @GetMapping("/my/bookings")
    public Result<List<BookingView>> myBookings(@RequestParam(required = false) String status) {
        long userId = SecurityUtils.requireMemberId();
        List<BookingView> views = bookingQueryService.myBookingViews(userId, status);
        var terms = cancellation.termsForUser(userId);
        views.forEach(view -> {
            view.setCancellationTerms(terms.get(view.getId()));
            view.setCancelReason(null);
        });
        return Result.ok(views);
    }

    public record CheckInRequest(@jakarta.validation.constraints.NotNull Long bookingId,
                                 @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64) String code) {}

    /** 使用教练在签到窗口内出示的短效签到码。 */
    @PostMapping("/attendance/check-in")
    public Result<Void> checkIn(@Valid @RequestBody CheckInRequest request) {
        long userId = SecurityUtils.requireMemberId();
        bookingService.checkInWithCode(userId, request.bookingId(), request.code());
        return Result.ok();
    }
}
