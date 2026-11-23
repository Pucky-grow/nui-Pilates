package com.yoyi.pilates.infra.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.SslVerificationMode;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;

class RedissonTlsTest {
    @Test void bundledPublicTencentCaLoadsThroughTheApplicationPemTrustStore() throws Exception {
        assertEquals("620fec36c5647475f0be9c51afe21d05d88ccf61cfadc578ceb069d8def97132",
                com.yoyi.pilates.infra.tools.RedisTrustCheck.verify());
    }

    @Test void tlsAndAclIdentityApplyToBookingLocksWithoutDisablingEndpointVerification() {
        var bundle = SslBundle.systemDefault();
        var config = RedissonConfig.connectionConfig("10.60.1.2", 6379, "runtime", "synthetic", 0, true, bundle).useSingleServer();
        assertEquals("rediss://10.60.1.2:6379", config.getAddress());
        assertEquals("runtime", config.getUsername());
        assertEquals(SslVerificationMode.STRICT, config.getSslVerificationMode());
        assertSame(bundle.getManagers().getTrustManagerFactory(), config.getSslTrustManagerFactory());
        assertThrows(IllegalStateException.class, () -> RedissonConfig.connectionConfig("localhost", 6379, "", "", 0, false, bundle));
    }

    @Test void localDevelopmentStillAllowsAnUnconfiguredPasswordWithoutSendingEmptyAuth() {
        var config = RedissonConfig.connectionConfig("127.0.0.1", 6379, "", "", 0, false, null).useSingleServer();
        assertEquals("redis://127.0.0.1:6379", config.getAddress());
        assertNull(config.getPassword());
        assertNull(config.getUsername());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="PILATES_REDIS_TLS_FIXTURE", matches="true")
    void realTlsPingLocksAndWrongAddressOrUntrustedCaRejection() throws Exception {
        String host = "127.0.0.1", password = System.getenv("PILATES_REDIS_TLS_PASSWORD");
        int port = Integer.parseInt(System.getenv("PILATES_REDIS_TLS_PORT"));
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        try (InputStream stream = Files.newInputStream(Path.of(System.getenv("PILATES_REDIS_TLS_CA")))) {
            int i=0;
            for (var cert:CertificateFactory.getInstance("X.509").generateCertificates(stream)) trust.setCertificateEntry("ca-"+(i++), cert);
        }
        var bundle = SslBundle.of(SslStoreBundle.of(null, null, trust));
        var options = ClientOptions.builder().sslOptions(SslOptions.builder().trustManager(bundle.getManagers().getTrustManagerFactory()).build()).build();
        var standalone = new RedisStandaloneConfiguration(host, port); standalone.setPassword(RedisPassword.of(password));
        var lettuce = new LettuceConnectionFactory(standalone, LettuceClientConfiguration.builder().clientOptions(options)
                .commandTimeout(Duration.ofSeconds(3)).useSsl().and().build());
        lettuce.afterPropertiesSet(); lettuce.start();
        try (var connection = lettuce.getConnection()) { assertEquals("PONG", connection.ping()); }
        finally { lettuce.destroy(); }
        var client = Redisson.create(RedissonConfig.connectionConfig(host, port, "", password, 0, true, bundle));
        String key = "acceptance:tls:" + java.util.UUID.randomUUID();
        try {
            var lock = client.getLock(key); assertTrue(lock.tryLock(2, 10, java.util.concurrent.TimeUnit.SECONDS));
            try { assertTrue(lock.isHeldByCurrentThread()); } finally { lock.unlock(); }
            assertFalse(lock.isLocked());
        } finally { client.shutdown(); }
        // Fixture certificate contains IP:127.0.0.1 only; localhost is deliberately not in SAN.
        assertThrows(Exception.class, () -> {
            var rejected = Redisson.create(RedissonConfig.connectionConfig("localhost", port, "", password, 0, true, bundle));
            rejected.shutdown(); fail("mismatched certificate must fail");
        });
        assertThrows(Exception.class, () -> {
            var rejected = Redisson.create(RedissonConfig.connectionConfig(host, port, "", password, 0, true, SslBundle.systemDefault()));
            rejected.shutdown(); fail("untrusted certificate must fail");
        });
    }
}
