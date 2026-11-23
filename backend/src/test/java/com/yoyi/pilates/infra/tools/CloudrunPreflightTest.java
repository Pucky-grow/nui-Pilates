package com.yoyi.pilates.infra.tools;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudrunPreflightTest {
    @Test void servesOnlyPreflightAndNeverClaimsBusinessReady() throws Exception {
        AtomicBoolean ready = new AtomicBoolean(false);
        var server = CloudrunPreflight.startServer(new InetSocketAddress("127.0.0.1", 0), ready);
        var client = HttpClient.newHttpClient();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String path : new String[]{"/api/auth/wx-login", "/api/bookings", "/api/upload", "/health/other"}) assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(origin + path)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(503, client.send(HttpRequest.newBuilder(URI.create(origin + "/ready")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            ready.set(true);
            var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/ready")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); assertTrue(response.body().contains("\"businessReady\":false")); assertTrue(response.body().contains("\"mysqlTls\":false"));
            assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(origin + "/ready")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { server.stop(0); }
    }
}
