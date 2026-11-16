package com.loofie.core.job.infra;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Who else shipped with this tracking number (duplicate-number rules, DECISIONS.md section 5). */
@Repository
public class TrackingNumberRepository {

    /** A job that already used the number: its brand and the creator it was shipped to. */
    public record Use(UUID jobId, UUID brandOrgId, UUID creatorId) {
    }

    private final JdbcClient jdbc;

    public TrackingNumberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises shipments that use the same number until the transaction ends, so two brands entering the same
     * number at the same moment cannot both pass the check. Transaction-scoped: safe behind a pooler.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String normalised) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:n, 0))").param("n", normalised).query().singleRow();
    }

    public List<Use> uses(String normalised) {
        return jdbc.sql("""
                SELECT DISTINCT s.job_id, j.brand_org_id, a.creator_id
                FROM shipments s
                JOIN jobs j ON j.id = s.job_id
                JOIN assignments a ON a.id = s.assignment_id
                WHERE s.tracking_norm = :n
                """)
                .param("n", normalised)
                .query((rs, i) -> new Use(rs.getObject("job_id", UUID.class), rs.getObject("brand_org_id", UUID.class),
                        rs.getObject("creator_id", UUID.class)))
                .list();
    }
}
