package com.loofie.core.job.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** F9: the creator's side of a dispute ({@code job_dispute_statements}), one per assignment. */
@Repository
public class DisputeStatementRepository {

    public record Statement(UUID assignmentId, UUID jobId, UUID creatorId, String statement, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public DisputeStatementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when this assignment already has a statement (one only, also under a double click). */
    public boolean insert(Statement s) {
        return jdbc.sql("""
                INSERT INTO job_dispute_statements (assignment_id, job_id, creator_id, statement, created_at)
                VALUES (:a, :job, :creator, :text, :at) ON CONFLICT (assignment_id) DO NOTHING""")
                .param("a", s.assignmentId()).param("job", s.jobId()).param("creator", s.creatorId())
                .param("text", s.statement()).param("at", OffsetDateTime.ofInstant(s.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    public List<Statement> forJob(UUID jobId) {
        return jdbc.sql("SELECT assignment_id, job_id, creator_id, statement, created_at FROM job_dispute_statements "
                        + "WHERE job_id = :job ORDER BY created_at").param("job", jobId)
                .query((rs, n) -> new Statement(rs.getObject("assignment_id", UUID.class),
                        rs.getObject("job_id", UUID.class), rs.getObject("creator_id", UUID.class),
                        rs.getString("statement"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
