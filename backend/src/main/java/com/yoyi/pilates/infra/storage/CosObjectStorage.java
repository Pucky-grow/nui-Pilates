package com.yoyi.pilates.infra.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Backend-only private COS. FileService remains responsible for identity and signed proxy URLs. */
@Component
@ConditionalOnProperty(name = "yoyi.storage.type", havingValue = "cos")
public class CosObjectStorage implements ObjectStorage {
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private final COSClient client;
    private final String bucket;
    private boolean privateBucketVerified;

    @Autowired
    public CosObjectStorage(@Value("${yoyi.storage.cos.region:}") String region,
            @Value("${yoyi.storage.cos.bucket:}") String bucket,
            @Value("${yoyi.storage.cos.secret-id:}") String secretId,
            @Value("${yoyi.storage.cos.secret-key:}") String secretKey,
            @Value("${yoyi.storage.cos.session-token:}") String sessionToken) {
        CosStorageSettings.validate(region, bucket, secretId, secretKey);
        this.bucket = bucket;
        this.client = new COSClient(credentials(secretId, secretKey, sessionToken), clientConfig(region));
    }

    CosObjectStorage(COSClient client, String bucket) {
        this.client = Objects.requireNonNull(client);
        this.bucket = bucket;
    }

    static COSCredentials credentials(String id, String key, String token) {
        return token == null || token.isBlank() ? new BasicCOSCredentials(id, key)
                : new BasicSessionCredentials(id, key, token);
    }

    static ClientConfig clientConfig(String region) {
        var config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        config.setCheckSSLCertificate(true);
        config.setConnectionRequestTimeout(3000);
        config.setConnectionTimeout(3000);
        config.setSocketTimeout(5000);
        config.setMaxErrorRetry(1);
        config.setMaxConnectionsCount(32);
        config.setRequestTimeOutEnable(true);
        config.setRequestTimeout(10000);
        config.setPrintShutdownStackTrace(false);
        return config;
    }

    @PostConstruct
    public void verifyPrivateBucket() throws IOException {
        try {
            var acl = client.getBucketAcl(bucket);
            String owner = acl == null || acl.getOwner() == null ? null : acl.getOwner().getId();
            // Require owner-only grants, including rejection of anonymous FULL_CONTROL/unknown groups.
            if (owner == null || owner.isBlank() || acl.getGrantsAsList().isEmpty()
                    || acl.getGrantsAsList().stream().anyMatch(grant -> grant.getGrantee() == null
                    || !owner.equals(grant.getGrantee().getIdentifier()))) {
                throw new IOException("COS requires an owner-only private bucket ACL; values withheld.");
            }
            privateBucketVerified = true;
        } catch (RuntimeException error) {
            throw failure("bucket ACL verification");
        } finally {
            if (!privateBucketVerified) close();
        }
    }

    @Override
    public void put(String key, byte[] data, String type) throws IOException {
        requireReadyKey(key);
        if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            throw new IOException("COS image size rejected.");
        }
        var metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType(type);
        // No public ACL, bucket changes or direct COS URL. New objects use the private bucket policy.
        try (var stream = new ByteArrayInputStream(data)) {
            client.putObject(new PutObjectRequest(bucket, key, stream, metadata));
        } catch (RuntimeException error) {
            throw failure("upload");
        }
    }

    @Override
    public byte[] read(String key) throws IOException {
        requireReadyKey(key);
        try (var object = client.getObject(bucket, key)) {
            var stream = object.getObjectContent();
            byte[] bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("COS image size rejected.");
            return bytes;
        } catch (IOException | RuntimeException error) {
            throw failure("download");
        }
    }

    private void requireReadyKey(String key) throws IOException {
        if (!privateBucketVerified) throw failure("private bucket verification required");
        if (key == null || key.length() > 512 || !key.matches("objects/[A-Za-z0-9/._-]+")
                || Arrays.asList(key.split("/", -1)).contains("..")) {
            throw new IOException("COS object key rejected.");
        }
    }

    private static IOException failure(String operation) {
        // SDK exceptions can contain signed URLs/identifiers; deliberately do not chain the raw cause.
        return new IOException("COS " + operation + " failed; configuration and response values withheld.");
    }

    @PreDestroy
    public void close() { client.shutdown(); }
}
