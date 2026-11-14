package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.app.JobViews.Deadline;
import com.loofie.core.job.app.JobViews.JobDetail;
import com.loofie.core.job.app.JobViews.JobFilter;
import com.loofie.core.job.app.JobViews.JobSummary;
import com.loofie.core.job.app.JobViews.Page;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.infra.JobReadRepository;
import com.loofie.core.job.infra.JobReadRepository.JobRow;

/** Read-only queries over jobs for the ops console (F10). Authorization is the caller's job. */
@Service
public class JobQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final JobReadRepository repo;
    private final Clock clock;

    public JobQueryService(JobReadRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<JobSummary> list(JobFilter filter, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Instant now = clock.instant();
        List<JobSummary> items = repo.find(filter, now, safeSize, (long) safePage * safeSize).stream()
                .map(r -> summary(r, now))
                .toList();
        return new Page<>(items, safePage, safeSize, repo.count(filter, now));
    }

    @Transactional(readOnly = true)
    public JobDetail detail(UUID jobId) {
        JobRow row = repo.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        Instant now = clock.instant();
        List<Deadline> deadlines = row.deadlines().entrySet().stream()
                .map(e -> new Deadline(e.getKey(), e.getValue(), e.getValue().isBefore(now)))
                .toList();
        return new JobDetail(summary(row, now), row.freeRevisionUsed(), deadlines, repo.assignments(jobId),
                repo.events(jobId));
    }

    private static JobSummary summary(JobRow r, Instant now) {
        Deadline next = r.deadlines().entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .map(e -> new Deadline(e.getKey(), e.getValue(), e.getValue().isBefore(now)))
                .orElse(null);
        return new JobSummary(r.id(), r.orderId(), r.brandOrgId(), r.status(), r.digitalOnly(), r.creatorFeePaise(),
                r.activeCreatorId(), next, r.updatedAt(), r.version());
    }
}
