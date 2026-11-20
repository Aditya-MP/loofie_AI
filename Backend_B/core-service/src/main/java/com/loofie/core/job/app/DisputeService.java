package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.DisputeStatementRepository;
import com.loofie.core.job.infra.DisputeStatementRepository.Statement;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.SwapOfferRepository;
import com.loofie.core.notification.app.NotificationService;

/**
 * F9: the creator's side of a dispute, and what ops reads before deciding it. The brand's note and checklist answers,
 * and every video, are in the ops drafts view (F7); this adds the creator's statement, the review deadline and the
 * swap offers.
 */
@Service
public class DisputeService {

    public static final int MIN_STATEMENT = 20;
    public static final int MAX_STATEMENT = 2000;

    public record StatementView(UUID creatorId, String statement, Instant createdAt) {
        static StatementView of(Statement s) {
            return new StatementView(s.creatorId(), s.statement(), s.createdAt());
        }
    }

    public record OpsDispute(UUID jobId, JobStatus status, Instant reviewDueAt, Instant refundIfNoCreatorBy,
                             List<StatementView> statements, List<SwapOfferService.OfferView> offers) {
    }

    private final JobRepository jobs;
    private final AssignmentRepository assignments;
    private final DisputeStatementRepository statements;
    private final SwapOfferRepository offers;
    private final JobFacts facts;
    private final NotificationService notifications;
    private final Clock clock;
    private final com.loofie.core.common.ScreenReadFence fence;

    public DisputeService(JobRepository jobs, AssignmentRepository assignments, DisputeStatementRepository statements,
                          SwapOfferRepository offers, JobFacts facts, NotificationService notifications, Clock clock,
                          com.loofie.core.common.ScreenReadFence fence) {
        this.fence = fence;
        this.jobs = jobs;
        this.assignments = assignments;
        this.statements = statements;
        this.offers = offers;
        this.facts = facts;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** One statement per creator per job, while it is disputed (F9 D4). */
    @Transactional
    public StatementView statement(Actor actor, UUID jobId, String text) {
        actor.requireRole(ActorRole.CREATOR, "Only the creator on the job sends a statement");
        JobFacts.Facts f = facts.forActor(actor, jobId); // she must be the job's current creator
        if (f.status() != JobStatus.DISPUTED) {
            throw new TransitionGuardException("A statement can be sent only while the brand's rejection is being "
                    + "reviewed");
        }
        String s = text == null ? "" : text.strip();
        if (s.length() < MIN_STATEMENT || s.length() > MAX_STATEMENT) {
            throw new TransitionGuardException("The statement must be " + MIN_STATEMENT + "-" + MAX_STATEMENT
                    + " characters");
        }
        Assignment a = assignments.findByJobIdAndStatus(jobId, Assignment.Status.ACTIVE)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        Instant now = clock.instant();
        Statement st = new Statement(a.getId(), jobId, actor.userId(), s, now);
        if (!statements.insert(st)) {
            throw new TransitionGuardException("You already sent your statement for this dispute");
        }
        notifications.toOps("DISPUTE_STATEMENT_ADDED", jobId, Map.of(), now); // the text stays in the ops view
        return StatementView.of(st);
    }

    public OpsDispute opsView(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        return fence.read("dispute", () -> {
            Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            return new OpsDispute(jobId, job.getStatus(), job.getDisputeReviewDueAt(), job.getReplacementDueAt(),
                    statements.forJob(jobId).stream().map(StatementView::of).toList(),
                    offers.forJob(jobId).stream().map(SwapOfferService.OfferView::of).toList());
        });
    }
}
