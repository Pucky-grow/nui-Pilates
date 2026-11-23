package com.yoyi.pilates.user.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yoyi.pilates.common.config.DevelopmentCapabilities;
import com.yoyi.pilates.common.exception.BusinessException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathBuilderException;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WxMpClientTest {
    private static final String PRIVATE = "synthetic-sensitive-do-not-log";
    private RestTemplate http;
    private StringRedisTemplate redis;
    private WxMpClient client;

    @BeforeEach void setUp() {
        http = mock(RestTemplate.class);
        redis = mock(StringRedisTemplate.class);
        var env = new MockEnvironment(); env.setActiveProfiles("staging");
        client = new WxMpClient(http, redis, new DevelopmentCapabilities(env));
        ReflectionTestUtils.setField(client, "appid", "synthetic-app");
        ReflectionTestUtils.setField(client, "secret", PRIVATE);
    }

    @Test void encodedCodeCannotInjectAnotherQueryParameterAndSuccessfulSessionIsCached() {
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(http.getForObject(any(URI.class), eq(String.class))).thenReturn(
                com.yoyi.pilates.common.util.JsonUtils.toJson(Map.of("openid", "synthetic-member", "session_key", "synthetic-session")));
        String code = "a+b&secret=injected/=?#";
        assertEquals("synthetic-member", client.code2session(code).getOpenid());
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).getForObject(uri.capture(), eq(String.class));
        String[] params = uri.getValue().getRawQuery().split("&");
        assertEquals(4, params.length);
        assertEquals(code, URLDecoder.decode(params[2].substring("js_code=".length()), StandardCharsets.UTF_8));
        assertNull(uri.getValue().getFragment());
        verify(values).set(anyString(), eq("synthetic-session"), any(java.time.Duration.class));
    }

    @Test void transportFailuresAreClassifiedWithoutLoggingSecretsOrCachingAnIdentity() {
        var handshake = new SSLHandshakeException(PRIVATE);
        handshake.initCause(new CertPathBuilderException(PRIVATE));
        for (var failure : new java.io.IOException[]{new UnknownHostException(PRIVATE), handshake,
                new SocketTimeoutException(PRIVATE), new ConnectException(PRIVATE)}) {
            var logger = (Logger) LoggerFactory.getLogger(WxMpClient.class);
            var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
            try {
                reset(http);
                when(http.getForObject(any(URI.class), eq(String.class))).thenThrow(new ResourceAccessException(PRIVATE, failure));
                var error = assertThrows(BusinessException.class, () -> client.code2session(PRIVATE));
                assertEquals(50000, error.getCode());
                assertFalse(error.getMessage().contains(PRIVATE));
                assertEquals(1, capture.list.size());
                assertTrue(capture.list.get(0).getFormattedMessage().startsWith("WECHAT_LOGIN_TRANSPORT category="));
                assertFalse(capture.list.get(0).getFormattedMessage().contains(PRIVATE));
                assertNull(capture.list.get(0).getThrowableProxy());
                verifyNoInteractions(redis);
            } finally { logger.detachAppender(capture); capture.stop(); }
        }
    }

    @Test void tlsCertificateFailureIsDistinguishedFromDnsAndTimeout() {
        var handshake = new SSLHandshakeException(PRIVATE);
        handshake.initCause(new CertPathBuilderException(PRIVATE));
        assertEquals("TLS_CERTIFICATE", WxTransportFailure.describe(handshake).category());
        assertEquals("DNS", WxTransportFailure.describe(new UnknownHostException(PRIVATE)).category());
        assertEquals("TIMEOUT", WxTransportFailure.describe(new SocketTimeoutException(PRIVATE)).category());
    }

    @Test void httpGatewayFailureDoesNotLogUrlBodyOrSessionData() {
        when(http.getForObject(any(URI.class), eq(String.class))).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, PRIVATE, HttpHeaders.EMPTY, PRIVATE.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals(50000, assertThrows(BusinessException.class, () -> client.code2session(PRIVATE)).getCode());
        verifyNoInteractions(redis);
    }

    @Test void invalidCodeAndMalformedSessionsCannotIssueAnIdentity() {
        for (var response : java.util.List.of(Map.of("errcode", 40029, "errmsg", PRIVATE),
                Map.of("openid", "synthetic-member"), Map.of("openid", "", "session_key", PRIVATE),
                Map.of("openid", "synthetic-member", "session_key", PRIVATE, "errcode", 40029))) {
            when(http.getForObject(any(URI.class), eq(String.class))).thenReturn(com.yoyi.pilates.common.util.JsonUtils.toJson(response));
            assertEquals(40100, assertThrows(BusinessException.class, () -> client.code2session(PRIVATE)).getCode());
        }
        verifyNoInteractions(redis);
    }

    @Test void wrongAppCredentialsAreReportedAsServiceFailureRatherThanMemberExpiry() {
        when(http.getForObject(any(URI.class), eq(String.class))).thenReturn(com.yoyi.pilates.common.util.JsonUtils.toJson(Map.of("errcode", 40013, "errmsg", PRIVATE)));
        assertEquals(50000, assertThrows(BusinessException.class, () -> client.code2session(PRIVATE)).getCode());
        verifyNoInteractions(redis);
    }
    @Test void actualMessageConvertersAcceptPlainTextAndJsonWechatResponses() {
        for (String mime : java.util.List.of("text/plain;charset=UTF-8", "application/json")) {
            RestTemplate actualHttp = new com.yoyi.pilates.infra.config.RestClientConfig().restTemplate();
            var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(actualHttp).build();
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"openid\":\"synthetic-member\",\"session_key\":\"synthetic-session\"}",
                            org.springframework.http.MediaType.parseMediaType(mime)));
            @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            var actualClient = new WxMpClient(actualHttp, redis, new DevelopmentCapabilities(new MockEnvironment()));
            ReflectionTestUtils.setField(actualClient, "appid", "synthetic-app");
            ReflectionTestUtils.setField(actualClient, "secret", PRIVATE);
            assertEquals("synthetic-member", actualClient.code2session("synthetic-code").getOpenid());
            server.verify();
        }
    }

    @Test void nonJsonAndTrailingContentNeverCacheIdentityOrLeakResponse() {
        var logger = (Logger) LoggerFactory.getLogger(WxMpClient.class);
        var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
        try {
            for (String body : java.util.List.of("<html>"+PRIVATE+"</html>", "[]", "null", " ",
                    "{\"openid\":\"synthetic-member\",\"session_key\":\""+PRIVATE+"\"} {}", "x".repeat(16385))) {
                when(http.getForObject(any(URI.class), eq(String.class))).thenReturn(body);
                assertEquals(50000, assertThrows(BusinessException.class, () -> client.code2session(PRIVATE)).getCode());
            }
            verifyNoInteractions(redis);
            assertTrue(capture.list.stream().allMatch(event -> !event.getFormattedMessage().contains(PRIVATE)
                    && event.getThrowableProxy() == null));
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

}
