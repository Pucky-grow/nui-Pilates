package com.yoyi.pilates.common.config;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Explicit isolated staging exception, never a general non-TLS production fallback. */
public final class CloudrunPrivateMysqlPolicy {
    public static final String ENVIRONMENT = "uni-test-d5gwbyymua6bf2ada";
    public static final String HOST = "10.39.100.38";
    public static final String DATABASE = "uni_pilates_test";
    public static final String UUID = "d1f6adf9-d5d5-11f1-9afc-0c42a17335be";
    public static final String VERSION = "8.0.30-cynos-3.1.19.002";
    public static final String USER = "uni_pilates_runtime";
    public static final String ACKNOWLEDGEMENT = "isolated-test-private-network-no-mysql-tls";
    private CloudrunPrivateMysqlPolicy() {}

    public static boolean enabled(Environment environment) {
        return environment.getProperty("yoyi.cloudrun.private-mysql.enabled", Boolean.class, false);
    }

    public static void validate(Environment environment) {
        if (!enabled(environment)) return;
        validateSettings(String.join(",", environment.getActiveProfiles()),
                environment.getProperty("yoyi.cloudrun.private-mysql.environment-id", ""),
                environment.getProperty("yoyi.cloudrun.private-mysql.acknowledgement", ""),
                environment.getProperty("spring.datasource.url", ""),
                environment.getProperty("spring.datasource.username", ""));
    }

    public static void validateSettings(String profile, String environmentId, String acknowledgement,
            String url, String user) {
        try {
            if (!"staging".equals(profile) || !ENVIRONMENT.equals(environmentId)
                    || !ACKNOWLEDGEMENT.equals(acknowledgement) || !USER.equals(user)
                    || url == null || !url.startsWith("jdbc:mysql://")
                    || url.matches("(?s).*[\\s;\\\\%].*")) reject();
            URI uri = URI.create(url.substring(5));
            if (!HOST.equals(uri.getHost()) || uri.getPort() != 3306
                    || !("/" + DATABASE).equals(uri.getRawPath())
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() == null) reject();
            Map<String, String> query = new HashMap<>();
            Set<String> allowed = Set.of("sslMode", "connectionTimeZone", "serverTimezone", "connectTimeout", "socketTimeout");
            for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || !allowed.contains(pair[0]) || query.putIfAbsent(pair[0], pair[1]) != null) reject();
            }
            if (!"DISABLED".equals(query.get("sslMode"))
                    || !"Asia/Shanghai".equals(query.get("connectionTimeZone"))
                    || query.containsKey("serverTimezone") && !"Asia/Shanghai".equals(query.get("serverTimezone"))) reject();
            for (String timeout : Set.of("connectTimeout", "socketTimeout")) {
                String value = query.get(timeout);
                if (value == null || !value.matches("[0-9]{3,5}")
                        || Integer.parseInt(value) < 100 || Integer.parseInt(value) > 15000) reject();
            }
        } catch (IllegalArgumentException error) { reject(); }
    }

    public static void verifyTarget(String database, String uuid, String version) {
        if (!DATABASE.equals(database) || !UUID.equals(uuid) || !VERSION.equals(version)) reject();
    }

    private static void reject() {
        throw new IllegalStateException("CloudRun private MySQL exception rejected: isolated staging target and explicit acknowledgement required; values withheld.");
    }
}
