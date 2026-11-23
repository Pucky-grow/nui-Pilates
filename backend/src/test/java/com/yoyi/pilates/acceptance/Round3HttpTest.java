package com.yoyi.pilates.acceptance;

import com.yoyi.pilates.PilatesApplication;
import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.booking.application.StockService;
import com.yoyi.pilates.system.infrastructure.entity.*;
import com.yoyi.pilates.system.infrastructure.mapper.*;
import com.yoyi.pilates.system.security.JwtService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two independent Spring HTTP instances / connection pools / Redisson clients, one owned MySQL and Redis. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="PILATES_TEST_MYSQL_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Round3HttpTest extends BookingFixture {
    @LocalServerPort int port;
    @Autowired SysUserMapper accounts; @Autowired SysRoleMapper roles;
    @Autowired SysUserRoleMapper accountRoles; @Autowired SysPermissionMapper permissions;
    @Autowired SysRolePermissionMapper mappings; @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt; @Autowired ObjectMapper json; @Autowired StockService stock;
    static ServletWebServerApplicationContext second;
    static int freePort(){try(var s=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())){return s.getLocalPort();}catch(java.io.IOException e){throw new RuntimeException(e);}}
    static final int VITE_PORT=freePort(), HTTPS_PORT=freePort();
    @org.springframework.test.context.DynamicPropertySource
    static void origins(org.springframework.test.context.DynamicPropertyRegistry values){values.add("yoyi.cors.allowed-origins",()->"http://127.0.0.1:"+VITE_PORT+",https://127.0.0.1:"+HTTPS_PORT);}

    static final HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    @BeforeAll static void secondInstance(){second=start();}
    static ServletWebServerApplicationContext start(){return (ServletWebServerApplicationContext)new SpringApplicationBuilder(PilatesApplication.class).run("--spring.profiles.active=junit","--server.port=0","--spring.sql.init.mode=never","--spring.main.banner-mode=off");}
    @AfterAll static void stop(){if(second!=null)second.close();}
    int secondPort(){return second.getWebServer().getPort();}
    JsonNode call(int target,String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+target+path)).timeout(java.time.Duration.ofSeconds(60));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());return json.readTree(response.body());
    }
    String member(Long id){String token=jwt.createToken(id,"MP_USER");redis.opsForValue().set(RedisKeys.loginToken(token),id.toString());return token;}
    record Account(String username,String password,String token,Long roleId,Long userId) {}
    Account account(String role) throws Exception {
        String username="round3_"+role.toLowerCase(),password=UUID.randomUUID().toString();
        var u=new SysUserEntity();u.setUsername(username);u.setPassword(encoder.encode(password));u.setName("隔离"+role);u.setStatus("ACTIVE");accounts.insert(u);
        var r=new SysRoleEntity();r.setCode(role);r.setName(role);r.setStatus("ACTIVE");roles.insert(r);var ur=new SysUserRoleEntity();ur.setUserId(u.getId());ur.setRoleId(r.getId());accountRoles.insert(ur);
        var codes=new ArrayList<>(List.of("dashboard:view"));
        if(role.equals("STORE_MANAGER"))codes.addAll(List.of("statistics:view","order:view","schedule:view","schedule:create","schedule:update","schedule:cancel","manager:course:coach:manage"));
        if(role.equals("STAFF"))codes.addAll(List.of("schedule:view","order:view"));
        if(role.equals("COACH")){codes.addAll(List.of("coach:schedule:view","coach:schedule:create","coach:schedule:update","coach:schedule:cancel"));var c=coaches.selectById(coachId);c.setSysUserId(u.getId());coaches.updateById(c);}
        for(String code:codes){var matches=permissions.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysPermissionEntity>().eq(SysPermissionEntity::getCode,code));SysPermissionEntity p;
            if(matches.isEmpty()){p=new SysPermissionEntity();p.setCode(code);p.setName(code);p.setType("API");permissions.insert(p);}else p=matches.get(0);
            var link=new SysRolePermissionEntity();link.setRoleId(r.getId());link.setPermissionId(p.getId());mappings.insert(link);
        }
        var login=call(port,"POST","/api/auth/admin-login",null,Map.of("username",username,"password",password));assertEquals(0,login.path("code").asInt());return new Account(username,password,login.path("data").path("token").asText(),r.getId(),u.getId());
    }
    Map<String,Object> schedule(int hour,int capacity){var s=schedules.selectById(scheduleId);return Map.of("courseId",courseId,"coachId",coachId,"classroomId",roomId,"courseDate",s.getCourseDate().toString(),"startTime",String.format("%02d:00:00",hour),"endTime",String.format("%02d:00:00",hour+1),"maxCapacity",capacity);}
    @Test @Order(1) void actualLoginFourRolesAndRevenueBoundary() throws Exception {
        new JdbcTemplate(dataSource).update("INSERT INTO `order`(order_no,user_id,order_type,total_amount,pay_amount,status) VALUES('round3-history-order',?,'PACKAGE',123,123,'PAID')",userId);
        for(String role:List.of("ADMIN","STORE_MANAGER","STAFF","COACH")){
            var actor=account(role);var stats=call(secondPort(),"GET","/api/admin/statistics/dashboard",actor.token(),null);assertEquals(0,stats.path("code").asInt());
            boolean finance=role.equals("ADMIN")||role.equals("STORE_MANAGER");assertEquals(finance,stats.path("data").has("todayRevenue"));assertEquals(finance,stats.path("data").has("monthRevenue"));
            if(!role.equals("COACH")){var orders=call(secondPort(),"GET","/api/admin/orders",actor.token(),null);assertEquals(0,orders.path("code").asInt());assertEquals(finance,orders.path("data").path("list").get(0).has("payAmount"));var detail=call(port,"GET","/api/admin/orders/1",actor.token(),null);assertEquals(finance,detail.path("data").path("order").has("totalAmount"));}
            var write=call(port,"POST","/api/admin/schedules",actor.token(),schedule(12+(finance?List.of("ADMIN","STORE_MANAGER").indexOf(role)*2:0),8));assertEquals(finance?0:40300,write.path("code").asInt());
            assertEquals(40300,call(secondPort(),"GET","/api/my/bookings",actor.token(),null).path("code").asInt());
            if(!finance)assertEquals(40300,call(secondPort(),"GET","/api/admin/statistics/revenue-trend",actor.token(),null).path("code").asInt());
        }
    }
    @Test @Order(2) void revokedPermissionAndDisabledRoleRejectOnBothInstancesWithoutCacheEviction() throws Exception {
        var actor=account("STORE_MANAGER");assertEquals(0,call(port,"GET","/api/admin/schedules/options",actor.token(),null).path("code").asInt());
        // Even pre-Round3 stale Redis grants cannot re-authorize the next request.
        redis.opsForValue().set("pilates:rbac:perms:"+actor.userId(),"schedule:create,schedule:view");redis.opsForValue().set("pilates:rbac:roles:"+actor.userId(),"STORE_MANAGER");
        var jdbc=new JdbcTemplate(dataSource);jdbc.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id IN (SELECT id FROM sys_permission WHERE code='schedule:create')",actor.roleId());
        for(int p:List.of(port,secondPort()))assertEquals(40300,call(p,"POST","/api/admin/schedules",actor.token(),schedule(12,8)).path("code").asInt());
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE id=?",actor.roleId());
        for(int p:List.of(port,secondPort()))assertEquals(40300,call(p,"GET","/api/admin/schedules/options",actor.token(),null).path("code").asInt());
    }
    @Test @Order(3) void hundredRealHttpMembersAcrossTwoInstancesCannotExceedEight() throws Exception {
        var work=new ArrayList<Runnable>();for(int i=0;i<100;i++){String t=member(newUser());int p=i%2==0?port:secondPort();work.add(()->{try{var r=call(p,"POST","/api/bookings",t,Map.of("scheduleId",scheduleId));if(r.path("code").asInt()!=0)throw new com.yoyi.pilates.common.exception.BusinessException(r.path("code").asInt(),r.path("message").asText());}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}});}
        assertEquals(8,parallel(work));assertEquals(8,schedules.selectById(scheduleId).getBookedCount());assertEquals(8,bookingMapper.selectCount(null));assertEquals("0",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
    }
    @Test @Order(4) void sameMemberRebooksAndDailyLimitAcrossInstances() throws Exception {
        String t=member(userId);Long old=call(port,"POST","/api/bookings",t,Map.of("scheduleId",scheduleId)).path("data").path("bookingId").asLong();assertEquals(0,call(secondPort(),"DELETE","/api/bookings/"+old,t,null).path("code").asInt());
        var work=new ArrayList<Runnable>();for(int i=0;i<8;i++){int p=i%2==0?port:secondPort();work.add(()->httpBook(p,t,scheduleId));}assertEquals(1,parallel(work));
        assertEquals(0,call(port,"DELETE","/api/bookings/"+old,t,null).path("code").asInt());assertEquals(1,schedules.selectById(scheduleId).getBookedCount());
        work.clear();for(int i=0;i<6;i++){Long id=newSchedule(8,1,12+i);int p=i%2==0?port:secondPort();work.add(()->httpBook(p,t,id));}assertEquals(2,parallel(work));
        assertEquals(3,new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM booking WHERE user_id=? AND deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')",Integer.class,userId));
    }
    void httpBook(int p,String t,Long id){try{var r=call(p,"POST","/api/bookings",t,Map.of("scheduleId",id));if(r.path("code").asInt()!=0)throw new com.yoyi.pilates.common.exception.BusinessException(r.path("code").asInt(),r.path("message").asText());}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}}
    @Test @Order(5) void twoInstanceShrinkAndCancellationRaces() throws Exception {
        var manager=account("STORE_MANAGER");for(int i=0;i<6;i++)bookings.book(newUser(),scheduleId,null,null);String member=member(newUser());
        assertEquals(40300,call(port,"POST","/api/orders",member,Map.of("packageId",1)).path("code").asInt());
        parallel(List.of(()->httpBook(port,member,scheduleId),()->{try{var r=call(secondPort(),"PUT","/api/admin/schedules/"+scheduleId,manager.token(),schedule(10,5));assertNotEquals(0,r.path("code").asInt());throw new com.yoyi.pilates.common.exception.BusinessException(r.path("code").asInt(),r.path("message").asText());}catch(com.yoyi.pilates.common.exception.BusinessException e){throw e;}catch(Exception e){throw new RuntimeException(e);}}));
        assertEquals(8,schedules.selectById(scheduleId).getMaxCapacity());
        assertEquals(0,call(port,"PUT","/api/admin/schedules/"+scheduleId,manager.token(),schedule(10,10)).path("code").asInt());
        for(int i=schedules.selectById(scheduleId).getBookedCount();i<10;i++)httpBook(secondPort(),member(newUser()),scheduleId);
        assertEquals("0",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        assertEquals(0,call(secondPort(),"POST","/api/admin/schedules/"+scheduleId+"/cancel",manager.token(),null).path("code").asInt());
        assertEquals(0,schedules.selectById(scheduleId).getBookedCount());assertNull(redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        assertNotEquals(0,call(port,"POST","/api/bookings",member,Map.of("scheduleId",scheduleId)).path("code").asInt());
        assertNotEquals(0,call(port,"POST","/api/attendance/check-in?scheduleId="+scheduleId,member,null).path("code").asInt());
    }
    @Test @Order(6) void secondInstanceRestartReadsCommittedHistoryAndRebuildsRedis() throws Exception {
        Long old=bookings.book(userId,scheduleId,null,null);bookings.cancel(userId,old);Long current=bookings.book(userId,scheduleId,null,null);String t=member(userId);
        second.close();redis.delete(RedisKeys.stock(scheduleId));second=start();
        assertEquals("BOOKED",call(secondPort(),"GET","/api/bookings/"+current,t,null).path("data").path("status").asText());
        second.getBean(StockService.class).reconcileAll();assertEquals("7",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        openCheckin();
        var admin=account("ADMIN");
        var ticket=call(port,"POST","/api/coach-end/schedules/"+scheduleId+"/attendance-code",admin.token(),null);
        assertEquals(0,ticket.path("code").asInt());
        var checkin=Map.of("bookingId",current,"code",ticket.path("data").path("code").asText());
        assertEquals(0,call(secondPort(),"POST","/api/attendance/check-in",t,checkin).path("code").asInt());
        assertEquals(0,call(port,"POST","/api/attendance/check-in",t,checkin).path("code").asInt());assertEquals(1,attendanceMapper.selectCount(null));
        assertNotEquals(0,call(port,"POST","/api/admin/schedules/"+scheduleId+"/cancel",admin.token(),null).path("code").asInt());assertEquals("SIGNED_IN",bookingMapper.selectById(current).getStatus());
    }
    @Test @Order(7) void realBrowserViteProxyAndProductionNginx() throws Exception {
        var manager=account("STORE_MANAGER");String t=member(userId);
        var extra=new com.yoyi.pilates.course.infrastructure.entity.CoachEntity();extra.setName("新指派教练");extra.setStatus("ACTIVE");extra.setYears(1);coaches.insert(extra);
        Path root=Path.of(System.getProperty("basedir")).getParent();Path output=Path.of(System.getProperty("pilates.build.directory"));
        var command=new ProcessBuilder("python3",root.resolve("scripts/round3/web.py").toString(),"--backend-port",String.valueOf(port),"--vite-port",String.valueOf(VITE_PORT),"--https-port",String.valueOf(HTTPS_PORT),"--output",output.toString());
        command.environment().put("PILATES_BROWSER_INPUT",json.writeValueAsString(Map.of("username",manager.username(),"password",manager.password(),"memberToken",t)));
        command.redirectErrorStream(true);command.redirectOutput(output.resolve("web-run.log").toFile());var process=command.start();assertTrue(process.waitFor(150,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"See web-run.log");
        var result=json.readTree(Files.readString(output.resolve("web-acceptance.json")));assertTrue(result.path("passed").asBoolean());
        scheduleId=result.path("vite").path("scheduleId").asLong();openCheckin();
        var admin = account("ADMIN");
        var ticket = call(port, "POST", "/api/coach-end/schedules/" + scheduleId + "/attendance-code", admin.token(), null);
        assertEquals(0, ticket.path("code").asInt());
        long bookingId = bookings.bookingsOfSchedule(scheduleId).stream().filter(b -> "BOOKED".equals(b.getStatus()))
                .findFirst().orElseThrow().getId();
        var body = Map.of("bookingId", bookingId, "code", ticket.path("data").path("code").asText());
        assertEquals(0,call(port,"POST","/api/attendance/check-in",t,body).path("code").asInt());assertEquals(1,attendanceMapper.selectCount(null));
    }

    @Test @Order(8) void realHttpFreePrivateAndUnconfiguredCapabilities() throws Exception {
        var c=courses.selectById(courseId);c.setType("PRIVATE");courses.updateById(c);
        var login=call(port,"POST","/api/auth/wx-login",null,Map.of("code","isolated_"+UUID.randomUUID()));assertEquals(0,login.path("code").asInt());String t=login.path("data").path("token").asText();
        // This is explicitly a development mock login, not proof of real WeChat authentication.
        assertEquals(0,call(port,"GET","/api/my/packages",t,null).path("data").size());
        Long old=null;for(int i=0;i<3;i++){var response=call(i%2==0?port:secondPort(),"POST","/api/bookings",t,Map.of("scheduleId",scheduleId));assertEquals(0,response.path("code").asInt());Long id=response.path("data").path("bookingId").asLong();if(old!=null)assertNotEquals(old,id);if(i<2)assertEquals(0,call(port,"DELETE","/api/bookings/"+id,t,null).path("code").asInt());old=id;}
        assertEquals(0,new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM `order`",Integer.class));assertEquals(1,schedules.selectById(scheduleId).getBookedCount());
        assertEquals(40300,call(port,"POST","/api/auth/phone/send-code",t,Map.of("phone","13000000000")).path("code").asInt());
        assertEquals(40300,call(secondPort(),"POST","/api/orders",t,Map.of("packageId",1)).path("code").asInt());
    }
    @Test @Order(9) void overlappingHttpCreatesAcrossInstancesHaveOneWinner() throws Exception {
        var admin=account("ADMIN");var work=new ArrayList<Runnable>();for(int p:List.of(port,secondPort()))work.add(()->{try{var r=call(p,"POST","/api/admin/schedules",admin.token(),schedule(14,8));if(r.path("code").asInt()!=0)throw new com.yoyi.pilates.common.exception.BusinessException(r.path("code").asInt(),r.path("message").asText());}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}});
        assertEquals(1,parallel(work));assertEquals(2,schedules.selectCount(null));
    }
}
