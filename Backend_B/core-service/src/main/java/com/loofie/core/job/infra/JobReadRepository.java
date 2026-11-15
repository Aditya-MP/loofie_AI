package com.loofie.core.job.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.job.read.JobViews.AssignmentView;
import com.loofie.core.job.read.JobViews.EventView;
import com.loofie.core.job.read.JobViews.Cursor;
import com.loofie.core.job.read.JobViews.JobFilter;
import com.loofie.core.job.read.JobViews.ShipmentView;
import com.loofie.core.job.domain.JobDeadline;
import com.loofie.core.job.domain.JobStatus;

/** SQL read side for the ops console. Never writes. */
@Repository
public class JobReadRepository {

    /** Deadline columns in display order (from {@link DeadlineColumns}, the single mapping). */
    public static final List<String> DEADLINE_COLUMNS = DeadlineColumns.ALL;

    private static final String SELECT = """
            SELECT j.id, j.order_id, j.brand_org_id, j.status, j.digital_only, j.creator_fee_paise,
                   j.free_revision_used, j.updated_at, j.version, a.creator_id AS active_creator_id,
                   j.delivery_issue_type, %s
            FROM jobs j
            LEFT JOIN assignments a ON a.job_id = j.id AND a.status = 'ACTIVE'
            """.formatted(DEADLINE_COLUMNS.stream().map(c -> "j." + c).collect(Collectors.joining(", ")));

    /** Reminder times: shown to ops, but passing one does not make a job overdue. */
    public static final Set<String> REMINDER_COLUMNS = Arrays.stream(JobDeadline.values())
            .filter(JobDeadline::isReminder).map(DeadlineColumns::of).collect(Collectors.toUnmodifiableSet());

    /** The earliest real deadline (reminders excluded), for the "overdue" filter. */
    static final String EARLIEST_DEADLINE = Arrays.stream(JobDeadline.values())
            .filter(d -> !d.isReminder()).map(d -> "j." + DeadlineColumns.of(d))
            .collect(Collectors.joining(", ", "LEAST(", ")"));

    /** One job row as stored, with its deadlines keyed by column name (only non-null ones). */
    public record JobRow(UUID id, UUID orderId, UUID brandOrgId, JobStatus status, boolean digitalOnly,
                         long creatorFeePaise, boolean freeRevisionUsed, Instant updatedAt, long version,
                         UUID activeCreatorId, String deliveryIssueType, Map<String, Instant> deadlines) {
    }

    private final JdbcClient jdbc;

    public JobReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page, newest change first. With {@code after} (keyset paging) it continues after that row and
     * {@code offset} must be 0: cost stays the same however deep the page, unlike OFFSET (464 ms at
     * offset 50,000 on 1M jobs, measured).
     */
    public List<JobRow> find(JobFilter filter, Instant now, int limit, long offset, Cursor after) {
        Where where = where(filter, now, after);
        return jdbc.sql(SELECT + where.sql + " ORDER BY j.updated_at DESC, j.id DESC LIMIT :limit OFFSET :offset")
                .params(where.params)
                .param("limit", limit)
                .param("offset", offset)
                .query(JobReadRepository::row)
                .list();
    }

    /**
     * Counts matching jobs but stops at {@code cap}: an exact count of a big list is a full scan
     * (282 ms on 1M jobs, 1.1 s with the overdue filter, measured), and it ran on every list call.
     */
    public long countUpTo(JobFilter filter, Instant now, long cap) {
        Where where = where(filter, now, null);
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM jobs j" + where.sql + " LIMIT :cap) capped")
                .params(where.params)
                .param("cap", cap)
                .query(Long.class)
                .single();
    }

    /** Caps every statement in the current transaction (read-only screens must never hold a connection long). */
    public void limitStatementTime(Duration limit) {
        jdbc.sql("SELECT set_config('statement_timeout', :ms, true)") // true = this transaction only
                .param("ms", String.valueOf(limit.toMillis()))
                .query(String.class)
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

    public List<ShipmentView> shipments(UUID jobId) {
        return jdbc.sql("""
                SELECT attempt, courier, tracking_number, shipped_at, courier_delivered_at, courier_delivered_source,
                       courier_delivered_recorded_at, received_at,
                       receipt_photo_ref IS NOT NULL AS has_photo
                FROM shipments WHERE job_id = :id ORDER BY attempt
                """)
                .param("id", jobId)
                .query((rs, n) -> new ShipmentView(rs.getInt("attempt"), rs.getString("courier"),
                        rs.getString("tracking_number"), instant(rs, "shipped_at"), instant(rs, "courier_delivered_at"),
                        rs.getString("courier_delivered_source"), instant(rs, "courier_delivered_recorded_at"),
                        instant(rs, "received_at"), rs.getBoolean("has_photo")))
                .list();
    }

    public List<EventView> events(UUID jobId) {
        return jdbc.sql("""
                SELECT event_id, job_version, action, from_state, to_state, actor_type, actor_id, reason,
                       occurred_at, payload::text AS payload
                FROM job_events WHERE job_id = :id ORDER BY occurred_at, job_version NULLS LAST, event_id
                """)
                .param("id", jobId)
                .query((rs, n) -> new EventView(rs.getObject("event_id", UUID.class), rs.getObject("job_version", Long.class),
                        rs.getString("action"), rs.getString("from_state"), rs.getString("to_state"),
                        rs.getString("actor_type"), rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                        instant(rs, "occurred_at"), rs.getString("payload")))
                .list();
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(JobFilter f, Instant now, Cursor after) {
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
        if (after != null) {
            // Row comparison matches ORDER BY updated_at DESC, id DESC and uses the (updated_at, id) index.
            clauses.add("(j.updated_at, j.id) < (:afterUpdatedAt, :afterId)");
            params.put("afterUpdatedAt", OffsetDateTime.ofInstant(after.updatedAt(), ZoneOffset.UTC));
            params.put("afterId", after.id());
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
                rs.getString("delivery_issue_type"), deadlines);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
