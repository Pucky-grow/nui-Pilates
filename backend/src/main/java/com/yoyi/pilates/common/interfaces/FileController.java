package com.yoyi.pilates.common.interfaces;
import com.yoyi.pilates.common.result.Result;import com.yoyi.pilates.infra.storage.FileService;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;import java.io.IOException;import java.util.Map;
@RestController @RequiredArgsConstructor
public class FileController {
 private final FileService files;
 @PostMapping("/api/upload") public Result<Map<String,String>> upload(@RequestParam("file")MultipartFile file,@RequestParam(defaultValue="AVATAR")String purpose,@RequestParam(required=false)Long ownerId)throws IOException{return Result.ok(files.upload(file,purpose,ownerId));}
 @GetMapping("/api/files/{id}/access") public Result<Map<String,String>> access(@PathVariable String id){var r=files.record(id);if(!files.canRead(r))throw com.yoyi.pilates.common.exception.BusinessException.of(com.yoyi.pilates.common.result.ResultCode.FORBIDDEN);return Result.ok(Map.of("url",files.resolve("/media/ref/"+id)));}
 @GetMapping("/media/public/{id}")public ResponseEntity<byte[]> publicImage(@PathVariable String id)throws IOException{return image(id,true,0,null);}
 @GetMapping("/media/access/{id}")public ResponseEntity<byte[]> privateImage(@PathVariable String id,@RequestParam long expires,@RequestParam String signature)throws IOException{return image(id,false,expires,signature);}
 private ResponseEntity<byte[]> image(String id,boolean publicOnly,long expiry,String signature)throws IOException{byte[] bytes=files.content(id,publicOnly,expiry,signature);return ResponseEntity.ok().contentType(MediaType.parseMediaType(files.record(id).get("content_type").toString())).cacheControl(publicOnly?CacheControl.maxAge(java.time.Duration.ofHours(1)):CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(bytes);}
}
