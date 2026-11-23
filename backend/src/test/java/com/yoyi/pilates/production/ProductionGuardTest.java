package com.yoyi.pilates.production;
import com.yoyi.pilates.common.config.ProductionEnvironmentGuard;import com.yoyi.pilates.common.util.IpUtils;
import org.springframework.mock.env.MockEnvironment;import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
class ProductionGuardTest {
 @Test void productionArtifactRejectsDevTestAndMissingProfile(){
  String previous=System.getProperty("pilates.production.artifact");
  try{System.setProperty("pilates.production.artifact","true");
   for(String profile:new String[]{"dev","test","junit","unknown"}){var environment=new MockEnvironment();environment.setActiveProfiles(profile);assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(environment));}
   assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(new MockEnvironment()));
  }finally{if(previous==null)System.clearProperty("pilates.production.artifact");else System.setProperty("pilates.production.artifact",previous);}
 }
 MockEnvironment valid(){var env=new MockEnvironment();env.setActiveProfiles("staging");return env.withProperty("yoyi.jwt.secret",java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID()).withProperty("yoyi.mq.type","outbox").withProperty("spring.datasource.username","scoped_runtime").withProperty("spring.datasource.password","synthetic-test-placeholder").withProperty("spring.data.redis.password","synthetic-test-placeholder").withProperty("spring.sql.init.mode","never").withProperty("yoyi.wx.login-enabled","false").withProperty("yoyi.storage.type","local").withProperty("yoyi.storage.local-for-isolated-test","true").withProperty("yoyi.public-base-url","https://isolated.example.test");}
 MockEnvironment validCos(){return valid().withProperty("yoyi.storage.type","cos").withProperty("yoyi.storage.cos.region","ap-shanghai").withProperty("yoyi.storage.cos.bucket","isolated-test-1250000000").withProperty("yoyi.storage.cos.secret-id","synthetic-id").withProperty("yoyi.storage.cos.secret-key","synthetic-key");}
 @Test void cosRequiresExplicitConfigurationAndDoesNotPermitLocalProductionFallback(){
  String zone=System.getProperty("user.timezone");
  try{System.setProperty("user.timezone","Asia/Shanghai");
   ProductionEnvironmentGuard.validate(validCos());
   var production=validCos();production.setActiveProfiles("prod");ProductionEnvironmentGuard.validate(production);
   for(String key:List.of("region","bucket","secret-id","secret-key"))assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(validCos().withProperty("yoyi.storage.cos."+key,"")));
   assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(validCos().withProperty("yoyi.storage.cos.region","https://untrusted.invalid")));
   var local=valid();local.setActiveProfiles("prod");assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(local));
   assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(validCos().withProperty("yoyi.wx.mock-login","true")));
  }finally{if(zone==null)System.clearProperty("user.timezone");else System.setProperty("user.timezone",zone);}
 }
 @Test void productionGuardRejectsMocksRootDefaultSecretsMixedProfilesAndEnabledUnconfiguredChannels(){String zone=System.getProperty("user.timezone");try{System.setProperty("user.timezone","Asia/Shanghai");ProductionEnvironmentGuard.validate(valid());for(String key:new String[]{"yoyi.wx.mock-login","yoyi.pay.mock-enabled","yoyi.sms.mock-enabled","yoyi.sales.package-enabled","yoyi.wx.subscribe-enabled","yoyi.wx.login-enabled"})assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(valid().withProperty(key,"true")));assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(valid().withProperty("spring.datasource.username","root")));assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(valid().withProperty("yoyi.jwt.secret","yoyi-pilates-dev-secret-key-please-change-in-prod-0123456789abcdef")));var mixed=valid();mixed.setActiveProfiles("staging","dev");assertThrows(IllegalStateException.class,()->ProductionEnvironmentGuard.validate(mixed));}finally{if(zone==null)System.clearProperty("user.timezone");else System.setProperty("user.timezone",zone);}}
 @Test void forwardedHeadersAreIgnoredExceptAnExplicitTrustedPeer(){
  var request=new MockHttpServletRequest();request.setRemoteAddr("203.0.113.20");request.addHeader("X-Real-IP","198.51.100.11");request.addHeader("X-Forwarded-For","198.51.100.12");IpUtils.configureTrustedProxies("");assertEquals("203.0.113.20",IpUtils.getClientIp(request));IpUtils.configureTrustedProxies("203.0.113.20/32");assertEquals("198.51.100.11",IpUtils.getClientIp(request));IpUtils.configureTrustedProxies("");
 }
}
