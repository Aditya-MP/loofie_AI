package com.loofie.core.job.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** F9 swap offers ({@code job_swap_offers}): ops proposes a replacement creator, the brand decides. */
@Repository
public class SwapOfferRepository {

    public enum Status { OFFERED, APPROVED, DECLINED, WITHDRAWN }

    public record Offer(UUID id, UUID jobId, UUID creatorId, int ratePerReelRupees, Status status, String note,
                        String suggestionId, UUID offeredBy, Instant offeredAt, UUID decidedBy, Instant decidedAt,
                        String declineReason, String closedReason) {
    }

    private final JdbcClient jdbc;

    public SwapOfferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the job already has an open offer (the partial unique index decides, also under a race). */
    public boolean insert(Offer o) {
        return jdbc.sql("""
                INSERT INTO job_swap_offers (id, job_id, creator_id, rate_per_reel_rupees, status, note, suggestion_id,
                                             offered_by, offered_at)
                VALUES (:id, :job, :creator, :rate, 'OFFERED', :note, :suggestion, :by, :at)
                ON CONFLICT (job_id) WHERE status = 'OFFERED' DO NOTHING""")
                .param("id", o.id()).param("job", o.jobId()).param("creator", o.creatorId())
                .param("rate", o.ratePerReelRupees()).param("note", o.note()).param("suggestion", o.suggestionId())
                .param("by", o.offeredBy()).param("at", ts(o.offeredAt())).update() == 1;
    }

    public Optional<Offer> open(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE job_id = :job AND status = 'OFFERED'").param("job", jobId)
                .query((rs, n) -> map(rs)).optional();
    }

    public List<Offer> forJob(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE job_id = :job ORDER BY offered_at").param("job", jobId)
                .query((rs, n) -> map(rs)).list();
    }

    /** Closes the open offer for this creator as APPROVED. False when there is none (no swap without an offer). */
    public boolean approve(UUID jobId, UUID creatorId, UUID by, Instant at) {
        return jdbc.sql("UPDATE job_swap_offers SET status = 'APPROVED', decided_by = :by, decided_at = :at "
                        + "WHERE job_id = :job AND creator_id = :creator AND status = 'OFFERED'")
                .param("job", jobId).param("creator", creatorId).param("by", by).param("at", ts(at)).update() == 1;
    }

    public boolean decline(UUID offerId, UUID by, String reason, Instant at) {
        return jdbc.sql("UPDATE job_swap_offers SET status = 'DECLINED', decided_by = :by, decided_at = :at, "
                        + "decline_reason = :reason WHERE id = :id AND status = 'OFFERED'")
                .param("id", offerId).param("by", by).param("reason", reason).param("at", ts(at)).update() == 1;
    }

    /** Withdraws the job's open offer (ops, or the job ended without a swap). */
    public boolean withdrawOpen(UUID jobId, UUID by, String why, Instant at) {
        return jdbc.sql("UPDATE job_swap_offers SET status = 'WITHDRAWN', decided_by = :by, decided_at = :at, "
                        + "closed_reason = :why WHERE job_id = :job AND status = 'OFFERED'")
                .param("job", jobId).param("by", by).param("why", why).param("at", ts(at)).update() == 1;
    }

    private static final String SELECT = "SELECT id, job_id, creator_id, rate_per_reel_rupees, status, note, "
            + "suggestion_id, offered_by, offered_at, decided_by, decided_at, decline_reason, closed_reason "
            + "FROM job_swap_offers";

    private static Offer map(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime decided = rs.getObject("decided_at", OffsetDateTime.class);
        return new Offer(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("creator_id", UUID.class), rs.getInt("rate_per_reel_rupees"),
                Status.valueOf(rs.getString("status")), rs.getString("note"), rs.getString("suggestion_id"),
                rs.getObject("offered_by", UUID.class), rs.getObject("offered_at", OffsetDateTime.class).toInstant(),
                rs.getObject("decided_by", UUID.class), decided == null ? null : decided.toInstant(),
                rs.getString("decline_reason"), rs.getString("closed_reason"));
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
