package com.yoyi.pilates.infra.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.core.io.ClassPathResource;

/** Offline artifact check: uses the same classpath PEM loader as application Redis TLS. */
public final class RedisTrustCheck {
    private static final String RESOURCE = "certificates/tencent-redis-ca.pem";

    private RedisTrustCheck() { }

    public static String verify() throws Exception {
        byte[] content;
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            content = input.readAllBytes();
        }
        String pem = new String(content, StandardCharsets.US_ASCII);
        if (!pem.contains("BEGIN CERTIFICATE") || pem.contains("PRIVATE KEY")) {
            throw new IllegalStateException("Redis trust resource must contain public certificates only.");
        }
        var stores = new PemSslStoreBundle((PemSslStoreDetails) null,
                PemSslStoreDetails.forCertificate("classpath:" + RESOURCE));
        var bundle = SslBundle.of(stores);
        if (stores.getTrustStore().size() == 0
                || bundle.getManagers().getTrustManagerFactory().getTrustManagers().length == 0) {
            throw new IllegalStateException("Redis trust resource must create nonempty trust managers.");
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    public static void main(String[] args) throws Exception {
        String sha256 = verify();
        System.out.println("{\"status\":\"passed\",\"sha256\":\"" + sha256
                + "\",\"trustManagerCreated\":true,\"networkUsed\":false}");
    }
}
