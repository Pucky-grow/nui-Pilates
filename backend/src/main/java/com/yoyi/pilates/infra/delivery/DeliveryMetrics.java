package com.yoyi.pilates.infra.delivery;
import io.micrometer.core.instrument.*;import org.springframework.stereotype.Component;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(name="yoyi.mq.type",havingValue="outbox")
public class DeliveryMetrics {
 public DeliveryMetrics(MeterRegistry metrics,JdbcTemplate jdbc){
  gauge(metrics,jdbc,"pilates.outbox.failed","SELECT COUNT(*) FROM event_outbox WHERE state='FAILED'");
  gauge(metrics,jdbc,"pilates.outbox.pending","SELECT COUNT(*) FROM event_outbox WHERE state='PENDING'");
  gauge(metrics,jdbc,"pilates.outbox.oldest.seconds","SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP),0) FROM event_outbox WHERE state='PENDING'");
  gauge(metrics,jdbc,"pilates.booking.mismatches","SELECT COUNT(*) FROM course_schedule s WHERE s.deleted=0 AND (s.booked_count>s.max_capacity OR s.booked_count<>(SELECT COUNT(*) FROM booking b WHERE b.schedule_id=s.id AND b.deleted=0 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')))");
 }
 private void gauge(MeterRegistry registry,JdbcTemplate jdbc,String name,String sql){Gauge.builder(name,jdbc,db->{try{return db.queryForObject(sql,Double.class);}catch(Exception e){return Double.NaN;}}).register(registry);}
}
