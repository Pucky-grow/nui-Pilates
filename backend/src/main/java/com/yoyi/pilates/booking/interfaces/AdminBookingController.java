package com.yoyi.pilates.booking.interfaces;

import com.yoyi.pilates.booking.interfaces.dto.ScheduledBookingRequest;
import com.yoyi.pilates.course.application.ScheduleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.booking.application.*;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.*;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.system.security.*;
import com.yoyi.pilates.user.infrastructure.mapper.UserMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
public class AdminBookingController {
    private final BookingService service;
    private final ScheduleService scheduling;
    private final com.yoyi.pilates.ledger.StudioLedgerAccess studioLedger;
    private final BookingQueryService queries;
    private final BookingMapper bookings;
    private final CourseScheduleMapper schedules;
    private final UserMapper users;
    private final com.yoyi.pilates.course.infrastructure.mapper.CourseMapper courses;

    public record Create(@NotNull Long userId, @NotNull Long scheduleId, @Size(max=255) String remark, @NotBlank @Size(max=255) String reason) {}
    public record Reason(@NotBlank @Size(max=255) String reason) {}
    public record Move(@NotNull Long scheduleId, @NotBlank @Size(max=255) String reason) {}

    @GetMapping
    @RequiresPerm("member:view")
    public Result<PageResult<Map<String,Object>>> list(@RequestParam(required=false) Long userId,
            @RequestParam(required=false) Long scheduleId,@RequestParam(required=false) String status,
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size) {
        if(page<1||size<1||size>100) throw BusinessException.of(ResultCode.BAD_REQUEST,"分页范围无效");
        var q=new LambdaQueryWrapper<BookingEntity>().eq(userId!=null,BookingEntity::getUserId,userId)
            .eq(scheduleId!=null,BookingEntity::getScheduleId,scheduleId)
            .eq(status!=null&&!status.isBlank(),BookingEntity::getStatus,status).orderByDesc(BookingEntity::getId);
        if(fromDate!=null||toDate!=null) {
            var ids=schedules.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity>()
                .ge(fromDate!=null,"course_date",fromDate).le(toDate!=null,"course_date",toDate)).stream().map(s->s.getId()).toList();
            if(ids.isEmpty()) q.apply("1=0");else q.in(BookingEntity::getScheduleId,ids);
        }
        if(keyword!=null&&!keyword.isBlank()) {
            var ids=users.selectList(new LambdaQueryWrapper<UserEntity>().isNotNull(!administrator(), UserEntity::getOpenid).and(w->w.like(UserEntity::getNickname,keyword).or().like(UserEntity::getPhone,keyword)))
                .stream().map(UserEntity::getId).toList();
            var courseIds=courses.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseEntity>().like(com.yoyi.pilates.course.infrastructure.entity.CourseEntity::getName,keyword)).stream().map(c->c.getId()).toList();
            var scheduleIds=courseIds.isEmpty()?List.<Long>of():schedules.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity>().in(com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity::getCourseId,courseIds)).stream().map(s->s.getId()).toList();
            q.and(w->{w.like(BookingEntity::getBookingNo,keyword);if(!ids.isEmpty()) w.or().in(BookingEntity::getUserId,ids);if(!scheduleIds.isEmpty())w.or().in(BookingEntity::getScheduleId,scheduleIds);});
        }
        return Result.ok(PageResult.of(bookings.selectPage(Page.of(page,size),q),this::view));
    }
    private Map<String,Object> view(BookingEntity b) {
        var v=new LinkedHashMap<String,Object>();
        v.put("booking",queries.bookingDetail(b.getUserId(),b.getId()));v.put("userId",b.getUserId());
        var u=users.selectById(b.getUserId());
        if (u != null && (u.getOpenid() != null || administrator())) {
            v.put("nickname", u.getNickname());
            v.put("phone", u.getPhone());
        } else if (u != null) {
            v.put("nickname", "线下会员");
        }
        return v;
    }
    @GetMapping("/{id}") @RequiresPerm("member:view")
    public Result<Map<String,Object>> detail(@PathVariable Long id){return Result.ok(view(service.requireBooking(id)));}
    @PostMapping @RequiresPerm("booking:manage")
    @OpLog(module="booking",action="admin_create",detail="#request.userId + ':' + #request.scheduleId + ' reason=' + #request.reason")
    public Result<Long> create(@Valid @RequestBody Create request){
        requireAdministrator();return Result.ok(service.book(request.userId(),request.scheduleId(),null,request.remark()));
    }
    @PostMapping("/new-schedule") @RequiresPerm("booking:manage")
    @OpLog(module = "booking", action = "admin_schedule_and_book",
            detail = "#request.userId + ' ' + #request.schedule.courseDate + ' ' + #request.schedule.startTime + ' reason=' + #request.reason")
    public Result<Long> createWithSchedule(@Valid @RequestBody ScheduledBookingRequest request) {
        requireAdministrator();
        if (request.userId() == null) throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择会员");
        return Result.ok(scheduling.createAndBook(request.schedule(), request.userId(), request.remark()));
    }
    @PostMapping("/{id}/cancel") @RequiresPerm("booking:manage")
    @OpLog(module="booking",action="admin_cancel",targetId="#id",detail="#request.reason")
    public Result<Void> cancel(@PathVariable Long id,@Valid @RequestBody Reason request){
        requireAdministrator();var b=service.requireBooking(id);service.cancelWithReason(b.getUserId(),id,"管理员取消："+request.reason());return Result.ok();
    }
    @PostMapping("/{id}/member-cancel") @RequiresPerm("booking:manage")
    @OpLog(module="booking",action="admin_member_cancel",targetId="#id",detail="#request.reason")
    public Result<Void> memberCancel(@PathVariable Long id, @Valid @RequestBody Reason request) {
        requireAdministrator();
        if (!studioLedger.enabled()) throw BusinessException.of(ResultCode.NOT_FOUND);
        var booking = service.requireBooking(id);
        service.cancelAtMemberRequest(booking.getUserId(), id, request.reason());
        return Result.ok();
    }
    @PostMapping("/{id}/move") @RequiresPerm("booking:manage")
    @OpLog(module="booking",action="admin_move",targetId="#id",detail="#request.scheduleId + ' reason=' + #request.reason")
    public Result<Long> move(@PathVariable Long id,@Valid @RequestBody Move request){
        requireAdministrator();return Result.ok(service.move(id,request.scheduleId(),request.reason()));
    }
    @PostMapping("/{id}/check-in") @RequiresPerm("staff:attendance:create")
    @OpLog(module="booking",action="admin_checkin",targetId="#id",detail="#request.reason")
    public Result<Void> checkin(@PathVariable Long id,@Valid @RequestBody Reason request){
        LoginUser actor = SecurityUtils.requireUser();
        if (!actor.isAdmin()) throw BusinessException.of(ResultCode.FORBIDDEN);
        service.checkIn(id, actor.isAdminRole() ? "ADMIN" : "STAFF", actor.getId());
        return Result.ok();
    }
    private boolean administrator() {var actor = SecurityUtils.requireUser(); return actor.isAdmin() && actor.isAdminRole();}
    private void requireAdministrator(){if(!administrator()) throw BusinessException.of(ResultCode.FORBIDDEN,"预约代操作仅管理员可用");}
}
