package com.loofie.core.job.infra;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.JobDeadline;
import com.loofie.core.job.domain.JobStatus;

/**
 * SQL for the timer worker: which jobs have a deadline due, and which reminders were already sent
 * ({@code deadline_notices}). Read-only on {@code jobs}: state changes always go through the engine.
 */
@Repository
public class JobDeadlineRepository {

    /** A job whose deadline has passed. */
    public record DueJob(UUID jobId, Instant dueAt) {
    }

    /** A reminder that is due and not yet sent. */
    public record DueNotice(UUID jobId, UUID orderId, long version, Instant dueAt) {
    }

    private final JdbcClient jdbc;

    public JobDeadlineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Jobs in {@code status} whose {@code deadline} is at or before {@code now}, oldest first, skipping {@code excluded}. */
    public List<DueJob> due(JobStatus status, JobDeadline deadline, Instant now, Collection<UUID> excluded, int limit) {
        String col = "j." + DeadlineColumns.of(deadline);
        String skip = excluded.isEmpty() ? "" : " AND j.id NOT IN (:excluded)";
        var spec = jdbc.sql("SELECT j.id, " + col + " AS due_at FROM jobs j WHERE j.status = :status AND " + col
                        + " <= :now" + skip + " ORDER BY " + col + " LIMIT :limit")
                .param("status", status.name())
                .param("now", utc(now))
                .param("limit", limit);
        if (!excluded.isEmpty()) {
            spec = spec.param("excluded", List.copyOf(excluded));
        }
        return spec.query((rs, n) -> new DueJob(rs.getObject("id", UUID.class),
                rs.getObject("due_at", OffsetDateTime.class).toInstant())).list();
    }

    /**
     * Jobs in {@code status} whose reminder {@code kind} is due ({@code before} ahead of {@code deadline})
     * and has not been sent for that due time yet.
     */
    public List<DueNotice> dueNotices(String kind, JobStatus status, JobDeadline deadline, Duration before, Instant now,
                                      int limit) {
        String col = "j." + DeadlineColumns.of(deadline);
        String due = "(" + col + " - make_interval(secs => :beforeSecs))";
        // "col <= now + before" (not "col - before <= now") so the column's index is used.
        return jdbc.sql("SELECT j.id, j.order_id, j.version, " + due + " AS due_at FROM jobs j"
                        + " WHERE j.status = :status AND " + col + " <= :horizon"
                        + " AND NOT EXISTS (SELECT 1 FROM deadline_notices n"
                        + "   WHERE n.job_id = j.id AND n.kind = :kind AND n.due_at = " + due + ")"
                        + " ORDER BY " + col + " LIMIT :limit")
                .param("status", status.name())
                .param("kind", kind)
                .param("beforeSecs", (double) before.toSeconds())
                .param("horizon", utc(now.plus(before)))
                .param("limit", limit)
                .query((rs, n) -> new DueNotice(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getLong("version"), rs.getObject("due_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Records a sent reminder. False if it was already recorded (another run or instance got there first). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordNotice(UUID jobId, String kind, Instant dueAt, UUID eventId, Instant sentAt) {
        return jdbc.sql("""
                INSERT INTO deadline_notices (job_id, kind, due_at, event_id, sent_at)
                VALUES (:jobId, :kind, :dueAt, :eventId, :sentAt)
                ON CONFLICT DO NOTHING
                """)
                .param("jobId", jobId).param("kind", kind).param("dueAt", utc(dueAt))
                .param("eventId", eventId).param("sentAt", utc(sentAt))
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
