package com.loofie.core.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File storage (decision C1).
 *
 * @param mode       {@code none} (uploads answer 503), {@code s3} (AWS S3 / Cloudflare R2 / MinIO) or {@code local}
 *                   (files in a folder on this machine, served by the app; development and tests only)
 * @param endpoint   s3: e.g. {@code https://s3.ap-south-1.amazonaws.com} or {@code https://<account>.r2.cloudflarestorage.com}
 * @param region     s3: e.g. {@code ap-south-1}; R2 uses {@code auto}
 * @param bucket     s3: the bucket
 * @param pathStyle  s3: {@code endpoint/bucket/key} (R2, MinIO) instead of {@code bucket.endpoint/key} (AWS)
 * @param accessKey  s3: from an environment variable, never the repo
 * @param secretKey  s3: from an environment variable, never the repo
 * @param localDir   local: where files are kept (empty = a temp folder)
 * @param localBaseUrl local: how the browser reaches this app, e.g. {@code http://localhost:8081}
 * @param allowLocal only the local and test profiles set this; {@code mode=local} without it stops the app at start
 */
@ConfigurationProperties("loofie.storage")
public record StorageProperties(String mode, String endpoint, String region, String bucket, boolean pathStyle,
                                String accessKey, String secretKey, String localDir, String localBaseUrl,
                                boolean allowLocal) {

    public StorageProperties {
        mode = mode == null || mode.isBlank() ? "none" : mode.trim().toLowerCase();
        if (!mode.equals("none") && !mode.equals("s3") && !mode.equals("local")) {
            throw new IllegalStateException("loofie.storage.mode must be none, s3 or local: " + mode);
        }
        if (mode.equals("local") && !allowLocal) {
            throw new IllegalStateException("loofie.storage.mode=local is for development only; use s3 in production");
        }
        if (mode.equals("s3") && (blank(endpoint) || blank(region) || blank(bucket) || blank(accessKey)
                || blank(secretKey))) {
            throw new IllegalStateException("loofie.storage.mode=s3 needs endpoint, region, bucket, access-key and "
                    + "secret-key (LOOFIE_STORAGE_* environment variables)");
        }
        if (mode.equals("s3") && !endpoint.trim().startsWith("https://") && !allowLocal) {
            throw new IllegalStateException("loofie.storage.endpoint must be https: " + endpoint);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
