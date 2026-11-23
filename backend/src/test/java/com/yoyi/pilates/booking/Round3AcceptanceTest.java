package com.yoyi.pilates.booking;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.booking.application.*;
import com.yoyi.pilates.booking.infrastructure.entity.*;
import com.yoyi.pilates.course.application.ScheduleService;
import com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.*;
import org.redisson.api.RedissonClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class Round3AcceptanceTest extends BookingFixture {
    @Autowired ScheduleExecution execution;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.yoyi.pilates.infra.mq.EventPublisher events;
    @Autowired com.yoyi.pilates.marketing.application.PointsService points;
    @Autowired com.yoyi.pilates.marketing.application.MarketingEventsConsumer.CheckinPointsConsumer checkinConsumer;
    @Autowired ScheduleService scheduling;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean StockService stock;
    @Autowired com.yoyi.pilates.course.application.BookingPolicy policy;
    @Autowired com.yoyi.pilates.coach.application.ScheduleChangeRequestService changes;
    @Autowired com.yoyi.pilates.booking.infrastructure.mapper.WaitlistMapper waitlist;
    @Autowired RedissonClient redisson;

    ScheduleCreateRequest capacity(int n) {
        var r=new ScheduleCreateRequest();org.springframework.beans.BeanUtils.copyProperties(schedules.selectById(scheduleId),r);r.setMaxCapacity(n);return r;
    }
    @Test void scheduleLockCoversBeforeCommitAndAfterCompletion() throws Exception {
        bookings.book(newUser(),scheduleId,null,null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        var completedWhileHeld=new AtomicBoolean();Long other=newUser();var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->execution.run(scheduleId,userId,false,()->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(1,schedules.incrBooked(scheduleId));
                BookingEntity b=new BookingEntity();b.setBookingNo("commit-barrier");b.setUserId(userId);b.setScheduleId(scheduleId);b.setStatus("BOOKED");b.setCreditsUsed(0);assertEquals(1,bookingMapper.insert(b));
                execution.changed(scheduleId);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly){entered.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}
                    @Override public void afterCompletion(int status){completedWhileHeld.set(status==STATUS_COMMITTED && redisson.getLock(RedisKeys.bookingLock(scheduleId)).isHeldByCurrentThread());}
                });return b.getId();
            }));
            assertTrue(entered.await(15,TimeUnit.SECONDS));
            var b=pool.submit(()->{attempted.countDown();return bookings.book(other,scheduleId,null,null);});assertTrue(attempted.await(5,TimeUnit.SECONDS));
            assertTrue(redisson.getLock(RedisKeys.bookingLock(scheduleId)).isLocked());
            assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));
            // A separate DB transaction sees only the already committed initial booking.
            assertEquals(1,schedules.selectById(scheduleId).getBookedCount());assertEquals("7",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
            release.countDown();assertNotNull(a.get(15,TimeUnit.SECONDS));assertNotNull(b.get(15,TimeUnit.SECONDS));
            assertTrue(completedWhileHeld.get());assertEquals(3,schedules.selectById(scheduleId).getBookedCount());assertEquals("5",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void signedCompletedAndNoShowAllOccupyCapacityAndDailyLimit() {
        for(String state:List.of("SIGNED_IN","COMPLETED","NO_SHOW")) {
            Long sid=newSchedule(8,1,12+List.of("SIGNED_IN","COMPLETED","NO_SHOW").indexOf(state));
            Long id=bookings.book(userId,sid,null,null);var b=bookingMapper.selectById(id);b.setStatus(state);bookingMapper.updateById(b);
        }
        assertThrows(BusinessException.class,()->bookings.book(userId,scheduleId,null,null));
        for(int i=0;i<3;i++){Long id=bookings.book(newUser(),scheduleId,null,null);var b=bookingMapper.selectById(id);b.setStatus(List.of("SIGNED_IN","COMPLETED","NO_SHOW").get(i));bookingMapper.updateById(b);}
        assertThrows(BusinessException.class,()->scheduling.update(scheduleId,capacity(2)));
        scheduling.update(scheduleId,capacity(3));assertEquals(3,schedules.selectById(scheduleId).getBookedCount());assertEquals("0",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }
    @Test void oldBookingIdCannotSigninOrReplayCancellationIntoNewBooking() {
        Long old=bookings.book(userId,scheduleId,null,null);bookings.cancel(userId,old);Long current=bookings.book(userId,scheduleId,null,null);openCheckin();
        assertThrows(BusinessException.class,()->bookings.checkIn(old,"USER_SCAN",userId));
        bookings.cancel(userId,old);assertEquals(0,bookings.cancelBySchedule(scheduleId,"old event"));
        assertEquals("BOOKED",bookingMapper.selectById(current).getStatus());assertEquals(0,attendanceMapper.selectCount(null));
        bookings.checkInBySchedule(userId,scheduleId);assertEquals("SIGNED_IN",bookingMapper.selectById(current).getStatus());assertEquals(1,attendanceMapper.selectCount(null));
    }
    @Test void mysql8ReservedRankMappingAllowsExistingCancellationConsumerQuery() {
        assertEquals(0,waitlist.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yoyi.pilates.booking.infrastructure.entity.WaitlistEntity>().eq(com.yoyi.pilates.booking.infrastructure.entity.WaitlistEntity::getStatus,"WAITING").orderByAsc(com.yoyi.pilates.booking.infrastructure.entity.WaitlistEntity::getRank)).size());
    }
    @Test void failedTransactionNeverPublishesUncommittedInventory() {
        assertThrows(IllegalStateException.class,()->execution.run(scheduleId,userId,false,()->{
            assertEquals(1,schedules.incrBooked(scheduleId));execution.changed(scheduleId);throw new IllegalStateException("injected rollback");
        }));
        assertEquals(0,schedules.selectById(scheduleId).getBookedCount());assertEquals(0,bookingMapper.selectCount(null));
        stock.initFromDb(scheduleId);assertEquals("8",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }
    @Test void reconciliationRebuildsAndCleansCancelledAndFinishedStocks() {
        bookings.book(userId,scheduleId,null,null);Long cancelled=newSchedule(8,1,15), finished=newSchedule(8,1,16);
        var c=schedules.selectById(cancelled);c.setStatus("CANCELLED");schedules.updateById(c);var f=schedules.selectById(finished);f.setStatus("FINISHED");schedules.updateById(f);
        redis.opsForValue().set(RedisKeys.stock(scheduleId),"999");redis.opsForValue().set(RedisKeys.stock(cancelled),"8");redis.opsForValue().set(RedisKeys.stock(finished),"8");
        redis.opsForValue().set("round3:unrelated","keep");assertEquals(3,stock.reconcileAll());
        assertEquals("7",redis.opsForValue().get(RedisKeys.stock(scheduleId)));assertNull(redis.opsForValue().get(RedisKeys.stock(cancelled)));assertNull(redis.opsForValue().get(RedisKeys.stock(finished)));assertEquals("keep",redis.opsForValue().get("round3:unrelated"));
    }
    @Test void reconciliationDoesNotStarveRowsAfterTheOldThousandRowLimit() {
        for(int i=0;i<1001;i++)newSchedule(8,2,12);
        Long last=schedules.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity>().orderByDesc(com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity::getId)).get(0).getId();
        assertEquals(1002,stock.reconcileAll());assertEquals("8",redis.opsForValue().get(RedisKeys.stock(last)));
    }

    @Test void exactShanghaiDeadlineAndCheckinWindowBoundaries() {
        var s=schedules.selectById(scheduleId);var start=java.time.LocalDateTime.of(s.getCourseDate(),s.getStartTime());
        var clock=new java.util.concurrent.atomic.AtomicReference<>(start.minusHours(1));
        try(var mocked=org.mockito.Mockito.mockStatic(com.yoyi.pilates.common.util.StudioTime.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(com.yoyi.pilates.common.util.StudioTime::now).thenAnswer(invocation->clock.get());
            assertEquals("AVAILABLE",policy.evaluate(s).code());Long id=bookings.book(userId,scheduleId,null,null);
            clock.set(start.minusMinutes(30).minusNanos(1));assertEquals("AVAILABLE",policy.evaluate(s).code());assertThrows(BusinessException.class,()->bookings.checkInBySchedule(userId,scheduleId));
            clock.set(start.minusMinutes(30));assertEquals("DEADLINE",policy.evaluate(s).code());bookings.checkInBySchedule(userId,scheduleId);
            clock.set(java.time.LocalDateTime.of(s.getCourseDate(),s.getEndTime()).minusNanos(1));bookings.checkInBySchedule(userId,scheduleId);
            clock.set(java.time.LocalDateTime.of(s.getCourseDate(),s.getEndTime()));assertThrows(BusinessException.class,()->bookings.checkInBySchedule(userId,scheduleId));
            assertEquals("SIGNED_IN",bookingMapper.selectById(id).getStatus());assertEquals(1,attendanceMapper.selectCount(null));
        }
    }
    @Test void recurringPartialInsertAndApprovalConflictRollbackCompletely() {
        newSchedule(8,2,14);long before=schedules.selectCount(null);
        var r=new com.yoyi.pilates.course.interfaces.dto.RecurringScheduleRequest();org.springframework.beans.BeanUtils.copyProperties(capacity(8),r);
        r.setFromDate(com.yoyi.pilates.common.util.StudioTime.today().plusDays(1));r.setToDate(com.yoyi.pilates.common.util.StudioTime.today().plusDays(2));r.setWeekdays(Set.of(1,2,3,4,5,6,7));r.setStartTime(java.time.LocalTime.of(14,0));r.setEndTime(java.time.LocalTime.of(15,0));
        assertThrows(BusinessException.class,()->scheduling.createRecurring(r));assertEquals(before,schedules.selectCount(null));
        newSchedule(8,1,14);var change=new com.yoyi.pilates.coach.infrastructure.entity.ScheduleChangeRequestEntity();change.setScheduleId(scheduleId);change.setType("TIME");change.setNewCourseDate(r.getFromDate());change.setNewStartTime(java.time.LocalTime.of(14,0));change.setNewEndTime(java.time.LocalTime.of(15,0));change.setReason("测试时间冲突");Long id=changes.submit(coachId,change);
        var preview = changes.preview(id);
        assertThrows(BusinessException.class,()->changes.approve(id,1L,true,null,preview.current().getVersion(),preview.affectedCount()));assertEquals("PENDING",changes.requireRequest(id).getStatus());assertEquals(java.time.LocalTime.of(10,0),schedules.selectById(scheduleId).getStartTime());
    }
    @Test void failedCacheSynchronizationAndEvictionAfterCancellationRecoverByReconciliation() {
        Long id=bookings.book(userId,scheduleId,null,null);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected Redis write failure")).when(stock).initFromDb(scheduleId);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected Redis eviction failure")).when(stock).evict(scheduleId);
        assertDoesNotThrow(()->scheduling.cancel(scheduleId,"isolated fault"));assertEquals("CANCELLED",bookingMapper.selectById(id).getStatus());assertEquals(0,schedules.selectById(scheduleId).getBookedCount());assertEquals("7",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        org.mockito.Mockito.doCallRealMethod().when(stock).initFromDb(scheduleId);org.mockito.Mockito.doCallRealMethod().when(stock).evict(scheduleId);
        assertEquals(1,stock.reconcileAll());assertNull(redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }

    @Test void concurrentRepeatedSigninEmitsOneEventAndAwardsOnlyOnce() throws Exception {
        config.set("points.checkin","5");Long id=bookings.book(userId,scheduleId,null,null);openCheckin();org.mockito.Mockito.clearInvocations(events);
        assertEquals(16,parallel(java.util.Collections.nCopies(16,()->bookings.checkInBySchedule(userId,scheduleId))));
        assertEquals(1,attendanceMapper.selectCount(null));assertEquals("SIGNED_IN",bookingMapper.selectById(id).getStatus());
        org.mockito.Mockito.verify(events,org.mockito.Mockito.times(1)).publish(org.mockito.ArgumentMatchers.eq(com.yoyi.pilates.infra.mq.Topics.BOOKING_EVENT),org.mockito.ArgumentMatchers.argThat(e->"BOOKING_CHECKED_IN".equals(e.getTag())));
        var booking=bookingMapper.selectById(id);var replay=com.yoyi.pilates.infra.mq.DomainEvent.of("BOOKING_CHECKED_IN",booking.getBookingNo(),java.util.Map.of("bookingId",id,"userId",userId,"scheduleId",scheduleId));
        checkinConsumer.onMessage(replay);checkinConsumer.onMessage(replay);
        assertEquals(5,points.balanceOf(userId));
        assertEquals(1,new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM points_record WHERE user_id=? AND biz_type='BOOKING' AND biz_id=?",Integer.class,userId,booking.getBookingNo()));
    }
}
