package com.yoyi.pilates.system;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.system.infrastructure.mapper.SystemConfigMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class SystemConfigCacheCommitTest {
 @Test void readersKeepValidCacheUntilWriteCommitsAndRollbackDoesNotEvict(){
  var redis=mock(StringRedisTemplate.class);var service=new SystemConfigService(mock(SystemConfigMapper.class),redis);
  TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
  try{
   service.evict("studio.name");verifyNoInteractions(redis);
   for(var synchronization:TransactionSynchronizationManager.getSynchronizations())synchronization.afterCommit();
   verify(redis).delete("pilates:config:studio.name");
  }finally{TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
  reset(redis);service.evict("studio.name");verify(redis).delete("pilates:config:studio.name");
 }
}
