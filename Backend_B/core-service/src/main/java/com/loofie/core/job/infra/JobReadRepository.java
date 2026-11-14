package com.loofie.core.job.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.job.app.JobViews.AssignmentView;
import com.loofie.core.job.app.JobViews.EventView;
import com.loofie.core.job.app.JobViews.JobFilter;
import com.loofie.core.job.domain.JobStatus;

/** SQL read side for the ops console. Never writes. */
@Repository
public class JobReadRepository {

    /** Deadline columns in display order. */
    public static final List<String> DEADLINE_COLUMNS = List.of("accept_due_at", "receipt_prompt_at",
            "receipt_fallback_at", "production_due_at", "review_due_at", "replacement_due_at");

    private static final String SELECT = """
            SELECT j.id, j.order_id, j.brand_org_id, j.status, j.digital_only, j.creator_fee_paise,
                   j.free_revision_used, j.updated_at, j.version, a.creator_id AS active_creator_id,
                   j.accept_due_at, j.receipt_prompt_at, j.receipt_fallback_at, j.production_due_at,
                   j.review_due_at, j.replacement_due_at
            FROM jobs j
            LEFT JOIN assignments a ON a.job_id = j.id AND a.status = 'ACTIVE'
            """;

    private static final String EARLIEST_DEADLINE = "LEAST(j.accept_due_at, j.receipt_prompt_at, j.receipt_fallback_at, "
            + "j.production_due_at, j.review_due_at, j.replacement_due_at)";

    /** One job row as stored, with its deadlines keyed by column name (only non-null ones). */
    public record JobRow(UUID id, UUID orderId, UUID brandOrgId, JobStatus status, boolean digitalOnly,
                         long creatorFeePaise, boolean freeRevisionUsed, Instant updatedAt, long version,
                         UUID activeCreatorId, Map<String, Instant> deadlines) {
    }

    private final JdbcClient jdbc;

    public JobReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<JobRow> find(JobFilter filter, Instant now, int limit, long offset) {
        Where where = where(filter, now);
        return jdbc.sql(SELECT + where.sql + " ORDER BY j.updated_at DESC, j.id DESC LIMIT :limit OFFSET :offset")
                .params(where.params)
                .param("limit", limit)
                .param("offset", offset)
                .query(JobReadRepository::row)
                .list();
    }

    public long count(JobFilter filter, Instant now) {
        Where where = where(filter, now);
        return jdbc.sql("SELECT count(*) FROM jobs j" + where.sql)
                .params(where.params)
                .query(Long.class)
                .single();
    }

    public Optional<JobRow> findById(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE j.id = :id").param("id", jobId).query(JobReadRepository::row).optional();
    }

    public List<AssignmentView> assignments(UUID jobId) {
        return jdbc.sql("""
                SELECT id, creator_id, status, close_reason, opened_at, closed_at
                FROM assignments WHERE job_id = :id ORDER BY opened_at, id
                """)
                .param("id", jobId)
                .query((rs, n) -> new AssignmentView(rs.getObject("id", UUID.class), rs.getObject("creator_id", UUID.class),
                        rs.getString("status"), rs.getString("close_reason"), instant(rs, "opened_at"),
                        instant(rs, "closed_at")))
                .list();
    }

    public List<EventView> events(UUID jobId) {
        return jdbc.sql("""
                SELECT event_id, job_version, action, from_state, to_state, actor_type, actor_id, reason,
                       occurred_at, payload::text AS payload
                FROM job_events WHERE job_id = :id ORDER BY job_version
                """)
                .param("id", jobId)
                .query((rs, n) -> new EventView(rs.getObject("event_id", UUID.class), rs.getLong("job_version"),
                        rs.getString("action"), rs.getString("from_state"), rs.getString("to_state"),
                        rs.getString("actor_type"), rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                        instant(rs, "occurred_at"), rs.getString("payload")))
                .list();
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(JobFilter f, Instant now) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            clauses.add("j.status IN (:statuses)");
            params.put("statuses", f.statuses().stream().map(Enum::name).toList());
        }
        if (f.brandOrgId() != null) {
            clauses.add("j.brand_org_id = :brandOrgId");
            params.put("brandOrgId", f.brandOrgId());
        }
        if (f.orderId() != null) {
            clauses.add("j.order_id = :orderId");
            params.put("orderId", f.orderId());
        }
        if (f.overdueOnly()) {
            clauses.add(EARLIEST_DEADLINE + " < :now");
            params.put("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        }
        String sql = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new Where(sql, params);
    }

    private static JobRow row(ResultSet rs, int n) throws SQLException {
        Map<String, Instant> deadlines = new LinkedHashMap<>();
        for (String column : DEADLINE_COLUMNS) {
            Instant at = instant(rs, column);
            if (at != null) {
                deadlines.put(column, at);
            }
        }
        return new JobRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("brand_org_id", UUID.class), JobStatus.valueOf(rs.getString("status")),
                rs.getBoolean("digital_only"), rs.getLong("creator_fee_paise"), rs.getBoolean("free_revision_used"),
                instant(rs, "updated_at"), rs.getLong("version"), rs.getObject("active_creator_id", UUID.class),
                deadlines);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
