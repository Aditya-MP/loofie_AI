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
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.JobDeadlineRepository;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;
import com.loofie.core.messaging.Topics;

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
    private final JobEventLog eventLog;
    private final Outbox outbox;
    private final JsonMapper json;

    public JobNoticeService(JobDeadlineRepository deadlines, JobEventLog eventLog, Outbox outbox, JsonMapper json) {
        this.deadlines = deadlines;
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
        outbox.enqueue(Topics.CORE_JOB, n.jobId().toString(), new EventEnvelope(eventId, DEADLINE_NOTICE, 1, now,
                EventEnvelope.PRODUCER, n.jobId(), n.jobVersion(), eventId, null, payload));
        return true;
    }
}
