package com.yoyi.pilates.booking;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.booking.infrastructure.entity.*;
import com.yoyi.pilates.booking.infrastructure.mapper.AttendanceMapper;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.course.application.ScheduleService;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.member.application.MemberService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundOneConsistencyTest extends BookingFixture {
    @Autowired ScheduleService scheduleService;
    @Autowired MemberService memberService;
    @MockitoSpyBean AttendanceMapper attendanceSpy;
    @MockitoSpyBean EventPublisher eventSpy;
    @MockitoSpyBean com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper scheduleSpy;
    @MockitoSpyBean com.yoyi.pilates.booking.application.StockService stockSpy;

    @Test void repeatedRebookingRetainsHistoryAndOldIdsAreHarmless() {
        Long a = bookings.book(userId,scheduleId,null,null); bookings.cancel(userId,a);
        Long b = bookings.book(userId,scheduleId,null,null); bookings.cancel(userId,a);
        assertEquals("BOOKED",bookingMapper.selectById(b).getStatus()); bookings.cancel(userId,b);
        Long c = bookings.book(userId,scheduleId,null,null);
        assertNotEquals(a,c); assertEquals(3,bookingMapper.selectCount(null)); assertEquals(1,schedules.selectById(scheduleId).getBookedCount());
        assertEquals("7",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }
    @Test void concurrentRebookingHasOneWinner() throws Exception {
        Long id=bookings.book(userId,scheduleId,null,null); bookings.cancel(userId,id);
        assertEquals(1,parallel(Collections.nCopies(8,()->bookings.book(userId,scheduleId,null,null))));
        assertEquals(1,schedules.selectById(scheduleId).getBookedCount());
    }
    @Test void hundredMembersNeverExceedEight() throws Exception {
        List<Runnable> tasks=new ArrayList<>(); for(int i=0;i<100;i++){Long id=newUser();tasks.add(()->bookings.book(id,scheduleId,null,null));}
        assertEquals(8,parallel(tasks)); assertEquals(8,schedules.selectById(scheduleId).getBookedCount());
        assertEquals(8,bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>().eq(BookingEntity::getStatus,"BOOKED")));
        assertEquals("0",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }
    @Test void memberDailyLimitCoversDifferentSchedules() throws Exception {
        List<Runnable> tasks=new ArrayList<>(); for(int i=0;i<6;i++){Long id=newSchedule(8,1,12+i);tasks.add(()->bookings.book(userId,id,null,null));}
        assertEquals(3,parallel(tasks));
    }
    @Test void concurrentCompletionOfDifferentClassesCountsBothOnce() throws Exception {
        memberService.ensureMember(userId);
        Long second = newSchedule(8,1,14);
        for (Long schedule : List.of(scheduleId, second)) {
            Long id = bookings.book(userId, schedule, null, null);
            var booked = bookingMapper.selectById(id);
            booked.setStatus("SIGNED_IN");
            bookingMapper.updateById(booked);
            var past = schedules.selectById(schedule);
            past.setCourseDate(StudioTime.today().minusDays(1));
            schedules.updateById(past);
        }

        assertEquals(2, parallel(List.of(
                () -> assertArrayEquals(new int[]{1, 0}, bookings.finishSchedule(scheduleId)),
                () -> assertArrayEquals(new int[]{1, 0}, bookings.finishSchedule(second)))));
        assertArrayEquals(new int[]{0, 0}, bookings.finishSchedule(second));
        var member = memberService.byUserId(userId);
        assertEquals(2, member.getTotalSessions());
        assertNotNull(member.getLastTrainingAt());
    }
    @Test void cancellationIsAtomicAndRedeliveryDoesNotRefundAgain() {
        Long id=bookings.book(userId,scheduleId,null,null); scheduleService.cancel(scheduleId,"测试"); scheduleService.cancel(scheduleId,"重试");
        assertEquals("CANCELLED",bookingMapper.selectById(id).getStatus()); assertEquals(0,schedules.selectById(scheduleId).getBookedCount());
        assertEquals(0,bookings.cancelBySchedule(scheduleId,"重复事件")); assertNull(redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }
    @Test void notificationFailureCannotRollBackCancellation() {
        Long id=bookings.book(userId,scheduleId,null,null); doThrow(new IllegalStateException("notification offline")).when(eventSpy).publish(anyString(),any());
        scheduleService.cancel(scheduleId,"测试"); assertEquals("CANCELLED",bookingMapper.selectById(id).getStatus());
    }
    @Test void signedInScheduleCannotBeCancelledAndDuplicateSigninIsIdempotent() {
        Long id=bookings.book(userId,scheduleId,null,null);openCheckin();bookings.checkInBySchedule(userId,scheduleId);bookings.checkInBySchedule(userId,scheduleId);
        assertEquals(1,attendanceMapper.selectCount(null)); assertThrows(BusinessException.class,()->scheduleService.cancel(scheduleId,"测试"));
        assertEquals("SCHEDULED",schedules.selectById(scheduleId).getStatus()); assertEquals("SIGNED_IN",bookingMapper.selectById(id).getStatus());
    }
    @Test void attendanceFailureRollsBackBookingStatus() {
        Long id=bookings.book(userId,scheduleId,null,null);openCheckin(); doThrow(new IllegalStateException("insert failed")).when(attendanceSpy).insert(any(AttendanceEntity.class));
        assertThrows(IllegalStateException.class,()->bookings.checkInBySchedule(userId,scheduleId)); assertEquals("BOOKED",bookingMapper.selectById(id).getStatus()); assertEquals(0,attendanceMapper.selectCount(null));
    }
    @Test void cancelAndSigninRaceHasOnlyConsistentOutcomes() throws Exception {
        Long id=bookings.book(userId,scheduleId,null,null);openCheckin(); parallel(List.of(()->scheduleService.cancel(scheduleId,"测试"),()->bookings.checkInBySchedule(userId,scheduleId)));
        boolean cancelled="CANCELLED".equals(schedules.selectById(scheduleId).getStatus()); assertEquals(cancelled?"CANCELLED":"SIGNED_IN",bookingMapper.selectById(id).getStatus()); assertEquals(cancelled?0:1,attendanceMapper.selectCount(null));
    }
    @Test void failedConditionalScheduleUpdateCannotRefundOrPublishSuccess() {
        Long id=bookings.book(userId,scheduleId,null,null);clearInvocations(eventSpy);
        doReturn(0).when(scheduleSpy).updateById(any(com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity.class));
        assertThrows(BusinessException.class,()->scheduleService.cancel(scheduleId,"stale version"));
        assertEquals("BOOKED",bookingMapper.selectById(id).getStatus());assertEquals("SCHEDULED",schedules.selectById(scheduleId).getStatus());
        assertEquals(1,schedules.selectById(scheduleId).getBookedCount());assertEquals("7",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        verifyNoInteractions(eventSpy);
    }
    @Test void databaseAloneRejectsSecondOccupiedReservation() {
        bookings.book(userId,scheduleId,null,null);BookingEntity duplicate=new BookingEntity();duplicate.setBookingNo("bypass-service");duplicate.setUserId(userId);duplicate.setScheduleId(scheduleId);duplicate.setStatus("BOOKED");duplicate.setCreditsUsed(0);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->bookingMapper.insert(duplicate));
    }
    @Test void cacheFailureAfterCommitDoesNotLieAboutDbAndNextWriteRecovers() {
        bookings.book(userId,scheduleId,null,null);
        doThrow(new IllegalStateException("cache unavailable")).when(stockSpy).initFromDb(scheduleId);
        var edit=new com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest();org.springframework.beans.BeanUtils.copyProperties(schedules.selectById(scheduleId),edit);edit.setMaxCapacity(10);
        assertDoesNotThrow(()->scheduleService.update(scheduleId,edit));assertEquals(10,schedules.selectById(scheduleId).getMaxCapacity());
        doCallRealMethod().when(stockSpy).initFromDb(scheduleId);bookings.book(newUser(),scheduleId,null,null);assertEquals("8",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }

}
