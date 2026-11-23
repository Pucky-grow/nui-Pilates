package com.yoyi.pilates.user.application;

import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 微信小程序开放能力客户端:
 * - code2session 登录凭证校验
 * - 手机号换取(getPhoneNumber 新版 code → phone)
 * dev 环境 yoyi.wx.mock-login=true 时不请求微信服务器, code 直接作为 mock openid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxMpClient {

    private static final com.fasterxml.jackson.databind.ObjectReader LOGIN_RESPONSE_READER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .readerFor(new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final String CODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code";
    private static final String PHONE_URL =
            "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=%s";
    private static final String ACCESS_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
    private static final String ACCESS_TOKEN_CACHE = "pilates:wx:access_token";

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final com.yoyi.pilates.common.config.DevelopmentCapabilities capabilities;

    @Value("${yoyi.wx.appid:}")
    private String appid;
    @Value("${yoyi.wx.secret:}")
    private String secret;
    @Value("${yoyi.wx.mock-login:false}")
    private boolean mockLogin;

    /**
     * 登录凭证校验.
     *
     * @param code wx.login() 返回的 code
     * @return WxSession(openid, sessionKey, unionid)
     */
    @Value("${yoyi.wx.login-enabled:true}") private boolean loginEnabled=true;
    @Value("${yoyi.wx.phone-enabled:false}") private boolean phoneEnabled;

    public WxSession code2session(String code) {
        if(!loginEnabled) throw BusinessException.of(ResultCode.FORBIDDEN,"微信登录未配置");
        if (mockLogin) {
            capabilities.requireMock(true);
            // 开发模式: code 即 openid, 便于多用户模拟(如 "user_a"/"user_b")
            String openid = "mock_" + (code == null || code.isBlank() ? UUID.randomUUID() : code);
            WxSession session = new WxSession();
            session.setOpenid(openid);
            session.setSessionKey(null);
            cacheSession(session);
            return session;
        }
        if (appid.isBlank() || secret.isBlank()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "微信登录尚未配置");
        }
        if (code == null || code.isBlank()) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "登录凭证不能为空");
        }
        Map<String, Object> resp;
        try {
            // URI overload prevents an already-encoded code from being encoded twice.
            URI uri = URI.create(String.format(CODE2SESSION_URL,
                    encode(appid), encode(secret), encode(code)));
            // WeChat can return JSON as text/plain; parse the content, not the MIME label.
            String body = restTemplate.getForObject(uri, String.class);
            resp = parseLoginResponse(body);
        } catch (ResourceAccessException failure) {
            var reason = WxTransportFailure.describe(failure);
            log.warn("WECHAT_LOGIN_TRANSPORT category={} rootType={}", reason.category(), reason.rootType());
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "微信登录服务暂时不可用，请稍后重试");
        } catch (RestClientResponseException failure) {
            log.warn("WECHAT_LOGIN_HTTP status={}", failure.getStatusCode().value());
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "微信登录服务暂时不可用，请稍后重试");
        }
        String errorCode = safeErrorCode(resp);
        if (resp == null || !(resp.get("openid") instanceof String openid) || openid.isBlank()
                || !(resp.get("session_key") instanceof String key) || key.isBlank()
                || !(errorCode.equals("NONE") || errorCode.equals("0"))) {
            // Never log errmsg: provider messages and exception URLs may contain credentials.
            log.warn("WECHAT_LOGIN_REJECTED errcode={}", errorCode);
            if (java.util.Set.of("40013", "40125", "40164", "-1", "45011").contains(errorCode)) {
                throw BusinessException.of(ResultCode.SYSTEM_ERROR, "微信登录服务暂时不可用，请稍后重试");
            }
            throw BusinessException.of(ResultCode.UNAUTHORIZED, "微信登录失败, 请重试");
        }
        WxSession session = new WxSession();
        session.setOpenid((String) resp.get("openid"));
        session.setSessionKey((String) resp.get("session_key"));
        session.setUnionid((String) resp.get("unionid"));
        cacheSession(session);
        return session;
    }

    private Map<String, Object> parseLoginResponse(String body) {
        try {
            if (body == null || body.isBlank() || body.length() > 16384) {
                throw new IllegalArgumentException();
            }
            Map<String, Object> response = LOGIN_RESPONSE_READER.readValue(body);
            if (response == null) throw new IllegalArgumentException();
            return response;
        } catch (Exception failure) {
            // Parser exceptions may embed the body: never log or propagate their details.
            log.warn("WECHAT_LOGIN_RESPONSE category=INVALID_JSON");
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "微信登录服务暂时不可用，请稍后重试");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String safeErrorCode(Map<String, Object> response) {
        Object value = response == null ? null : response.get("errcode");
        if (value == null) return "NONE";
        String text = String.valueOf(value);
        return text.matches("-?\\d{1,8}") ? text : "INVALID";
    }

    /**
     * 手机号快速验证(新版 getPhoneNumber): code → phone.
     * mock 模式下优先返回明文 phone, 否则把 code 当手机号便于联调.
     */
    public String getPhoneByCode(String code, String plainPhoneIfMock) {
        if(!phoneEnabled && !mockLogin) throw BusinessException.of(ResultCode.FORBIDDEN,"微信手机号能力未启用");
        if (mockLogin) {
            capabilities.requireMock(true);
            return (plainPhoneIfMock == null || plainPhoneIfMock.isBlank()) ? code : plainPhoneIfMock;
        }
        if (appid.isBlank() || secret.isBlank()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "微信手机号验证尚未配置");
        }
        if (code == null || code.isBlank()) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "缺少手机号授权 code");
        }
        String accessToken = getAccessToken();
        Map<String, Object> body = Map.of("code", code);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.exchange(
                    String.format(PHONE_URL, accessToken), HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class).getBody();
            Object errcode = resp == null ? null : resp.get("errcode");
            if (errcode == null || !"0".equals(String.valueOf(errcode))) {
                log.warn("getPhoneNumber 业务失败，响应内容不记录");
                throw BusinessException.of(ResultCode.UNAUTHORIZED, "手机号获取失败, 请重试");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> phoneInfo = (Map<String, Object>) resp.get("phone_info");
            if (phoneInfo == null || phoneInfo.get("purePhoneNumber") == null) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "手机号解析失败, 请重试");
            }
            return (String) phoneInfo.get("purePhoneNumber");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("getPhoneNumber 失败 errorType={}",e.getClass().getSimpleName());
            throw BusinessException.of(ResultCode.UNAUTHORIZED, "手机号获取失败, 请重试");
        }
    }

    private String getAccessToken() {
        String cached = redisTemplate.opsForValue().get(ACCESS_TOKEN_CACHE);
        if (cached != null) {
            return cached;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restTemplate.getForObject(
                String.format(ACCESS_TOKEN_URL, appid, secret), Map.class);
        if (resp == null || resp.get("access_token") == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED, "获取微信 access_token 失败");
        }
        String token = (String) resp.get("access_token");
        redisTemplate.opsForValue().set(ACCESS_TOKEN_CACHE, token, Duration.ofSeconds(7000));
        return token;
    }

    private void cacheSession(WxSession session) {
        if (session.getSessionKey() != null) {
            redisTemplate.opsForValue().set(
                    RedisKeys.wxSession(session.getOpenid()), session.getSessionKey(), Duration.ofHours(2));
        }
    }

    @Data
    public static class WxSession {
        private String openid;
        private String sessionKey;
        private String unionid;
    }
}
