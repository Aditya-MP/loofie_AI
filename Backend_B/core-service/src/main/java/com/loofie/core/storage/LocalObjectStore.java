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
    public Ticket presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
        Instant expires = clock.instant().plus(ttl);
        return new Ticket("PUT", url("PUT", key, contentType, sizeBytes, expires),
                Map.of("Content-Type", contentType, "Content-Length", String.valueOf(sizeBytes)), expires);
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return url("GET", key, "", -1, clock.instant().plus(ttl));
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

    @Override
    public void download(String key, Path target) {
        try {
            Files.copy(file(key), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StoreUnavailableException("Cannot read " + key + ": " + e, e);
        }
    }

    @Override
    public void upload(String key, Path source, String contentType) {
        try {
            Path f = file(key);
            Files.createDirectories(f.getParent());
            Files.copy(source, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(typeFile(key), contentType);
        } catch (IOException e) {
            throw new StoreUnavailableException("Cannot write " + key + ": " + e, e);
        }
    }

    /** Checks a signed URL's parameters; false when forged, altered or expired. {@code length} is -1 for a GET. */
    boolean valid(String method, String key, String contentType, long length, long expiresEpoch, String sig) {
        if (sig == null || clock.instant().getEpochSecond() > expiresEpoch) {
            return false;
        }
        return MessageDigest.isEqual(sign(method, key, contentType == null ? "" : contentType, length, expiresEpoch)
                .getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Streams one upload to disk; false (and nothing kept) unless it is exactly {@code size} bytes, as S3 refuses a body
     * that does not match the signed Content-Length.
     */
    boolean writeExactly(String key, String contentType, java.io.InputStream in, long size) {
        Path f = file(key);
        Path tmp = Path.of(f + ".part");
        try {
            Files.createDirectories(f.getParent());
            long n = 0;
            try (var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) != -1; ) {
                    n += r;
                    if (n > size) {
                        break;
                    }
                    out.write(buf, 0, r);
                }
            }
            if (n != size) {
                Files.deleteIfExists(tmp);
                return false;
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(typeFile(key), contentType);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private URI url(String method, String key, String contentType, long length, Instant expires) {
        long exp = expires.getEpochSecond();
        return URI.create(baseUrl + PATH + key + "?m=" + method + "&ct=" + URLEncoder.encode(contentType,
                StandardCharsets.UTF_8) + (length < 0 ? "" : "&len=" + length) + "&exp=" + exp
                + "&sig=" + sign(method, key, contentType, length, exp));
    }

    private String sign(String method, String key, String contentType, long length, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((method + "|" + key + "|" + contentType + "|" + length + "|"
                    + exp).getBytes(StandardCharsets.UTF_8)));
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
