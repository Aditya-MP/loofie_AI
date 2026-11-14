package com.loofie.core.job.app;

import java.util.List;
import java.util.UUID;

import com.loofie.core.job.domain.JobStatus;

/** Outcome of one transition call. {@code eventIds} has two entries when a follow-up step ran. */
public record TransitionResult(UUID jobId, JobStatus from, JobStatus to, long version, List<UUID> eventIds) {

    public TransitionResult {
        eventIds = List.copyOf(eventIds);
    }
}
