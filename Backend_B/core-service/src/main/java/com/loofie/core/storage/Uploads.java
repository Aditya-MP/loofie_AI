package com.loofie.core.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;

/**
 * Every storage key the app hands out, who it belongs to, and whether it was used. A profile (or later a job) accepts
 * only a key issued here, to that person, for that purpose, that really holds a file within the size limit: never a
 * URL or free text (ops opens these files: stored XSS / phishing risk, the F6 rule).
 */
@Component
public class Uploads {

    /** What an upload is for decides where it goes, which types it may be and how big. */
    public enum Purpose {
        CREATOR_EVIDENCE("creator-evidence", 5L * 1024 * 1024,
                Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/heic", "heic"));

        final String prefix;
        final long maxBytes;
        final Map<String, String> types;

        Purpose(String prefix, long maxBytes, Map<String, String> types) {
            this.prefix = prefix;
            this.maxBytes = maxBytes;
            this.types = types;
        }

        public long maxBytes() {
            return maxBytes;
        }

        public List<String> types() {
            return types.keySet().stream().sorted().toList();
        }
    }

    public static final Duration TICKET_TTL = Duration.ofMinutes(5);
    public static final Duration VIEW_TTL = Duration.ofMinutes(5);

    /** What the browser needs to upload one file. */
    public record UploadTicket(String key, String method, URI url, Map<String, String> headers, Instant expiresAt,
                               long maxBytes) {
    }

    /** The upload cannot be used as asked (wrong type, someone else's key, missing file, too big): 422. */
    public static final class UploadRefusedException extends RuntimeException {
        public UploadRefusedException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final ObjectStore store;
    private final Clock clock;

    public Uploads(JdbcClient jdbc, ObjectStore store, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.clock = clock;
    }

    public boolean ready() {
        return store.ready();
    }

    /** Issues a key and a 5-minute signed upload URL for one file. */
    public UploadTicket issue(UUID owner, Purpose purpose, String contentType) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase();
        String ext = purpose.types.get(type);
        if (ext == null) {
            throw new UploadRefusedException("This file type is not accepted. Use " + String.join(", ",
                    purpose.types()));
        }
        String key = purpose.prefix + "/" + owner + "/" + Ids.newId() + "." + ext;
        ObjectStore.Ticket t = store.presignPut(key, type, TICKET_TTL); // 503 before anything is recorded
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO uploads (key, owner_id, purpose, content_type, max_bytes, issued_at, expires_at)
                VALUES (:key, :owner, :purpose, :type, :max, :now, :exp)""")
                .param("key", key).param("owner", owner).param("purpose", purpose.name()).param("type", type)
                .param("max", purpose.maxBytes).param("now", ts(now)).param("exp", ts(t.expiresAt())).update();
        return new UploadTicket(key, t.method(), t.url(), t.headers(), t.expiresAt(), purpose.maxBytes);
    }

    /**
     * Checks a key before it is attached to something: issued to this owner for this purpose, not used yet, and the
     * file is really there and within the limit. Asks the store (HTTP), so call it outside a transaction.
     *
     * @return the stored size
     */
    public long verify(String key, UUID owner, Purpose purpose) {
        Row row = find(key).orElseThrow(() -> new UploadRefusedException("Unknown upload. Upload the file first."));
        if (!row.owner().equals(owner) || !row.purpose().equals(purpose.name()) || row.deleted()) {
            throw new UploadRefusedException("Unknown upload. Upload the file first.");
        }
        if (row.used()) {
            throw new UploadRefusedException("This upload is already attached.");
        }
        ObjectStore.ObjectInfo info = store.stat(key).orElseThrow(() -> new UploadRefusedException(
                "The file was not uploaded (or the upload did not finish). Try again."));
        if (info.size() <= 0 || info.size() > row.maxBytes()) {
            throw new UploadRefusedException("The file must be at most " + (row.maxBytes() / (1024 * 1024)) + " MB.");
        }
        return info.size();
    }

    /** Marks a verified key as used, inside the caller's transaction. False if it was used meanwhile. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markUsed(String key, long size) {
        return jdbc.sql("UPDATE uploads SET used_at = :now, size_bytes = :size WHERE key = :key AND used_at IS NULL "
                        + "AND deleted_at IS NULL")
                .param("now", ts(clock.instant())).param("size", size).param("key", key).update() == 1;
    }

    /** A short-lived URL to look at one file (ops review). */
    public URI viewUrl(String key) {
        return store.presignGet(key, VIEW_TTL);
    }

    /** The file is no longer needed: it is removed from storage by the cleanup, never left behind. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestDelete(String key) {
        jdbc.sql("UPDATE uploads SET delete_requested_at = :now WHERE key = :key AND delete_requested_at IS NULL")
                .param("now", ts(clock.instant())).param("key", key).update();
    }

    /** Account deletion: every file this person uploaded is queued for removal from storage. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int requestDeleteAll(UUID owner) {
        return jdbc.sql("UPDATE uploads SET delete_requested_at = :now WHERE owner_id = :owner "
                        + "AND delete_requested_at IS NULL AND deleted_at IS NULL")
                .param("now", ts(clock.instant())).param("owner", owner).update();
    }

    /**
     * Files to remove now: queued for deletion, or issued but never attached and long expired (a ticket nobody used
     * leaves a stray file behind if the upload happened).
     */
    List<String> dueForRemoval(Instant unusedBefore, int limit) {
        return jdbc.sql("""
                (SELECT key FROM uploads WHERE delete_requested_at IS NOT NULL AND deleted_at IS NULL
                 ORDER BY delete_requested_at LIMIT :limit)
                UNION ALL
                (SELECT key FROM uploads WHERE used_at IS NULL AND deleted_at IS NULL AND expires_at < :before
                 AND delete_requested_at IS NULL ORDER BY expires_at LIMIT :limit)""")
                .param("limit", limit).param("before", ts(unusedBefore)).query(String.class).list();
    }

    void markDeleted(String key) {
        jdbc.sql("UPDATE uploads SET deleted_at = :now WHERE key = :key AND deleted_at IS NULL")
                .param("now", ts(clock.instant())).param("key", key).update();
    }

    Optional<Row> find(String key) {
        return jdbc.sql("SELECT owner_id, purpose, max_bytes, used_at IS NOT NULL AS used, "
                        + "deleted_at IS NOT NULL OR delete_requested_at IS NOT NULL AS deleted FROM uploads WHERE key = :key")
                .param("key", key)
                .query((rs, n) -> new Row(rs.getObject("owner_id", UUID.class), rs.getString("purpose"),
                        rs.getLong("max_bytes"), rs.getBoolean("used"), rs.getBoolean("deleted")))
                .optional();
    }

    record Row(UUID owner, String purpose, long maxBytes, boolean used, boolean deleted) {
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
