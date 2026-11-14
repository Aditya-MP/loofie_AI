package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.read.JobViews.Deadline;
import com.loofie.core.job.read.JobViews.JobDetail;
import com.loofie.core.job.read.JobViews.JobFilter;
import com.loofie.core.job.read.JobViews.JobSummary;
import com.loofie.core.job.read.JobViews.Page;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.infra.JobReadRepository;
import com.loofie.core.job.infra.JobReadRepository.JobRow;

/** Read-only queries over jobs for the ops console (F10). Every query is OPS only, checked here. */
@Service
public class JobQueryService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final String OPS_ONLY = "The ops console is for OPS only";

    private final JobReadRepository repo;
    private final Clock clock;

    public JobQueryService(JobReadRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<JobSummary> list(Actor actor, JobFilter filter, int page, int size) {
        actor.requireRole(ActorRole.OPS, OPS_ONLY);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Instant now = clock.instant();
        List<JobSummary> items = repo.find(filter, now, safeSize, (long) safePage * safeSize).stream()
                .map(r -> summary(r, now))
                .toList();
        return new Page<>(items, safePage, safeSize, repo.count(filter, now));
    }

    @Transactional(readOnly = true)
    public JobDetail detail(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.OPS, OPS_ONLY);
        JobRow row = repo.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        Instant now = clock.instant();
        List<Deadline> deadlines = row.deadlines().entrySet().stream()
                .map(e -> deadline(e.getKey(), e.getValue(), now))
                .toList();
        return new JobDetail(summary(row, now), row.freeRevisionUsed(), row.deliveryIssueType(), deadlines,
                repo.assignments(jobId), repo.shipments(jobId), repo.events(jobId));
    }

    private static Deadline deadline(String name, Instant at, Instant now) {
        return new Deadline(name, at, at.isBefore(now) && !JobReadRepository.REMINDER_COLUMNS.contains(name));
    }

    private static JobSummary summary(JobRow r, Instant now) {
        Deadline next = r.deadlines().entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .map(e -> deadline(e.getKey(), e.getValue(), now))
                .orElse(null);
        return new JobSummary(r.id(), r.orderId(), r.brandOrgId(), r.status(), r.digitalOnly(), r.creatorFeePaise(),
                r.activeCreatorId(), next, r.updatedAt(), r.version());
    }
}
