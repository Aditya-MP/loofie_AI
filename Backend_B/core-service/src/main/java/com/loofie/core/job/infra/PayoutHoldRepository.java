package com.loofie.core.job.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** F10 payout holds ({@code payout_holds}) and the payout queue (jobs approved or with a payout pending). */
@Repository
public class PayoutHoldRepository {

    public record Hold(UUID id, UUID jobId, UUID placedBy, String placedRole, Instant placedAt, String reason,
                       UUID releasedBy, String releasedRole, Instant releasedAt, String releaseReason) {
    }

    /** One line of the payout queue. */
    public record QueueRow(UUID jobId, UUID brandOrgId, String status, long creatorFeePaise, Instant since,
                           UUID holdId, String holdReason, Instant heldAt) {
    }

    private final JdbcClient jdbc;

    public PayoutHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the job already has an open hold. */
    public boolean place(Hold h) {
        return jdbc.sql("""
                INSERT INTO payout_holds (id, job_id, placed_by, placed_role, placed_at, reason)
                VALUES (:id, :job, :by, :role, :at, :reason)
                ON CONFLICT (job_id) WHERE released_at IS NULL DO NOTHING""")
                .param("id", h.id()).param("job", h.jobId()).param("by", h.placedBy()).param("role", h.placedRole())
                .param("at", ts(h.placedAt())).param("reason", h.reason()).update() == 1;
    }

    public Optional<Hold> open(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE job_id = :job AND released_at IS NULL").param("job", jobId)
                .query((rs, n) -> map(rs)).optional();
    }

    public boolean isHeld(UUID jobId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payout_holds WHERE job_id = :job AND released_at IS NULL)")
                .param("job", jobId).query(Boolean.class).single();
    }

    /** Releases the open hold; false when there is none any more (released meanwhile). */
    public boolean release(UUID holdId, UUID by, String role, String reason, Instant at) {
        return jdbc.sql("UPDATE payout_holds SET released_by = :by, released_role = :role, released_at = :at, "
                        + "release_reason = :reason WHERE id = :id AND released_at IS NULL")
                .param("id", holdId).param("by", by).param("role", role).param("at", ts(at)).param("reason", reason)
                .update() == 1;
    }

    public List<Hold> history(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE job_id = :job ORDER BY placed_at").param("job", jobId)
                .query((rs, n) -> map(rs)).list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM payout_holds WHERE released_at IS NULL").query(Long.class).single();
    }

    /**
     * The payout queue: APPROVED and PAYOUT_PENDING jobs, oldest first, with their open hold if any; {@code heldOnly}
     * keeps the held ones. Keyset on (updated_at, id).
     */
    public List<QueueRow> queue(boolean heldOnly, Instant afterAt, UUID afterId, int limit) {
        String held = heldOnly ? " AND h.id IS NOT NULL" : "";
        String after = afterAt == null ? "" : " AND (j.updated_at, j.id) > (:afterAt, :afterId)";
        var spec = jdbc.sql("SELECT j.id, j.brand_org_id, j.status, j.creator_fee_paise, j.updated_at, h.id AS hold_id, "
                        + "h.reason, h.placed_at FROM jobs j "
                        + "LEFT JOIN payout_holds h ON h.job_id = j.id AND h.released_at IS NULL "
                        + "WHERE j.status IN ('APPROVED', 'PAYOUT_PENDING')" + held + after
                        + " ORDER BY j.updated_at, j.id LIMIT :limit")
                .param("limit", limit);
        if (afterAt != null) {
            spec = spec.param("afterAt", ts(afterAt)).param("afterId", afterId);
        }
        return spec.query((rs, n) -> {
            OffsetDateTime heldAt = rs.getObject("placed_at", OffsetDateTime.class);
            return new QueueRow(rs.getObject("id", UUID.class), rs.getObject("brand_org_id", UUID.class),
                    rs.getString("status"), rs.getLong("creator_fee_paise"),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getObject("hold_id", UUID.class),
                    rs.getString("reason"), heldAt == null ? null : heldAt.toInstant());
        }).list();
    }

    private static final String SELECT = "SELECT id, job_id, placed_by, placed_role, placed_at, reason, released_by, "
            + "released_role, released_at, release_reason FROM payout_holds";

    private static Hold map(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime released = rs.getObject("released_at", OffsetDateTime.class);
        return new Hold(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("placed_by", UUID.class), rs.getString("placed_role"),
                rs.getObject("placed_at", OffsetDateTime.class).toInstant(), rs.getString("reason"),
                rs.getObject("released_by", UUID.class), rs.getString("released_role"),
                released == null ? null : released.toInstant(), rs.getString("release_reason"));
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
