package com.yoyi.pilates.system.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 签发与解析. 仅存放身份信息(uid/type), 权限每次请求实时加载;
 * 登录态吊销依赖 Redis 登录标记(见 JwtAuthenticationFilter).
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long mpTtl;
    private final long adminTtl;

    public JwtService(@Value("${yoyi.jwt.secret}") String secret,
                      @Value("${yoyi.jwt.mp-ttl}") long mpTtl,
                      @Value("${yoyi.jwt.admin-ttl}") long adminTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.mpTtl = mpTtl;
        this.adminTtl = adminTtl;
    }

    public String createToken(Long userId, String type) {
        return createToken(userId,type,0L);
    }
    public String createToken(Long userId,String type,Long authVersion) {
        long ttl = "ADMIN".equals(type) ? adminTtl : mpTtl;
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("type", type);
        claims.put("av",authVersion==null?0:authVersion);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttl))
                .signWith(key)
                .compact();
    }

    /** @return Claims(uid, type); 解析失败返回 null */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    public long ttlOf(String type) {
        return "ADMIN".equals(type) ? adminTtl : mpTtl;
    }
}
