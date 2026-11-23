package com.yoyi.pilates.infra.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/** Offline security boundaries only. Never constructs a COS SDK client or connects to a network. */
public final class CosConnectionProbeOfflineTest {
    private static final String BUCKET = "synthetic-probe-1500000000";
    private static int checks;

    public static void main(String[] ignored) throws Exception {
        Path directory = Files.createTempDirectory("cos-probe-offline-");
        Path config = directory.resolve("synthetic.properties");
        try {
            Files.writeString(config, settings());
            Files.setPosixFilePermissions(config, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            var valid = CosConnectionProbe.loadSettings(config, BUCKET, "ap-shanghai");
            require(valid.bucket().equals(BUCKET));
            require(!valid.toString().contains("synthetic-secret"));
            fails(() -> CosConnectionProbe.loadSettings(config, "other-1500000000", "ap-shanghai"));
            fails(() -> CosConnectionProbe.loadSettings(config, BUCKET, "ap-beijing"));
            Files.setPosixFilePermissions(config, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
            fails(() -> CosConnectionProbe.loadSettings(config, BUCKET, "ap-shanghai"));
            Files.setPosixFilePermissions(config, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Path linked = directory.resolve("linked.properties");
            Files.createSymbolicLink(linked, config);
            try { fails(() -> CosConnectionProbe.loadSettings(linked, BUCKET, "ap-shanghai")); }
            finally { Files.delete(linked); }
            for (String damaged : new String[]{settings() + "cos.region=ap-shanghai\n",
                    settings() + "unknown.key=value\n", settings() + "cos\\u002eregion=ap-shanghai\n",
                    settings().replace("100053657418", "100053657475"),
                    settings().replace("ap-shanghai", "https://unexpected.invalid"),
                    settings().replace("cos.secret-key=synthetic-secret-key", "cos.secret-key=")}) {
                Files.writeString(config, damaged);
                fails(() -> CosConnectionProbe.loadSettings(config, BUCKET, "ap-shanghai"));
            }
            Files.writeString(config, settings());
            var canonical = "qcs::cam::uin/100053657418:uin/100053657418";
            require(CosConnectionProbe.ownerMatches(canonical, "100053657418"));
            require(!CosConnectionProbe.ownerMatches(canonical + "0", "100053657418"));
            require(!CosConnectionProbe.ownerMatches("qcs::cam::uin/100053657475:uin/100053657475", "100053657418"));
            require(!CosConnectionProbe.ownerMatches("100053657418", "100053657418"));
            require(!CosConnectionProbe.ownerMatches(null, "100053657418"));
            String report = new ObjectMapper().writeValueAsString(CosConnectionProbe.initialTargetReport(valid, "preflight"));
            require(!report.contains(valid.secretId()) && !report.contains(valid.secretKey()) && !report.contains(valid.token()));
            require(CosConnectionProbe.anonymousClient().followRedirects() == HttpClient.Redirect.NEVER);
            require("HTTPS".equals(CosConnectionProbe.anonymousClient().sslParameters().getEndpointIdentificationAlgorithm()));
            byte[] png = CosConnectionProbe.syntheticPng();
            require(ImageIO.read(new ByteArrayInputStream(png)).getWidth() == 1 && png.length < 1024);
            String key = CosConnectionProbe.newObjectKey();
            require(key.startsWith("objects/acceptance/") && key.matches("objects/acceptance/[A-Za-z0-9._-]+"));
            require(!key.equals(CosConnectionProbe.newObjectKey()));
            var uri = CosConnectionProbe.unsignedObjectUri(valid, key);
            require("https".equals(uri.getScheme()) && uri.getQuery() == null && uri.getUserInfo() == null);
            String[] args = {"--config", config.toString(), "--confirm-test-bucket", BUCKET,
                    "--confirm-region", "ap-shanghai", "--mode", "preflight", "--report", directory.resolve("new.json").toString()};
            require(CosConnectionProbe.parseArguments(args).size() == 5);
            args[2] = "--config";
            fails(() -> CosConnectionProbe.parseArguments(args));
            fails(() -> CosConnectionProbe.validateOutput(Path.of("relative.json"), config));
            fails(() -> CosConnectionProbe.validateOutput(config, config));
            require(CosConnectionProbe.sha256(png).length() == 64);
            Path checkpoint = directory.resolve("checkpoint.json");
            try {
                var writer = new CosConnectionProbe.OwnedReport(checkpoint);
                Map<String, Object> safeReport = new LinkedHashMap<>(CosConnectionProbe.initialTargetReport(valid, "write-read"));
                safeReport.put("status", "incomplete");
                safeReport.put("writeAttempted", true);
                safeReport.put("writeConfirmed", false);
                safeReport.put("objectKey", key);
                writer.write(safeReport);
                String durable = Files.readString(checkpoint);
                require(durable.contains(key) && durable.contains("incomplete"));
                require(!durable.contains(valid.secretId()) && !durable.contains(valid.secretKey()) && !durable.contains(valid.token()));
                require(Files.getPosixFilePermissions(checkpoint).equals(Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE)));
                var stranger = new CosConnectionProbe.OwnedReport(checkpoint);
                fails(() -> stranger.write(safeReport));
                require(Files.readString(checkpoint).equals(durable));
                safeReport.put("status", "passed");
                safeReport.put("writeConfirmed", true);
                writer.write(safeReport);
                require(Files.readString(checkpoint).contains("passed") && Files.readString(checkpoint).contains(key));
                Path replacement = directory.resolve("replacement.json");
                Files.writeString(replacement, "unrelated existing evidence");
                Files.move(replacement, checkpoint, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                fails(() -> writer.write(safeReport));
                require(Files.readString(checkpoint).equals("unrelated existing evidence"));
            } finally {
                Files.deleteIfExists(checkpoint);
            }
            System.out.println("{\"status\":\"passed\",\"type\":\"offline-cos-probe-boundaries\",\"assertions\":"
                    + checks + ",\"networkConnected\":false,\"cloudObjectsWritten\":false}");
        } finally {
            Files.deleteIfExists(config);
            Files.deleteIfExists(directory);
        }
    }

    private static String settings() {
        return "cos.region=ap-shanghai\ncos.bucket=" + BUCKET + "\ncos.secret-id=synthetic-secret-id\n"
                + "cos.secret-key=synthetic-secret-key\ncos.session-token=synthetic-session-token\n"
                + "cos.expected-owner-uin=100053657418\n";
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("offline boundary check failed; values withheld");
    }

    private static void fails(Checked operation) throws Exception {
        checks++;
        try { operation.run(); }
        catch (Exception expected) {
            if (expected.getMessage() != null && expected.getMessage().contains("synthetic-secret")) {
                throw new AssertionError("configuration value leaked");
            }
            return;
        }
        throw new AssertionError("unsafe configuration accepted");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
