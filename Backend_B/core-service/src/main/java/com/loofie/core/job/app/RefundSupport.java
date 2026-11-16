package com.loofie.core.job.app;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.JobReadRepository;
import com.loofie.core.job.infra.JobRepository;

/** What the refund feature may read and change on a job, without touching the job tables itself. */
@Service
public class RefundSupport {

    /**
     * The facts a refund is worked out from.
     *
     * @param creatorEverAccepted   a creator accepted this job at some point (someone lost work: four-eyes rule)
     * @param lastReasonCode        the latest reason code on the job (e.g. BRAND_LATE_SHIP when the creator dropped out)
     */
    public record Basis(UUID jobId, UUID orderId, UUID brandOrgId, JobStatus status, long creatorFeePaise,
                        boolean creatorEverAccepted, String lastReasonCode, long version) {
    }

    private final JobRepository jobs;
    private final JobReadRepository reads;

    public RefundSupport(JobRepository jobs, JobReadRepository reads) {
        this.jobs = jobs;
        this.reads = reads;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Basis basis(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        return new Basis(job.getId(), job.getOrderId(), job.getBrandOrgId(), job.getStatus(), job.getCreatorFeePaise(),
                reads.everAccepted(jobId), reads.lastReasonCode(jobId).orElse(null), job.getVersion());
    }

    /** Holds the job while its request is open. Optimistic lock: a job that moved meanwhile is refused. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(UUID jobId, long expectedVersion) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (job.getVersion() != expectedVersion) {
            throw new TransitionGuardException("The job changed while the refund was being prepared; try again");
        }
        if (job.isRefundHold()) {
            throw new TransitionGuardException("This job already has an open refund request");
        }
        job.holdForRefund();
        jobs.saveAndFlush(job);
    }

    /** A rejected or withdrawn request lets the job move again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        job.releaseRefundHold();
        jobs.saveAndFlush(job);
    }
}
