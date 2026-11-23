package com.yoyi.pilates.system.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.*;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.system.application.SystemConfigService;
import com.yoyi.pilates.system.infrastructure.entity.*;
import com.yoyi.pilates.system.infrastructure.mapper.*;
import com.yoyi.pilates.system.security.*;
import com.yoyi.pilates.booking.infrastructure.entity.*;
import com.yoyi.pilates.booking.infrastructure.mapper.*;
import com.yoyi.pilates.course.infrastructure.entity.*;
import com.yoyi.pilates.course.infrastructure.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;

@RestController
@RequestMapping("/api/admin/workspace")
@RequiredArgsConstructor
public class AdminWorkspaceController {
    private final CourseScheduleMapper schedules;
    private final BookingMapper bookings;
    private final CourseProposalMapper proposals;
    private final OperationLogMapper logs;
    private final SystemConfigMapper configs;
    private final SystemConfigService config;
    private final PlatformTransactionManager manager;
    private static final Map<String,int[]> RULES=Map.of(
        "booking.advance_days",new int[]{0,365},"booking.deadline_minutes",new int[]{0,1440},
        "booking.max_per_day",new int[]{1,50},"booking.cancel_free_hours",new int[]{0,720},
        "booking.cancel_late_deduct",new int[]{0,100});

    @GetMapping("/summary") @RequiresPerm("dashboard:view")
    public Result<Map<String,Object>> summary(){
        var actor=SecurityUtils.requireUser();
        if(!actor.isAdminRole()&&!actor.getRoles().contains("STORE_MANAGER")) throw BusinessException.of(ResultCode.FORBIDDEN);
        var today=schedules.selectList(new LambdaQueryWrapper<CourseScheduleEntity>().eq(CourseScheduleEntity::getCourseDate,StudioTime.today()));
        var ids=today.stream().map(CourseScheduleEntity::getId).toList();
        long occupied=0,signed=0;
        if(!ids.isEmpty()){
            occupied=bookings.selectCount(new LambdaQueryWrapper<BookingEntity>().in(BookingEntity::getScheduleId,ids).in(BookingEntity::getStatus,List.of("BOOKED","SIGNED_IN","COMPLETED","NO_SHOW")));
            signed=bookings.selectCount(new LambdaQueryWrapper<BookingEntity>().in(BookingEntity::getScheduleId,ids).in(BookingEntity::getStatus,List.of("SIGNED_IN","COMPLETED")));
        }
        return Result.ok(Map.of("date",StudioTime.today(),"todaySchedules",today.stream().filter(s->!"CANCELLED".equals(s.getStatus())).count(),
            "occupied",occupied,"signed",signed,"pendingProposals",actor.hasPerm("manager:course:proposal:view")?
            proposals.selectCount(new LambdaQueryWrapper<CourseProposalEntity>().eq(CourseProposalEntity::getStatus,"PENDING_REVIEW")):0));
    }
    @GetMapping("/rules") @RequiresPerm("system:manage")
    public Result<Map<String,Object>> rules(){
        var values=new TreeMap<String,Object>();
        for(var key:RULES.keySet()) values.put(key,config.getInt(key,switch(key){case "booking.advance_days"->7;case "booking.deadline_minutes"->30;case "booking.max_per_day"->3;case "booking.cancel_free_hours"->4;default->1;}));
        return Result.ok(Map.of("values",values,"bookingMode",config.get("booking.paymentMode","FREE"),"timezone","Asia/Shanghai","checkinWindow","开课前30分钟至课程结束前"));
    }
    @PutMapping("/rules") @RequiresPerm("system:manage")
    @OpLog(module="config",action="update_booking_rules")
    public Result<Void> saveRules(@RequestBody Map<String,Object> input){
        Map<String,Integer> values=new LinkedHashMap<>();
        for(var entry:input.entrySet()) {
            String text=String.valueOf(entry.getValue());
            if(!text.matches("[0-9]{1,6}"))throw BusinessException.of(ResultCode.BAD_REQUEST,"规则必须是非负整数");
            values.put(entry.getKey(),Integer.valueOf(text));
        }
        if(values.isEmpty()||!RULES.keySet().containsAll(values.keySet())) throw BusinessException.of(ResultCode.BAD_REQUEST,"包含不支持的规则");
        values.forEach((k,v)->{var range=RULES.get(k);if(v==null||v<range[0]||v>range[1])throw BusinessException.of(ResultCode.BAD_REQUEST,"规则数值超出范围："+k);});
        new TransactionTemplate(manager).execute(s->{
            values.forEach((key,value)->{
                var e=configs.selectOne(new LambdaQueryWrapper<SystemConfigEntity>().eq(SystemConfigEntity::getConfigKey,key));
                if(e==null){e=new SystemConfigEntity();e.setConfigKey(key);e.setConfigValue(value.toString());configs.insert(e);}
                else{e.setConfigValue(value.toString());if(configs.updateById(e)!=1)throw BusinessException.of(ResultCode.CONFLICT);}
            });
            return null;
        });
        values.keySet().forEach(key -> {
            try {
                config.evict(key);
            } catch (RuntimeException ignored) {
                // The database is committed and the cache has a short TTL.
            }
        });
        return Result.ok();
    }
    @GetMapping("/logs") @RequiresPerm("system:manage")
    public Result<PageResult<OperationLogEntity>> logs(@RequestParam(required=false) String module,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size){
        if(page<1||size<1||size>100)throw BusinessException.of(ResultCode.BAD_REQUEST);
        return Result.ok(PageResult.of(logs.selectPage(Page.of(page,size),new LambdaQueryWrapper<OperationLogEntity>()
            .eq(module!=null&&!module.isBlank(),OperationLogEntity::getModule,module).orderByDesc(OperationLogEntity::getId)),e->e));
    }
}
