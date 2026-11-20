package com.loofie.core.job.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.notification.app.NotificationService;

/**
 * F9: who must hear about a dispute outcome, a creator leaving a job, or an invitation, in the same transaction as the
 * change. Payloads are ids, codes and times; never the brand's note, the creator's statement or an ops note.
 * Refund decisions are told by the refund flow itself.
 */
@Component
public class DisputeNotices {

    /** Key in the engine's facts map (not in the event): the creator whose assignment this change closed. */
    static final String LEFT_CREATOR = "f9.leftCreatorId";

    private final NotificationService notifications;
    private final AssignmentRepository assignments;

    public DisputeNotices(NotificationService notifications, AssignmentRepository assignments) {
        this.notifications = notifications;
        this.assignments = assignments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void after(Job job, JobAction action, JobStatus start, Map<String, Object> facts, Instant now) {
        UUID jobId = job.getId();
        UUID left = (UUID) facts.get(LEFT_CREATOR);
        switch (action) {
            case INVITE, APPROVE_SWAP -> active(job).ifPresent(c -> notifications.toCreator(c, "JOB_INVITED", jobId,
                    times("acceptBy", job.getAcceptDueAt()), now));
            case RESOLVE_APPROVE -> {
                active(job).ifPresent(c -> notifications.toCreator(c, "DISPUTE_RESOLVED_APPROVED", jobId, Map.of(), now));
                notifications.toBrand(job.getBrandOrgId(), "DISPUTE_RESOLVED_APPROVED", jobId, Map.of(), now);
            }
            case RESOLVE_REPLACE -> {
                if (left != null) {
                    notifications.toCreator(left, "DISPUTE_RESOLVED_REPLACED", jobId, Map.of(), now);
                }
            }
            case RESOLVE_REFUND -> {
                if (start == JobStatus.DISPUTED && left != null) {
                    notifications.toCreator(left, "DISPUTE_RESOLVED_REFUNDED", jobId, Map.of(), now);
                }
            }
            default -> {
                // no dispute fact
            }
        }
        // A paid job lost its creator: the brand learns the 10-day promise, ops must offer someone.
        if (JobTransitionService.WAITING_FOR_CREATOR.contains(job.getStatus())
                && !JobTransitionService.WAITING_FOR_CREATOR.contains(start)) {
            Map<String, Object> p = times("refundIfNoCreatorBy", job.getReplacementDueAt());
            p.put("cause", action.name());
            notifications.toBrand(job.getBrandOrgId(), "REPLACEMENT_STARTED", jobId, p, now);
            notifications.toOps("REPLACEMENT_NEEDED", jobId, p, now);
        }
    }

    private Optional<UUID> active(Job job) {
        return assignments.findByJobIdAndStatus(job.getId(), Assignment.Status.ACTIVE).map(Assignment::getCreatorId);
    }

    private static Map<String, Object> times(String key, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (at != null) {
            m.put(key, at.toString());
        }
        return m;
    }
}
