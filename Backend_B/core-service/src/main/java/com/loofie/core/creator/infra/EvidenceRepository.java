package com.loofie.core.creator.infra;

import static com.loofie.core.creator.infra.CreatorProfileRepository.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.creator.domain.Evidence;

/** creator_evidence: the screenshots behind self-reported numbers and fast-track level requests. */
@Repository
public class EvidenceRepository {

    private static final String COLUMNS = "id, creator_id, upload_key, kind, status, review_note, reviewed_by, "
            + "reviewed_at, created_at";

    private final JdbcClient jdbc;

    public EvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Evidence e) {
        jdbc.sql("""
                INSERT INTO creator_evidence (id, creator_id, upload_key, kind, status, created_at)
                VALUES (:id, :creator, :key, :kind, :status, :now)""")
                .param("id", e.id()).param("creator", e.creatorId()).param("key", e.uploadKey())
                .param("kind", e.kind().name()).param("status", e.status().name()).param("now", ts(e.createdAt()))
                .update();
    }

    public Optional<Evidence> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM creator_evidence WHERE id = :id").param("id", id)
                .query(EvidenceRepository::map).optional();
    }

    public Optional<Evidence> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM creator_evidence WHERE id = :id FOR UPDATE").param("id", id)
                .query(EvidenceRepository::map).optional();
    }

    public List<Evidence> forCreator(UUID creatorId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM creator_evidence WHERE creator_id = :id ORDER BY created_at")
                .param("id", creatorId).query(EvidenceRepository::map).list();
    }

    public long live(UUID creatorId, Evidence.Kind kind) {
        return jdbc.sql("SELECT count(*) FROM creator_evidence WHERE creator_id = :id AND kind = :kind "
                        + "AND status IN ('PENDING', 'ACCEPTED')")
                .param("id", creatorId).param("kind", kind.name()).query(Long.class).single();
    }

    /** For the ops overview: screenshots waiting to be checked. */
    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM creator_evidence WHERE status = 'PENDING'").query(Long.class).single();
    }

    /** The ops verification queue: oldest pending first. */
    public List<Evidence> pending(Instant after, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM creator_evidence WHERE status = 'PENDING' AND created_at > :after "
                        + "ORDER BY created_at LIMIT :limit")
                .param("after", ts(after)).param("limit", limit).query(EvidenceRepository::map).list();
    }

    /** One row of the ops screenshot queue, with the creator's name and handle in the same query. */
    public record QueueRow(UUID evidenceId, UUID creatorId, String displayName, String instagramHandle,
                           Evidence.Kind kind, Instant uploadedAt) {
    }

    /** The ops verification queue in one query (no lookup per row): oldest pending first. */
    public List<QueueRow> pendingQueue(Instant after, int limit) {
        return jdbc.sql("""
                SELECT e.id, e.creator_id, p.display_name, p.instagram_handle, e.kind, e.created_at
                FROM creator_evidence e JOIN creator_profiles p ON p.user_id = e.creator_id
                WHERE e.status = 'PENDING' AND e.created_at > :after
                ORDER BY e.created_at LIMIT :limit""")
                .param("after", ts(after)).param("limit", limit)
                .query((rs, n) -> new QueueRow(rs.getObject("id", UUID.class), rs.getObject("creator_id", UUID.class),
                        rs.getString("display_name"), rs.getString("instagram_handle"),
                        Evidence.Kind.valueOf(rs.getString("kind")), instant(rs.getTimestamp("created_at"))))
                .list();
    }

    public void review(UUID id, Evidence.Status status, String note, UUID by, Instant now) {
        jdbc.sql("UPDATE creator_evidence SET status = :status, review_note = :note, reviewed_by = :by, "
                        + "reviewed_at = :now WHERE id = :id")
                .param("id", id).param("status", status.name()).param("note", note).param("by", by)
                .param("now", ts(now)).update();
    }

    /** Account deletion: every screenshot is taken down (the files are deleted from storage by the cleanup). */
    public int removeAll(UUID creatorId, Instant now) {
        return jdbc.sql("UPDATE creator_evidence SET status = 'REMOVED', reviewed_at = :now WHERE creator_id = :id "
                        + "AND status <> 'REMOVED'")
                .param("id", creatorId).param("now", ts(now)).update();
    }

    private static Evidence map(ResultSet rs, int n) throws SQLException {
        return new Evidence(rs.getObject("id", UUID.class), rs.getObject("creator_id", UUID.class),
                rs.getString("upload_key"), Evidence.Kind.valueOf(rs.getString("kind")),
                Evidence.Status.valueOf(rs.getString("status")), rs.getString("review_note"),
                rs.getObject("reviewed_by", UUID.class), instant(rs.getTimestamp("reviewed_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
