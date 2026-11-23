package com.yoyi.pilates.system.security;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.JsonUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置:
 * - 无状态(JWT), 关闭 session/csrf
 * - 公开: 登录、微信回调、首页/课程/教练/文章等浏览接口、静态资源
 * - 其余接口必须登录; 接口级权限由 {@link RequiresPerm} 注解控制
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 登录与回调
                .requestMatchers("/api/auth/**", "/api/payment/wechat/callback").permitAll()
                // 公开浏览接口(首页/课程/教练/课包/文章/Banner/门店)
                .requestMatchers(HttpMethod.GET,
                        "/api/capabilities", "/api/home", "/api/booking-rules", "/api/courses/**", "/api/course-categories/**",
                        "/api/course-schedules/**", "/api/coaches/**", "/api/packages/**",
                        "/api/articles/**", "/api/article-categories/**",
                        "/api/banners/**", "/api/studio/**").permitAll()
                // 静态资源与健康检查
                .requestMatchers("/media/public/**", "/media/access/**", "/error", "/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus").permitAll()
                // 后台与小程序其余接口都需要登录
                .anyRequest().authenticated()
            )
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) -> writeJson(res, ResultCode.UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) -> writeJson(res, ResultCode.FORBIDDEN))
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, ResultCode rc) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtils.toJson(Result.error(rc)));
    }
}
