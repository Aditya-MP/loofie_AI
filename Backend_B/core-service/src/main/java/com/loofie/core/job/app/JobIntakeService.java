package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.TransitionRule;
import com.loofie.core.job.domain.TransitionTable;
import com.loofie.core.job.infra.JobRepository;

/**
 * Creates the jobs of a submitted order. Each job is stored in AWAITING_PAYMENT and its first
 * log entry is DRAFT_BRIEF -> AWAITING_PAYMENT (SUBMIT_ORDER), so the log matches spec F5.
 * Called by the order module inside its own transaction.
 */
@Service
public class JobIntakeService {

    public record NewJob(boolean digitalOnly, long creatorFeePaise) {
    }

    private final JobRepository jobs;
    private final JobEventRecorder recorder;
    private final Clock clock;

    public JobIntakeService(JobRepository jobs, JobEventRecorder recorder, Clock clock) {
        this.jobs = jobs;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> createJobs(UUID orderId, UUID brandOrgId, Actor actor, List<NewJob> newJobs, UUID correlationId) {
        TransitionRule rule = TransitionTable.find(JobStatus.DRAFT_BRIEF, JobAction.SUBMIT_ORDER).orElseThrow();
        if (!rule.roles().contains(actor.role()) || !brandOrgId.equals(actor.orgId())) {
            throw new ActorNotAllowedException(actor.role() + " may not submit this order");
        }
        Instant now = clock.instant();
        List<UUID> ids = new ArrayList<>();
        for (NewJob n : newJobs) {
            Job job = Job.forNewOrder(Ids.newId(), orderId, brandOrgId, n.digitalOnly(), n.creatorFeePaise(), now);
            jobs.saveAndFlush(job);
            recorder.recordStateChange(job, null, JobStatus.DRAFT_BRIEF, JobAction.SUBMIT_ORDER, actor, null, now,
                    correlationId, null);
            ids.add(job.getId());
        }
        return ids;
    }
}
