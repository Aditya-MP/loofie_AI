package com.loofie.core.job.app;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobRepository;

/**
 * The few facts about one job that other features need (F7 drafts), without its tables. Scope rules are the engine's:
 * a brand sees its own jobs, a creator the job she is assigned to; anyone else gets "not found".
 */
@Service
public class JobFacts {

    /**
     * @param activeCreatorId the creator currently on the job, or null
     * @param reviewDueAt     when the brand's review window ends (IN_REVIEW only)
     */
    public record Facts(UUID jobId, JobStatus status, UUID brandOrgId, UUID activeCreatorId, long version,
                        boolean freeRevisionUsed, Instant reviewDueAt, Instant productionDueAt) {
    }

    private final JobRepository jobs;
    private final AssignmentRepository assignments;

    JobFacts(JobRepository jobs, AssignmentRepository assignments) {
        this.jobs = jobs;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    public Optional<Facts> find(UUID jobId) {
        return jobs.findById(jobId).map(this::facts);
    }

    /** The job's facts if this actor may see the job, else {@link JobNotFoundException} (same answer as a missing job). */
    @Transactional(readOnly = true)
    public Facts forActor(Actor actor, UUID jobId) {
        Facts f = find(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        boolean inScope = switch (actor.role().acting()) {
            case OPS, OPS_LEAD, FOUNDER, SYSTEM -> true;
            case BRAND_ADMIN, BRAND_MEMBER -> f.brandOrgId().equals(actor.orgId());
            case CREATOR -> actor.userId().equals(f.activeCreatorId());
        };
        if (!inScope) {
            throw new JobNotFoundException(jobId);
        }
        return f;
    }

    private Facts facts(Job j) {
        UUID creator = assignments.findByJobIdAndStatus(j.getId(), Assignment.Status.ACTIVE)
                .map(Assignment::getCreatorId).orElse(null);
        return new Facts(j.getId(), j.getStatus(), j.getBrandOrgId(), creator, j.getVersion(), j.isFreeRevisionUsed(),
                j.getReviewDueAt(), j.getProductionDueAt());
    }
}
