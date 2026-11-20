package com.loofie.core.refund.infra;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.JobAction;
import com.loofie.core.refund.domain.RefundCase;
import com.loofie.core.refund.domain.RefundReason;

/** SQL for {@code refund_requests}. A decided row is frozen by a database trigger (V11). */
@Repository
public class RefundRequestRepository {

    public enum Status { REQUESTED, APPROVED, REJECTED, WITHDRAWN }

    /** One request as stored. Money in paise. {@code note} is ops free text: it never leaves core. */
    public record Row(UUID id, UUID jobId, UUID brandOrgId, JobAction action, RefundCase refundCase,
                      RefundReason reason, String note, long brandCash, long brandCredit, long creatorKillFee,
                      boolean creatorAccepted, boolean leadMaySelfApprove, boolean founderRequired, UUID requestedBy,
                      String requestedByRole, Instant requestedAt, Status status, UUID leadApprovedBy,
                      Instant leadApprovedAt, UUID founderApprovedBy, Instant founderApprovedAt, UUID decidedBy,
                      Instant decidedAt, String decisionNote, String jobReasonCode) {

        public long total() {
            return brandCash + brandCredit + creatorKillFee;
        }
    }

    private static final String COLUMNS = """
            id, job_id, brand_org_id, action, refund_case, reason_code, note, brand_cash_paise, brand_credit_paise,
            creator_kill_fee_paise, creator_accepted, lead_may_self_approve, founder_required, requested_by,
            requested_by_role, requested_at, status, lead_approved_by, lead_approved_at, founder_approved_by,
            founder_approved_at, decided_by, decided_at, decision_note, job_reason_code""";

    private final JdbcClient jdbc;

    public RefundRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Row r) {
        jdbc.sql("""
                INSERT INTO refund_requests (id, job_id, brand_org_id, action, refund_case, reason_code, note,
                    brand_cash_paise, brand_credit_paise, creator_kill_fee_paise, creator_accepted, lead_may_self_approve,
                    founder_required, requested_by, requested_by_role, requested_at, status, version, job_reason_code)
                VALUES (:id, :job, :brand, :action, :case, :reason, :note, :cash, :credit, :kill, :accepted, :self,
                    :founder, :by, :role, :at, 'REQUESTED', 0, :jobCode)
                """)
                .param("id", r.id()).param("job", r.jobId()).param("brand", r.brandOrgId())
                .param("action", r.action().name()).param("case", r.refundCase().name())
                .param("reason", r.reason().name()).param("note", r.note()).param("cash", r.brandCash())
                .param("credit", r.brandCredit()).param("kill", r.creatorKillFee())
                .param("accepted", r.creatorAccepted()).param("self", r.leadMaySelfApprove())
                .param("founder", r.founderRequired()).param("by", r.requestedBy()).param("role", r.requestedByRole())
                .param("at", utc(r.requestedAt())).param("jobCode", r.jobReasonCode())
                .update();
    }

    public Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund_requests WHERE id = :id").param("id", id)
                .query(RefundRequestRepository::row).optional();
    }

    /** Locks the row for a decision: two approvers at the same moment are serialised here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Row> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund_requests WHERE id = :id FOR UPDATE").param("id", id)
                .query(RefundRequestRepository::row).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void leadApproved(UUID id, UUID by, Instant at) {
        jdbc.sql("""
                UPDATE refund_requests SET lead_approved_by = :by, lead_approved_at = :at, version = version + 1
                WHERE id = :id AND status = 'REQUESTED'
                """).param("id", id).param("by", by).param("at", utc(at)).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void founderApproved(UUID id, UUID by, Instant at) {
        jdbc.sql("""
                UPDATE refund_requests SET founder_approved_by = :by, founder_approved_at = :at, version = version + 1
                WHERE id = :id AND status = 'REQUESTED'
                """).param("id", id).param("by", by).param("at", utc(at)).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void decide(UUID id, Status status, UUID by, Instant at, String note) {
        int n = jdbc.sql("""
                UPDATE refund_requests SET status = :status, decided_by = :by, decided_at = :at, decision_note = :note,
                    version = version + 1
                WHERE id = :id AND status = 'REQUESTED'
                """).param("id", id).param("status", status.name()).param("by", by).param("at", utc(at))
                .param("note", note).update();
        if (n != 1) {
            throw new IllegalStateException("Refund request " + id + " is no longer open");
        }
    }

    /** For the ops overview: requests waiting for a decision. */
    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM refund_requests WHERE status = 'REQUESTED'").query(Long.class).single();
    }

    public int approvedForBrandSince(UUID brandOrgId, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM refund_requests
                WHERE brand_org_id = :brand AND status = 'APPROVED' AND decided_at >= :since
                """).param("brand", brandOrgId).param("since", utc(since)).query(Integer.class).single();
    }

    /** The brand's requests since a time, newest first: shown to the approver. */
    public List<Row> brandHistory(UUID brandOrgId, Instant since) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund_requests WHERE brand_org_id = :brand AND requested_at >= :since"
                        + " ORDER BY requested_at DESC LIMIT 50")
                .param("brand", brandOrgId).param("since", utc(since)).query(RefundRequestRepository::row).list();
    }

    public List<Row> open(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund_requests WHERE status = 'REQUESTED' ORDER BY requested_at LIMIT :limit")
                .param("limit", limit).query(RefundRequestRepository::row).list();
    }

    /** Requests decided in [from, to): the daily summary. */
    public List<Row> decidedBetween(Instant from, Instant to) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund_requests WHERE decided_at >= :from AND decided_at < :to"
                        + " ORDER BY decided_at")
                .param("from", utc(from)).param("to", utc(to)).query(RefundRequestRepository::row).list();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("brand_org_id", UUID.class), JobAction.valueOf(rs.getString("action")),
                RefundCase.valueOf(rs.getString("refund_case")), RefundReason.valueOf(rs.getString("reason_code")),
                rs.getString("note"), rs.getLong("brand_cash_paise"), rs.getLong("brand_credit_paise"),
                rs.getLong("creator_kill_fee_paise"), rs.getBoolean("creator_accepted"),
                rs.getBoolean("lead_may_self_approve"), rs.getBoolean("founder_required"),
                rs.getObject("requested_by", UUID.class), rs.getString("requested_by_role"), instant(rs, "requested_at"),
                Status.valueOf(rs.getString("status")), rs.getObject("lead_approved_by", UUID.class),
                instant(rs, "lead_approved_at"), rs.getObject("founder_approved_by", UUID.class),
                instant(rs, "founder_approved_at"), rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"),
                rs.getString("decision_note"), rs.getString("job_reason_code"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
