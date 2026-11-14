package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.TransitionRule;
import com.loofie.core.job.domain.TransitionTable;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobRepository;

/**
 * The single entry point for changing a job's state. Nothing else writes job status.
 *
 * <p>One transaction per call: optimistic-lock check (@Version), state update, assignment change,
 * deadline recalculation, job_events row and outbox row. Any failure rolls all of it back.
 * A race (e.g. auto-approve vs the brand's click) is settled by the optimistic lock: the loser
 * gets an {@code ObjectOptimisticLockingFailureException} and nothing is written for it.
 */
@Service
public class JobTransitionService {

    private final JobRepository jobs;
    private final AssignmentRepository assignments;
    private final JobEventRecorder recorder;
    private final Clock clock;

    public JobTransitionService(JobRepository jobs, AssignmentRepository assignments, JobEventRecorder recorder,
                                Clock clock) {
        this.jobs = jobs;
        this.assignments = assignments;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Transactional
    public TransitionResult transition(UUID jobId, JobAction action, Actor actor, String reason) {
        return transition(TransitionCommand.of(jobId, action, actor, reason));
    }

    @Transactional
    public TransitionResult transition(TransitionCommand cmd) {
        Job job = jobs.findById(cmd.jobId()).orElseThrow(() -> new JobNotFoundException(cmd.jobId()));
        JobStatus start = job.getStatus();
        UUID correlationId = cmd.correlationId() != null ? cmd.correlationId() : Ids.newId();

        List<UUID> eventIds = new ArrayList<>();
        TransitionRule rule = requireRule(job.getStatus(), cmd.action());
        authorize(job, rule, cmd.actor(), cmd.reason());
        UUID eventId = applyStep(job, rule, cmd.actor(), cmd.reason(), cmd.creatorId(), correlationId, null);
        eventIds.add(eventId);

        // Automatic follow-ups (e.g. PRODUCT_RECEIVED -> IN_PRODUCTION) run as SYSTEM in the same transaction.
        while (rule.followUp() != null) {
            rule = requireRule(job.getStatus(), rule.followUp());
            eventId = applyStep(job, rule, Actor.system(), null, null, correlationId, eventId);
            eventIds.add(eventId);
        }
        return new TransitionResult(job.getId(), start, job.getStatus(), job.getVersion(), eventIds);
    }

    private static TransitionRule requireRule(JobStatus from, JobAction action) {
        return TransitionTable.find(from, action).orElseThrow(() -> new IllegalTransitionException(from, action));
    }

    private void authorize(Job job, TransitionRule rule, Actor actor, String reason) {
        if (!rule.roles().contains(actor.role())) {
            throw new ActorNotAllowedException(actor.role() + " may not " + rule.action() + " (allowed: " + rule.roles() + ")");
        }
        if (actor.role() == ActorRole.OPS && (reason == null || reason.isBlank())) {
            throw new TransitionGuardException("Ops actions need a reason");
        }
        if (actor.role().isBrand() && !job.getBrandOrgId().equals(actor.orgId())) {
            throw new ActorNotAllowedException("Job " + job.getId() + " does not belong to this brand");
        }
        if (actor.role() == ActorRole.CREATOR) {
            UUID assigned = activeAssignment(job).map(Assignment::getCreatorId).orElse(null);
            if (!actor.userId().equals(assigned)) {
                throw new ActorNotAllowedException("Creator is not assigned to job " + job.getId());
            }
        }
    }

    private UUID applyStep(Job job, TransitionRule rule, Actor actor, String reason, UUID creatorId,
                           UUID correlationId, UUID causationId) {
        Instant now = clock.instant();
        JobStatus from = job.getStatus();

        checkGuards(job, rule);
        UUID assignmentId = changeAssignment(job, rule, creatorId, now);
        if (rule.action() == JobAction.REQUEST_REVISION) {
            job.markFreeRevisionUsed();
        }
        job.moveTo(rule.to(), now);
        JobDeadlines.apply(job, now);
        jobs.saveAndFlush(job); // optimistic lock check happens here; bumps version

        return recorder.recordStateChange(job, assignmentId, from, rule.action(), actor, reason, now,
                correlationId, causationId);
    }

    private static void checkGuards(Job job, TransitionRule rule) {
        switch (rule.action()) {
            // TODO(open-q): paid revisions go through a ChangeRequest (F7), not a second free revision.
            case REQUEST_REVISION -> {
                if (job.isFreeRevisionUsed()) {
                    throw new TransitionGuardException("The free revision has already been used");
                }
            }
            case REJECT -> {
                if (!job.isFreeRevisionUsed()) {
                    throw new TransitionGuardException("Reject is only allowed after the free revision");
                }
            }
            case START_DIGITAL -> {
                if (!job.isDigitalOnly()) {
                    throw new TransitionGuardException("Job needs a product; use MARK_SHIPPED");
                }
            }
            case MARK_SHIPPED -> {
                if (job.isDigitalOnly()) {
                    throw new TransitionGuardException("Digital-only job has no product to ship");
                }
            }
            default -> {
                // no extra guard yet
            }
        }
    }

    /** Opens or closes the creator assignment where the transition requires it. Returns the relevant assignment id. */
    private UUID changeAssignment(Job job, TransitionRule rule, UUID creatorId, Instant now) {
        Optional<Assignment> active = activeAssignment(job);
        switch (rule.action()) {
            case INVITE, APPROVE_SWAP -> {
                if (creatorId == null) {
                    throw new TransitionGuardException(rule.action() + " needs the creatorId to invite");
                }
                if (active.isPresent()) {
                    throw new TransitionGuardException("Job " + job.getId() + " already has an active creator");
                }
                Assignment opened = Assignment.open(Ids.newId(), job.getId(), creatorId, now);
                assignments.save(opened);
                return opened.getId();
            }
            case DECLINE, ACCEPT_TIMEOUT, PRODUCTION_EXPIRED, RESOLVE_REPLACE, RESOLVE_REFUND, CANCEL -> {
                active.ifPresent(a -> {
                    a.close(rule.action().name(), now);
                    assignments.save(a);
                });
                return active.map(Assignment::getId).orElse(null);
            }
            default -> {
                return active.map(Assignment::getId).orElse(null);
            }
        }
    }

    private Optional<Assignment> activeAssignment(Job job) {
        return assignments.findByJobIdAndStatus(job.getId(), Assignment.Status.ACTIVE);
    }
}
