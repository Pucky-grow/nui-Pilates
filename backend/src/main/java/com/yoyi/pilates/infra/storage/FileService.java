package com.yoyi.pilates.infra.storage;
import com.yoyi.pilates.common.exception.BusinessException;import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.security.*;import com.yoyi.pilates.coach.application.CoachStudentService;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.multipart.MultipartFile;import javax.imageio.ImageIO;import javax.crypto.Mac;import javax.crypto.spec.SecretKeySpec;
import java.io.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;
@Service @RequiredArgsConstructor
public class FileService {
 private final ObjectStorage storage;private final JdbcTemplate jdbc;private final StringRedisTemplate redis;private final CoachStudentService students;
 @Value("${yoyi.jwt.secret}")private String signingKey;
 @Value("${yoyi.public-base-url:}")private String base;
 private static final String REF="/media/ref/";
 public Map<String,String> upload(MultipartFile file,String purpose,Long ownerId)throws IOException {
  LoginUser actor=SecurityUtils.requireUser();
  if(!Set.of("AVATAR","BODY_RECORD","PUBLIC_ASSET").contains(purpose))bad("上传用途无效");
  boolean publicAsset=purpose.equals("PUBLIC_ASSET");String ownerType=actor.getType();long owner=actor.getId();
  if(publicAsset){boolean management=actor.isAdmin()&&(actor.hasPerm("content:manage")||actor.hasPerm("course:update")||actor.hasPerm("coach:manage"));boolean proposal=actor.isCoach()&&actor.getCoachId()!=null&&actor.hasPerm("coach:course:proposal:create");if(!management&&!proposal)deny();}
  else if(ownerId!=null){if(!actor.isAdmin()||!actor.hasPerm("member:update"))deny();if(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=? AND deleted=0",Integer.class,ownerId)!=1)bad("会员不存在");ownerType="MP_USER";owner=ownerId;}
  Long count=redis.execute(new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],60) end;return n",Long.class),List.of("pilates:upload:rate:"+actor.getType()+":"+actor.getId()));
  if(count==null||count>20)bad("上传频繁，请稍后重试");
  if(jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM file_object WHERE uploader_type=? AND uploader_id=? AND created_at>=CURRENT_DATE",Long.class,actor.getType(),actor.getId())>100L*1024*1024)bad("今日上传额度已用完");
  if(file==null||file.isEmpty()||file.getSize()>5*1024*1024)bad("图片不能为空且须小于5MB");
  String original=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);String extension=original.contains(".")?original.substring(original.lastIndexOf('.')+1):"";
  Map<String,String> allowed=Map.of("jpg","jpeg","jpeg","jpeg","png","png","gif","gif");if(!allowed.containsKey(extension))bad("支持可解码的 JPG、PNG、GIF 图片");
  byte[] bytes=file.getBytes();String format;
  try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw BusinessException.of(ResultCode.BAD_REQUEST,"图片不能解码");var reader=readers.next();try{reader.setInput(input);format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!allowed.get(extension).equals(format)||reader.getWidth(0)>6000||reader.getHeight(0)>6000||(long)reader.getWidth(0)*reader.getHeight(0)>20000000)bad("图片类型或尺寸不符合要求");if(reader.read(0)==null)bad("图片不能解码");}finally{reader.dispose();}}
  String id=UUID.randomUUID().toString(),key="objects/"+LocalDate.now(ZoneId.of("Asia/Shanghai"))+"/"+id+"."+extension,type="image/"+format;
  storage.put(key,bytes,type);
  // If DB commit fails the unreferenced object is retained for reviewed inventory, never automatically deleted.
  jdbc.update("INSERT INTO file_object(public_id,object_key,owner_type,owner_id,uploader_type,uploader_id,purpose,visibility,content_type,byte_size,sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,key,ownerType,owner,actor.getType(),actor.getId(),purpose,publicAsset?"PUBLIC":"PRIVATE",type,bytes.length,sha256(bytes));
  return Map.of("url",REF+id,"objectId",id,"previewUrl",resolve(REF+id));
 }
 public Map<String,Object> record(String id){if(!id.matches("[a-f0-9-]{36}"))throw BusinessException.of(ResultCode.NOT_FOUND);var rows=jdbc.queryForList("SELECT * FROM file_object WHERE public_id=?",id);if(rows.isEmpty())throw BusinessException.of(ResultCode.NOT_FOUND);return rows.get(0);}
 public boolean canRead(Map<String,Object> row){
  if(row.get("visibility").equals("PUBLIC"))return true;
  var actor=SecurityUtils.current().orElse(null);if(actor==null)return false;
  long owner=((Number)row.get("owner_id")).longValue();String type=row.get("owner_type").toString();
  if(actor.getType().equals(type)&&actor.getId()==owner)return true;
  if(!actor.isAdmin()||!type.equals("MP_USER"))return false;
  if(actor.isAdminRole()||actor.hasPerm("member:view"))return true;
  return actor.isCoach()&&students.hasServed(actor.getCoachId(),owner);
 }
 public String resolve(String ref){
  if(ref==null||!ref.startsWith(REF))return ref;String id=ref.substring(REF.length());var row=record(id);
  if(row.get("visibility").equals("PUBLIC"))return base+"/media/public/"+id;
  if(!canRead(row))return "";
  long expires=Instant.now().getEpochSecond()+300;return base+"/media/access/"+id+"?expires="+expires+"&signature="+signature(id,expires);
 }
 public String normalize(String value){
  if(value==null)return null;
  var matcher=java.util.regex.Pattern.compile("^(?:https://[^/]+|http://[^/]+)?/media/(?:ref|access|public)/([a-f0-9-]{36})(?:\\?.*)?$").matcher(value);
  if(!matcher.matches())return value;String id=matcher.group(1);if(!canRead(record(id)))deny();return REF+id;
 }

 public String memberAvatar(String value, long userId) {
  if (value == null || value.length() > 512) bad("头像地址无效");
  if (value.startsWith(REF)) {
   var image = record(value.substring(REF.length()));
   if (!"AVATAR".equals(image.get("purpose")) || !"MP_USER".equals(image.get("owner_type"))
       || ((Number) image.get("owner_id")).longValue() != userId) {
    bad("请选择为该会员上传的头像");
   }
  } else if (!(value.isBlank() || value.startsWith("/uploads/") || value.startsWith("https://") || value.startsWith("http://"))) {
   bad("头像地址无效");
  }
  return value;
 }

 public byte[] content(String id,boolean publicOnly,long expires,String signature)throws IOException {
  var row=record(id);
  if(publicOnly&&!row.get("visibility").equals("PUBLIC"))deny();
  if(!publicOnly&&(expires<Instant.now().getEpochSecond()||expires>Instant.now().getEpochSecond()+301||!java.security.MessageDigest.isEqual(signature(id,expires).getBytes(StandardCharsets.US_ASCII),Objects.toString(signature,"").getBytes(StandardCharsets.US_ASCII))))deny();
  return storage.read(row.get("object_key").toString());
 }
 private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 private String signature(String id,long expiry){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("private-file:"+id+":"+expiry).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private void bad(String message){throw BusinessException.of(ResultCode.BAD_REQUEST,message);}private void deny(){throw BusinessException.of(ResultCode.FORBIDDEN);}
}
