package com.yoyi.pilates.infra.tools;

import com.sun.net.httpserver.HttpServer;
import com.yoyi.pilates.common.config.CloudrunPrivateMysqlPolicy;
import com.yoyi.pilates.infra.storage.CosObjectStorage;
import com.yoyi.pilates.ledger.StudioLedgerAccess;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Separate deployment preflight. No Spring, Redis, accounts, migrations, or business API. */
public final class CloudrunPreflight {
    private CloudrunPreflight() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        try {
            if (!"true".equals(env.get("CLOUDRUN_PRIVATE_MYSQL_ENABLED"))) throw new IllegalStateException();
            CloudrunPrivateMysqlPolicy.validateSettings(required(env, "SPRING_PROFILES_ACTIVE"),
                    required(env, "CLOUDRUN_ENVIRONMENT_ID"), required(env, "CLOUDRUN_PRIVATE_MYSQL_ACK"),
                    required(env, "DATABASE_URL"), required(env, "MYSQL_USER"));
            required(env, "MYSQL_PASSWORD");
            required(env, "COS_REGION"); required(env, "COS_BUCKET");
            required(env, "COS_SECRET_ID"); required(env, "COS_SECRET_KEY");
            if (!"cos".equals(env.get("STORAGE_TYPE"))) throw new IllegalStateException();
        } catch (RuntimeException rejected) {
            System.err.println("STOP: isolated preflight configuration rejected; values withheld.");
            System.exit(2); return;
        }
        var ready = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpServer server = startServer(new InetSocketAddress("0.0.0.0", 8080), ready);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        var checker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(checker::shutdownNow));
        checker.scheduleWithFixedDelay(() -> {
            String stage = "mysql";
            try {
                checkDatabase(env);
                stage = "cos-private-acl";
                try (var ignored = new CosCheck(env)) { }
                ready.set(true);
                System.out.println("PREFLIGHT: MySQL identity/schema/scoped-grants and COS private ACL passed; Redis and business APIs not started; MySQL TLS is not active.");
            } catch (Exception failure) {
                ready.set(false);
                System.err.println("PREFLIGHT: dependency check failed at " + stage + "; exception, SQL, credentials and target values withheld.");
            }
        }, 0, 5, java.util.concurrent.TimeUnit.MINUTES);
    }

    static HttpServer startServer(InetSocketAddress address, java.util.concurrent.atomic.AtomicBoolean ready) throws Exception {
        HttpServer server = HttpServer.create(address, 8);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status; String payload;
            if (!"GET".equals(exchange.getRequestMethod())) { status = 405; payload = "{\"status\":\"METHOD_NOT_ALLOWED\"}"; }
            else if (path.equals("/health")) { status = 200; payload = "{\"mode\":\"preflight\",\"status\":\"UP\",\"businessReady\":false}"; }
            else if (path.equals("/ready")) { status = ready.get() ? 200 : 503; payload = "{\"mode\":\"preflight\",\"dependenciesReady\":" + ready.get() + ",\"mysqlTls\":false,\"businessReady\":false}"; }
            else { status = 404; payload = "{\"status\":\"NOT_FOUND\"}"; }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }

    static void checkDatabase(Map<String, String> env) throws Exception {
        Properties settings = new Properties();
        settings.setProperty("user", env.get("MYSQL_USER")); settings.setProperty("password", env.get("MYSQL_PASSWORD"));
        for (String key : Set.of("allowMultiQueries", "allowPublicKeyRetrieval", "allowLoadLocalInfile", "autoDeserialize", "autoReconnect")) settings.setProperty(key, "false");
        settings.setProperty("logger", "com.mysql.cj.log.NullLogger");
        try (Connection connection = DriverManager.getConnection(env.get("DATABASE_URL"), settings)) {
            connection.setReadOnly(true);
            CloudrunPrivateMysqlPolicy.verifyTarget(one(connection, "SELECT DATABASE()"), one(connection, "SELECT @@server_uuid"), one(connection, "SELECT VERSION()"));
            if (!one(connection, "SELECT CURRENT_USER()").startsWith(CloudrunPrivateMysqlPolicy.USER + "@")) throw new IllegalStateException();
            try (var query = connection.createStatement()) { query.execute("SET SESSION time_zone='+08:00'"); }
            if (!"+08:00".equals(one(connection, "SELECT @@session.time_zone"))
                    || !one(connection, "SELECT @@session.sql_mode").matches(".*STRICT_(TRANS|ALL)_TABLES.*")) throw new IllegalStateException();
            if (!"0".equals(one(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND (engine<>'InnoDB' OR table_collation NOT LIKE 'utf8mb4%')"))) throw new IllegalStateException();
            if (!"1".equals(one(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='auth_version'"))) throw new IllegalStateException();
            String[] names = {"V001__structure.sql", "V002__base.sql", "V003__delivery.sql", StudioLedgerAccess.MIGRATION};
            String[] hashes = {"5131773b57da5f67bed85972b6767c70b65f178c2e12919b5eb67d33a2a01346", "182dc86bc23c0e95ffa984000deece015a64b96d9c418feb2171da1d4ff43b76", "ea579d8b6c1385e14020f4c6eaa96e5cd502727da574d1e1cf25b4f71c84ea4e", StudioLedgerAccess.MIGRATION_SHA256};
            int[] steps = {55, 24, 5, StudioLedgerAccess.MIGRATION_STEPS};
            int applied = 0;
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT version,checksum,state,applied_steps,total_steps FROM schema_history ORDER BY version")) {
                while (rows.next()) {
                    int i = applied++;
                    if (i >= names.length || !names[i].equals(rows.getString(1)) || !hashes[i].equals(rows.getString(2))
                            || !"DONE".equals(rows.getString(3)) || rows.getInt(4) != steps[i] || rows.getInt(5) != steps[i]) {
                        throw new IllegalStateException();
                    }
                }
            }
            boolean ledger = applied == names.length;
            if (applied < 3 || (!ledger && "true".equalsIgnoreCase(env.get("STUDIO_LEDGER_ENABLED")))) throw new IllegalStateException();
            int tableCount = 58 + (ledger ? StudioLedgerAccess.TABLES_ADDED : 0);
            if (!Integer.toString(tableCount).equals(one(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'"))) throw new IllegalStateException();
            if (ledger && !"YES".equals(one(connection, "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='openid'"))) throw new IllegalStateException();
            if (!"FREE".equals(one(connection, "SELECT config_value FROM system_config WHERE config_key='booking.paymentMode' AND deleted=0"))) throw new IllegalStateException();
            // Check exact runtime privileges without returning account/grant strings to HTTP/logs.
            var permissions = new java.util.HashMap<String, Set<String>>();
            try (var query = connection.createStatement(); var rows = query.executeQuery("SHOW GRANTS")) {
                while (rows.next()) {
                    String grant = rows.getString(1);
                    if (grant.contains("WITH GRANT OPTION")) throw new IllegalStateException();
                    var match = java.util.regex.Pattern.compile("^GRANT (.+?) ON (.+?) TO .+$").matcher(grant);
                    if (!match.matches()) throw new IllegalStateException();
                    String scope = match.group(2).replace("`", "");
                    if (scope.equals("*.*") && match.group(1).equals("USAGE")) continue;
                    if (!scope.startsWith(CloudrunPrivateMysqlPolicy.DATABASE + ".") || scope.endsWith(".*")) throw new IllegalStateException();
                    Set<String> privileges = new java.util.HashSet<>(java.util.Arrays.asList(match.group(1).split(",\\s*")));
                    String table = scope.substring(scope.indexOf('.') + 1);
                    Set<String> expected = DatabaseTool.runtimePrivileges(table);
                    if (!privileges.equals(expected) || permissions.putIfAbsent(table, privileges) != null) throw new IllegalStateException();
                }
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")) {
                while (rows.next()) if (!permissions.containsKey(rows.getString(1))) throw new IllegalStateException();
            }
            if (permissions.size() != tableCount) throw new IllegalStateException();
        }
    }

    private static String one(Connection connection, String sql) throws Exception {
        try (var query = connection.createStatement(); var rows = query.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException();
            return rows.getString(1);
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key); if (value == null || value.isBlank()) throw new IllegalStateException(); return value;
    }

    private static final class CosCheck implements AutoCloseable {
        private final CosObjectStorage storage;
        CosCheck(Map<String, String> env) throws Exception {
            storage = new CosObjectStorage(env.get("COS_REGION"), env.get("COS_BUCKET"), env.get("COS_SECRET_ID"), env.get("COS_SECRET_KEY"), env.getOrDefault("COS_SESSION_TOKEN", ""));
            storage.verifyPrivateBucket();
        }
        public void close() { storage.close(); }
    }
}
