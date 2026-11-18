package com.loofie.core.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Development and tests only ({@code loofie.storage.mode=local}): files in a folder, uploaded and viewed through
 * {@link LocalStorageController} with URLs signed the same way in spirit as S3's (method, key, type, expiry), so the
 * front end and tests use exactly the production flow.
 */
final class LocalObjectStore implements ObjectStore {

    static final String PATH = "/api/core/dev/storage/";
    static final long MAX_BYTES = 20L * 1024 * 1024;

    private final Path dir;
    private final String baseUrl;
    private final Clock clock;
    private final byte[] secret = new byte[32];

    LocalObjectStore(StorageProperties props, Clock clock) {
        this.clock = clock;
        this.baseUrl = (props.localBaseUrl() == null || props.localBaseUrl().isBlank() ? "http://localhost:8081"
                : props.localBaseUrl().trim()).replaceAll("/+$", "");
        try {
            this.dir = props.localDir() == null || props.localDir().isBlank()
                    ? Files.createTempDirectory("loofie-storage") : Files.createDirectories(Path.of(props.localDir()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        new SecureRandom().nextBytes(secret);
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public Ticket presignPut(String key, String contentType, Duration ttl) {
        Instant expires = clock.instant().plus(ttl);
        return new Ticket("PUT", url("PUT", key, contentType, expires), Map.of("Content-Type", contentType), expires);
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return url("GET", key, "", clock.instant().plus(ttl));
    }

    @Override
    public Optional<ObjectInfo> stat(String key) {
        Path f = file(key);
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ObjectInfo(Files.size(f), Files.readString(typeFile(key))));
        } catch (IOException e) {
            throw new StoreUnavailableException(e.toString(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(file(key));
            Files.deleteIfExists(typeFile(key));
        } catch (IOException e) {
            throw new StoreUnavailableException(e.toString(), e);
        }
    }

    /** Checks a signed URL's parameters; false when forged, altered or expired. */
    boolean valid(String method, String key, String contentType, long expiresEpoch, String sig) {
        if (sig == null || clock.instant().getEpochSecond() > expiresEpoch) {
            return false;
        }
        return MessageDigest.isEqual(sign(method, key, contentType == null ? "" : contentType, expiresEpoch)
                .getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    void write(String key, String contentType, byte[] bytes) {
        try {
            Path f = file(key);
            Files.createDirectories(f.getParent());
            Files.write(f, bytes);
            Files.writeString(typeFile(key), contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    byte[] read(String key) {
        try {
            return Files.readAllBytes(file(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private URI url(String method, String key, String contentType, Instant expires) {
        long exp = expires.getEpochSecond();
        return URI.create(baseUrl + PATH + key + "?m=" + method + "&ct=" + URLEncoder.encode(contentType,
                StandardCharsets.UTF_8) + "&exp=" + exp + "&sig=" + sign(method, key, contentType, exp));
    }

    private String sign(String method, String key, String contentType, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((method + "|" + key + "|" + contentType + "|" + exp)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keys are ours (letters, digits, '-', '/', '.'), but never let one climb out of the folder. */
    private Path file(String key) {
        Path f = dir.resolve(key).normalize();
        if (!f.startsWith(dir) || key.contains("..")) {
            throw new IllegalArgumentException("Bad key");
        }
        return f;
    }

    private Path typeFile(String key) {
        return Path.of(file(key) + ".type");
    }
}
