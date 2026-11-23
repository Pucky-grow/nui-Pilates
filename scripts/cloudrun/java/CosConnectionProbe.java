package com.yoyi.pilates.infra.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLParameters;

/** Opt-in real COS probe; no Spring context, database, Redis, bucket mutation or object deletion. */
public final class CosConnectionProbe {
    static final String EXPECTED_OWNER_UIN = "100053657418";
    static final Set<String> CONFIG_KEYS = Set.of("cos.region", "cos.bucket", "cos.secret-id",
            "cos.secret-key", "cos.session-token", "cos.expected-owner-uin");
    static final Set<String> ARGUMENT_KEYS = Set.of("--config", "--confirm-test-bucket",
            "--confirm-region", "--mode", "--report");
    private static final ObjectMapper JSON = new ObjectMapper();

    record Settings(String region, String bucket, String secretId, String secretKey,
                    String token, String expectedOwnerUin) {
        @Override public String toString() { return "CosProbeSettings[values withheld]"; }
    }

    public static void main(String[] arguments) {
        if (arguments.length == 1 && "--help".equals(arguments[0])) {
            System.out.println("CosConnectionProbe --config /SECURE/cos.properties "
                    + "--confirm-test-bucket FULL_BUCKET --confirm-region ap-shanghai "
                    + "--mode preflight|write-read --report /ABSOLUTE/new-report.json");
            return;
        }
        // SDK and HTTP wire logs may contain request signatures. Only emit our allowlisted report.
        silenceSdkLogs();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "real-cos-connection-probe");
        report.put("checkedAtUtc", Instant.now().toString());
        report.put("status", "failed");
        report.put("networkAttempted", false);
        report.put("writeAttempted", false);
        report.put("writeConfirmed", false);
        report.put("objectDeleted", false);
        report.put("doesNotProve", List.of("Bucket Policy or CDN review", "privacy of all existing objects",
                "full application authorization or signed-proxy URL acceptance", "WeChat or cloud backend deployment"));
        Map<String, String> checks = new LinkedHashMap<>();
        report.put("checks", checks);
        String stage = "configuration";
        OwnedReport output = null;
        int exitCode = 1;
        try {
            Map<String, String> args = parseArguments(arguments);
            Path config = Path.of(args.get("--config")).toAbsolutePath().normalize();
            output = new OwnedReport(validateOutput(Path.of(args.get("--report")), config));
            Settings settings = loadSettings(config, args.get("--confirm-test-bucket"),
                    args.get("--confirm-region"));
            String mode = args.get("--mode");
            if (!Set.of("preflight", "write-read").contains(mode)) throw rejected();
            report.putAll(initialTargetReport(settings, mode));
            checks.put(stage, "passed");

            stage = "expected-owner";
            report.put("networkAttempted", true);
            COSClient ownerClient = new COSClient(CosObjectStorage.credentials(settings.secretId(),
                    settings.secretKey(), settings.token()), CosObjectStorage.clientConfig(settings.region()));
            try {
                var acl = ownerClient.getBucketAcl(settings.bucket());
                String owner = acl == null || acl.getOwner() == null ? null : acl.getOwner().getId();
                if (!ownerMatches(owner, settings.expectedOwnerUin())) throw rejected();
            } finally {
                ownerClient.shutdown();
            }
            checks.put(stage, "passed");
            report.put("ownerUinMatched", true);

            CosObjectStorage storage = new CosObjectStorage(settings.region(), settings.bucket(),
                    settings.secretId(), settings.secretKey(), settings.token());
            try {
                stage = "owner-only-bucket-acl";
                storage.verifyPrivateBucket();
                checks.put(stage, "passed");
                if ("write-read".equals(mode)) {
                    byte[] png = syntheticPng();
                    String key = newObjectKey();
                    report.put("objectKey", key);
                    report.put("objectBytes", png.length);
                    report.put("sha256", sha256(png));
                    report.put("objectRetention", "A synthetic test object may remain at objectKey; this probe never deletes it.");
                    stage = "signed-https-upload";
                    report.put("writeAttempted", true);
                    // Persist the key before any PUT. A killed/timed-out process must leave an audit trail.
                    report.put("status", "incomplete");
                    output.write(report);
                    storage.put(key, png, "image/png");
                    report.put("writeConfirmed", true);
                    checks.put(stage, "passed");

                    stage = "signed-https-download-bytes-and-sha256";
                    byte[] downloaded = storage.read(key);
                    if (!Arrays.equals(png, downloaded) || !sha256(png).equals(sha256(downloaded))) throw rejected();
                    checks.put(stage, "passed");

                    stage = "anonymous-https-forbidden";
                    URI object = unsignedObjectUri(settings, key);
                    HttpRequest request = HttpRequest.newBuilder(object).timeout(Duration.ofSeconds(10))
                            .header("Cache-Control", "no-cache").GET().build();
                    int status = anonymousClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    report.put("anonymousHttpStatus", status);
                    if (status != 403) throw rejected();
                    checks.put(stage, "passed");
                } else {
                    report.put("objectReadWrite", "not-run-preflight-is-read-only");
                    report.put("anonymousAccess", "not-run-preflight-is-read-only");
                }
            } finally {
                storage.close();
            }
            report.put("status", "passed");
            exitCode = 0;
        } catch (Exception | LinkageError ignored) {
            report.put("status", "failed");
            checks.put(stage, "failed");
            report.put("failureStage", stage);
            report.put("failureDetail", "Values, request URLs, SDK responses and exception causes withheld.");
        }
        try {
            String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
            if (output != null) {
                output.write(report);
            }
            System.out.print(text);
        } catch (Exception ignored) {
            System.err.println("STOP: probe report could not be written; all configuration values withheld.");
            exitCode = 1;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    static Map<String, String> parseArguments(String[] arguments) {
        if (arguments.length != ARGUMENT_KEYS.size() * 2) throw rejected();
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!ARGUMENT_KEYS.contains(arguments[index]) || arguments[index + 1].isBlank()
                    || values.putIfAbsent(arguments[index], arguments[index + 1]) != null) throw rejected();
        }
        if (!values.keySet().equals(ARGUMENT_KEYS)) throw rejected();
        return values;
    }

    static Settings loadSettings(Path config, String confirmedBucket, String confirmedRegion) throws IOException {
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(config)
                || Files.size(config) > 65536) throw rejected();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(config);
        if (!permissions.equals(Set.of(PosixFilePermission.OWNER_READ))
                && !permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) throw rejected();
        String text = StandardCharsets.US_ASCII.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(config))).toString();
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            if (line.contains("\\") || line.chars().anyMatch(c -> c < 32 || c == 127)) throw rejected();
            int separator = line.indexOf('=');
            if (separator < 1) throw rejected();
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (!CONFIG_KEYS.contains(key) || values.putIfAbsent(key, value) != null) throw rejected();
        }
        String region = values.get("cos.region"), bucket = values.get("cos.bucket");
        String owner = values.get("cos.expected-owner-uin");
        if (!EXPECTED_OWNER_UIN.equals(owner) || !confirmedBucket.equals(bucket)
                || !confirmedRegion.equals(region)) throw rejected();
        CosStorageSettings.validate(region, bucket, values.get("cos.secret-id"), values.get("cos.secret-key"));
        return new Settings(region, bucket, values.get("cos.secret-id"), values.get("cos.secret-key"),
                values.getOrDefault("cos.session-token", ""), owner);
    }

    static Path validateOutput(Path proposed, Path config) throws IOException {
        Path output = proposed.toAbsolutePath().normalize();
        if (!proposed.isAbsolute() || output.equals(config) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw rejected();
        if (!Files.isDirectory(output.getParent())) throw rejected();
        return output;
    }

    static Map<String, Object> initialTargetReport(Settings settings, String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("region", settings.region());
        result.put("bucket", settings.bucket());
        result.put("expectedOwnerUin", settings.expectedOwnerUin());
        result.put("mode", mode);
        result.put("transport", "HTTPS with certificate verification; anonymous redirects disabled");
        return result;
    }

    static boolean ownerMatches(String ownerId, String expected) {
        return EXPECTED_OWNER_UIN.equals(expected)
                && ("qcs::cam::uin/" + expected + ":uin/" + expected).equals(ownerId);
    }

    static HttpClient anonymousClient() {
        SSLParameters ssl = new SSLParameters();
        ssl.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).sslParameters(ssl).build();
    }

    static URI unsignedObjectUri(Settings settings, String key) {
        // Region and bucket have already passed the provider's restricted format validation.
        return URI.create("https://" + settings.bucket() + ".cos." + settings.region() + ".myqcloud.com/" + key);
    }

    static byte[] syntheticPng() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x204060);
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", stream)) throw rejected();
            return stream.toByteArray();
        }
    }

    static String newObjectKey() {
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        return "objects/acceptance/cos-" + date + "-" + UUID.randomUUID().toString().replace("-", "") + ".png";
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Creates a new report exclusively; atomic subsequent writes only replace our own recorded inode. */
    static final class OwnedReport {
        private final Path path;
        private Object ownedFileKey;
        private static final Set<PosixFilePermission> PRIVATE = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);

        OwnedReport(Path path) { this.path = path; }

        void write(Map<String, Object> report) throws IOException {
            byte[] bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (ownedFileKey == null) {
                // CREATE_NEW rejects pre-existing files/symlinks even when created after CLI validation.
                try (FileChannel channel = FileChannel.open(path,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(PRIVATE))) {
                    ownedFileKey = fileKey(path);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                return;
            }
            requireOwned();
            Path pending = Files.createTempFile(path.getParent(), ".cos-probe-report-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PRIVATE));
            try {
                try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Object nextFileKey = fileKey(pending);
                requireOwned();
                Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                ownedFileKey = nextFileKey;
                requireOwned();
            } finally {
                Files.deleteIfExists(pending);
            }
        }

        private void requireOwned() throws IOException {
            if (!ownedFileKey.equals(fileKey(path))) throw rejected();
        }

        private static Object fileKey(Path path) throws IOException {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.fileKey() == null) throw rejected();
            return attributes.fileKey();
        }
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("COS probe rejected; values withheld.");
    }

    private static void silenceSdkLogs() {
        var factory = org.slf4j.LoggerFactory.getILoggerFactory();
        if (factory instanceof ch.qos.logback.classic.LoggerContext context) {
            context.getLogger("com.qcloud.cos").setLevel(ch.qos.logback.classic.Level.OFF);
            context.getLogger("org.apache.http").setLevel(ch.qos.logback.classic.Level.OFF);
        }
    }
}
