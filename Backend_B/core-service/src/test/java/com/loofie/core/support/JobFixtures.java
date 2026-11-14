package com.loofie.core.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.JobStatus;

/**
 * Inserts jobs directly in a chosen state, bypassing the engine, so tests can start anywhere
 * in the state machine. Test-only: production code never writes job status like this.
 */
@Component
public class JobFixtures {

    public record Seeded(UUID orderId, UUID jobId, UUID brandOrgId, UUID creatorId) {
    }

    private final JdbcClient jdbc;

    public JobFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Seeded jobIn(JobStatus status) {
        return jobIn(status, false, false);
    }

    /** Creates an order + job in {@code status}. Adds an active creator assignment when the state implies one. */
    public Seeded jobIn(JobStatus status, boolean digitalOnly, boolean freeRevisionUsed) {
        UUID orderId = Ids.newId();
        UUID jobId = Ids.newId();
        UUID brandOrgId = Ids.newId();
        UUID creatorId = Ids.newId();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

        jdbc.sql("INSERT INTO orders (id, brand_org_id, created_at, version) VALUES (?, ?, ?, 0)")
                .params(orderId, brandOrgId, now).update();
        jdbc.sql("""
                INSERT INTO jobs (id, order_id, brand_org_id, status, digital_only, creator_fee_paise,
                                  free_revision_used, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 500000, ?, ?, ?, 0)
                """)
                .params(jobId, orderId, brandOrgId, status.name(), digitalOnly, freeRevisionUsed, now, now).update();

        if (hasActiveCreator(status)) {
            jdbc.sql("""
                    INSERT INTO assignments (id, job_id, creator_id, status, opened_at, version)
                    VALUES (?, ?, ?, 'ACTIVE', ?, 0)
                    """)
                    .params(Ids.newId(), jobId, creatorId, now).update();
        }
        return new Seeded(orderId, jobId, brandOrgId, creatorId);
    }

    /** Sets one deadline column directly, e.g. to make a timer due. Column names come from tests only. */
    public void setDeadline(UUID jobId, String column, Instant at) {
        jdbc.sql("UPDATE jobs SET " + column + " = ? WHERE id = ?")
                .params(OffsetDateTime.ofInstant(at, ZoneOffset.UTC), jobId).update();
    }

    public long eventCount(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM job_events WHERE job_id = ?").param(jobId).query(Long.class).single();
    }

    public long outboxCount(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ?").param(jobId.toString())
                .query(Long.class).single();
    }

    public String status(UUID jobId) {
        return jdbc.sql("SELECT status FROM jobs WHERE id = ?").param(jobId).query(String.class).single();
    }

    private static boolean hasActiveCreator(JobStatus s) {
        return switch (s) {
            case CREATOR_INVITED, CREATOR_ACCEPTED, PRODUCT_SHIPPED, DELIVERY_ISSUE, PRODUCT_RECEIVED, IN_PRODUCTION,
                 DRAFT_SUBMITTED, IN_REVIEW, REVISION_REQUESTED, APPROVED, DISPUTED, PAYOUT_PENDING,
                 PAID_OUT, CLOSED -> true;
            default -> false;
        };
    }
}
