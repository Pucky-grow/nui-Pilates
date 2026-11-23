package com.yoyi.pilates.common.aspect;

import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.util.IpUtils;
import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.system.infrastructure.entity.OperationLogEntity;
import com.yoyi.pilates.system.infrastructure.mapper.OperationLogMapper;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * {@link OpLog} 切面: 方法执行成功后异步落库, 操作失败也记录(标注异常信息).
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OpLogAspect {

    private final OperationLogMapper operationLogMapper;
    private final SpelKeyResolver spelKeyResolver;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Only business fields from a fixed allowlist; never passwords, openid or tokens. */
    private String snapshot(ProceedingJoinPoint pjp,OpLog annotation) {
        try {
            String table=null,columns=null;
            switch(annotation.module()) {
                case "course" -> {
                    if(annotation.action().equals("classroom_update")){table="classroom";columns="name,type,capacity,equipment,status";}
                    else if(annotation.action().equals("category_update")){table="course_category";columns="name,status,sort";}
                    else if(java.util.Set.of("update","shelf","delete").contains(annotation.action())){table="course";columns="name,type,duration_minutes,status,category_id,description";}
                }
                case "coach" -> {if(java.util.Set.of("update","delete").contains(annotation.action())){table="coach";columns="name,title,status,years";}}
                case "schedule" -> {if(java.util.Set.of("update","cancel").contains(annotation.action())){table="course_schedule";columns="course_id,coach_id,classroom_id,course_date,start_time,end_time,max_capacity,booked_count,status";}}
                case "booking" -> {if(java.util.Set.of("admin_cancel","admin_move","admin_checkin").contains(annotation.action())){table="booking";columns="id,user_id,schedule_id,status,cancel_reason";}}
                case "config" -> {if(annotation.action().equals("update_booking_rules"))return jdbc.queryForList("SELECT config_key,config_value FROM system_config WHERE deleted=0 AND config_key IN ('booking.advance_days','booking.deadline_minutes','booking.max_per_day','booking.cancel_free_hours','booking.cancel_late_deduct') ORDER BY config_key").toString();}
            }
            if(table==null||annotation.targetId().isBlank())return "";
            String id=spelKeyResolver.resolve(pjp,annotation.targetId());
            return jdbc.queryForList("SELECT "+columns+" FROM "+table+" WHERE id=? AND deleted=0",Long.valueOf(id)).toString();
        } catch(Exception ignored){return "";}
    }
    private String bounded(String value,int limit){return value!=null&&value.length()>limit?value.substring(0,limit):value;}

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OpLog opLog) throws Throwable {
        String before=snapshot(pjp,opLog);
        try {
            Object result = pjp.proceed();
            saveLog(pjp, opLog, "SUCCESS", null,before,snapshot(pjp,opLog));
            return result;
        } catch (Throwable t) {
            saveLog(pjp, opLog, "FAILED", t.getMessage(),before,"");
            throw t;
        }
    }

    private void saveLog(ProceedingJoinPoint pjp, OpLog opLog, String outcome, String error,String before,String after) {
        try {
            OperationLogEntity entity = new OperationLogEntity();
            SecurityUtils.current().ifPresentOrElse(u -> {
                entity.setOperatorId(u.getId());
                entity.setOperatorName(u.getType() + ":" + (u.getName() == null ? u.getId() : u.getName()));
            }, () -> {
                entity.setOperatorName("anonymous");
            });
            entity.setModule(opLog.module());
            entity.setAction(opLog.action());
            if (!opLog.targetId().isBlank()) {
                entity.setTargetId(spelKeyResolver.resolve(pjp, opLog.targetId()));
            }
            String detail = opLog.detail().isBlank()
                    ? abbreviate(Arrays.toString(pjp.getArgs()))
                    : spelKeyResolver.resolve(pjp, opLog.detail());
            entity.setDetail("["+outcome+"] "+bounded(detail,600)+(before.isEmpty()?"":" | before="+bounded(before,550))
                +(after.isEmpty()?"":" | after="+bounded(after,550))+(error!=null?" | error="+bounded(error,200):""));
            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setIp(IpUtils.getClientIp(request));
            }
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            // 日志失败绝不影响主流程
            log.error("操作日志写入失败", e);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String abbreviate(String s) {
        return s != null && s.length() > 1800 ? s.substring(0, 1800) : s;
    }
}
