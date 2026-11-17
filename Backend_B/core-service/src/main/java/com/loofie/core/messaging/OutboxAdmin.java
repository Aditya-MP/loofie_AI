package com.loofie.core.messaging;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;

/**
 * Ops' view of events Backend A refused for good (parked): what, for which job, and why; and a resend once the
 * cause is fixed, so this never needs an engineer with SQL. Resending is for an OPS_LEAD and is logged.
 */
@Service
public class OutboxAdmin {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdmin.class);

    /** {@code jobId} is the message key (a job id for every job event). The envelope is not shown. */
    public record Parked(UUID id, String eventType, String jobId, Instant createdAt, Instant parkedAt, int attempts,
                         String lastError) {
    }

    private final JdbcClient jdbc;

    public OutboxAdmin(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Parked> parked(Actor actor, int limit) {
        actor.requireRole(ActorRole.OPS, "Outbox tools are for ops");
        return jdbc.sql("""
                SELECT id, event_type, message_key, created_at, parked_at, attempts, last_error FROM outbox
                WHERE parked_at IS NOT NULL ORDER BY parked_at DESC LIMIT :limit
                """)
                .param("limit", Math.min(Math.max(limit, 1), 500))
                .query((rs, n) -> new Parked(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("message_key"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("parked_at", OffsetDateTime.class).toInstant(), rs.getInt("attempts"),
                        rs.getString("last_error")))
                .list();
    }

    /** Puts a parked event back in the queue (after its cause was fixed). False when it is not parked. */
    @Transactional
    public boolean requeue(Actor actor, UUID id) {
        if (actor.role() != ActorRole.OPS_LEAD && actor.role() != ActorRole.FOUNDER) {
            throw new ActorNotAllowedException("Resending a refused event is for an OPS_LEAD");
        }
        int n = jdbc.sql("""
                UPDATE outbox SET parked_at = NULL, attempts = 0, last_error = NULL, next_attempt_at = NULL
                WHERE id = :id AND parked_at IS NOT NULL AND published_at IS NULL
                """).param("id", id).update();
        if (n == 1) {
            log.info("Outbox row {} requeued by {} {}", id, actor.role(), actor.userId());
        }
        return n == 1;
    }
}
