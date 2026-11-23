package com.yoyi.pilates.infra.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.COSSessionCredentials;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CosObjectStorageTest {
    private static final String BUCKET = "isolated-test-1250000000";
    private static final String OWNER = "qcs::cam::uin/100000000001:uin/100000000001";
    private static final String KEY = "objects/2026-11-01/synthetic.png";

    private static AccessControlList privateAcl() {
        var acl = new AccessControlList();
        acl.setOwner(new Owner(OWNER, "synthetic-owner"));
        acl.grantPermission(new UinGrantee(OWNER), Permission.FullControl);
        return acl;
    }

    private CosObjectStorage ready(COSClient client) throws IOException {
        when(client.getBucketAcl(BUCKET)).thenReturn(privateAcl());
        var storage = new CosObjectStorage(client, BUCKET);
        storage.verifyPrivateBucket();
        return storage;
    }

    @Test void rejectsMissingCredentialsOrEndpointLikeConfigurationWithoutEchoingValues() {
        for (String[] values : List.of(
                new String[]{"https://untrusted.invalid", BUCKET, "fixture-id", "fixture-key"},
                new String[]{"ap-shanghai", "wrong-bucket", "fixture-id", "fixture-key"},
                new String[]{"ap-shanghai", BUCKET, "", "fixture-key"},
                new String[]{"ap-shanghai", BUCKET, "fixture-id", ""})) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> CosStorageSettings.validate(values[0], values[1], values[2], values[3]));
            assertEquals("Invalid COS configuration; values withheld.", error.getMessage());
        }
    }

    @Test void productionTransportRequiresHttpsCertificateChecksAndBoundedTimeouts() {
        var config = CosObjectStorage.clientConfig("ap-shanghai");
        assertEquals(HttpProtocol.https, config.getHttpProtocol());
        assertTrue(config.isCheckSSLCertificate());
        assertTrue(config.getConnectionTimeout() <= 5000);
        assertTrue(config.getSocketTimeout() <= 5000);
        assertTrue(config.getRequestTimeOutEnable());
        assertTrue(config.getRequestTimeout() < 15000);
        assertFalse(CosObjectStorage.credentials("fixture-id", "fixture-key", "") instanceof COSSessionCredentials);
        var temporary = (COSSessionCredentials) CosObjectStorage.credentials("fixture-id", "fixture-key", "fixture-token");
        assertEquals("fixture-token", temporary.getSessionToken());
    }

    @Test void rejectsPublicReadWriteFullControlAndNonOwnerAclBeforeAnyObjectAccess() {
        for (Permission permission : List.of(Permission.Read, Permission.Write, Permission.FullControl)) {
            COSClient client = mock(COSClient.class);
            var acl = privateAcl();
            acl.grantPermission(GroupGrantee.AllUsers, permission);
            when(client.getBucketAcl(BUCKET)).thenReturn(acl);
            var storage = new CosObjectStorage(client, BUCKET);
            assertThrows(IOException.class, storage::verifyPrivateBucket);
            assertThrows(IOException.class, () -> storage.put(KEY, new byte[]{1}, "image/png"));
            verify(client).getBucketAcl(BUCKET);
            verify(client).shutdown();
            verifyNoMoreInteractions(client);
        }
        COSClient client = mock(COSClient.class);
        var acl = privateAcl();
        acl.grantPermission(new UinGrantee("qcs::cam::anyone:anyone"), Permission.FullControl);
        when(client.getBucketAcl(BUCKET)).thenReturn(acl);
        assertThrows(IOException.class, () -> new CosObjectStorage(client, BUCKET).verifyPrivateBucket());
    }

    @Test void failsClosedWhenAclCannotBeVerifiedAndDoesNotChainSdkMessages() {
        COSClient client = mock(COSClient.class);
        when(client.getBucketAcl(BUCKET)).thenThrow(new CosClientException("synthetic-signed-response-do-not-log"));
        var error = assertThrows(IOException.class, () -> new CosObjectStorage(client, BUCKET).verifyPrivateBucket());
        assertFalse(error.getMessage().contains("synthetic-signed-response"));
        assertNull(error.getCause());
        verify(client).shutdown();
    }

    @Test void writesOriginalImageAndMetadataWithoutAclMutationAndClosesReadStream() throws Exception {
        COSClient client = mock(COSClient.class);
        var storage = ready(client);
        byte[] image = {1, 2, 3, 4};
        doAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            assertEquals(BUCKET, request.getBucketName());
            assertEquals(KEY, request.getKey());
            assertEquals("image/png", request.getMetadata().getContentType());
            assertEquals(image.length, request.getMetadata().getContentLength());
            assertNull(request.getCannedAcl());
            assertNull(request.getAccessControlList());
            assertArrayEquals(image, request.getInputStream().readAllBytes());
            return new PutObjectResult();
        }).when(client).putObject(any(PutObjectRequest.class));
        class TrackedInput extends ByteArrayInputStream {
            boolean closed;
            TrackedInput() { super(image); }
            @Override public void close() { closed = true; }
        }
        var input = new TrackedInput();
        var object = new COSObject();
        object.setObjectContent(new COSObjectInputStream(input, new org.apache.http.client.methods.HttpGet()));
        when(client.getObject(BUCKET, KEY)).thenReturn(object);
        storage.put(KEY, image, "image/png");
        assertArrayEquals(image, storage.read(KEY));
        assertTrue(input.closed);
        storage.close();
        verify(client).getBucketAcl(BUCKET);
        verify(client).putObject(any(PutObjectRequest.class));
        verify(client).getObject(BUCKET, KEY);
        verify(client).shutdown();
        verifyNoMoreInteractions(client);
    }

    @Test void rejectsUnverifiedAccessPathEscapesAndOversizedImages() throws Exception {
        COSClient client = mock(COSClient.class);
        var unverified = new CosObjectStorage(client, BUCKET);
        assertThrows(IOException.class, () -> unverified.read(KEY));
        verifyNoInteractions(client);
        var storage = ready(client);
        for (String key : List.of("../secret", "objects/../secret", "/objects/x.png", "https://untrusted.invalid/x", "objects/..\\secret")) {
            assertThrows(IOException.class, () -> storage.read(key));
        }
        assertThrows(IOException.class, () -> storage.put(KEY, new byte[5 * 1024 * 1024 + 1], "image/png"));
        var object = new COSObject();
        object.setObjectContent(new ByteArrayInputStream(new byte[5 * 1024 * 1024 + 1]));
        when(client.getObject(BUCKET, KEY)).thenReturn(object);
        assertThrows(IOException.class, () -> storage.read(KEY));
    }

    @Test void sdkUploadAndReadFailuresAreSanitized() throws Exception {
        COSClient client = mock(COSClient.class);
        var storage = ready(client);
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(new CosClientException("synthetic-token-response"));
        when(client.getObject(BUCKET, KEY)).thenThrow(new CosClientException("synthetic-token-response"));
        var upload = assertThrows(IOException.class, () -> storage.put(KEY, new byte[]{1}, "image/png"));
        var download = assertThrows(IOException.class, () -> storage.read(KEY));
        for (var error : List.of(upload, download)) {
            assertFalse(error.getMessage().contains("synthetic-token-response"));
            assertNull(error.getCause());
        }
    }

    @Test void officialSdkSignsAclUploadAndDownloadAgainstIsolatedLoopbackHttpFixture() throws Exception {
        // This proves SDK/wire/dependency compatibility, not real COS or cloud TLS acceptance.
        var data = new AtomicReference<byte[]>();
        var failure = new AtomicReference<Throwable>();
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        // HttpCore emits COS-style canonical Content-Length; do not disable SDK length checks.
        var server = ServerBootstrap.bootstrap().setLocalAddress(InetAddress.getByName("127.0.0.1"))
                .setListenerPort(0).registerHandler("*", (request, httpResponse, context) -> {
            try {
                assertNotNull(request.getFirstHeader("Authorization"));
                assertEquals("fixture-token", request.getFirstHeader("x-cos-security-token").getValue());
                var uri = URI.create(request.getRequestLine().getUri());
                byte[] response;
                if ("acl".equals(uri.getQuery())) {
                    requests.add("ACL");
                    response = ("<AccessControlPolicy><Owner><ID>" + OWNER + "</ID></Owner><AccessControlList>"
                            + "<Grant><Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\"><ID>" + OWNER + "</ID></Grantee><Permission>FULL_CONTROL</Permission></Grant>"
                            + "</AccessControlList></AccessControlPolicy>").getBytes(StandardCharsets.UTF_8);
                    httpResponse.setHeader("Content-Type", "application/xml");
                } else if ("PUT".equals(request.getRequestLine().getMethod())) {
                    requests.add("PUT");
                    assertEquals("/" + KEY, uri.getPath());
                    assertNull(request.getFirstHeader("x-cos-acl"));
                    assertEquals("image/png", request.getFirstHeader("Content-Type").getValue());
                    data.set(EntityUtils.toByteArray(((HttpEntityEnclosingRequest) request).getEntity()));
                    httpResponse.setHeader("ETag", '"' + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data.get())) + '"');
                    response = new byte[0];
                } else {
                    requests.add("GET");
                    assertEquals("/" + KEY, uri.getPath());
                    response = data.get();
                    httpResponse.setHeader("Content-Type", "image/png");
                }
                httpResponse.setStatusCode(200);
                httpResponse.setEntity(new ByteArrayEntity(response));
            } catch (Throwable error) {
                failure.set(error);
                httpResponse.setStatusCode(500);
                httpResponse.setEntity(new ByteArrayEntity(new byte[0]));
            }
        }).create();
        server.start();
        var config = CosObjectStorage.clientConfig("ap-shanghai");
        config.setHttpProtocol(HttpProtocol.http); // Only the package-private injected test client uses loopback HTTP.
        String endpoint = "127.0.0.1:" + server.getLocalPort();
        config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(endpoint, endpoint));
        config.setMaxErrorRetry(0);
        var storage = new CosObjectStorage(new COSClient(CosObjectStorage.credentials("fixture-id", "fixture-key", "fixture-token"), config), BUCKET);
        try {
            storage.verifyPrivateBucket();
            byte[] image = {1, 2, 3, 4};
            storage.put(KEY, image, "image/png");
            assertArrayEquals(image, storage.read(KEY));
            assertNull(failure.get());
            assertEquals(List.of("ACL", "PUT", "GET"), requests);
        } finally {
            storage.close();
            server.shutdown(1, TimeUnit.SECONDS);
        }
    }
}
