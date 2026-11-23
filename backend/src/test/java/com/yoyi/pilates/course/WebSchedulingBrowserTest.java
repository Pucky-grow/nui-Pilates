package com.yoyi.pilates.course;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.system.infrastructure.entity.*;
import com.yoyi.pilates.system.infrastructure.mapper.*;
import com.yoyi.pilates.system.security.JwtService;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import java.nio.file.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="yoyi.cors.allowed-origins=http://127.0.0.1:5179")
@EnabledIfEnvironmentVariable(named="PILATES_LIVE_WEB_URL",matches=".+")
class WebSchedulingBrowserTest extends BookingFixture {
    @LocalServerPort int port;
    @Autowired SysUserMapper accounts;
    @Autowired SysRoleMapper roles;
    @Autowired SysUserRoleMapper userRoles;
    @Autowired SysPermissionMapper permissions;
    @Autowired SysRolePermissionMapper rolePermissions;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate rest;
    @Autowired com.yoyi.pilates.booking.application.AttendanceCodeService attendanceCodes;
    String token(String type){String t=jwt.createToken(userId,type);redis.opsForValue().set(RedisKeys.loginToken(t),"1");return t;}
    @Test void realBrowserRealHttpFreeBookingAndSignin() throws Exception {
        SysUserEntity account=new SysUserEntity();account.setId(userId);account.setUsername("manager");account.setPassword("unused");account.setName("店长");account.setStatus("ACTIVE");accounts.insert(account);
        SysRoleEntity role=new SysRoleEntity();role.setCode("STORE_MANAGER");role.setName("店长");role.setStatus("ACTIVE");roles.insert(role);
        SysUserRoleEntity ur=new SysUserRoleEntity();ur.setUserId(userId);ur.setRoleId(role.getId());userRoles.insert(ur);
        for(String code:List.of("schedule:view","schedule:create","schedule:update","schedule:cancel","manager:course:coach:manage")){
            SysPermissionEntity p=new SysPermissionEntity();p.setCode(code);p.setName(code);p.setType("API");permissions.insert(p);
            SysRolePermissionEntity rp=new SysRolePermissionEntity();rp.setRoleId(role.getId());rp.setPermissionId(p.getId());rolePermissions.insert(rp);
        }
        String member=token("MP_USER");String admin=token("ADMIN");
        String url=System.getenv("PILATES_LIVE_WEB_URL")+"/tests/live.html?backend="+URLEncoder.encode("http://127.0.0.1:"+port,StandardCharsets.UTF_8);
        Path root=Path.of(System.getProperty("basedir")).getParent();Path output=Path.of(System.getProperty("pilates.build.directory",root.resolve("backend/target-round2").toString())).resolve("live-browser.json");Files.createDirectories(output.getParent());
        ProcessBuilder command=new ProcessBuilder("node",root.resolve("scripts/check-web-mount.mjs").toString());command.environment().put("MOUNT_URL",url);command.environment().put("PILATES_BROWSER_INPUT",json.writeValueAsString(Map.of("adminToken",admin,"memberToken",member)));command.redirectErrorStream(true);command.redirectOutput(output.toFile());Process browser=command.start();
        assertTrue(browser.waitFor(90,TimeUnit.SECONDS),"browser timed out");String report=Files.readString(output);assertEquals(0,browser.exitValue(),report);
        var result=json.readTree(report);assertTrue(result.get("passed").asBoolean(),report);scheduleId=result.get("scheduleId").asLong();Long bookingId=result.get("bookingId").asLong();
        assertEquals(3,bookingMapper.countHistory(scheduleId));assertEquals(1,schedules.selectById(scheduleId).getBookedCount());
        // Only the isolated H2 fixture is advanced into the real check-in window; production DB is never touched.
        openCheckin();HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(member);
        var ticket = attendanceCodes.issue(schedules.selectById(scheduleId));
        var body = Map.of("bookingId", bookingId, "code", ticket.code());
        var response=rest.exchange("/api/attendance/check-in",HttpMethod.POST,new HttpEntity<>(body,headers),Map.class);
        assertEquals(0,response.getBody().get("code"));assertEquals("SIGNED_IN",bookingMapper.selectById(bookingId).getStatus());assertEquals(1,attendanceMapper.selectCount(null));
        assertEquals("9",redis.opsForValue().get(RedisKeys.stock(scheduleId)));
        assertEquals(0,rest.exchange("/api/my/orders",HttpMethod.GET,new HttpEntity<>(headers),Map.class).getBody().get("code"));
    }
}
