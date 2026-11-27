package com.yoyi.pilates.security;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.system.infrastructure.entity.*;
import com.yoyi.pilates.system.infrastructure.mapper.*;
import com.yoyi.pilates.system.security.JwtService;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.member.application.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.LocalTime;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@AutoConfigureMockMvc
class MemberHttpBoundaryTest extends BookingFixture {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired SysUserMapper accounts;
    @Autowired SysRoleMapper roles;
    @Autowired SysUserRoleMapper accountRoles;
    @Autowired SysPermissionMapper permissions;
    @Autowired SysRolePermissionMapper rolePermissions;
    @Autowired ObjectMapper json;
    @Autowired MemberService members;
    @Autowired com.yoyi.pilates.system.application.RbacService rbac;

    @Test void issuedQrMatchesBookingAndAttendanceSource() throws Exception {
        backoffice("ADMIN");
        String admin = token("ADMIN");
        String member = token("MP_USER");
        Long id = bookings.book(userId, scheduleId, null, null);
        String issue = "/api/coach-end/schedules/" + scheduleId + "/attendance-code";
        mvc.perform(post(issue).header("Authorization", admin)).andExpect(jsonPath("$.code").value(40900));
        openCheckin();
        mvc.perform(post(issue).header("Authorization", member)).andExpect(jsonPath("$.code").value(40300));
        var response = mvc.perform(post(issue).header("Authorization", admin)).andExpect(jsonPath("$.code").value(0)).andReturn();
        var ticket = json.readTree(response.getResponse().getContentAsByteArray()).path("data");
        String code = ticket.path("code").asText();
        String image = ticket.path("image").asText();
        var png = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(image.split(",", 2)[1])));
        var bits = new com.google.zxing.RGBLuminanceSource(png.getWidth(), png.getHeight(),
                png.getRGB(0, 0, png.getWidth(), png.getHeight(), null, 0, png.getWidth()));
        var barcode = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(bits));
        assertEquals(code, new com.google.zxing.qrcode.QRCodeReader().decode(barcode).getText());
        String path = "/api/attendance/check-in";
        mvc.perform(post(path).header("Authorization", member).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("bookingId", id + 1, "code", code))))
                .andExpect(jsonPath("$.code").value(41006));
        String body = json.writeValueAsString(Map.of("bookingId", id, "code", code));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(path).header("Authorization", member).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertEquals(1, attendanceMapper.selectCount(null));
        assertEquals("ONSITE_QR", attendanceMapper.selectList(null).get(0).getCheckinType());
        redis.delete("pilates:attendance:code:" + code);
        mvc.perform(post(path).header("Authorization", member).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test void authorizedFrontDeskSignsInWithDistinctSource() throws Exception {
        Long role = backoffice("STAFF");
        var permission = new SysPermissionEntity();
        permission.setCode("staff:attendance:create");
        permission.setName("前台代签到");
        permission.setType("API");
        permissions.insert(permission);
        var grant = new SysRolePermissionEntity();
        grant.setRoleId(role);
        grant.setPermissionId(permission.getId());
        rolePermissions.insert(grant);
        Long id = bookings.book(userId, scheduleId, null, null);
        openCheckin();
        mvc.perform(post("/api/admin/bookings/" + id + "/check-in").header("Authorization", token("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"核对现场学员\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("STAFF", attendanceMapper.selectList(null).get(0).getCheckinType());
    }

    @Test void memberDetailShowsPublicReasonAfterWholeClassCancellation() throws Exception {
        Long id = bookings.book(userId, scheduleId, null, null);
        bookings.cancelSchedule(scheduleId, "教室临时维护");
        mvc.perform(get("/api/bookings/" + id).header("Authorization", token("MP_USER")))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.publicCancelReason").value("教室临时维护"));
    }
    String token(String type){String t=jwt.createToken(userId,type);redis.opsForValue().set(RedisKeys.loginToken(t),"1");return "Bearer "+t;}
    Long backoffice(String role){SysUserEntity u=new SysUserEntity();u.setId(userId);u.setUsername("backoffice");u.setPassword("unused-in-jwt-test");u.setName("后台");u.setStatus("ACTIVE");accounts.insert(u);SysRoleEntity r=new SysRoleEntity();r.setCode(role);r.setName(role);r.setStatus("ACTIVE");roles.insert(r);SysUserRoleEntity ur=new SysUserRoleEntity();ur.setUserId(userId);ur.setRoleId(r.getId());accountRoles.insert(ur);return r.getId();}
    @Test void realAdminJwtCannotReadOrMutateMemberNamespacesDespiteIdCollision() throws Exception {
        backoffice("ADMIN");String auth=token("ADMIN");
        for(String route:List.of("/api/my/bookings","/api/my/orders","/api/my/packages","/api/training-records","/api/assessments","/api/measurements/trend","/api/notifications","/api/my/coupons","/api/my/points","/api/my/feedback","/api/invitation/my-code","/api/my/reviews"))
            mvc.perform(get(route).header("Authorization",auth)).andExpect(jsonPath("$.code").value(40300));
        mvc.perform(put("/api/auth/profile").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"伪造修改\"}")).andExpect(jsonPath("$.code").value(40300));
        mvc.perform(post("/api/bookings").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{\"scheduleId\":"+scheduleId+"}")).andExpect(jsonPath("$.code").value(40300));
        assertEquals("会员",users.selectById(userId).getNickname());assertEquals(0,bookingMapper.selectCount(null));
    }
    @Test void boundWechatCoachKeepsOwnMemberIdentityAndOnlyActualOperationalPermissions() throws Exception {
        Long role=backoffice("COACH");var p=new SysPermissionEntity();p.setCode("coach:schedule:create");p.setName("本人排课");p.setType("API");permissions.insert(p);var link=new SysRolePermissionEntity();link.setRoleId(role);link.setPermissionId(p.getId());rolePermissions.insert(link);
        var c=coaches.selectById(coachId);c.setSysUserId(userId);c.setOpenid(users.selectById(userId).getOpenid());coaches.updateById(c);
        String member=token("MP_USER");
        mvc.perform(post("/api/bookings").header("Authorization",member).contentType(MediaType.APPLICATION_JSON).content("{\"scheduleId\":"+scheduleId+"}")).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/coach-end/home/banners").header("Authorization",member)).andExpect(jsonPath("$.code").value(40300));
        String body="{\"courseId\":"+courseId+",\"coachId\":999,\"classroomId\":"+roomId+",\"courseDate\":\""+StudioTime.today().plusDays(2)+"\",\"startTime\":\"12:00\",\"endTime\":\"13:00\",\"maxCapacity\":8}";
        mvc.perform(post("/api/coach-end/schedules").header("Authorization",member).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(0));
        roles.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysRoleEntity>().eq(SysRoleEntity::getId,role).set(SysRoleEntity::getStatus,"DISABLED"));
        rbac.evictCache(userId);
        mvc.perform(post("/api/coach-end/schedules").header("Authorization",member).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(40300));
    }
    @Test void managementAliasAndStrictCapacityRejectClientCoercion() throws Exception {
        backoffice("ADMIN");String auth=token("ADMIN");
        mvc.perform(get("/api/admin/schedules/options").header("Authorization",auth)).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.courses[0].name").value("测试课"));
        String body="{\"courseId\":"+courseId+",\"coachId\":"+coachId+",\"classroomId\":"+roomId+",\"courseDate\":\""+StudioTime.today().plusDays(2)+"\",\"startTime\":\"12:00\",\"endTime\":\"13:00\",\"maxCapacity\":1.5}";
        mvc.perform(post("/api/admin/schedules").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(40000));
    }

    @Test void coachConfirmsExactMemberAndBooksOwnArbitraryTimeForGroupAndPrivate() throws Exception {
        long role = backoffice("COACH");
        var permission = new SysPermissionEntity();
        permission.setCode("coach:schedule:create");
        permission.setName("创建本人排课");
        permission.setType("API");
        permissions.insert(permission);
        var grant = new SysRolePermissionEntity();
        grant.setRoleId(role);
        grant.setPermissionId(permission.getId());
        rolePermissions.insert(grant);
        var coach = coaches.selectById(coachId);
        coach.setSysUserId(userId);
        coaches.updateById(coach);
        members.ensureMember(userId);
        var user = users.selectById(userId);
        user.setPhone("13800138000");
        users.updateById(user);
        String coachAuth = token("ADMIN");
        String lookup = "/api/coach-end/schedules/booking-member";
        String reference = json.writeValueAsString(Map.of("memberReference", user.getPhone()));
        mvc.perform(post(lookup).header("Authorization", token("MP_USER")).contentType(MediaType.APPLICATION_JSON).content(reference))
                .andExpect(jsonPath("$.code").value(40300));
        mvc.perform(post(lookup).header("Authorization", coachAuth).contentType(MediaType.APPLICATION_JSON).content(reference))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.maskedPhone").value("138****8000")).andExpect(jsonPath("$.data.phone").doesNotExist());
        mvc.perform(post(lookup).header("Authorization", coachAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("memberReference", members.byUserId(userId).getMemberNo()))))
                .andExpect(jsonPath("$.data.userId").value(userId));

        var body = new LinkedHashMap<String, Object>();
        body.put("memberReference", user.getPhone());
        body.put("userId", userId + 1000);
        body.put("reason", "已核对上课时间");
        var schedule = new LinkedHashMap<String, Object>(Map.of("courseId", courseId, "coachId", 999,
                "classroomId", roomId, "courseDate", StudioTime.today().plusDays(12).toString(),
                "startTime", "15:17", "endTime", "16:17", "maxCapacity", 8));
        body.put("schedule", schedule);
        String create = "/api/coach-end/schedules/book";
        mvc.perform(post(create).header("Authorization", coachAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(40900));
        assertEquals(1, schedules.selectCount(null));

        body.put("userId", userId);
        var created = mvc.perform(post(create).header("Authorization", coachAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long groupScheduleId = json.readTree(created.getResponse().getContentAsByteArray()).path("data").path("scheduleId").asLong();
        var group = schedules.selectById(groupScheduleId);
        assertEquals(coachId, group.getCoachId());
        assertEquals(LocalTime.of(15, 17), group.getStartTime());
        assertEquals(1, group.getBookedCount());

        var privateCourse = courses.selectById(courseId);
        privateCourse.setId(null);
        privateCourse.setName("一对一测试");
        privateCourse.setType("PRIVATE");
        courses.insert(privateCourse);
        schedule.put("courseId", privateCourse.getId());
        schedule.put("maxCapacity", 1);
        schedule.put("courseDate", StudioTime.today().plusDays(13).toString());
        mvc.perform(post(create).header("Authorization", coachAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(40300));
        var qualification = new com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity();
        qualification.setCourseId(privateCourse.getId());
        qualification.setCoachId(coachId);
        qualification.setStatus("APPROVED");
        qualifications.insert(qualification);
        var privateBooking = mvc.perform(post(create).header("Authorization", coachAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long privateScheduleId = json.readTree(privateBooking.getResponse().getContentAsByteArray()).path("data").path("scheduleId").asLong();
        assertEquals(1, schedules.selectById(privateScheduleId).getMaxCapacity());
        assertEquals(1, schedules.selectById(privateScheduleId).getBookedCount());
        assertEquals(2, bookingMapper.selectCount(null));
    }

    @Test void adminCanAddCourseToScheduleChoicesAndBookItWithMember() throws Exception {
        backoffice("ADMIN");
        members.ensureMember(userId);
        String auth = token("ADMIN");
        var added = mvc.perform(post("/api/admin/courses").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "新私教课程", "type", "PRIVATE", "durationMinutes", 45,
                                "difficulty", "BEGINNER", "creditsCost", 1, "status", "ON_SHELF"))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = json.readTree(added.getResponse().getContentAsByteArray()).path("data").asLong();
        mvc.perform(get("/api/admin/schedules/options").header("Authorization", auth))
                .andExpect(jsonPath("$.data.courses.length()").value(2))
                .andExpect(jsonPath("$.data.courses[?(@.type == 'PRIVATE')].name").value("新私教课程"));
        mvc.perform(put("/api/admin/courses/" + id + "/coaches/" + coachId).header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));
        var schedule = Map.of("courseId", id, "coachId", coachId, "classroomId", roomId,
                "courseDate", StudioTime.today().plusDays(2).toString(),
                "startTime", "13:37", "endTime", "14:22", "maxCapacity", 1);
        mvc.perform(post("/api/admin/bookings/new-schedule").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", userId, "schedule", schedule, "reason", "会员已确认"))))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, bookingMapper.selectCount(null));
        assertEquals(2, schedules.selectCount(null));
    }
}
