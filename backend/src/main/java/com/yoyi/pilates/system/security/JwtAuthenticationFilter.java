package com.yoyi.pilates.system.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.yoyi.pilates.common.constant.RedisKeys;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器:
 * 1. 解析 Authorization: Bearer {token}
 * 2. 校验 JWT 签名与过期
 * 3. 校验 Redis 登录标记(支持登出吊销)
 * 4. 经 LoginUserLoader 加载角色/权限, 写入 SecurityContext
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "Authorization";
    public static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectProvider<LoginUserLoader> loaderProvider;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   StringRedisTemplate redisTemplate,
                                   ObjectProvider<LoginUserLoader> loaderProvider) {
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.loaderProvider = loaderProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (StringUtils.isNotBlank(header) && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            Claims claims = jwtService.parse(token);
            if (claims != null && Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.loginToken(token)))) {
                Long uid = claims.get("uid", Long.class);
                String type = claims.get("type", String.class);
                LoginUserLoader loader = loaderProvider.getIfAvailable();
                if (loader != null && uid != null && type != null) {
                    loader.load(uid, type).filter(user -> !user.isAdmin() || java.util.Objects.equals(
                        claims.get("av",Long.class)==null?0L:claims.get("av",Long.class),user.getAuthVersion()==null?0L:user.getAuthVersion())).ifPresent(this::setAuthentication);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void setAuthentication(LoginUser user) {
        var authorities = user.getRoles() == null ? Collections.<SimpleGrantedAuthority>emptyList()
                : user.getRoles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
