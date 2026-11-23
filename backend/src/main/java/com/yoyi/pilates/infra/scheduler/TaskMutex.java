package com.yoyi.pilates.infra.scheduler;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;

/** Watchdog renewal (no fixed lease); business state/unique constraints remain authoritative. */
@Aspect @Component @RequiredArgsConstructor
public class TaskMutex {
 private final RedissonClient redis;
 private final MeterRegistry metrics;
 @Around("execution(public * com.yoyi.pilates.infra.scheduler.PilatesScheduler.*(..))")
 public Object execute(ProceedingJoinPoint call) throws Throwable {
  String name=call.getSignature().getName(); var lock=redis.getLock("pilates:task:"+name);
  if(!lock.tryLock()) return null;
  try { Object result=call.proceed();metrics.counter("pilates.task.runs","task",name,"result","success").increment();return result; }
  catch(Throwable failure) {metrics.counter("pilates.task.runs","task",name,"result","failure").increment();throw failure;}
  finally {if(lock.isHeldByCurrentThread())lock.unlock();}
 }
}
