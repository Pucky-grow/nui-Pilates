package com.yoyi.pilates;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 优仪普拉提后端服务入口.
 *
 * <p>模块化单体 + DDD 分层: interfaces → application → domain → infrastructure.</p>
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.yoyi.pilates.**.infrastructure.mapper")
public class PilatesApplication {

    public static void main(String[] args) {
        SpringApplication.run(PilatesApplication.class, args);
    }
}
