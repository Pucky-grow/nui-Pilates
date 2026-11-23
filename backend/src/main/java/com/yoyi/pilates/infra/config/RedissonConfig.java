package com.yoyi.pilates.infra.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SslVerificationMode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

/**
 * Redisson 客户端显式装配.
 *
 * <p>原因: redisson-spring-boot-starter 会把 {@code spring.data.redis.password} 的空字符串
 * 当作"有密码"而发送 {@code AUTH ""}, 与 spring-data-redis(空串=无密码) 行为不一致,
 * 导致本机无密码 Redis 连接失败. 这里仅当密码非空时才设置, 二者行为对齐.</p>
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean tls,
            @Value("${spring.data.redis.ssl.bundle:}") String bundleName,
            ObjectProvider<SslBundles> bundles) {
        SslBundle bundle = StringUtils.isBlank(bundleName) ? null : bundles.getObject().getBundle(bundleName);
        return Redisson.create(connectionConfig(host, port, username, password, database, tls, bundle));
    }

    // Both RedisTemplate and booking locks use the same CA bundle and server address.
    static Config connectionConfig(String host, int port, String username, String password,
            int database, boolean tls, SslBundle bundle) {
        if (!tls && bundle != null) throw new IllegalStateException("Redis CA bundle requires TLS.");
        Config config = new Config();
        config.useSingleServer()
                .setAddress((tls ? "rediss://" : "redis://") + host + ":" + port)
                .setDatabase(database)
                .setSslVerificationMode(SslVerificationMode.STRICT)
                .setConnectTimeout(3000)
                .setTimeout(3000)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        if (tls && bundle != null) {
            config.useSingleServer().setSslTrustManagerFactory(bundle.getManagers().getTrustManagerFactory());
        }
        if (StringUtils.isNotBlank(username)) config.useSingleServer().setUsername(username);
        if (StringUtils.isNotBlank(password)) {
            config.useSingleServer().setPassword(password);
        }
        return config;
    }
}
