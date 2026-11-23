package com.yoyi.pilates.infra.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline configuration/privilege regression only; never connects to MySQL. */
public final class MySqlConnectionProbeOfflineTest {
    private static int assertions;
    private static final String URL = "jdbc:mysql://mysql.private.invalid:3306/uni_pilates_test?sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai";

    public static void main(String[] args) throws Exception {
        MySqlConnectionProbe.validateUrl(URL);
        MySqlConnectionProbe.validateUrl(URL + "&connectTimeout=5000&socketTimeout=15000");
        MySqlConnectionProbe.validateUrl(URL.replace("serverTimezone=Asia/Shanghai", "connectionTimeZone=Asia%2FShanghai"));
        assertions += 3;
        for (String invalid : List.of(URL.replace("VERIFY_IDENTITY", "REQUIRED"), URL.replace("VERIFY_IDENTITY", "DISABLED"),
                URL.replace("sslMode=VERIFY_IDENTITY&", ""), URL + "&sslMode=VERIFY_IDENTITY", URL + "&user=fixture",
                URL + "&autoReconnect=true", URL + "&reconnectAtTxEnd=true", URL + "&sessionVariables=sql_mode='NONE'",
                URL + "&allowLoadLocalInfile=true", URL + "&allowMultiQueries=true", URL + "&socketFactory=untrusted.Factory",
                URL + "&%73essionVariables=x", URL + "&socketTimeout=0", URL + "&socketTimeout=60001",
                URL.replace("mysql.private.invalid", "fixture@mysql.private.invalid"),
                URL.replace("uni_pilates_test?", "business_database?"), URL.replace(":3306", ":0"),
                URL.replace("Asia/Shanghai", "UTC"), URL + ";sessionVariables=x", URL + "#ignored")) {
            rejected(() -> MySqlConnectionProbe.validateUrl(invalid));
        }
        Map<String, String> settings = new HashMap<>(Map.of("url", URL, "database", "uni_pilates_test", "user", "fixture_runtime", "password", "synthetic-password-not-a-real-credential"));
        var properties = MySqlConnectionProbe.validateSettings(settings);
        check(properties.getProperty("logger").equals("com.mysql.cj.log.NullLogger"));
        check(properties.getProperty("allowMultiQueries").equals("false"));
        check(properties.getProperty("allowLoadLocalInfile").equals("false"));
        check(properties.getProperty("enabledTLSProtocols").equals("TLSv1.2,TLSv1.3"));
        Map<String, String> root = new HashMap<>(settings); root.put("user", "root"); rejected(() -> MySqlConnectionProbe.validateSettings(root));
        Map<String, String> unknown = new HashMap<>(settings); unknown.put("sessionVariables", "sql_mode=NONE"); rejected(() -> MySqlConnectionProbe.validateSettings(unknown));
        Map<String, String> incompleteTrust = new HashMap<>(settings); incompleteTrust.put("tls.truststore.password", "fixture"); rejected(() -> MySqlConnectionProbe.validateSettings(incompleteTrust));

        Set<String> tables = Set.of("schema_history", "booking");
        List<String> valid = List.of("GRANT USAGE ON *.* TO 'fixture'@'10.%'", "GRANT SELECT ON `uni_pilates_test`.`schema_history` TO 'fixture'@'10.%'", "GRANT SELECT, INSERT, UPDATE, DELETE ON `uni_pilates_test`.`booking` TO 'fixture'@'10.%'");
        MySqlConnectionProbe.validateGrants(valid, tables); assertions++;
        MySqlConnectionProbe.validateGrants(List.of("GRANT SELECT ON `uni_pilates_test`.* TO 'fixture'@'10.%'", "GRANT INSERT,UPDATE,DELETE ON `uni_pilates_test`.`booking` TO 'fixture'@'10.%'"), tables); assertions++;
        for (String extra : List.of("GRANT SELECT ON *.* TO 'fixture'@'10.%'", "GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.* TO 'fixture'@'10.%'",
                "GRANT INSERT ON `uni_pilates_test`.`schema_history` TO 'fixture'@'10.%'", "GRANT CREATE ON `uni_pilates_test`.`booking` TO 'fixture'@'10.%'",
                "GRANT SELECT ON `another_database`.`booking` TO 'fixture'@'10.%'", "GRANT 'broad_role'@'%' TO 'fixture'@'10.%'",
                "GRANT SELECT ON `uni_pilates_test`.`booking` TO 'fixture'@'10.%' WITH GRANT OPTION", "GRANT ALL PRIVILEGES ON `uni_pilates_test`.* TO 'fixture'@'10.%'")) {
            List<String> bad = new ArrayList<>(valid); bad.add(extra); rejected(() -> MySqlConnectionProbe.validateGrants(bad, tables));
        }
        rejected(() -> MySqlConnectionProbe.validateGrants(valid.subList(0, 2), tables));
        rejected(() -> MySqlConnectionProbe.options(new String[]{"--config", "fixture", "--config", "other"}));
        rejected(() -> MySqlConnectionProbe.options(new String[]{"--config"}));

        Path directory = Files.createTempDirectory("mysql-probe-offline-");
        try {
            Path file = directory.resolve("fixture.properties");
            String plain = "url=" + URL + "\ndatabase=uni_pilates_test\nuser=fixture_runtime\npassword=synthetic-secret-not-for-live-use\n";
            Files.writeString(file, plain, StandardCharsets.US_ASCII);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            check(MySqlConnectionProbe.readProtectedConfig(file).get("database").equals("uni_pilates_test"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
            rejectedChecked(() -> MySqlConnectionProbe.readProtectedConfig(file));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(file, plain + "database=business_database\n", StandardCharsets.US_ASCII);
            rejectedChecked(() -> MySqlConnectionProbe.readProtectedConfig(file));
            Files.writeString(file, plain + "user\\u002e=x\n", StandardCharsets.US_ASCII);
            rejectedChecked(() -> MySqlConnectionProbe.readProtectedConfig(file));
            Path symlink = directory.resolve("symlink.properties"); Files.createSymbolicLink(symlink, file);
            rejectedChecked(() -> MySqlConnectionProbe.readProtectedConfig(symlink));
            Files.delete(symlink);
            String previous = Files.readString(file);
            check(MySqlConnectionProbe.execute(new String[]{"--config", file.toString(), "--report", file.toString()}) == 2);
            check(Files.readString(file).equals(previous));
            Path unrelated = directory.resolve("manifest.json");
            Files.writeString(unrelated, "{\"kind\":\"immutable-artifact\"}");
            check(MySqlConnectionProbe.execute(new String[]{"--config", file.toString(), "--report", unrelated.toString()}) == 2);
            check(Files.readString(unrelated).equals("{\"kind\":\"immutable-artifact\"}"));
            Files.delete(unrelated);
            Path oldProbe = directory.resolve("old-probe.json");
            String oldEvidence = "{\"type\":\"real-jdbc-mysql-connection\",\"status\":\"passed\"}";
            Files.writeString(oldProbe, oldEvidence);
            check(MySqlConnectionProbe.execute(new String[]{"--config", file.toString(), "--report", oldProbe.toString()}) == 2);
            check(Files.readString(oldProbe).equals(oldEvidence));
            Files.delete(oldProbe);
            Path freshFailure = directory.resolve("fresh-failure.json");
            check(MySqlConnectionProbe.execute(new String[]{"--config", file.toString(), "--report", freshFailure.toString()}) == 2);
            check(Files.getPosixFilePermissions(freshFailure).equals(PosixFilePermissions.fromString("rw-------")));
            check(Files.readString(freshFailure).contains("\"databaseConnected\":false"));
            check(Files.readString(freshFailure).contains("\"status\":\"failed\""));
            Files.delete(freshFailure);
            Files.delete(file);
        } finally { Files.deleteIfExists(directory); }
        check(MySqlConnectionProbe.json(Map.of("quoted", "a\"b\nc")).equals("{\"quoted\":\"a\\\"b\\u000ac\"}"));
        System.out.println(MySqlConnectionProbe.json(Map.of("status", "passed", "type", "offline-mysql-probe-boundaries",
                "assertions", assertions, "databaseConnected", false, "businessDataWritten", false)));
    }

    private static void check(boolean value) { if (!value) throw new AssertionError("offline MySQL probe assertion failed"); assertions++; }
    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("unsafe configuration/grant accepted"); }
        catch (MySqlConnectionProbe.ProbeRejected expected) {
            check(expected.getMessage().equals("MySQL probe rejected; configuration and response values withheld."));
        }
    }
    private static void rejectedChecked(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe protected config accepted"); }
        catch (MySqlConnectionProbe.ProbeRejected expected) { assertions++; }
    }
    private interface Checked { void run() throws Exception; }
}
