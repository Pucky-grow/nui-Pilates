package com.yoyi.pilates.infra.storage;

/** Validates explicit configuration before any SDK or database connection; never prints values. */
public final class CosStorageSettings {
    private CosStorageSettings() {}

    public static void validate(String region, String bucket, String secretId, String secretKey) {
        if (region == null || !region.matches("[a-z]{2}-[a-z]+(?:-[0-9]+)?")
                || bucket == null || bucket.length() > 63
                || !bucket.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?-[0-9]+")
                || secretId == null || secretId.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Invalid COS configuration; values withheld.");
        }
    }
}
