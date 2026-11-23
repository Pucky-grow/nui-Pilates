package com.yoyi.pilates.acceptance;

import com.yoyi.pilates.common.config.DevelopmentCapabilities;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.user.application.WxMpClient;
import com.yoyi.pilates.user.interfaces.PhoneController;
import com.yoyi.pilates.user.application.UserService;
import com.yoyi.pilates.trade.application.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Round3ConfigurationTest {
    DevelopmentCapabilities policy(String... profiles){var env=new MockEnvironment();env.setActiveProfiles(profiles);return new DevelopmentCapabilities(env);}
    @Test void productionTestUnknownAndMixedProfilesCannotEnableMocks(){
        for(String[] p:new String[][]{{"prod"},{"test"},{"prod","dev"},{"junit","prod"},{"unknown"},{}})assertFalse(policy(p).allowsMocks());
        assertTrue(policy("dev").allowsMocks());assertTrue(policy("junit").allowsMocks());
    }
    @Test void productionMockWechatCannotIssueAnIdentity(){
        var http=mock(RestTemplate.class);var redis=mock(StringRedisTemplate.class);var wx=new WxMpClient(http,redis,policy("prod"));ReflectionTestUtils.setField(wx,"mockLogin",true);
        assertThrows(BusinessException.class,()->wx.code2session("fixed"));assertThrows(BusinessException.class,()->wx.getPhoneByCode("fixed",null));verifyNoInteractions(http,redis);
    }
    @Test void missingWechatCredentialsFailClosedWithoutNetwork(){
        var http=mock(RestTemplate.class);var redis=mock(StringRedisTemplate.class);var wx=new WxMpClient(http,redis,policy("prod"));ReflectionTestUtils.setField(wx,"appid","");ReflectionTestUtils.setField(wx,"secret","");
        assertThrows(BusinessException.class,()->wx.code2session("code"));assertThrows(BusinessException.class,()->wx.getPhoneByCode("code",null));verifyNoInteractions(http,redis);
    }
    @Test void unconfiguredSmsCannotVerifyOrBindEvenWithAnOldCode(){
        var redis=mock(StringRedisTemplate.class);var users=mock(UserService.class);var phone=new PhoneController(redis,users,policy("prod"));ReflectionTestUtils.setField(phone,"mockEnabled",true);
        assertThrows(BusinessException.class,()->phone.bind(Map.of("phone","13000000000","code","123456")));verifyNoInteractions(redis,users);
    }
    @Test void productionMockPaymentRejectsBeforeReadingOrMutatingAnyOrder(){
        var service=new PaymentService(null,null,null,null,null,null,null,policy("prod"));ReflectionTestUtils.setField(service,"mockEnabled",true);
        assertThrows(BusinessException.class,()->service.mockPay(1L,1L));assertThrows(BusinessException.class,()->service.createPayParams(1L,1L));
    }
}
