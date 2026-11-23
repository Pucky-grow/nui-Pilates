package com.yoyi.pilates.booking.application;

import com.yoyi.pilates.common.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** 统一锁顺序：取锁后开启事务，提交后校准缓存，最后解锁。禁止携带外层事务进入。 */
@Slf4j
@Component
public class ScheduleExecution {
    public static final String RESOURCES = "pilates:lock:0:schedule-resources";
    private final RedissonClient redisson;
    private final StockService stock;
    private final TransactionTemplate transaction;
    private final ThreadLocal<Set<Long>> changed = new ThreadLocal<>();

    public ScheduleExecution(RedissonClient redisson, StockService stock, PlatformTransactionManager manager) {
        this.redisson = redisson;
        this.stock = stock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static String memberLock(Long id) { return "pilates:lock:1:member-booking:" + id; }

    public <T> T run(Long scheduleId, Long memberId, boolean resources, Supplier<T> work) {
        return runMany(scheduleId == null ? List.of() : List.of(scheduleId), memberId, resources, work);
    }

    public <T> T runMany(Collection<Long> scheduleIds, Long memberId, boolean resources, Supplier<T> work) {
        return runForMembers(scheduleIds, memberId == null ? List.of() : List.of(memberId), resources, work);
    }

    public <T> T runForMembers(Collection<Long> scheduleIds, Collection<Long> memberIds,
                               boolean resources, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || changed.get() != null) {
            throw new IllegalStateException("排课操作必须从无事务的统一锁入口进入");
        }
        SortedSet<String> keys = new TreeSet<>();
        if (resources) keys.add(RESOURCES);
        for (Long id : memberIds) keys.add(memberLock(id));
        for (Long id : scheduleIds) keys.add(RedisKeys.bookingLock(id));
        List<RLock> held = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        try {
            for (String key : keys) {
                RLock lock = redisson.getLock(key);
                lock.lock(); // watchdog renewal; no lease shorter than the DB transaction
                held.add(lock);
            }
            changed.set(ids);
            T result = transaction.execute(status -> work.get());
            for (Long id : ids) {
                try { stock.initFromDb(id); }
                catch (RuntimeException error) {
                    // 数据库已经提交，不能向用户谎报回滚；下一次写入和定时对账会重新校准。
                    log.error("排课已提交，库存缓存待校准 scheduleId={}", id, error);
                    try { stock.evict(id); } catch (RuntimeException ignored) { }
                }
            }
            return result;
        } finally {
            changed.remove();
            Collections.reverse(held);
            for (RLock lock : held) {
                try { if (lock.isHeldByCurrentThread()) lock.unlock(); }
                catch (RuntimeException error) { log.error("排课锁释放失败，等待 Redis 锁租期恢复", error); }
            }
        }
    }

    public void changed(Long scheduleId) {
        Set<Long> ids = changed.get();
        if (ids == null) throw new IllegalStateException("必须在排课事务内标记库存变化");
        ids.add(scheduleId);
    }
}
