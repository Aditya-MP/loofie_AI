package com.loofie.core.job.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** F10 ops minutes ({@code ops_time_entries}), append-only: inserts and reads only. */
@Repository
public class OpsTimeRepository {

    public record Entry(UUID id, UUID jobId, UUID orderId, int minutes, String activity, String note, UUID correctsId,
                        UUID loggedBy, String loggedRole, Instant loggedAt) {
    }

    /** One line of the margin report: a job, its creator fee and the ops minutes logged on it. */
    public record ReportRow(UUID jobId, UUID orderId, UUID brandOrgId, String status, long creatorFeePaise,
                            long minutes) {
    }

    private final JdbcClient jdbc;

    public OpsTimeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the entry it corrects already has a correction (one each). */
    public boolean insert(Entry e) {
        return jdbc.sql("""
                INSERT INTO ops_time_entries (id, job_id, order_id, minutes, activity, note, corrects_id, logged_by,
                                              logged_role, logged_at)
                VALUES (:id, :job, :order, :minutes, :activity, :note, :corrects, :by, :role, :at)
                ON CONFLICT (corrects_id) WHERE corrects_id IS NOT NULL DO NOTHING""")
                .param("id", e.id()).param("job", e.jobId()).param("order", e.orderId()).param("minutes", e.minutes())
                .param("activity", e.activity()).param("note", e.note()).param("corrects", e.correctsId())
                .param("by", e.loggedBy()).param("role", e.loggedRole()).param("at", ts(e.loggedAt())).update() == 1;
    }

    public Optional<Entry> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public List<Entry> forJob(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE job_id = :job ORDER BY logged_at, id").param("job", jobId)
                .query((rs, n) -> map(rs)).list();
    }

    /** Every entry of an order: its own and its jobs'. */
    public List<Entry> forOrder(UUID orderId) {
        return jdbc.sql(SELECT + " WHERE order_id = :order ORDER BY logged_at, id").param("order", orderId)
                .query((rs, n) -> map(rs)).list();
    }

    public boolean orderExists(UUID orderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM orders WHERE id = :id)").param("id", orderId)
                .query(Boolean.class).single();
    }

    public long totalForJob(UUID jobId) {
        return jdbc.sql("SELECT coalesce(sum(minutes), 0) FROM ops_time_entries WHERE job_id = :job")
                .param("job", jobId).query(Long.class).single();
    }

    /** Jobs that had ops minutes logged between the two times, with all their minutes (for the margin review). */
    public List<ReportRow> report(Instant from, Instant to, int limit) {
        return jdbc.sql("""
                SELECT j.id, j.order_id, j.brand_org_id, j.status, j.creator_fee_paise, t.minutes
                FROM (SELECT job_id, sum(minutes) AS minutes FROM ops_time_entries
                      WHERE job_id IN (SELECT DISTINCT job_id FROM ops_time_entries
                                       WHERE logged_at >= :from AND logged_at < :to AND job_id IS NOT NULL)
                      GROUP BY job_id) t
                JOIN jobs j ON j.id = t.job_id
                ORDER BY t.minutes DESC, j.id LIMIT :limit""")
                .param("from", ts(from)).param("to", ts(to)).param("limit", limit)
                .query((rs, n) -> new ReportRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getLong(5), rs.getLong(6))).list();
    }

    /** Average ops minutes per job among jobs closed since {@code since} (null when none closed). */
    public Double minutesPerClosedJob(Instant since) {
        Double avg = jdbc.sql("""
                SELECT coalesce(avg(coalesce(t.minutes, 0)), -1) FROM job_events e
                LEFT JOIN (SELECT job_id, sum(minutes) AS minutes FROM ops_time_entries WHERE job_id IS NOT NULL
                           GROUP BY job_id) t ON t.job_id = e.job_id
                WHERE e.action = 'CLOSE' AND e.occurred_at >= :since""")
                .param("since", ts(since)).query(Double.class).single();
        return avg < 0 ? null : avg; // -1: no job closed in the period
    }

    private static final String SELECT = "SELECT id, job_id, order_id, minutes, activity, note, corrects_id, logged_by, "
            + "logged_role, logged_at FROM ops_time_entries";

    private static Entry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getInt("minutes"), rs.getString("activity"),
                rs.getString("note"), rs.getObject("corrects_id", UUID.class), rs.getObject("logged_by", UUID.class),
                rs.getString("logged_role"), rs.getObject("logged_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
