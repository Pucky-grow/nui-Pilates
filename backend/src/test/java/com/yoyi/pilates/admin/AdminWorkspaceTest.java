package com.yoyi.pilates.admin;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.booking.application.StockService;
import com.yoyi.pilates.member.infrastructure.entity.MemberEntity;
import com.yoyi.pilates.member.infrastructure.mapper.MemberMapper;
import com.yoyi.pilates.system.infrastructure.entity.*;
import com.yoyi.pilates.system.infrastructure.mapper.*;
import com.yoyi.pilates.system.security.JwtService;
import com.yoyi.pilates.course.application.CourseAdminService;
import com.yoyi.pilates.course.interfaces.dto.*;
import com.yoyi.pilates.common.exception.BusinessException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, production services and an exclusively owned MySQL/Redis via accept.py. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminWorkspaceTest extends BookingFixture {
    @LocalServerPort int port;
    @Autowired SysUserMapper accounts; @Autowired SysRoleMapper roles; @Autowired SysUserRoleMapper links;
    @Autowired SysPermissionMapper permissions; @Autowired SysRolePermissionMapper grants;
    @Autowired PasswordEncoder encoder; @Autowired ObjectMapper json; @Autowired JwtService jwt;
    @Autowired MemberMapper members; @Autowired StockService stock; @Autowired CourseAdminService resources;
    final HttpClient client=HttpClient.newHttpClient();
    JsonNode call(String method,String path,String token,Object body) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(token!=null)req.header("Authorization","Bearer "+token);
        req.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=client.send(req.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());return json.readTree(response.body());
    }
    JsonNode ok(String method,String path,String token,Object body)throws Exception {var r=call(method,path,token,body);assertEquals(0,r.path("code").asInt(),()->r.path("message").asText());return r.path("data");}
    String account(String role,String... codes)throws Exception {
        var a=new SysUserEntity();a.setUsername("a_"+UUID.randomUUID().toString().substring(0,16));String password=UUID.randomUUID().toString();a.setPassword(encoder.encode(password));a.setName("隔离测试"+role);a.setStatus("ACTIVE");accounts.insert(a);
        var existing=roles.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRoleEntity>().eq(SysRoleEntity::getCode,role));
        SysRoleEntity r;if(existing.isEmpty()){r=new SysRoleEntity();r.setCode(role);r.setName(role);r.setStatus("ACTIVE");roles.insert(r);}else r=existing.get(0);
        var link=new SysUserRoleEntity();link.setUserId(a.getId());link.setRoleId(r.getId());links.insert(link);
        for(var code:codes){var ps=permissions.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysPermissionEntity>().eq(SysPermissionEntity::getCode,code));SysPermissionEntity p;
            if(ps.isEmpty()){p=new SysPermissionEntity();p.setCode(code);p.setName(code);p.setType("API");permissions.insert(p);}else p=ps.get(0);
            var g=new SysRolePermissionEntity();g.setRoleId(r.getId());g.setPermissionId(p.getId());grants.insert(g);}
        return ok("POST","/api/auth/admin-login",null,Map.of("username",a.getUsername(),"password",password)).path("token").asText();
    }
    MemberEntity member(Long uid,String number){var m=new MemberEntity();m.setUserId(uid);m.setMemberNo(number);m.setLevelId(1L);m.setStatus("ACTIVE");m.setTotalSessions(0);m.setTotalConsumption(new java.math.BigDecimal("200"));members.insert(m);return m;}
    String memberToken(Long uid){var t=jwt.createToken(uid,"MP_USER");redis.opsForValue().set(RedisKeys.loginToken(t),uid.toString());return t;}
    long bookHttp(String admin,Long uid,Long sid)throws Exception{return ok("POST","/api/admin/bookings",admin,Map.of("userId",uid,"scheduleId",sid,"reason","隔离验收")).asLong();}
    String movePath(long id){return "/api/admin/bookings/"+id+"/move";}
    void inventory(Long sid,int expected){assertEquals(expected,schedules.selectById(sid).getBookedCount());assertEquals(expected,bookings.bookingsOfSchedule(sid).size());assertEquals(schedules.selectById(sid).getMaxCapacity()-expected,Integer.parseInt(redis.opsForValue().get(RedisKeys.stock(sid))));}

    @Test void fullManagementHttpFlowPreservesHistoryAndFreeMode() throws Exception {
        String admin=account("ADMIN");member(userId,"M-accept");
        var profile=new HashMap<String,Object>();profile.put("nickname","测试会员更新");profile.put("gender",1);profile.put("validUntil","2030-01-01");profile.put("reason","信息更正");
        ok("PUT","/api/admin/members/"+userId,admin,profile);
        assertEquals("测试会员更新",ok("GET","/api/admin/members/"+userId,admin,null).path("user").path("nickname").asText());
        long old=bookHttp(admin,userId,scheduleId);Long target=newSchedule(8,1,12);
        long next=ok("POST",movePath(old),admin,Map.of("scheduleId",target,"reason","调整时间")).asLong();assertNotEquals(old,next);
        assertEquals("CANCELLED",bookingMapper.selectById(old).getStatus());assertEquals("BOOKED",bookingMapper.selectById(next).getStatus());inventory(scheduleId,0);inventory(target,1);
        ok("POST","/api/admin/bookings/"+old+"/cancel",admin,Map.of("reason","旧请求重试"));assertEquals("BOOKED",bookingMapper.selectById(next).getStatus());
        ok("POST","/api/admin/bookings/"+next+"/cancel",admin,Map.of("reason","会员申请"));
        long again=bookHttp(admin,userId,target);assertNotEquals(next,again);assertEquals(0,bookingMapper.selectById(again).getCreditsUsed());inventory(target,1);
        var jdbc=new JdbcTemplate(dataSource);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM `order`",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM package_credit_log",Integer.class));
        assertEquals(3,ok("GET","/api/admin/bookings?userId="+userId,admin,null).path("total").asInt());
        String keyword=java.net.URLEncoder.encode("测试课",java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(3,ok("GET","/api/admin/bookings?keyword="+keyword,admin,null).path("total").asInt());
    }
    @Test void fullTargetAndDailyLimitRollbackLeaveOriginalBookingAndStock() throws Exception {
        String admin=account("ADMIN");Long target=newSchedule(1,2,12);long old=bookHttp(admin,userId,scheduleId);bookHttp(admin,newUser(),target);
        assertNotEquals(0,call("POST",movePath(old),admin,Map.of("scheduleId",target,"reason","满班调整")).path("code").asInt());
        assertEquals("BOOKED",bookingMapper.selectById(old).getStatus());inventory(scheduleId,1);inventory(target,1);
        Long free=newSchedule(8,2,14);Long occupied=newSchedule(8,2,16);config.set("booking.max_per_day","1");bookHttp(admin,userId,occupied);
        assertNotEquals(0,call("POST",movePath(old),admin,Map.of("scheduleId",free,"reason","当日限额")).path("code").asInt());
        assertEquals("BOOKED",bookingMapper.selectById(old).getStatus());inventory(scheduleId,1);assertEquals(0,schedules.selectById(free).getBookedCount());
    }
    @Test void concurrentMovesOfSameOldIdHaveOneEffect()throws Exception {
        String admin=account("ADMIN");long old=bookHttp(admin,userId,scheduleId);Long a=newSchedule(8,1,12),b=newSchedule(8,1,14);
        var gate=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var jobs=new ArrayList<Future<Integer>>();for(Long target:List.of(a,b))jobs.add(pool.submit(()->{gate.await();return call("POST",movePath(old),admin,Map.of("scheduleId",target,"reason","并发调课")).path("code").asInt();}));
            assertEquals(1,jobs.stream().filter(f->{try{return f.get()==0;}catch(Exception e){throw new RuntimeException(e);}}).count());
        }finally{pool.shutdownNow();}
        assertEquals(1,bookings.myBookings(userId,null).stream().filter(r->"BOOKED".equals(r.getStatus())).count());inventory(scheduleId,0);
        assertEquals(1,schedules.selectById(a).getBookedCount()+schedules.selectById(b).getBookedCount());
    }
    @Test void allNonAdminRolesAndMemberAreRejectedByMutationRulesAndLogs()throws Exception {
        long old=bookings.book(userId,scheduleId,null,null);
        for(var role:List.of("STORE_MANAGER","STAFF","COACH")) {
            String token=account(role,"member:view","schedule:view");
            assertEquals(40300,call("POST","/api/admin/bookings",token,Map.of("userId",userId,"scheduleId",scheduleId,"reason","权限验证")).path("code").asInt());
            for(String action:List.of("cancel","check-in"))assertEquals(40300,call("POST","/api/admin/bookings/"+old+"/"+action,token,Map.of("reason","权限验证")).path("code").asInt());
            assertEquals(40300,call("GET","/api/admin/workspace/rules",token,null).path("code").asInt());assertEquals(40300,call("GET","/api/admin/workspace/logs",token,null).path("code").asInt());
        }
        assertEquals(40300,call("GET","/api/admin/bookings",memberToken(userId),null).path("code").asInt());
        assertEquals("BOOKED",bookingMapper.selectById(old).getStatus());
    }
    @Test void keywordIsFilteredBeforePaginationAndSensitiveIdentityIsNotReturned()throws Exception {
        String admin=account("ADMIN");member(userId,"FIND-early");for(int i=0;i<25;i++)member(newUser(),"OTHER-"+i);
        var d=ok("GET","/api/admin/members?keyword=FIND-early&page=1&size=10",admin,null);
        assertEquals(1,d.path("total").asInt());assertEquals(userId.longValue(),d.path("list").get(0).path("member").path("userId").asLong());
        var detail=ok("GET","/api/admin/members/"+userId,admin,null);assertFalse(detail.path("user").has("openid"));assertFalse(detail.path("user").has("unionid"));
        assertEquals(0,ok("GET","/api/admin/members?keyword=absent",admin,null).path("total").asInt());
        String staff=account("STAFF","member:view");assertFalse(ok("GET","/api/admin/members/"+userId,staff,null).path("member").has("totalConsumption"));
    }
    @Test void memberValidationIsAtomicAndDisableRevokesExistingLogin()throws Exception {
        String admin=account("ADMIN");member(userId,"M-state");String token=memberToken(userId);
        assertNotEquals(0,call("PUT","/api/admin/members/"+userId,admin,Map.of("nickname","不应生效","status","invalid")).path("code").asInt());assertEquals("会员",users.selectById(userId).getNickname());
        assertNotEquals(0,call("PUT","/api/admin/members/"+userId,admin,Map.of("openid","forged")).path("code").asInt());
        ok("PUT","/api/admin/members/"+userId,admin,Map.of("status","DISABLED","reason","停用验收"));
        assertEquals(40100,call("GET","/api/auth/me",token,null).path("code").asInt());
        assertNotEquals(0,call("POST","/api/admin/bookings",admin,Map.of("userId",userId,"scheduleId",scheduleId,"reason","停用账号")).path("code").asInt());
        ok("PUT","/api/admin/members/"+userId,admin,Map.of("status","ACTIVE","validUntil","","reason","恢复验收"));
        assertEquals("ACTIVE",users.selectById(userId).getStatus());assertNull(members.selectById(1L).getValidUntil());
        var audit=ok("GET","/api/admin/workspace/logs?module=member",admin,null);assertTrue(audit.path("total").asInt()>=2);
        assertTrue(audit.path("list").toString().contains("before="));
    }
    @Test void rulesAreWhitelistedStrictAndInvalidateCachedValues()throws Exception {
        String admin=account("ADMIN");config.getInt("booking.max_per_day",3);
        ok("PUT","/api/admin/workspace/rules",admin,Map.of("booking.max_per_day",2,"booking.advance_days",5));
        assertEquals(2,config.getInt("booking.max_per_day",3));assertEquals("FREE",config.get("booking.paymentMode","FREE"));
        for(Object body:List.of(Map.of("booking.paymentMode",0),Map.of("booking.max_per_day",0),Map.of("booking.max_per_day",1.5),Map.of("booking.max_per_day",true)))
            assertNotEquals(0,call("PUT","/api/admin/workspace/rules",admin,body).path("code").asInt());
        assertEquals(2,config.getInt("booking.max_per_day",3));assertEquals("Asia/Shanghai",ok("GET","/api/admin/workspace/rules",admin,null).path("timezone").asText());
    }
    @Test void resourceEditsCannotInvalidateUpcomingSchedulesAndHistoricalDeletesAreRejected()throws Exception {
        String admin=account("ADMIN");var room=Map.of("name","教室","type","GROUP","capacity",7,"status","ACTIVE");
        assertNotEquals(0,call("PUT","/api/admin/classrooms/"+roomId,admin,room).path("code").asInt());assertEquals(20,rooms.selectById(roomId).getCapacity());
        assertNotEquals(0,call("DELETE","/api/admin/courses/"+courseId,admin,null).path("code").asInt());
        assertNotEquals(0,call("DELETE","/api/admin/coaches/"+coachId,admin,null).path("code").asInt());
        assertNotEquals(0,call("PUT","/api/admin/coaches/"+coachId,admin,Map.of("name","教练","status","DISABLED")).path("code").asInt());
        assertEquals("ACTIVE",coaches.selectById(coachId).getStatus());
        assertEquals(scheduleId.longValue(),ok("GET","/api/admin/schedules/"+scheduleId,admin,null).path("id").asLong());
    }
    @Test void adminCheckinRemainsAtomicIdempotentAndPreventsCancellation()throws Exception {
        String admin=account("ADMIN");long id=bookHttp(admin,userId,scheduleId);openCheckin();
        for(int i=0;i<2;i++)ok("POST","/api/admin/bookings/"+id+"/check-in",admin,Map.of("reason","现场签到"));
        assertEquals(1,attendanceMapper.selectCount(null));assertEquals("SIGNED_IN",bookingMapper.selectById(id).getStatus());
        assertNotEquals(0,call("POST","/api/admin/schedules/"+scheduleId+"/cancel?reason=check",admin,Map.of()).path("code").asInt());
        assertNotEquals(0,call("POST",movePath(id),admin,Map.of("scheduleId",newSchedule(8,2,12),"reason","已签到调整")).path("code").asInt());
    }
    @Test void requiredReasonAndRevokedPermissionFailWithoutWrites()throws Exception {
        String admin=account("ADMIN");assertNotEquals(0,call("POST","/api/admin/bookings",admin,Map.of("userId",userId,"scheduleId",scheduleId,"reason"," ")).path("code").asInt());
        assertEquals(0,bookingMapper.selectCount(null));
        String manager=account("STORE_MANAGER","member:view");member(userId,"M-revoke");ok("GET","/api/admin/members",manager,null);
        new JdbcTemplate(dataSource).update("UPDATE sys_role_permission SET deleted=1 WHERE role_id IN (SELECT id FROM sys_role WHERE code='STORE_MANAGER')");
        assertEquals(40300,call("GET","/api/admin/members",manager,null).path("code").asInt());
    }
    @Test void createCourseCoachQualificationRoomAndScheduleThenManageCapacityAndCancel()throws Exception {
        String admin=account("ADMIN");
        long coach=ok("POST","/api/admin/coaches",admin,Map.of("name","新增隔离教练","status","ACTIVE","years",2)).asLong();
        long room=ok("POST","/api/admin/classrooms",admin,Map.of("name","新增隔离教室","type","GROUP","capacity",4,"status","ACTIVE")).asLong();
        var body=new HashMap<String,Object>();body.put("categoryId",categories.selectList(null).get(0).getId());body.put("name","新增隔离小班");body.put("type","GROUP");body.put("difficulty","BEGINNER");
        body.put("durationMinutes",60);body.put("creditsCost",1);body.put("price",0);body.put("notices","注意事项");body.put("targetUsers","测试人群");body.put("trainingFocus","测试目标");body.put("status","ON_SHELF");
        long course=ok("POST","/api/admin/courses",admin,body).asLong();
        body.put("description","更新介绍");ok("PUT","/api/admin/courses/"+course,admin,body);
        assertEquals("注意事项",ok("GET","/api/admin/courses/"+course,admin,null).path("notices").asText());
        ok("PUT","/api/admin/courses/"+course+"/coaches/"+coach,admin,Map.of());
        var request=new HashMap<String,Object>();request.put("courseId",course);request.put("coachId",coach);request.put("classroomId",room);request.put("courseDate",com.yoyi.pilates.common.util.StudioTime.today().plusDays(2).toString());request.put("startTime","14:00");request.put("endTime","15:00");request.put("maxCapacity",3);
        long sid=ok("POST","/api/admin/schedules",admin,request).asLong();
        for(int i=0;i<3;i++)bookHttp(admin,newUser(),sid);inventory(sid,3);
        request.put("maxCapacity",2);assertNotEquals(0,call("PUT","/api/admin/schedules/"+sid,admin,request).path("code").asInt());inventory(sid,3);
        request.put("maxCapacity",4);ok("PUT","/api/admin/schedules/"+sid,admin,request);bookHttp(admin,newUser(),sid);inventory(sid,4);
        assertEquals(4,ok("GET","/api/admin/bookings?scheduleId="+sid,admin,null).path("total").asInt());
        var log=ok("GET","/api/admin/workspace/logs?module=schedule",admin,null).path("list").toString();assertTrue(log.contains("before="));assertTrue(log.contains("after="));
        ok("POST","/api/admin/schedules/"+sid+"/cancel?reason=isolated-test",admin,Map.of());
        assertEquals("CANCELLED",schedules.selectById(sid).getStatus());assertEquals(0,schedules.selectById(sid).getBookedCount());assertNull(redis.opsForValue().get(RedisKeys.stock(sid)));
        assertEquals(4,ok("GET","/api/admin/bookings?scheduleId="+sid+"&status=CANCELLED",admin,null).path("total").asInt());
        ok("PUT","/api/admin/coaches/"+coach,admin,Map.of("name","新增隔离教练","status","DISABLED"));
        ok("PUT","/api/admin/classrooms/"+room,admin,Map.of("name","新增隔离教室","type","GROUP","capacity",4,"status","DISABLED"));
        var summary=ok("GET","/api/admin/workspace/summary",admin,null);assertTrue(summary.has("todaySchedules"));
    }
    @Test void memberPhoneAndInvalidDatesAreValidatedAndAuditMasksPhone()throws Exception {
        String admin=account("ADMIN");member(userId,"M-phone");
        ok("PUT","/api/admin/members/"+userId,admin,Map.of("phone","13900000000","gender",2,"avatarUrl","/uploads/test.png","reason","更正测试资料"));
        assertEquals("13900000000",users.selectById(userId).getPhone());
        assertEquals(40000,call("PUT","/api/admin/members/"+userId,admin,Map.of("validUntil","invalid-date")).path("code").asInt());
        assertEquals(40000,call("PUT","/api/admin/members/"+userId,admin,Map.of("gender","bad")).path("code").asInt());
        String logs=ok("GET","/api/admin/workspace/logs?module=member",admin,null).toString();assertFalse(logs.contains("13900000000"));assertTrue(logs.contains("before="));
    }

    @Test void invalidResourceFieldsAreRejectedBeforePersisting()throws Exception {
        String admin=account("ADMIN");
        assertEquals(40000,call("POST","/api/admin/classrooms",admin,Map.of("name","测试","type","invalid","capacity",8)).path("code").asInt());
        assertEquals(40000,call("POST","/api/admin/classrooms",admin,Map.of("name","测试","type","GROUP","capacity",8,"equipment","x".repeat(513))).path("code").asInt());
        assertEquals(40000,call("POST","/api/admin/course-categories",admin,Map.of("code","","name","测试分类")).path("code").asInt());
        assertEquals(40000,call("POST","/api/admin/coaches",admin,Map.of("name","测试教练","years",-1)).path("code").asInt());
        assertEquals(1,rooms.selectCount(null));assertEquals(1,coaches.selectCount(null));
    }

}
