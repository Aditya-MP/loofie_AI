package com.loofie.core.draft.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.draft.domain.DraftStatus;

/** {@code job_drafts}: one row per uploaded video, and the media worker's queue (lease, retry). */
@Repository
public class DraftRepository {

    public record Draft(UUID id, UUID jobId, UUID creatorId, int attempt, String kind, String masterKey,
                        long masterBytes, String previewKey, DraftStatus status, String probeJson, String failuresJson,
                        Instant submittedAt, Instant readyAt, Instant failedAt, Instant approvedAt,
                        Instant deleteAfter, int tries, String lastError, boolean opsAlerted) {
    }

    /** Files of a draft whose retention ended. */
    public record Purge(UUID id, String masterKey, String previewKey) {
    }

    private static final String COLUMNS = "id, job_id, creator_id, attempt, kind, master_key, master_bytes, preview_key, "
            + "status, probe::text AS probe, failures::text AS failures, submitted_at, ready_at, failed_at, approved_at, "
            + "delete_after, tries, last_error, ops_alerted";

    private final JdbcClient jdbc;

    public DraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int nextAttempt(UUID jobId) {
        return jdbc.sql("SELECT COALESCE(MAX(attempt), 0) + 1 FROM job_drafts WHERE job_id = :job")
                .param("job", jobId).query(Integer.class).single();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when a video is already being processed for this job */
    public void insert(UUID id, UUID jobId, UUID creatorId, int attempt, String kind, String masterKey, long bytes,
                       Instant now) {
        jdbc.sql("""
                INSERT INTO job_drafts (id, job_id, creator_id, attempt, kind, master_key, master_bytes, status,
                                        submitted_at, next_try_at, updated_at)
                VALUES (:id, :job, :creator, :attempt, :kind, :key, :bytes, 'CHECKING', :now, :now, :now)""")
                .param("id", id).param("job", jobId).param("creator", creatorId).param("attempt", attempt)
                .param("kind", kind).param("key", masterKey).param("bytes", bytes).param("now", ts(now)).update();
    }

    public Optional<Draft> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_drafts WHERE id = :id").param("id", id)
                .query(DraftRepository::row).optional();
    }

    /** Newest first. */
    public List<Draft> forJob(UUID jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_drafts WHERE job_id = :job ORDER BY attempt DESC LIMIT 20")
                .param("job", jobId).query(DraftRepository::row).list();
    }

    /** The draft the brand reviews: the newest READY one. */
    public Optional<Draft> current(UUID jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_drafts WHERE job_id = :job AND status = 'READY' "
                        + "ORDER BY attempt DESC LIMIT 1")
                .param("job", jobId).query(DraftRepository::row).optional();
    }

    /** Locks the newest READY draft (the brand's review): two reviewers at once wait for each other. */
    public Optional<Draft> currentForUpdate(UUID jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_drafts WHERE job_id = :job AND status = 'READY' "
                        + "ORDER BY attempt DESC LIMIT 1 FOR UPDATE")
                .param("job", jobId).query(DraftRepository::row).optional();
    }

    /**
     * The worker takes up to {@code limit} videos that are due and not leased by another worker (each instance takes
     * different rows: SKIP LOCKED). The lease expires on its own if the worker dies.
     */
    public List<Draft> claim(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE job_drafts SET lease_until = :until, tries = tries + 1, updated_at = :now
                WHERE id IN (SELECT id FROM job_drafts
                             WHERE status IN ('CHECKING', 'PREVIEWING') AND next_try_at <= :now
                               AND (lease_until IS NULL OR lease_until < :now)
                             ORDER BY next_try_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING""" + " " + COLUMNS)
                .param("until", ts(leaseUntil)).param("now", ts(now)).param("limit", limit)
                .query(DraftRepository::row).list();
    }

    /** Checks passed: the probe is kept, the preview comes next. */
    public void markPreviewing(UUID id, String probeJson, Instant now) {
        jdbc.sql("UPDATE job_drafts SET status = 'PREVIEWING', probe = CAST(:probe AS jsonb), updated_at = :now "
                        + "WHERE id = :id AND status = 'CHECKING'")
                .param("probe", probeJson).param("now", ts(now)).param("id", id).update();
    }

    public void setPreview(UUID id, String previewKey, Instant now) {
        jdbc.sql("UPDATE job_drafts SET preview_key = :key, updated_at = :now WHERE id = :id AND status = 'PREVIEWING'")
                .param("key", previewKey).param("now", ts(now)).param("id", id).update();
    }

    /** The video reached the brand; any older READY one of the job is replaced. */
    public void markReady(UUID id, UUID jobId, Instant now) {
        jdbc.sql("UPDATE job_drafts SET status = 'SUPERSEDED', updated_at = :now WHERE job_id = :job "
                        + "AND status = 'READY' AND id <> :id")
                .param("now", ts(now)).param("job", jobId).param("id", id).update();
        jdbc.sql("UPDATE job_drafts SET status = 'READY', ready_at = :now, lease_until = NULL, last_error = NULL, "
                        + "updated_at = :now WHERE id = :id AND status = 'PREVIEWING'")
                .param("now", ts(now)).param("id", id).update();
    }

    public void markFailed(UUID id, String failuresJson, String probeJson, Instant now, Instant deleteAfter) {
        jdbc.sql("""
                UPDATE job_drafts SET status = 'FAILED', failures = CAST(:failures AS jsonb),
                       probe = COALESCE(CAST(:probe AS jsonb), probe), failed_at = :now, delete_after = :del,
                       lease_until = NULL, updated_at = :now
                WHERE id = :id AND status IN ('CHECKING', 'PREVIEWING')""")
                .param("failures", failuresJson).param("probe", probeJson).param("now", ts(now))
                .param("del", ts(deleteAfter)).param("id", id).update();
    }

    /** The job moved on while the video was processed (cancelled, replaced creator...): it is kept, not delivered. */
    public void markSuperseded(UUID id, String why, Instant now, Instant deleteAfter) {
        jdbc.sql("UPDATE job_drafts SET status = 'SUPERSEDED', last_error = :why, lease_until = NULL, "
                        + "delete_after = :del, updated_at = :now WHERE id = :id AND status IN ('CHECKING', 'PREVIEWING')")
                .param("why", cut(why)).param("del", ts(deleteAfter)).param("now", ts(now)).param("id", id).update();
    }

    /** Our problem (storage, tools): try again later. */
    public void retryLater(UUID id, Instant nextTry, String error, Instant now) {
        jdbc.sql("UPDATE job_drafts SET next_try_at = :next, last_error = :err, lease_until = NULL, updated_at = :now "
                        + "WHERE id = :id AND status IN ('CHECKING', 'PREVIEWING')")
                .param("next", ts(nextTry)).param("err", cut(error)).param("now", ts(now)).param("id", id).update();
    }

    public void markOpsAlerted(UUID id) {
        jdbc.sql("UPDATE job_drafts SET ops_alerted = true WHERE id = :id").param("id", id).update();
    }

    /** A video uploaded before {@code deadline} is still being processed. */
    public boolean inFlightSince(UUID jobId, Instant deadline) {
        if (deadline == null) {
            return jdbc.sql("SELECT EXISTS (SELECT 1 FROM job_drafts WHERE job_id = :job AND status IN ('CHECKING', "
                            + "'PREVIEWING'))")
                    .param("job", jobId).query(Boolean.class).single();
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM job_drafts WHERE job_id = :job AND status IN ('CHECKING', "
                        + "'PREVIEWING') AND submitted_at <= :deadline)")
                .param("job", jobId).param("deadline", ts(deadline)).query(Boolean.class).single();
    }

    /** Checks passed and the preview is stored, not delivered yet: its upload time. */
    public Optional<Instant> deliverable(UUID jobId, UUID draftId) {
        return jdbc.sql("SELECT submitted_at FROM job_drafts WHERE id = :id AND job_id = :job AND status = 'PREVIEWING' "
                        + "AND preview_key IS NOT NULL")
                .param("id", draftId).param("job", jobId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).optional();
    }

    /** D9: the approved video and its earlier versions are kept until {@code deleteAfter}. */
    public void approved(UUID jobId, Instant at, Instant deleteAfter) {
        jdbc.sql("UPDATE job_drafts SET approved_at = :at, updated_at = :at WHERE job_id = :job AND status = 'READY'")
                .param("at", ts(at)).param("job", jobId).update();
        jdbc.sql("UPDATE job_drafts SET delete_after = :del, updated_at = :at WHERE job_id = :job "
                        + "AND status IN ('READY', 'SUPERSEDED')")
                .param("del", ts(deleteAfter)).param("at", ts(at)).param("job", jobId).update();
    }

    public Optional<Instant> approvedAt(UUID jobId) {
        return jdbc.sql("SELECT approved_at FROM job_drafts WHERE job_id = :job AND approved_at IS NOT NULL "
                        + "ORDER BY approved_at DESC LIMIT 1")
                .param("job", jobId).query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).optional();
    }

    public List<Purge> dueForPurge(Instant now, int limit) {
        return jdbc.sql("SELECT id, master_key, preview_key FROM job_drafts WHERE delete_after <= :now "
                        + "AND status NOT IN ('PURGED', 'CHECKING', 'PREVIEWING') ORDER BY delete_after LIMIT :limit")
                .param("now", ts(now)).param("limit", limit)
                .query((rs, n) -> new Purge(rs.getObject("id", UUID.class), rs.getString("master_key"),
                        rs.getString("preview_key")))
                .list();
    }

    public void markPurged(UUID id, Instant now) {
        jdbc.sql("UPDATE job_drafts SET status = 'PURGED', updated_at = :now WHERE id = :id")
                .param("now", ts(now)).param("id", id).update();
    }

    /** Jobs with videos that never got a retention date (never approved) and were uploaded before {@code before}. */
    public List<UUID> jobsWithoutRetention(Instant before, int limit) {
        return jdbc.sql("SELECT DISTINCT job_id FROM job_drafts WHERE delete_after IS NULL AND submitted_at < :before "
                        + "AND status IN ('READY', 'SUPERSEDED') LIMIT :limit")
                .param("before", ts(before)).param("limit", limit).query(UUID.class).list();
    }

    public void setRetention(UUID jobId, Instant deleteAfter, Instant now) {
        jdbc.sql("UPDATE job_drafts SET delete_after = :del, updated_at = :now WHERE job_id = :job "
                        + "AND delete_after IS NULL AND status IN ('READY', 'SUPERSEDED')")
                .param("del", ts(deleteAfter)).param("now", ts(now)).param("job", jobId).update();
    }

    /** Processing health for ops: videos waiting and the oldest wait. */
    public record Backlog(long waiting, Instant oldestSubmittedAt) {
    }

    public Backlog backlog() {
        return jdbc.sql("SELECT count(*) AS n, min(submitted_at) AS oldest FROM job_drafts "
                        + "WHERE status IN ('CHECKING', 'PREVIEWING')")
                .query((rs, n) -> {
                    OffsetDateTime o = rs.getObject("oldest", OffsetDateTime.class);
                    return new Backlog(rs.getLong("n"), o == null ? null : o.toInstant());
                }).single();
    }

    private static Draft row(ResultSet rs, int n) throws SQLException {
        return new Draft(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("creator_id", UUID.class), rs.getInt("attempt"), rs.getString("kind"),
                rs.getString("master_key"), rs.getLong("master_bytes"), rs.getString("preview_key"),
                DraftStatus.valueOf(rs.getString("status")), rs.getString("probe"), rs.getString("failures"),
                inst(rs, "submitted_at"), inst(rs, "ready_at"), inst(rs, "failed_at"), inst(rs, "approved_at"),
                inst(rs, "delete_after"), rs.getInt("tries"), rs.getString("last_error"), rs.getBoolean("ops_alerted"));
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
