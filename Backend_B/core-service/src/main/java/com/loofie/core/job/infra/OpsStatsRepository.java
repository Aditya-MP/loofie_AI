package com.loofie.core.job.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** F10 ops overview: counts and last-30-days performance from jobs, job_events and swap offers (read-only). */
@Repository
public class OpsStatsRepository {

    /** A span between entering a state and leaving it (dispute decided, creator found). */
    public record Span(Instant entered, Instant left) {
    }

    private final JdbcClient jdbc;

    public OpsStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> jobsByStatus() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) FROM jobs GROUP BY status ORDER BY status")
                .query((rs, n) -> out.put(rs.getString(1), rs.getLong(2))).list();
        return out;
    }

    /**
     * Jobs whose running clock has passed, per clock. A deadline column is set only while the job is in its state
     * (JobDeadlines clears the others).
     */
    public Map<String, Long> overdue(Instant now) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("ACCEPT", count("accept_due_at", now));
        out.put("SHIP", count("ship_due_at", now));
        out.put("ISSUE_RESPONSE", count("issue_response_due_at", now));
        out.put("PRODUCTION", count("production_due_at", now));
        out.put("REVIEW", count("review_due_at", now));
        out.put("DISPUTE_REVIEW", count("dispute_review_due_at", now));
        out.put("REPLACEMENT", count("replacement_due_at", now));
        return out;
    }

    /**
     * An index-only count on the column's partial index (measured 1-3 ms on 1M jobs, against 455 ms for one scan of
     * the active jobs). No status condition: JobDeadlines sets a column only while the job is in that column's
     * state, and that is what makes it index-only. The column name is a constant from this class.
     */
    private long count(String dueColumn, Instant now) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE " + dueColumn + " < :now")
                .param("now", ts(now)).query(Long.class).single();
    }

    public long openSwapOffers() {
        return jdbc.sql("SELECT count(*) FROM job_swap_offers WHERE status = 'OFFERED'").query(Long.class).single();
    }

    /** Disputes decided since {@code since}: when each was opened and when it was decided. */
    public List<Span> disputesDecided(Instant since) {
        return spans("""
                SELECT (SELECT max(e.occurred_at) FROM job_events e
                        WHERE e.job_id = r.job_id AND e.to_state = 'DISPUTED' AND e.occurred_at <= r.occurred_at),
                       r.occurred_at
                FROM job_events r
                WHERE r.action IN ('RESOLVE_APPROVE', 'RESOLVE_REPLACE', 'RESOLVE_REFUND') AND r.from_state = 'DISPUTED'
                  AND r.occurred_at >= :since""", since);
    }

    /** Swaps approved since {@code since}: when the job lost its creator and when the new one was invited. */
    public List<Span> replacementsFound(Instant since) {
        return spans("""
                SELECT (SELECT max(e.occurred_at) FROM job_events e
                        WHERE e.job_id = r.job_id AND e.to_state IN ('DECLINED', 'REPLACEMENT_NEEDED')
                          AND e.occurred_at <= r.occurred_at),
                       r.occurred_at
                FROM job_events r
                WHERE r.action = 'APPROVE_SWAP' AND r.occurred_at >= :since""", since);
    }

    private List<Span> spans(String sql, Instant since) {
        return jdbc.sql(sql).param("since", ts(since)).query((rs, n) -> {
            OffsetDateTime entered = rs.getObject(1, OffsetDateTime.class);
            return new Span(entered == null ? null : entered.toInstant(), rs.getObject(2, OffsetDateTime.class).toInstant());
        }).list();
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
