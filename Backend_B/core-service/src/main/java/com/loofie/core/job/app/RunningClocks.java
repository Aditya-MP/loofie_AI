package com.loofie.core.job.app;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.infra.JobDeadlineRepository;

/** What other features may ask about the job clocks, without touching the jobs table themselves. */
@Service
public class RunningClocks {

    private final JobDeadlineRepository deadlines;

    public RunningClocks(JobDeadlineRepository deadlines) {
        this.deadlines = deadlines;
    }

    /** Brand shipping and ops dispute-review clocks (the working-day ones) still running after {@code instant}. */
    @Transactional(readOnly = true)
    public long workingDayClocksEndingAfter(Instant instant) {
        return deadlines.countWorkingDayClocksEndingAfter(instant);
    }
}
