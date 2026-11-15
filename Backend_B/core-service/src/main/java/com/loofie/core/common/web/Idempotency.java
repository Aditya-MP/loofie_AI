package com.loofie.core.common.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} support (IETF draft-ietf-httpapi-idempotency-key-header, the Stripe model).
 *
 * <p>The key row is inserted <b>first, in the same transaction as the action</b>:
 * <ul>
 *   <li>first request: the row is new, the action runs, its response is stored, both commit together;</li>
 *   <li>a retry after that commit: the insert conflicts, the stored response is returned unchanged
 *       and the action does not run again;</li>
 *   <li>a retry while the first is still running: Postgres makes the insert wait for the first
 *       transaction; if it commits, the retry gets its response; if it failed, the retry runs;</li>
 *   <li>the same key with a different request: refused (a client bug, never silently ignored).</li>
 * </ul>
 * A failed action stores nothing, so retrying a failed request runs it again. Keys are scoped per
 * caller and kept for 24 hours (see RetentionJob).
 */
@Component
public class Idempotency {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID_KEY = Pattern.compile("^[\\x21-\\x7E]{1,128}$"); // printable ASCII, no spaces

    /** The action's result and whether it was replayed from an earlier identical request. */
    public record Outcome<T>(T value, boolean replayed) {
    }

    public static class InvalidKeyException extends RuntimeException {
        InvalidKeyException() {
            super(HEADER + " must be 1-128 printable ASCII characters without spaces");
        }
    }

    public static class KeyRequiredException extends RuntimeException {
        public KeyRequiredException(String what) {
            super(what + " needs an " + HEADER + " header (a new UUID per user action, reused for its retries)");
        }
    }

    public static class KeyReusedException extends RuntimeException {
        KeyReusedException() {
            super("This " + HEADER + " was already used for a different request");
        }
    }

    public static class InProgressException extends RuntimeException {
        InProgressException() {
            super("A request with this " + HEADER + " is still being processed; retry shortly");
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public Idempotency(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public <T> Outcome<T> run(String scope, String key, String requestHash, Class<T> type, Supplier<T> action) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new InvalidKeyException();
        }
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (scope, idem_key, request_hash, response, created_at)
                VALUES (:scope, :key, :hash, NULL, :now)
                ON CONFLICT (scope, idem_key) DO NOTHING
                """)
                .param("scope", scope)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .update();

        if (inserted == 1) {
            T value = action.get();
            jdbc.sql("UPDATE idempotency_keys SET response = CAST(:response AS jsonb) WHERE scope = :scope AND idem_key = :key")
                    .param("response", json.writeValueAsString(value))
                    .param("scope", scope)
                    .param("key", key)
                    .update();
            return new Outcome<>(value, false);
        }

        var stored = jdbc.sql("SELECT request_hash, response::text AS response FROM idempotency_keys"
                        + " WHERE scope = :scope AND idem_key = :key")
                .param("scope", scope)
                .param("key", key)
                .query().singleRow();
        if (!requestHash.equals(stored.get("request_hash"))) {
            throw new KeyReusedException();
        }
        Object response = stored.get("response");
        if (response == null) {
            throw new InProgressException();
        }
        return new Outcome<>(json.readValue((String) response, type), true);
    }

    /** SHA-256 (hex) of the parts that identify a request, e.g. the path id and the JSON body. */
    public static String hash(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                sha.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
