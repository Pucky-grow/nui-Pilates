package com.yoyi.pilates.infra.tools;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent JDBC/TLS read-only probe. Does not start Spring, Redis, migrations or bootstrap. */
public final class MySqlConnectionProbe {
    static final String DATABASE = "uni_pilates_test";
    static final String DEFAULT_FULL_VERSION = "8.0.30-cynos-3.1.19.002";
    static final List<String> MIGRATIONS = List.of("V001__structure.sql", "V002__base.sql", "V003__delivery.sql", "V004__studio_ledger.sql");
    static final List<Integer> EXPECTED_STEPS = List.of(55, 24, 5, 13);
    private static final Set<String> CONFIG_KEYS = Set.of("url", "database", "user", "password",
            "tls.truststore.path", "tls.truststore.password", "tls.truststore.type");
    private static final Set<String> URL_KEYS = Set.of("sslMode", "serverTimezone", "connectionTimeZone",
            "connectTimeout", "socketTimeout");
    private static final Set<PosixFilePermission> ALLOWED_MODES = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<String> DML = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final Pattern GRANT = Pattern.compile("^GRANT (.+?) ON (.+?) TO .+$", Pattern.CASE_INSENSITIVE);

    private MySqlConnectionProbe() {}

    public static void main(String[] args) {
        int code = execute(args);
        if (code != 0) System.exit(code);
    }

    static int execute(String[] args) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkedAtUtc", Instant.now().toString());
        report.put("type", "real-jdbc-mysql-connection");
        report.put("status", "failed");
        report.put("databaseConnected", false);
        report.put("businessDataWritten", false);
        report.put("accountsOrGrantsModified", false);
        report.put("springOrRedisStarted", false);
        report.put("sessionSettingsOnly", List.of("read-only connection", "time_zone=+08:00"));
        report.put("doesNotProve", List.of("full backend HTTP acceptance", "Redis", "COS", "WeChat"));
        List<Map<String, Object>> checks = new ArrayList<>();
        report.put("checks", checks);
        Path reportPath = null;
        String stage = "arguments";
        try {
            Map<String, String> options = options(args);
            Path destination = Path.of(required(options, "--report")).toAbsolutePath().normalize();
            Path config = Path.of(required(options, "--config")).toAbsolutePath().normalize();
            if (destination.equals(config) || Files.isSymbolicLink(destination)
                    || !destination.getFileName().toString().endsWith(".json")) fail("unsafe-report-path");
            ensureReportDestination(destination);
            reportPath = destination;
            String uuid = required(options, "--expect-server-uuid");
            if (!uuid.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) fail("expected-uuid-required");
            if (!DATABASE.equals(required(options, "--confirm-isolated-database"))) fail("isolated-database-required");
            String version = options.getOrDefault("--expect-full-version", DEFAULT_FULL_VERSION);
            if (!version.matches("8\\.0\\.[0-9]+(?:[-.A-Za-z0-9]+)?")) fail("expected-mysql8-version-required");
            Path migrations = Path.of(required(options, "--migration-directory")).toAbsolutePath().normalize();
            Map<String, String> migrationHashes = new LinkedHashMap<>();
            for (int index = 0; index < MIGRATIONS.size(); index++) {
                Path file = migrations.resolve(MIGRATIONS.get(index));
                if (index == 3 && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue; // legacy V003 bundle
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) fail("reviewed-migration-files-required");
                byte[] bytes = Files.readAllBytes(file);
                if (DatabaseTool.split(new String(bytes, StandardCharsets.UTF_8)).size() != EXPECTED_STEPS.get(index)) fail("reviewed-migration-step-count-mismatch");
                migrationHashes.put(MIGRATIONS.get(index), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            stage = "configuration";
            Map<String, String> settings = readProtectedConfig(config);
            Properties connectionProperties = validateSettings(settings);
            passed(checks, stage, Map.of("sslMode", "VERIFY_IDENTITY", "credentialsWithheld", true));
            report.put("expectedDatabase", DATABASE);
            report.put("expectedServerUuid", uuid.toLowerCase(Locale.ROOT));
            report.put("expectedFullVersion", version);
            stage = "jdbc-connect";
            try (Connection connection = DriverManager.getConnection(settings.get("url"), connectionProperties)) {
                report.put("databaseConnected", true);
                connection.setReadOnly(true);
                stage = "tls";
                String cipher;
                try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    if (!rows.next()) fail("tls-cipher-unavailable");
                    cipher = rows.getString(2);
                }
                if (cipher == null || cipher.isBlank()) fail("tls-not-active");
                passed(checks, stage, Map.of("activeCipher", safeMetadata(cipher), "certificateIdentityVerification", "Connector/J VERIFY_IDENTITY connection succeeded"));
                stage = "identity";
                String actualDatabase = one(connection, "SELECT DATABASE()");
                String actualUuid = one(connection, "SELECT @@server_uuid");
                String actualVersion = one(connection, "SELECT VERSION()");
                String product = connection.getMetaData().getDatabaseProductName();
                if (!DATABASE.equals(actualDatabase) || !uuid.equalsIgnoreCase(actualUuid)
                        || !version.equals(actualVersion) || !"MySQL".equals(product)) fail("isolated-server-identity-mismatch");
                String authenticatedAccount = one(connection, "SELECT CURRENT_USER()");
                if (!authenticatedAccount.startsWith(settings.get("user") + "@")
                        || authenticatedAccount.toLowerCase(Locale.ROOT).startsWith("root@")) fail("runtime-authenticated-account-mismatch");
                passed(checks, stage, Map.of("database", actualDatabase, "serverUuid", actualUuid,
                        "fullVersion", actualVersion, "versionComment", safeMetadata(one(connection, "SELECT @@version_comment")),
                        "databaseProduct", product, "configuredRuntimeAccountMatched", true));
                stage = "session-parameters";
                String originalTimeZone = one(connection, "SELECT @@session.time_zone");
                try (Statement statement = connection.createStatement()) { statement.execute("SET SESSION time_zone='+08:00'"); }
                String effectiveTimeZone = one(connection, "SELECT @@session.time_zone");
                String globalTimeZone = one(connection, "SELECT @@global.time_zone");
                String systemTimeZone = one(connection, "SELECT @@system_time_zone");
                // CST is ambiguous across regions; require the explicit target setting instead.
                boolean globalShanghai = Set.of("+08:00", "Asia/Shanghai").contains(globalTimeZone);
                if (!"+08:00".equals(effectiveTimeZone) || !globalShanghai) fail("server-or-session-timezone-mismatch");
                Set<String> modes = new HashSet<>(Arrays.asList(one(connection, "SELECT @@session.sql_mode").split(",")));
                if (!modes.contains("STRICT_TRANS_TABLES") && !modes.contains("STRICT_ALL_TABLES")) fail("strict-sql-mode-required");
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("originalSessionTimeZone", safeMetadata(originalTimeZone));
                parameters.put("effectiveSessionTimeZone", effectiveTimeZone);
                parameters.put("globalTimeZone", safeMetadata(globalTimeZone));
                parameters.put("systemTimeZone", safeMetadata(systemTimeZone));
                parameters.put("sqlModes", modes.stream().sorted().toList());
                parameters.put("sessionIsolation", safeMetadata(one(connection, "SELECT @@session.transaction_isolation")));
                parameters.put("globalIsolation", safeMetadata(one(connection, "SELECT @@global.transaction_isolation")));
                parameters.put("serverCharacterSet", safeMetadata(one(connection, "SELECT @@character_set_server")));
                parameters.put("serverCollation", safeMetadata(one(connection, "SELECT @@collation_server")));
                String readOnly = one(connection, "SELECT @@session.transaction_read_only");
                if (!"1".equals(readOnly)) fail("read-only-session-required");
                parameters.put("readOnlySession", readOnly);
                passed(checks, stage, parameters);
                stage = "schema";
                Set<String> tables = new HashSet<>();
                try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")) {
                    while (rows.next()) tables.add(rows.getString(1));
                }
                if (!tables.contains("schema_history")) fail("schema-history-required");
                if (!"0".equals(one(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND (table_collation NOT LIKE 'utf8mb4%' OR engine<>'InnoDB')"))) fail("table-encoding-or-engine-mismatch");
                List<Map<String, Object>> actualMigrations = new ArrayList<>();
                try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT version,checksum,state,applied_steps,total_steps FROM schema_history ORDER BY version")) {
                    int count = 0;
                    while (rows.next()) {
                        if (count >= MIGRATIONS.size()) fail("unknown-migration-history");
                        String name = rows.getString(1);
                        if (!MIGRATIONS.get(count).equals(name) || !Objects.equals(migrationHashes.get(name), rows.getString(2))
                                || !"DONE".equals(rows.getString(3)) || rows.getInt(4) != EXPECTED_STEPS.get(count)
                                || rows.getInt(5) != EXPECTED_STEPS.get(count)) fail("migration-history-mismatch");
                        actualMigrations.add(Map.of("version", name, "checksum", rows.getString(2), "state", "DONE",
                                "appliedSteps", rows.getInt(4), "totalSteps", rows.getInt(5)));
                        count++;
                    }
                    if (count < 3 || tables.size() != (count == 4 ? 70 : 58)) fail("migration-table-count-mismatch");
                }
                if (!"FREE".equals(one(connection, "SELECT config_value FROM system_config WHERE config_key='booking.paymentMode' AND deleted=0"))) fail("free-booking-mode-required");
                if (!"1".equals(one(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='auth_version'"))) fail("account-auth-version-schema-required");
                passed(checks, stage, Map.of("baseTables", tables.size(), "allBaseTablesUtf8mb4InnoDb", true,
                        "migrations", actualMigrations, "bookingMode", "FREE"));
                stage = "runtime-permissions";
                List<String> grants = new ArrayList<>();
                try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SHOW GRANTS")) {
                    while (rows.next()) grants.add(rows.getString(1));
                }
                validateGrants(grants, tables);
                passed(checks, stage, Map.of("scope", DATABASE, "businessTablesDml", tables.size() - (tables.contains("studio_wallet_entry") ? 3 : 1),
                        "schemaHistorySelectOnly", true, "globalOrDdlOrGrantOptionOrRolePrivileges", false,
                        "rawGrantsWithheld", true));
                report.put("status", "passed");
            }
            writeReport(reportPath, report);
            System.out.println("PASS: real isolated MySQL JDBC/TLS/runtime-permissions/schema probe. Report contains no credentials.");
            return 0;
        } catch (Exception error) {
            report.put("status", "failed");
            report.put("failedStage", stage);
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("name", stage);
            failure.put("status", "failed");
            failure.put("reason", error instanceof ProbeRejected rejected ? rejected.reason : error instanceof SQLException ? "jdbc-error-values-withheld" : "probe-error-values-withheld");
            failure.put("errorClass", error.getClass().getSimpleName());
            if (error instanceof SQLException sql) {
                String state = sql.getSQLState();
                failure.put("sqlState", state != null && state.matches("[0-9A-Z]{5}") ? state : "withheld");
                failure.put("vendorCode", sql.getErrorCode());
            }
            checks.add(failure);
            try { if (reportPath != null) writeReport(reportPath, report); }
            catch (Exception ignored) { /* Never print target paths, URL or credential-bearing I/O errors. */ }
            System.err.println("STOP: MySQL connection acceptance failed at " + stage + "; raw exception, SQL, URLs and credentials withheld.");
            return 2;
        }
    }

    static Map<String, String> options(String[] args) {
        Set<String> allowed = Set.of("--config", "--expect-server-uuid", "--expect-full-version", "--confirm-isolated-database", "--migration-directory", "--report");
        if (args.length % 2 != 0) fail("invalid-arguments");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || options.putIfAbsent(args[i], args[i + 1]) != null) fail("invalid-or-duplicate-arguments");
        }
        return options;
    }

    static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) fail("missing-required-setting");
        return value;
    }

    static Map<String, String> readProtectedConfig(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) fail("protected-regular-config-required");
        var permissions = Files.getPosixFilePermissions(path);
        if (!permissions.contains(PosixFilePermission.OWNER_READ) || !ALLOWED_MODES.containsAll(permissions)) fail("config-mode-must-be-0400-or-0600");
        String text = Files.readString(path, StandardCharsets.US_ASCII);
        if (text.indexOf('\\') >= 0 || text.indexOf('\0') >= 0 || text.length() > 65536) fail("plain-ascii-properties-required");
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!")) continue;
            int separator = line.indexOf('=');
            if (separator < 1) fail("plain-key-value-properties-required");
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!CONFIG_KEYS.contains(key) || values.putIfAbsent(key, value) != null) fail("unknown-or-duplicate-config-key");
        }
        return values;
    }

    static Properties validateSettings(Map<String, String> settings) {
        if (!CONFIG_KEYS.containsAll(settings.keySet()) || !DATABASE.equals(required(settings, "database"))) fail("isolated-config-required");
        String user = required(settings, "user");
        if (!user.matches("[A-Za-z][A-Za-z0-9_]{2,31}") || "root".equalsIgnoreCase(user)) fail("non-root-runtime-account-required");
        required(settings, "password");
        validateUrl(required(settings, "url"));
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", settings.get("password"));
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
        properties.setProperty("enabledTLSProtocols", "TLSv1.2,TLSv1.3");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("autoDeserialize", "false");
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowUrlInLocalInfile", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        properties.setProperty("logger", "com.mysql.cj.log.NullLogger");
        if (settings.containsKey("tls.truststore.path")) {
            try {
                Path trust = Path.of(required(settings, "tls.truststore.path"));
                if (!trust.isAbsolute() || Files.isSymbolicLink(trust) || !Files.isRegularFile(trust, LinkOption.NOFOLLOW_LINKS)) fail("regular-absolute-truststore-required");
                String type = settings.getOrDefault("tls.truststore.type", "PKCS12");
                if (!Set.of("PKCS12", "JKS").contains(type)) fail("truststore-type-rejected");
                properties.setProperty("trustCertificateKeyStoreUrl", trust.toUri().toString());
                properties.setProperty("trustCertificateKeyStoreType", type);
                properties.setProperty("trustCertificateKeyStorePassword", required(settings, "tls.truststore.password"));
                properties.setProperty("fallbackToSystemTrustStore", "false");
            } catch (RuntimeException error) {
                if (error instanceof ProbeRejected rejected) throw rejected;
                fail("truststore-configuration-rejected");
            }
        } else if (settings.containsKey("tls.truststore.password") || settings.containsKey("tls.truststore.type")) fail("incomplete-truststore-configuration");
        return properties;
    }

    static void validateUrl(String url) {
        try {
            if (!url.startsWith("jdbc:mysql://") || url.matches("(?s).*[\\s;\\\\].*")) fail("strict-mysql-url-required");
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || !uri.getPath().equals("/" + DATABASE)
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) fail("strict-mysql-url-required");
            Map<String, String> query = new HashMap<>();
            if (uri.getRawQuery() == null) fail("verify-identity-url-required");
            for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) fail("url-parameters-rejected");
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (!URL_KEYS.contains(key) || query.putIfAbsent(key, value) != null) fail("url-parameters-rejected");
            }
            if (!"VERIFY_IDENTITY".equals(query.get("sslMode"))) fail("verify-identity-url-required");
            for (String key : List.of("serverTimezone", "connectionTimeZone")) {
                if (query.containsKey(key) && !"Asia/Shanghai".equals(query.get(key))) fail("shanghai-connection-timezone-required");
            }
            if (!query.containsKey("serverTimezone") && !query.containsKey("connectionTimeZone")) fail("shanghai-connection-timezone-required");
            for (String key : List.of("connectTimeout", "socketTimeout")) {
                if (query.containsKey(key) && (!query.get(key).matches("[0-9]{3,5}") || Integer.parseInt(query.get(key)) < 100 || Integer.parseInt(query.get(key)) > 60000)) fail("bounded-timeouts-required");
            }
        } catch (IllegalArgumentException error) {
            if (error instanceof ProbeRejected rejected) throw rejected;
            fail("url-parameters-rejected");
        }
    }

    static void validateGrants(List<String> grants, Set<String> tables) {
        Map<String, Set<String>> tablePrivileges = new HashMap<>();
        for (String grant : grants) {
            if (grant == null || grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION")) fail("grant-option-or-null-grant-rejected");
            var matcher = GRANT.matcher(grant);
            if (!matcher.matches()) fail("role-or-unsupported-grant-requires-review");
            Set<String> privileges = new HashSet<>();
            for (String item : matcher.group(1).split(",")) privileges.add(item.trim().toUpperCase(Locale.ROOT));
            String scope = matcher.group(2).replace("`", "");
            if (scope.equals("*.*") && privileges.equals(Set.of("USAGE"))) continue;
            if (!DML.containsAll(privileges)) fail("runtime-ddl-or-global-privilege-rejected");
            if (!scope.startsWith(DATABASE + ".")) fail("out-of-database-grant-rejected");
            String table = scope.substring(DATABASE.length() + 1);
            if (table.equals("*")) {
                if (!privileges.equals(Set.of("SELECT"))) fail("schema-history-write-by-database-grant-rejected");
                for (String known : tables) tablePrivileges.computeIfAbsent(known, ignored -> new HashSet<>()).add("SELECT");
            } else {
                if (!tables.contains(table)) fail("unexpected-table-grant-rejected");
                if (table.equals("schema_history") && !privileges.equals(Set.of("SELECT"))) fail("schema-history-write-grant-rejected");
                tablePrivileges.computeIfAbsent(table, ignored -> new HashSet<>()).addAll(privileges);
            }
        }
        for (String table : tables) {
            Set<String> required = DatabaseTool.runtimePrivileges(table);
            if (!required.equals(tablePrivileges.get(table))) fail("runtime-table-permissions-incomplete");
        }
    }

    private static String one(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) fail("expected-single-value-missing");
            String value = rows.getString(1);
            if (rows.next()) fail("expected-single-value-ambiguous");
            return value;
        }
    }

    private static String safeMetadata(String value) {
        if (value == null || value.length() > 250 || !value.matches("[A-Za-z0-9 ._()+:/,=-]*")) fail("metadata-shape-requires-review");
        return value;
    }

    private static void passed(List<Map<String, Object>> checks, String name, Map<String, ?> details) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name);
        check.put("status", "passed");
        check.putAll(details);
        checks.add(check);
    }

    private static void writeReport(Path path, Map<String, Object> report) throws IOException {
        ensureReportDestination(path);
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        ByteBuffer bytes = ByteBuffer.wrap((json(report) + "\n").getBytes(StandardCharsets.UTF_8));
        boolean created = false;
        Object ownedFileKey = null;
        // One CREATE_NEW write, owner-only from creation. Existing evidence is never replaced.
        try {
            var channel = Files.newByteChannel(path,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            created = true;
            try (channel) {
                ownedFileKey = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                while (bytes.hasRemaining()) channel.write(bytes);
            }
        } catch (IOException | RuntimeException error) {
            // Remove only this attempt's new, unfinished output, never an existing/replaced file.
            if (created && ownedFileKey != null) {
                try {
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isRegularFile() && Objects.equals(ownedFileKey, attributes.fileKey())) Files.delete(path);
                } catch (IOException ignored) { /* Exit code remains failed if the filesystem cannot clean up. */ }
            }
            throw error;
        }
    }

    private static void ensureReportDestination(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) fail("unsafe-report-path");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) fail("refusing-to-overwrite-existing-report");
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((key, entry) -> parts.add(json(key.toString()) + ":" + json(entry)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> parts = new ArrayList<>();
            items.forEach(item -> parts.add(json(item)));
            return "[" + String.join(",", parts) + "]";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (char ch : value.toString().toCharArray()) {
            if (ch == '\\' || ch == '"') escaped.append('\\').append(ch);
            else if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch));
            else escaped.append(ch);
        }
        return escaped.append('"').toString();
    }

    static final class ProbeRejected extends IllegalArgumentException {
        final String reason;
        ProbeRejected(String reason) { super("MySQL probe rejected; configuration and response values withheld."); this.reason = reason; }
    }

    private static void fail(String reason) { throw new ProbeRejected(reason); }
}
