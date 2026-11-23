package com.yoyi.pilates.common.config;
import org.springframework.boot.SpringApplication;import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;import org.springframework.core.env.ConfigurableEnvironment;
import java.util.*;
/** Runs after config loading and before any bean/data connection; errors never echo values. */
public final class ProductionEnvironmentGuard implements EnvironmentPostProcessor,Ordered {
 public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
 public void postProcessEnvironment(ConfigurableEnvironment env,SpringApplication app){validate(env);}
 public static void validate(ConfigurableEnvironment env){
  Set<String> profiles=new HashSet<>(Arrays.asList(env.getActiveProfiles()));
  if(Boolean.getBoolean("pilates.production.artifact") && (profiles.size()!=1 || (!profiles.contains("prod")&&!profiles.contains("staging"))))fail("production artifact profile");
  if(!profiles.contains("prod")&&!profiles.contains("staging"))return;
  CloudrunPrivateMysqlPolicy.validate(env);
  if(profiles.stream().anyMatch(p->!Set.of("prod","staging").contains(p))||profiles.size()!=1)fail("profile");
  String jwt=env.getProperty("yoyi.jwt.secret","");if(jwt.length()<48||jwt.toLowerCase(Locale.ROOT).matches(".*(please.change|dev.secret|example|changeme).*"))fail("JWT");
  for(String key:List.of("yoyi.wx.mock-login","yoyi.pay.mock-enabled","yoyi.pay.initiation-enabled","yoyi.pay.refund-enabled","yoyi.sms.mock-enabled","yoyi.sales.package-enabled","yoyi.wx.subscribe-enabled"))if(env.getProperty(key,Boolean.class,false))fail(key);
  if(!"outbox".equals(env.getProperty("yoyi.mq.type")))fail("event delivery");
  for(String key:List.of("spring.datasource.username","spring.datasource.password","spring.data.redis.password"))if(env.getProperty(key,"").isBlank())fail("credentials");
  if("root".equalsIgnoreCase(env.getProperty("spring.datasource.username")))fail("database root");
  if(!"never".equals(env.getProperty("spring.sql.init.mode")))fail("SQL initialization");
  if(env.getProperty("yoyi.wx.login-enabled",Boolean.class,true))for(String key:List.of("yoyi.wx.appid","yoyi.wx.secret"))if(env.getProperty(key,"").isBlank())fail("WeChat login credentials");
  if(env.getProperty("yoyi.wx.phone-enabled",Boolean.class,false)&&!env.getProperty("yoyi.wx.login-enabled",Boolean.class,true))fail("phone capability");
  String storage=env.getProperty("yoyi.storage.type","");
  if(!Set.of("oss","cos").contains(storage)&&!(profiles.contains("staging")&&storage.equals("local")&&env.getProperty("yoyi.storage.local-for-isolated-test",Boolean.class,false)))fail("private object storage");
  if(storage.equals("oss")){
   for(String key:List.of("yoyi.storage.oss.endpoint","yoyi.storage.oss.bucket"))if(env.getProperty(key,"").isBlank())fail("OSS configuration");
   if(env.getProperty("yoyi.storage.oss.role-name","").isBlank()&&(env.getProperty("yoyi.storage.oss.access-key-id","").isBlank()||env.getProperty("yoyi.storage.oss.access-key-secret","").isBlank()))fail("OSS credentials");
  }
  if(storage.equals("cos")){
   try{com.yoyi.pilates.infra.storage.CosStorageSettings.validate(env.getProperty("yoyi.storage.cos.region",""),env.getProperty("yoyi.storage.cos.bucket",""),env.getProperty("yoyi.storage.cos.secret-id",""),env.getProperty("yoyi.storage.cos.secret-key",""));}
   catch(IllegalArgumentException invalid){fail("COS configuration");}
  }
  String origin=env.getProperty("yoyi.public-base-url","");if(!origin.startsWith("https://")||origin.contains("your-domain")||origin.endsWith("/"))fail("public HTTPS origin");
  for(String cors:env.getProperty("yoyi.cors.allowed-origins","").split(","))if(!cors.isBlank()&&(!cors.startsWith("https://")||cors.contains("*")))fail("CORS");
  if(!"Asia/Shanghai".equals(System.getProperty("user.timezone")))fail("JVM timezone (use -Duser.timezone=Asia/Shanghai)");
 }
 private static void fail(String field){throw new IllegalStateException("Production startup rejected: "+field+". No configuration values emitted.");}
}
