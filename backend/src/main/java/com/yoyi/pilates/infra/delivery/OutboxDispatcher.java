package com.yoyi.pilates.infra.delivery;

import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.infra.mq.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** DB side effects and DONE commit together. No network effects are allowed in handlers. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="yoyi.mq.type", havingValue="outbox")
public class OutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ObjectProvider<EventConsumer> consumers;
    @Value("${yoyi.delivery.workers-enabled:true}") private boolean enabled;
    @Scheduled(fixedDelay=2000, initialDelay=5000)
    public void tick() { if (enabled) drain(100); }
    public int drain(int limit) {
        int completed = 0;
        for (int n=0; n<Math.min(limit,1000); n++) {
            AtomicLong id = new AtomicLong();
            try {
                Boolean found = new TransactionTemplate(transactions).execute(tx -> {
                    var rows = jdbc.queryForList("SELECT id,topic,payload FROM event_outbox WHERE state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
                    if (rows.isEmpty()) return false;
                    Map<String,Object> row=rows.get(0); id.set(((Number)row.get("id")).longValue());
                    DomainEvent event=JsonUtils.fromJson(row.get("payload").toString(),DomainEvent.class);
                    consumers.stream().filter(c->c.topic().equals(row.get("topic"))).forEach(c->c.onMessage(event));
                    if(jdbc.update("UPDATE event_outbox SET state='DONE',completed_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND state='PENDING'",id.get())!=1) throw new IllegalStateException("outbox claim lost");
                    return true;
                });
                if (!Boolean.TRUE.equals(found)) break;
                completed++;
            } catch (RuntimeException error) {
                if(id.get()==0) throw error;
                // Retry metadata commits separately after every handler effect has rolled back.
                new TransactionTemplate(transactions).executeWithoutResult(tx -> jdbc.update(
                    "UPDATE event_outbox SET attempts=attempts+1,state=CASE WHEN attempts>=10 THEN 'FAILED' ELSE 'PENDING' END,next_attempt_at=?,last_error=? WHERE id=? AND state='PENDING'",
                    LocalDateTime.now().plusMinutes(1),error.getClass().getSimpleName(),id.get()));
            }
        }
        return completed;
    }
}
