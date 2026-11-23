package com.yoyi.pilates.notify.application;

import com.yoyi.pilates.common.util.JsonUtils;
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

import java.time.Duration;
import java.util.Map;

/**
 * 微信订阅消息客户端:
 * - 获取/缓存 access_token(Redis 7000s)
 * - 发送订阅消息(每位用户每次订阅授权仅一次, 由前端 collect 授权计数)
 * mock 模式下仅打印日志(不请求微信), 保证开发闭环可运行.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxSubscribeMsgClient {

    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
    private static final String SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=%s";
    private static final String TOKEN_CACHE = "pilates:wx:access_token";

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;

    @Value("${yoyi.wx.appid:}")
    private String appid;
    @Value("${yoyi.wx.secret:}")
    private String secret;
    @Value("${yoyi.wx.mock-login:true}")
    private boolean mockLogin;

    public void send(String openid, String templateId, Map<String, String> vars, String page) {
        throw new IllegalStateException("WeChat delivery is disabled until tested subscription consent and templates are configured");
    }

    private void legacySend(String openid, String templateId, Map<String, String> vars, String page) {
        if (mockLogin) {
            log.debug("WeChat subscription mock is disabled");
            return;
        }
        String accessToken = getAccessToken();
        Map<String, Object> body = Map.of(
                "touser", openid,
                "template_id", templateId,
                "page", page == null ? "" : page,
                "data", vars);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate.exchange(
                    String.format(SEND_URL, accessToken), HttpMethod.POST,
                    new HttpEntity<>(JsonUtils.toJson(body), headers), String.class);
            log.debug("Legacy subscription request completed");
        } catch (Exception e) {
            throw new IllegalStateException("WeChat transport failure");
        }
    }

    private String getAccessToken() {
        String cached = redisTemplate.opsForValue().get(TOKEN_CACHE);
        if (cached != null) {
            return cached;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restTemplate.getForObject(
                String.format(TOKEN_URL, appid, secret), Map.class);
        if (resp == null || resp.get("access_token") == null) {
            throw new IllegalStateException("获取微信 access_token 失败");
        }
        String token = (String) resp.get("access_token");
        redisTemplate.opsForValue().set(TOKEN_CACHE, token, Duration.ofSeconds(7000));
        return token;
    }
}
