package com.yoyi.pilates.production;
import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.system.application.AccountService;import com.yoyi.pilates.user.application.AuthService;
import com.yoyi.pilates.system.security.*;import com.yoyi.pilates.infra.delivery.*;import com.yoyi.pilates.infra.mq.*;
import com.yoyi.pilates.infra.storage.*;import com.yoyi.pilates.common.exception.BusinessException;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;import java.net.*;import java.net.http.*;import java.io.*;import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"yoyi.mq.type=outbox","yoyi.delivery.workers-enabled=false","yoyi.upload.dir=${java.io.tmpdir}/pilates-production-files-test"})
class ProductionServiceTest extends BookingFixture {
 @Autowired AuthService auth;@Autowired AccountService accounts;@Autowired JdbcTemplate jdbc;
 @Test void boundCoachUploadAndProposalPermissionRevocationRespectActualGrants() throws Exception {
  admin();long account=accounts.create("synthetic_coach","合成教练账号",password,"COACH",coachId);
  jdbc.update("UPDATE coach SET openid=? WHERE id=?",users.selectById(userId).getOpenid(),coachId);
  jdbc.update("INSERT INTO sys_permission(code,name,type) VALUES('coach:course:proposal:create','本人方案','API')");
  jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) SELECT r.id,p.id FROM sys_role r JOIN sys_permission p WHERE r.code='COACH' AND p.code='coach:course:proposal:create'");
  String token=jwt.createToken(userId,"MP_USER");redis.opsForValue().set(com.yoyi.pilates.common.constant.RedisKeys.loginToken(token),"1");
  var client=HttpClient.newHttpClient();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  java.util.function.Supplier<Integer> create=()->{try{
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/coach-end/course-proposals")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"courseName\":\"合成草稿\",\"courseType\":\"GROUP\"}")).build();
   return mapper.readTree(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).path("code").asInt();
  }catch(Exception error){throw new RuntimeException(error);}};
  assertEquals(0,create.get());
  var actor=context.getBean(LoginUserLoader.class).load(userId,"MP_USER").orElseThrow();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
  var bytes=new ByteArrayOutputStream();ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
  var upload=files.upload(new MockMultipartFile("file","coach.png","image/png",bytes.toByteArray()),"PUBLIC_ASSET",null);assertTrue(upload.get("previewUrl").startsWith("/media/public/"));
  jdbc.update("DELETE FROM sys_role_permission WHERE permission_id=(SELECT id FROM sys_permission WHERE code='coach:course:proposal:create')");
  assertEquals(40300,create.get());
  actor=context.getBean(LoginUserLoader.class).load(userId,"MP_USER").orElseThrow();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
  assertThrows(BusinessException.class,()->files.upload(new MockMultipartFile("file","coach.png","image/png",bytes.toByteArray()),"PUBLIC_ASSET",null));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM course_proposal",Integer.class));
 }
 @MockitoSpyBean OutboxPublisher publisher;@Autowired OutboxDispatcher dispatcher;@Autowired FileService files;
 @Autowired PlatformTransactionManager transactions;@Autowired JwtService jwt;
 @MockitoSpyBean PasswordEncoder encoder;
 @MockitoSpyBean com.yoyi.pilates.notify.application.NotificationEventConsumer notifications;
 @LocalServerPort int port;
 private final String password=UUID.randomUUID()+"Strong1";
 private Long admin(){jdbc.update("INSERT INTO sys_role(code,name,status) VALUES('ADMIN','管理员','ACTIVE'),('STAFF','前台','ACTIVE'),('STORE_MANAGER','店长','ACTIVE'),('COACH','教练','ACTIVE')");jdbc.update("INSERT INTO sys_user(username,password,name,status) VALUES('secure_operator',?,'隔离管理员','ACTIVE')",encoder.encode(password));long id=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='secure_operator'",Long.class);jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='ADMIN'",id);actor(id,"ADMIN",Set.of("ADMIN"));return id;}
 private void actor(long id,String type,Set<String> roles){LoginUser user=new LoginUser();user.setId(id);user.setType(type);user.setRoles(roles);user.setPermissions(Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
 private int httpCode(String token)throws Exception {var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/me")).header("Authorization","Bearer "+token).GET().build();String body=HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString()).body();return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("code").asInt();}
 @Test void loginLimitBlocksBeforeBcryptThenExpires() {
  admin();for(int n=0;n<10;n++)assertThrows(BusinessException.class,()->auth.adminLogin("secure_operator","wrong"));
  clearInvocations(encoder);assertThrows(BusinessException.class,()->auth.adminLogin("secure_operator",password));verify(encoder,never()).matches(any(),any());
  String key="pilates:login:failure:account:"+LoginProtection.digest("secure_operator");assertTrue(redis.getExpire(key)>0&&redis.getExpire(key)<=300);redis.delete(key);assertNotNull(auth.adminLogin("secure_operator",password).getToken());
 }
 @Test void resetDisableAndOwnPasswordRevokeRealHttpSessions()throws Exception{
  long root=admin();String token=auth.adminLogin("secure_operator",password).getToken();assertEquals(0,httpCode(token));accounts.reset(root,password+"2");assertEquals(40100,httpCode(token));
  Long staff=accounts.create("new_staff","隔离前台",password,"STAFF",null);String staffToken=auth.adminLogin("new_staff",password).getToken();assertEquals(0,httpCode(staffToken));accounts.status(staff,"DISABLED");assertEquals(40100,httpCode(staffToken));accounts.status(staff,"ACTIVE");assertEquals(40100,httpCode(staffToken));
  actor(staff,"ADMIN",Set.of("STAFF"));assertThrows(BusinessException.class,()->accounts.reset(root,password));accounts.changeOwnPassword(password,password+"3");assertEquals(40100,httpCode(staffToken));
  actor(root,"ADMIN",Set.of("ADMIN"));assertThrows(BusinessException.class,()->accounts.create("other_root","不可建",password,"ADMIN",null));
 }
 @Test void privateFilesCheckIdentityNamespaceAndExpirationPublicImagesRemainReadable()throws Exception {
  admin();actor(userId,"MP_USER",Set.of("MEMBER"));var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
  var uploaded=files.upload(new MockMultipartFile("file","image.png","image/png",bytes.toByteArray()),"AVATAR",null);String id=uploaded.get("objectId");assertTrue(uploaded.get("url").startsWith("/media/ref/"));String signed=uploaded.get("previewUrl");var parts=URI.create(signed).getQuery().split("&");long expiry=Long.parseLong(parts[0].split("=")[1]);String sig=parts[1].split("=")[1];assertArrayEquals(bytes.toByteArray(),files.content(id,false,expiry,sig));assertThrows(BusinessException.class,()->files.content(id,false,expiry-600,sig));
  actor(userId,"ADMIN",Set.of("COACH"));assertFalse(files.canRead(files.record(id)));assertEquals("",files.resolve(uploaded.get("url")));actor(userId,"MP_USER",Set.of("MEMBER"));assertEquals(uploaded.get("url"),files.normalize(signed));
  assertThrows(BusinessException.class,()->files.upload(new MockMultipartFile("file","fake.png","image/png","<script>".getBytes()),"AVATAR",null));
  actor(1,"ADMIN",Set.of("ADMIN"));var material=files.upload(new MockMultipartFile("file","cover.png","image/png",bytes.toByteArray()),"PUBLIC_ASSET",null);SecurityContextHolder.clearContext();assertArrayEquals(bytes.toByteArray(),files.content(material.get("objectId"),true,0,null));assertThrows(BusinessException.class,()->files.content(id,true,0,null));
 }
 @Test void outboxCommitRollbackRetryAndRedeliveryHaveOneBusinessEffect(){
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(tx->{publisher.publish("test",DomainEvent.of("TEST","rolled-back",Map.of()));throw new IllegalStateException("rollback");}));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox",Integer.class));
  Long id=bookings.book(userId,scheduleId,null,null);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox",Integer.class));
  doThrow(new IllegalStateException("controlled handler failure")).when(notifications).onMessage(any());assertEquals(0,dispatcher.drain(10));assertEquals(1,jdbc.queryForObject("SELECT attempts FROM event_outbox",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
  reset(notifications);jdbc.update("UPDATE event_outbox SET next_attempt_at=CURRENT_TIMESTAMP");assertEquals(1,dispatcher.drain(10));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));jdbc.update("UPDATE event_outbox SET state='PENDING',next_attempt_at=CURRENT_TIMESTAMP");assertEquals(1,dispatcher.drain(10));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));assertEquals("BOOKED",bookingMapper.selectById(id).getStatus());
 }
 @Test void cancellationRebookingHasDistinctDurableEventKeys(){
  Long first=bookings.book(userId,scheduleId,null,null);bookings.cancel(userId,first);Long second=bookings.book(userId,scheduleId,null,null);bookings.cancel(userId,first);assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox",Integer.class));assertEquals(3,dispatcher.drain(100));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));assertEquals("BOOKED",bookingMapper.selectById(second).getStatus());
 }
 @Autowired org.springframework.context.ApplicationContext context;
 @Test void schedulerVisitsBeyondFirstTwoHundredAndFiveHundredRows(){
  var scheduler=context.getAutowireCapableBeanFactory().createBean(com.yoyi.pilates.infra.scheduler.PilatesScheduler.class);
  jdbc.update("UPDATE course_schedule SET course_date=DATE_SUB(CURRENT_DATE,INTERVAL 1 DAY) WHERE id=?",scheduleId);
  for(int n=0;n<205;n++)newSchedule(8,-1,10);
  scheduler.finishPassedSchedules();assertEquals(206,jdbc.queryForObject("SELECT COUNT(*) FROM course_schedule WHERE status='FINISHED'",Integer.class));
  for(int n=0;n<505;n++){long id=newSchedule(8,0,10);jdbc.update("UPDATE course_schedule SET course_date=DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),start_time='00:01:00',end_time='00:02:00' WHERE id=?",id);}
  // The early 500 future rows must not hide a later due record.
  long last=newSchedule(8,-1,12);scheduler.finishPassedSchedules();assertEquals("FINISHED",schedules.selectById(last).getStatus());
 }

 @Test void durableEnqueueFailureRollsBackBookingAndSeat(){
  doThrow(new IllegalStateException("controlled durable insert failure")).when(publisher).publish(eq(Topics.BOOKING_EVENT),any());
  assertThrows(IllegalStateException.class,()->bookings.book(userId,scheduleId,null,null));assertEquals(0,bookingMapper.selectCount(null));assertEquals(0,schedules.selectById(scheduleId).getBookedCount());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox",Integer.class));
 }

 @Test void remindersVisitLaterDueBookingBeyondFiveHundredEarlierRows(){
  var now=com.yoyi.pilates.common.util.StudioTime.now();var start=now.plusMinutes(65);var end=start.plusMinutes(30);
  org.junit.jupiter.api.Assumptions.assumeTrue(start.toLocalDate().equals(end.toLocalDate()));
  for(int n=0;n<505;n++)newSchedule(8,-1,8);
  long late=newSchedule(8,0,10);jdbc.update("UPDATE course_schedule SET course_date=?,start_time=?,end_time=? WHERE id=?",start.toLocalDate(),start.toLocalTime(),end.toLocalTime(),late);
  long booking=bookings.book(userId,late,null,null);
  var scheduler=context.getAutowireCapableBeanFactory().createBean(com.yoyi.pilates.infra.scheduler.PilatesScheduler.class);scheduler.courseRemind();scheduler.courseRemind();
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE biz_type='COURSE_REMIND' AND biz_id=?",Integer.class,String.valueOf(booking)));
 }

 @Autowired com.yoyi.pilates.trade.application.OrderService orders;
 @MockitoSpyBean com.yoyi.pilates.marketing.application.CouponService couponSpy;
 @Test void timeoutOrderBatchContinuesBeyondFailedRowAndRollsBackThatOrder(){
  for(int n=0;n<205;n++)jdbc.update("INSERT INTO `order`(order_no,user_id,total_amount,pay_amount,points_amount,status,created_at) VALUES(?,?,0,0,0,'PENDING',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 60 MINUTE))","historical-test-"+n,userId);
  jdbc.update("UPDATE `order` SET user_coupon_id=42 WHERE id=1");doThrow(new IllegalStateException("controlled restore failure")).when(couponSpy).restore(eq(42L),eq(1L));
  assertEquals(204,orders.closeTimeoutOrders(30));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class));assertEquals("CLOSED",jdbc.queryForObject("SELECT status FROM `order` WHERE id=205",String.class));
  reset(couponSpy);jdbc.update("UPDATE `order` SET user_coupon_id=NULL WHERE id=1");assertEquals(1,orders.closeTimeoutOrders(30));assertEquals(0,orders.closeTimeoutOrders(30));
 }

 @MockitoSpyBean org.redisson.api.RedissonClient redissonSpy;
 @Test void databaseMemberMutexProtectsDailyLimitWhenRedisMemberMutexIsUnavailable()throws Exception {
  var missing=mock(org.redisson.api.RLock.class);when(missing.isHeldByCurrentThread()).thenReturn(true);
  doReturn(missing).when(redissonSpy).getLock(com.yoyi.pilates.booking.application.ScheduleExecution.memberLock(userId));
  List<Runnable> calls=new ArrayList<>();for(int n=0;n<6;n++){long schedule=newSchedule(8,1,12+n);calls.add(()->bookings.book(userId,schedule,null,null));}
  assertEquals(3,parallel(calls));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE status='BOOKED'",Integer.class));
 }

 @Autowired com.yoyi.pilates.trade.application.PaymentService payments;
 @Autowired com.yoyi.pilates.trade.application.RefundService refunds;
 @Test void positivePaymentInitiationAndRefundStayClosedBeforeAnyEffect(){
  jdbc.update("INSERT INTO `order`(order_no,user_id,total_amount,pay_amount,status) VALUES('closed-capability',?,10,10,'PENDING')",userId);
  long order=jdbc.queryForObject("SELECT id FROM `order` WHERE order_no='closed-capability'",Long.class);
  org.springframework.test.util.ReflectionTestUtils.setField(payments,"mockEnabled",false);
  try {assertThrows(BusinessException.class,()->payments.createPayParams(userId,order));}finally{org.springframework.test.util.ReflectionTestUtils.setField(payments,"mockEnabled",true);}
  assertThrows(BusinessException.class,()->refunds.refund(order,"隔离拒绝测试","synthetic"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM refund",Integer.class));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM `order` WHERE id=?",String.class,order));
 }

 @Test void multipartHttpUploadAndCourseBannerCoachRoundTripKeepStableReferences() throws Exception {
  admin();String token=auth.adminLogin("secure_operator",password).getToken();
  var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
  var png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);
  var client=HttpClient.newHttpClient();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var result=uploadImage(client,token,"purpose=PUBLIC_ASSET",png.toByteArray());
  String ref=result.path("url").asText(),preview=result.path("previewUrl").asText();
  assertTrue(ref.startsWith("/media/ref/"));assertTrue(preview.startsWith("/media/public/"));
  var picture=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+preview)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
  assertEquals(200,picture.statusCode());assertArrayEquals(png.toByteArray(),picture.body());
  var resources=List.of(
    Map.of("path","/api/admin/courses/"+courseId,"method","PUT","body",Map.of("categoryId",courses.selectById(courseId).getCategoryId(),"name","合成课程","cover",preview,"durationMinutes",60,"type","GROUP","difficulty","BEGINNER","creditsCost",1,"status","ON_SHELF")),
    Map.of("path","/api/admin/coaches/"+coachId,"method","PUT","body",Map.of("name","合成教练","avatar",preview,"years",1,"status","ACTIVE")),
    Map.of("path","/api/admin/banners","method","POST","body",Map.of("title","合成素材","imageUrl",preview,"linkType","NONE","sort",0)));
  for(var resource:resources){
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+resource.get("path"))).header("Authorization","Bearer "+token).header("Content-Type","application/json")
      .method(resource.get("method").toString(),HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(resource.get("body")))).build();
   assertEquals(0,mapper.readTree(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).path("code").asInt());
  }
  assertEquals(ref,jdbc.queryForObject("SELECT cover FROM course WHERE id=?",String.class,courseId));
  assertEquals(ref,jdbc.queryForObject("SELECT avatar FROM coach WHERE id=?",String.class,coachId));
  assertEquals(ref,jdbc.queryForObject("SELECT image_url FROM banner",String.class));
  var detail=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/admin/courses/"+courseId)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(preview,mapper.readTree(detail.body()).path("data").path("cover").asText());
 }

 @Test void uploadedMemberAvatarCanBeSavedAndReadBackOnlyForItsOwner() throws Exception {
  admin();
  String token = auth.adminLogin("secure_operator", password).getToken();
  context.getBean(com.yoyi.pilates.member.application.MemberService.class).ensureMember(userId);
  jdbc.update("UPDATE `user` SET openid=NULL WHERE id=?", userId);
  long otherUser = newUser();
  var png = new ByteArrayOutputStream();
  ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
  var client = HttpClient.newHttpClient();
  var json = new com.fasterxml.jackson.databind.ObjectMapper();
  var avatar = uploadImage(client, token, "purpose=AVATAR&ownerId=" + userId, png.toByteArray());
  var otherAvatar = uploadImage(client, token, "purpose=AVATAR&ownerId=" + otherUser, png.toByteArray());
  var nonAvatar = uploadImage(client, token, "purpose=BODY_RECORD&ownerId=" + userId, png.toByteArray());
  var memberUri = URI.create("http://127.0.0.1:" + port + "/api/admin/members/" + userId);

  for (var upload : List.of(avatar, otherAvatar, nonAvatar)) {
   var request = HttpRequest.newBuilder(memberUri).header("Authorization", "Bearer " + token)
       .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
           json.writeValueAsString(Map.of("avatarUrl", upload.path("previewUrl").asText(), "reason", "测试会员头像")))).build();
   int code = json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("code").asInt(-1);
   assertEquals(upload == avatar ? 0 : 40000, code);
   assertEquals(avatar.path("url").asText(), users.selectById(userId).getAvatarUrl());
  }

  var read = HttpRequest.newBuilder(memberUri).header("Authorization", "Bearer " + token).GET().build();
  var result = json.readTree(client.send(read, HttpResponse.BodyHandlers.ofString()).body());
  assertEquals(0, result.path("code").asInt(-1));
  String preview = result.path("data").path("user").path("avatarUrl").asText();
  assertTrue(preview.startsWith("/media/access/" + avatar.path("objectId").asText() + "?"));
  var picture = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + preview)).GET().build(),
      HttpResponse.BodyHandlers.ofByteArray());
  assertEquals(200, picture.statusCode());
  assertArrayEquals(png.toByteArray(), picture.body());
 }

 private com.fasterxml.jackson.databind.JsonNode uploadImage(HttpClient client, String token, String query, byte[] image) throws Exception {
  String boundary = "pilates-" + UUID.randomUUID();
  var payload = new ByteArrayOutputStream();
  payload.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n")
      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  payload.write(image);
  payload.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var upload = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/upload?" + query))
      .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
      .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray())).build();
  var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(client.send(upload, HttpResponse.BodyHandlers.ofString()).body());
  assertEquals(0, result.path("code").asInt(-1), () -> result.path("message").asText());
  return result.path("data");
 }

}
