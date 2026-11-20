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
    private final java.time.Clock clock;

    public JobFixtures(JdbcClient jdbc, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * F7: a video that passed its checks and has its preview, as the media worker hands it to the engine. Reuses the
     * job's video in processing if there is one. Uploaded now (the test clock): "on time" is judged by this.
     */
    public com.loofie.core.job.app.TransitionDetails checkedDraft(UUID jobId) {
        UUID existing = jdbc.sql("SELECT id FROM job_drafts WHERE job_id = ? AND status = 'PREVIEWING'")
                .param(jobId).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return com.loofie.core.job.app.TransitionDetails.draft(existing);
        }
        UUID creator = jdbc.sql("SELECT creator_id FROM assignments WHERE job_id = ? AND status = 'ACTIVE'")
                .param(jobId).query(UUID.class).optional().orElse(Ids.newId());
        int attempt = jdbc.sql("SELECT COALESCE(MAX(attempt), 0) + 1 FROM job_drafts WHERE job_id = ?").param(jobId)
                .query(Integer.class).single();
        UUID id = Ids.newId();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO job_drafts (id, job_id, creator_id, attempt, kind, master_key, master_bytes, preview_key,
                                        status, submitted_at, next_try_at, lease_until, updated_at)
                VALUES (?, ?, ?, ?, 'FIRST', ?, 1000, ?, 'PREVIEWING', ?, ?, ?, ?)""")
                .params(id, jobId, creator, attempt, "drafts/" + creator + "/" + id + ".mp4",
                        "previews/" + creator + "/" + id + ".mp4", now, now.plusYears(10), now.plusYears(10), now)
                .update();
        return com.loofie.core.job.app.TransitionDetails.draft(id);
    }

    /** Marks the job's video in processing as delivered (what the worker does after PREVIEW_READY). */
    public void draftDelivered(UUID jobId) {
        jdbc.sql("UPDATE job_drafts SET status = 'READY', ready_at = submitted_at WHERE job_id = ? "
                        + "AND status = 'PREVIEWING'").param(jobId).update();
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

    /**
     * F9: an open swap offer for {@code creatorId}, as ops would make it, so engine tests can approve a swap with a
     * made-up creator (the offer endpoint itself needs a real, bookable profile: see DisputeFlowTest).
     */
    public void swapOffered(UUID jobId, UUID creatorId) {
        jdbc.sql("""
                INSERT INTO job_swap_offers (id, job_id, creator_id, rate_per_reel_rupees, status, note, offered_by,
                                             offered_at)
                VALUES (:id, :job, :creator, 1000, 'OFFERED', 'test offer', :ops, now())""")
                .param("id", UUID.randomUUID()).param("job", jobId).param("creator", creatorId)
                .param("ops", UUID.randomUUID()).update();
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

    /** A valid receipt photo storage key (the only format the engine accepts). */
    public static String photoKey() {
        return "receipts/" + UUID.randomUUID() + ".jpg";
    }

    /** A tracking number no other job uses (duplicate numbers across brands are blocked). */
    public static String trackingNumber() {
        return "DL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** Whether {@link #jobIn} puts an active creator on a job in this state. */
    public static boolean hasActiveCreator(JobStatus s) {
        return switch (s) {
            case CREATOR_INVITED, CREATOR_ACCEPTED, PRODUCT_SHIPPED, DELIVERY_ISSUE, PRODUCT_RECEIVED, IN_PRODUCTION,
                 DRAFT_SUBMITTED, IN_REVIEW, REVISION_REQUESTED, APPROVED, DISPUTED, PAYOUT_PENDING,
                 PAID_OUT, CLOSED -> true;
            default -> false;
        };
    }
}
