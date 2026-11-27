package com.yoyi.pilates.course;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.course.application.*;
import com.yoyi.pilates.course.infrastructure.entity.*;
import com.yoyi.pilates.course.interfaces.*;
import com.yoyi.pilates.course.interfaces.dto.*;
import com.yoyi.pilates.coach.application.*;
import com.yoyi.pilates.coach.infrastructure.entity.*;
import com.yoyi.pilates.coach.infrastructure.mapper.*;
import com.yoyi.pilates.member.application.MemberService;
import com.yoyi.pilates.product.infrastructure.mapper.*;
import com.yoyi.pilates.trade.infrastructure.mapper.OrderMapper;
import com.yoyi.pilates.system.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingAndFreeFlowTest extends BookingFixture {
    @Autowired ScheduleService scheduleService;
    @Autowired CourseQueryService query;
    @Autowired CourseCoachService qualificationService;
    @Autowired BookingPolicy policy;
    @Autowired ScheduleAdminController admin;
    @Autowired CoachCourseController coachController;
    @Autowired CoachLeaveMapper leaves;
    @Autowired ScheduleChangeRequestService changes;
    @Autowired OrderMapper orders;
    @Autowired UserPackageMapper packages;
    @Autowired PackageCreditLogMapper credits;
    @Autowired MemberService members;
    ScheduleCreateRequest request(int hour) {
        var r=new ScheduleCreateRequest();r.setCourseId(courseId);r.setCoachId(coachId);r.setClassroomId(roomId);r.setCourseDate(StudioTime.today().plusDays(1));r.setStartTime(LocalTime.of(hour,0));r.setEndTime(LocalTime.of(hour+1,0));r.setMaxCapacity(8);return r;
    }
    ScheduleCreateRequest edit(int capacity) {var r=request(10);r.setMaxCapacity(capacity);return r;}
    @Test void freeGroupAndPrivateDoNotTouchTradeOrCredits(){
        Long a=bookings.book(userId,scheduleId,null,null);bookings.cancel(userId,a);
        var c=courses.selectById(courseId);c.setType("PRIVATE");courses.updateById(c);
        Long b=bookings.book(userId,scheduleId,null,null);assertNull(bookingMapper.selectById(b).getUserPackageId());assertEquals(0,bookingMapper.selectById(b).getCreditsUsed());
        assertEquals(0,packages.selectCount(null));assertEquals(0,orders.selectCount(null));assertEquals(0,credits.selectCount(null));
    }
    @Test void capacityExpansionAndFloorUseActualOccupiedCount(){
        for(int i=0;i<6;i++)bookings.book(newUser(),scheduleId,null,null);
        assertThrows(BusinessException.class,()->scheduleService.update(scheduleId,edit(5)));
        scheduleService.update(scheduleId,edit(10));for(int i=0;i<4;i++)bookings.book(newUser(),scheduleId,null,null);
        assertEquals(10,schedules.selectById(scheduleId).getBookedCount());assertFalse(query.scheduleDetail(scheduleId).isBookable());
        assertEquals("0",redis.opsForValue().get(com.yoyi.pilates.common.constant.RedisKeys.stock(scheduleId)));
    }
    @Test void bookingAndShrinkRaceCannotExceedCapacity() throws Exception {
        for(int i=0;i<5;i++)bookings.book(newUser(),scheduleId,null,null);Long sixth=newUser();
        parallel(List.of(()->bookings.book(sixth,scheduleId,null,null),()->scheduleService.update(scheduleId,edit(5))));
        var s=schedules.selectById(scheduleId);assertTrue(s.getMaxCapacity()>=s.getBookedCount());
    }
    @Test void bookingAndWholeCancellationRaceIsConsistent() throws Exception {
        parallel(List.of(()->bookings.book(userId,scheduleId,null,null),()->scheduleService.cancel(scheduleId,"race")));
        assertEquals("CANCELLED",schedules.selectById(scheduleId).getStatus());assertEquals(0,schedules.selectById(scheduleId).getBookedCount());
        assertEquals(0,bookingMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yoyi.pilates.booking.infrastructure.entity.BookingEntity>().eq(com.yoyi.pilates.booking.infrastructure.entity.BookingEntity::getStatus,"BOOKED")));
    }
    @Test void parallelOverlappingCreationOnlyOneWins() throws Exception {
        assertEquals(1,parallel(List.of(()->scheduleService.create(request(12)),()->scheduleService.create(request(12)))));
    }
    @Test void newScheduleAndBookingCommitTogetherWhenRequestsOverlap() throws Exception {
        members.ensureMember(userId);
        assertEquals(1, parallel(List.of(
                () -> scheduleService.createAndBook(request(12), userId, "核对预约"),
                () -> scheduleService.createAndBook(request(12), userId, "核对预约"))));
        assertEquals(2, schedules.selectCount(null));
        var booking = bookingMapper.selectList(null).get(0);
        assertEquals("BOOKED", booking.getStatus());
        assertEquals(1, schedules.selectById(booking.getScheduleId()).getBookedCount());
        assertEquals("7", redis.opsForValue().get(com.yoyi.pilates.common.constant.RedisKeys.stock(booking.getScheduleId())));
    }
    @Test void rejectedMemberAppointmentRollsBackNewSchedule() {
        members.ensureMember(userId);
        config.set("booking.max_per_day", "1");
        bookings.book(userId, scheduleId, null, null);
        var error = assertThrows(BusinessException.class, () -> scheduleService.createAndBook(request(13), userId, null));
        assertTrue(error.getMessage().contains("预约"));
        assertEquals(1, schedules.selectCount(null));
        assertEquals(1, bookingMapper.selectCount(null));
    }
    @Test void differentCoachesCannotShareOverlappingRoom() throws Exception {
        CoachEntity other=new CoachEntity();other.setName("另一教练");other.setStatus("ACTIVE");other.setYears(1);coaches.insert(other);qualificationService.assign(courseId,other.getId(),1L);
        var a=request(12);var b=request(12);b.setCoachId(other.getId());assertEquals(1,parallel(List.of(()->scheduleService.create(a),()->scheduleService.create(b))));
    }
    @Test void editingAlsoChecksConcurrentResourceConflicts() throws Exception {
        Long a=scheduleService.create(request(12));Long b=scheduleService.create(request(14));
        assertEquals(1,parallel(List.of(()->scheduleService.update(a,request(16)),()->scheduleService.update(b,request(16)))));
    }
    @Test void anyHistoryOnlyAllowsCapacityEdits(){
        Long id=bookings.book(userId,scheduleId,null,null);bookings.cancel(userId,id);
        assertThrows(BusinessException.class,()->scheduleService.update(scheduleId,request(12)));scheduleService.update(scheduleId,edit(9));
    }
    @Test void recurringUsesSameValidationAndRollsBackAll(){
        var r=new RecurringScheduleRequest();org.springframework.beans.BeanUtils.copyProperties(request(12),r);r.setFromDate(StudioTime.today().minusDays(2));r.setToDate(StudioTime.today().plusDays(2));r.setWeekdays(Set.of(1,2,3,4,5,6,7));
        assertThrows(BusinessException.class,()->scheduleService.createRecurring(r));assertEquals(1,schedules.selectCount(null));
        r.setFromDate(StudioTime.today().plusDays(2));r.setToDate(StudioTime.today().plusDays(3));r.setStartTime(LocalTime.of(15,0));r.setEndTime(LocalTime.of(14,0));assertThrows(BusinessException.class,()->scheduleService.createRecurring(r));
    }
    @Test void invalidCapacityRoomAndApprovedLeaveAreRejected(){
        var r=request(12);r.setMaxCapacity(0);assertThrows(BusinessException.class,()->scheduleService.create(r));r.setMaxCapacity(21);assertThrows(BusinessException.class,()->scheduleService.create(r));r.setMaxCapacity(8);
        var leave=new CoachLeaveEntity();leave.setCoachId(coachId);leave.setLeaveDate(r.getCourseDate());leave.setStartTime(r.getStartTime());leave.setEndTime(r.getEndTime());leave.setStatus("APPROVED");leaves.insert(leave);assertThrows(BusinessException.class,()->scheduleService.create(r));
    }
    @Test void qualificationAssignmentRecoveryIsExplicitAndScoped(){
        var q=qualifications.selectList(null).get(0);q.setStatus("DISABLED");qualifications.updateById(q);assertEquals("QUALIFICATION_DISABLED",query.scheduleDetail(scheduleId).getBookingStatus());
        qualificationService.assign(courseId,coachId,1L);assertTrue(query.scheduleDetail(scheduleId).isBookable());assertEquals(1,qualifications.selectCount(null));
    }
    @Test void displayAndSubmitSharePublicReasons(){
        var c=courses.selectById(courseId);c.setStatus("OFF_SHELF");courses.updateById(c);var view=query.scheduleDetail(scheduleId);
        var error=assertThrows(BusinessException.class,()->bookings.book(userId,scheduleId,null,null));assertEquals(view.getUnbookableReason(),error.getMessage());assertFalse(view.isBookable());
        assertEquals(0,query.listSchedules(StudioTime.today(),StudioTime.today().plusDays(7),null,null,null,null,null,true).size());
        assertEquals(1,query.listSchedules(StudioTime.today(),StudioTime.today().plusDays(7),null,null,null,null,null,false).size());
    }
    @Test void explicitPublicStatesCoverWindowDeadlineFullCancelledAndEnded(){
        var s=schedules.selectById(scheduleId);s.setCourseDate(StudioTime.today().plusDays(8));schedules.updateById(s);assertEquals("NOT_OPEN",query.scheduleDetail(scheduleId).getBookingStatus());
        s=schedules.selectById(scheduleId);var boundaryStart=StudioTime.now().plusMinutes(10);s.setCourseDate(boundaryStart.toLocalDate());s.setStartTime(boundaryStart.toLocalTime());s.setEndTime(boundaryStart.plusMinutes(20).toLocalTime());schedules.updateById(s);assertEquals("DEADLINE",query.scheduleDetail(scheduleId).getBookingStatus());
        s=schedules.selectById(scheduleId);s.setStatus("CANCELLED");schedules.updateById(s);assertEquals("CANCELLED",query.scheduleDetail(scheduleId).getBookingStatus());
        s=schedules.selectById(scheduleId);s.setStatus("FINISHED");schedules.updateById(s);assertEquals("ENDED",query.scheduleDetail(scheduleId).getBookingStatus());
    }
    @Test void approvedChangePreservesBookingIdentityAndOriginalHistory(){
        Long bookingId = bookings.book(userId, scheduleId, null, null);
        Long other = newUser();
        Long cancelled = bookings.book(other, scheduleId, null, null);
        bookings.cancel(other, cancelled);
        var request = new ScheduleChangeRequestEntity();
        request.setScheduleId(scheduleId);
        request.setType("TIME");
        request.setReason("测试调课");
        request.setNewCourseDate(StudioTime.today().plusDays(1));
        request.setNewStartTime(LocalTime.of(12, 0));
        request.setNewEndTime(LocalTime.of(13, 0));
        Long change = changes.submit(coachId, request);
        var stale = changes.preview(change);
        assertEquals(1, stale.affectedCount());
        bookings.book(other, scheduleId, null, null);
        assertThrows(BusinessException.class, () -> changes.approve(change, 1L, true, null,
                stale.current().getVersion(), stale.affectedCount()));
        var preview = changes.preview(change);
        changes.approve(change, 1L, true, null, preview.current().getVersion(), preview.affectedCount());
        var replacement = schedules.selectById(bookingMapper.selectById(bookingId).getScheduleId());
        assertEquals("APPROVED", changes.requireRequest(change).getStatus());
        assertNotEquals(scheduleId, replacement.getId());
        assertEquals(LocalTime.of(12, 0), replacement.getStartTime());
        assertEquals(2, replacement.getBookedCount());
        assertEquals(scheduleId, bookingMapper.selectById(cancelled).getScheduleId());
        assertEquals("CANCELLED", schedules.selectById(scheduleId).getStatus());
        assertEquals(2, new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM notification WHERE biz_type='SCHEDULE_CHANGE'", Integer.class));
    }
    @Test void overlappingMemberBookingAndMoveAreRejected(){
        Long first = bookings.book(userId, scheduleId, null, null);
        Long overlap = newSchedule(8, 1, 10);
        Long later = newSchedule(8, 1, 11);
        var conflict = assertThrows(BusinessException.class, () -> bookings.book(userId, overlap, null, null));
        assertTrue(conflict.getMessage().contains("已有预约"));
        Long adjacent = bookings.book(userId, later, null, null);
        assertThrows(BusinessException.class, () -> bookings.move(adjacent, overlap, "测试挪课"));
        assertEquals("BOOKED", bookingMapper.selectById(adjacent).getStatus());
        assertEquals("BOOKED", bookingMapper.selectById(first).getStatus());
        assertEquals(0, schedules.selectById(overlap).getBookedCount());
    }
    @Test void privateCapacityAppliesToNewAndHistoricalSchedules(){
        var course = courses.selectById(courseId);
        course.setType("PRIVATE");
        courses.updateById(course);
        var invalid = request(12);
        invalid.setMaxCapacity(2);
        assertThrows(BusinessException.class, () -> admin.create(invalid));
        assertEquals(1, query.scheduleDetail(scheduleId).getRemaining());
        bookings.book(userId, scheduleId, null, null);
        assertEquals("FULL", query.scheduleDetail(scheduleId).getBookingStatus());
        assertThrows(BusinessException.class, () -> bookings.book(newUser(), scheduleId, null, null));
    }
    void actor(String role,Set<String> permissions){LoginUser u=new LoginUser();u.setId(1L);u.setType("ADMIN");u.setRoles(Set.of(role));u.setPermissions(permissions);u.setCoachId(coachId);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));}
    @Test void scheduleRoleBoundariesAndActualCoachPermission(){
        actor("STAFF",Set.of("schedule:view"));assertThrows(BusinessException.class,()->admin.create(request(12)));assertThrows(BusinessException.class,()->admin.update(scheduleId,edit(9)));assertThrows(BusinessException.class,()->admin.cancel(scheduleId,"no"));
        actor("STORE_MANAGER",Set.of("schedule:view","schedule:create","schedule:update","schedule:cancel"));assertNotNull(admin.create(request(12)).getData());
        actor("COACH",Set.of());assertThrows(BusinessException.class,()->coachController.createSchedule(request(14)));
        actor("COACH",Set.of("coach:schedule:create"));var r=request(14);r.setCoachId(999L);Long id=coachController.createSchedule(r).getData();assertEquals(coachId,schedules.selectById(id).getCoachId());
        actor("MEMBER",Set.of());assertThrows(BusinessException.class,()->admin.create(request(16)));
        actor("ADMIN",Set.of());assertNotNull(admin.create(request(16)).getData());
    }
}
