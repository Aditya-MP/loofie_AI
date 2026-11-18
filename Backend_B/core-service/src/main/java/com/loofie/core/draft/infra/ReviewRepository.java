package com.loofie.core.draft.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code draft_reviews} (the brand's checklist answers) and {@code job_ratings}. */
@Repository
public class ReviewRepository {

    public record Review(UUID id, UUID draftId, String decision, String answersJson, String note, UUID reviewerId,
                         String reviewerRole, Instant reviewedAt) {
    }

    public record Rating(int stars, String comment, UUID ratedBy, Instant ratedAt) {
    }

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID jobId, UUID draftId, String decision, String answersJson, String note,
                       UUID reviewerId, String reviewerRole, Instant at) {
        jdbc.sql("""
                INSERT INTO draft_reviews (id, job_id, draft_id, decision, answers, note, reviewer_id, reviewer_role,
                                           reviewed_at)
                VALUES (:id, :job, :draft, :decision, CAST(:answers AS jsonb), :note, :by, :role, :at)""")
                .param("id", id).param("job", jobId).param("draft", draftId).param("decision", decision)
                .param("answers", answersJson).param("note", note).param("by", reviewerId).param("role", reviewerRole)
                .param("at", ts(at)).update();
    }

    /** Newest first. */
    public List<Review> forJob(UUID jobId) {
        return jdbc.sql("SELECT id, draft_id, decision, answers::text AS answers, note, reviewer_id, reviewer_role, "
                        + "reviewed_at FROM draft_reviews WHERE job_id = :job ORDER BY reviewed_at DESC LIMIT 20")
                .param("job", jobId)
                .query((rs, n) -> new Review(rs.getObject("id", UUID.class), rs.getObject("draft_id", UUID.class),
                        rs.getString("decision"), rs.getString("answers"), rs.getString("note"),
                        rs.getObject("reviewer_id", UUID.class), rs.getString("reviewer_role"),
                        rs.getObject("reviewed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the job is already rated */
    public void insertRating(UUID jobId, UUID creatorId, int stars, String comment, UUID by, Instant at) {
        jdbc.sql("INSERT INTO job_ratings (job_id, creator_id, stars, comment, rated_by, rated_at) "
                        + "VALUES (:job, :creator, :stars, :comment, :by, :at)")
                .param("job", jobId).param("creator", creatorId).param("stars", stars).param("comment", comment)
                .param("by", by).param("at", ts(at)).update();
    }

    public Optional<Rating> rating(UUID jobId) {
        return jdbc.sql("SELECT stars, comment, rated_by, rated_at FROM job_ratings WHERE job_id = :job")
                .param("job", jobId)
                .query((rs, n) -> new Rating(rs.getInt("stars"), rs.getString("comment"),
                        rs.getObject("rated_by", UUID.class), rs.getObject("rated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
