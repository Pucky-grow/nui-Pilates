package com.yoyi.pilates.infra.delivery;
import lombok.RequiredArgsConstructor;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.bind.annotation.*;
import com.yoyi.pilates.system.security.*;import com.yoyi.pilates.common.annotation.OpLog;import com.yoyi.pilates.common.result.*;
@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/delivery")
public class DeliveryController {
 private final JdbcTemplate jdbc;
 @PostMapping("/outbox/{id}/retry") @RequiresPerm("system:manage") @OpLog(module="delivery",action="retry",targetId="#id",detail="'人工重试已失败的站内事件'")
 public Result<Integer> retry(@PathVariable Long id){if(!SecurityUtils.requireUser().isAdminRole())throw com.yoyi.pilates.common.exception.BusinessException.of(ResultCode.FORBIDDEN);return Result.ok(jdbc.update("UPDATE event_outbox SET state='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND state='FAILED'",id));}
}
