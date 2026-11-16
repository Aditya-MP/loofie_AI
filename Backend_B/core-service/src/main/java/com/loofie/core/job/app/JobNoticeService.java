package com.loofie.core.job.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.events.JobEventLog;
import com.loofie.core.events.JobEventLog.JobEventRecord;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobDeadlineRepository;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;
import com.loofie.core.notification.app.NotificationService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Raises a reminder or alert for a job deadline, exactly once per job, kind and due time.
 *
 * <p>In one transaction: the {@code deadline_notices} row (the exactly-once guard), a {@code TimerFired}
 * row in {@code job_events} (Foundation v2 F12: "timers fired" must be logged, it is training data), and a
 * {@code JobDeadlineNotice} outbox event for the notification service. The state of the job does not change.
 */
@Service
public class JobNoticeService {

    public static final String TIMER_FIRED = "TimerFired";
    public static final String DEADLINE_NOTICE = "JobDeadlineNotice";

    /** A notice to raise. {@code audience} is who should be told: BRAND, CREATOR, OPS. */
    public record Notice(UUID jobId, UUID orderId, long jobVersion, JobStatus status, String kind, Instant dueAt,
                         List<String> audience) {
    }

    private final JobDeadlineRepository deadlines;
    private final AssignmentRepository assignments;
    private final JobEventLog eventLog;
    private final Outbox outbox;
    private final JsonMapper json;
    private final JobRepository jobs;
    private final NotificationService notifications;

    public JobNoticeService(JobDeadlineRepository deadlines, AssignmentRepository assignments, JobEventLog eventLog,
                            Outbox outbox, JsonMapper json, JobRepository jobs, NotificationService notifications) {
        this.jobs = jobs;
        this.notifications = notifications;
        this.deadlines = deadlines;
        this.assignments = assignments;
        this.eventLog = eventLog;
        this.outbox = outbox;
        this.json = json;
    }

    /** Returns false if this notice was already raised (another run or instance got there first). */
    @Transactional
    public boolean raise(Notice n, Instant now) {
        UUID eventId = Ids.newId();
        if (!deadlines.recordNotice(n.jobId(), n.kind(), n.dueAt(), eventId, now)) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", n.jobId().toString());
        payload.put("orderId", n.orderId().toString());
        // The creator the notice is about, if the job has one (Backend A's /ai/feedback needs it as creator_ref).
        // One indexed lookup per notice raised; only runs when a notice is actually new.
        UUID creatorId = assignments.findByJobIdAndStatus(n.jobId(), Assignment.Status.ACTIVE)
                .map(Assignment::getCreatorId).orElse(null);
        payload.put("creatorId", creatorId == null ? null : creatorId.toString());
        payload.put("kind", n.kind());
        payload.put("jobStatus", n.status().name());
        payload.put("jobVersion", n.jobVersion());
        payload.put("dueAt", n.dueAt().toString());
        payload.put("audience", n.audience());

        // job_version is left empty: it identifies state changes, and this is not one (the unique
        // (job_id, job_version) index would otherwise clash with the change that set the deadline).
        eventLog.append(new JobEventRecord(eventId, TIMER_FIRED, n.orderId(), n.jobId(), null, null,
                ActorRole.SYSTEM.name(), null, n.kind(), n.status().name(), n.status().name(), null, null,
                json.writeValueAsString(payload), now));

        // aggregateVersion = the job's version when the notice was raised. It is NOT unique per event (a
        // JobStateChanged has the same version); consumers order notices by dueAt.
        outbox.enqueue(Outbox.JOB_STREAM, n.jobId().toString(), new EventEnvelope(eventId, DEADLINE_NOTICE, 1, now,
                EventEnvelope.PRODUCER, n.jobId(), n.jobVersion(), eventId, null, payload));

        // The people the notice is for see it in the app (same transaction: never lost, never for a rolled-back change).
        Map<String, Object> shown = Map.of("kind", n.kind(), "dueAt", n.dueAt().toString(), "jobStatus", n.status().name());
        for (String who : n.audience()) {
            switch (who) {
                case "BRAND" -> jobs.findById(n.jobId()).ifPresent(j -> notifications.toBrand(j.getBrandOrgId(), n.kind(),
                        n.jobId(), shown, now));
                case "CREATOR" -> {
                    if (creatorId != null) {
                        notifications.toCreator(creatorId, n.kind(), n.jobId(), shown, now);
                    }
                }
                case "OPS" -> notifications.toOps(n.kind(), n.jobId(), shown, now);
                default -> throw new IllegalArgumentException("Unknown audience " + who);
            }
        }
        return true;
    }
}
