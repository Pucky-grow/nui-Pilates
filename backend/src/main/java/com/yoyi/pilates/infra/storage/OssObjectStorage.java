package com.yoyi.pilates.infra.storage;
import com.aliyun.oss.*;import com.aliyun.oss.common.auth.*;import com.aliyun.oss.model.ObjectMetadata;
import java.io.*;import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
/** Private bucket. Public material is deliberately proxied; no bucket ACL is ever changed. */
@Component @ConditionalOnProperty(name="yoyi.storage.type",havingValue="oss")
public class OssObjectStorage implements ObjectStorage {
 private final OSS client;private final String bucket;
 public OssObjectStorage(@Value("${yoyi.storage.oss.endpoint}")String endpoint,@Value("${yoyi.storage.oss.bucket}")String bucket,
 @Value("${yoyi.storage.oss.role-name:}")String role,@Value("${yoyi.storage.oss.access-key-id:}")String id,
 @Value("${yoyi.storage.oss.access-key-secret:}")String secret,@Value("${yoyi.storage.oss.security-token:}")String token){
  this.bucket=bucket;
  if(!endpoint.startsWith("https://"))throw new IllegalArgumentException("OSS requires HTTPS");
  CredentialsProvider provider=!role.isBlank()?new InstanceProfileCredentialsProvider(role):new DefaultCredentialProvider(id,secret,token.isBlank()?null:token);
  var config=new ClientBuilderConfiguration();config.setConnectionTimeout(5000);config.setSocketTimeout(10000);config.setMaxErrorRetry(1);
  client=new OSSClientBuilder().build(endpoint,provider,config);
 }
 public void put(String key,byte[] bytes,String type){var metadata=new ObjectMetadata();metadata.setContentType(type);metadata.setContentLength(bytes.length);client.putObject(bucket,key,new ByteArrayInputStream(bytes),metadata);}
 public byte[] read(String key)throws IOException{try(var object=client.getObject(bucket,key);var stream=object.getObjectContent()){return stream.readAllBytes();}}
 @PreDestroy public void close(){client.shutdown();}
}
