package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.ServiceBusyException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.infra.JobReadRepository;
import com.loofie.core.job.infra.JobReadRepository.JobRow;
import com.loofie.core.job.read.JobViews.Cursor;
import com.loofie.core.job.read.JobViews.Deadline;
import com.loofie.core.job.read.JobViews.JobDetail;
import com.loofie.core.job.read.JobViews.JobFilter;
import com.loofie.core.job.read.JobViews.JobSummary;
import com.loofie.core.job.read.JobViews.Page;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Read-only queries over jobs for the ops console (F10). Every query is OPS only, checked here.
 *
 * <p><b>Isolation from the engine.</b> Measured: slow list queries once held all pooled connections and
 * the whole service failed at 50 requests a second. So reads here are fenced in:
 * <ul>
 *   <li>lists and single-job reads each have their own bulkhead (measured: when they shared one, heavy
 *       lists starved the 2 ms detail reads), at most {@code listMaxConcurrent} / {@code detailMaxConcurrent}
 *       at once; a caller waits up to {@code queueWait},
 *       then gets 503, so reads can never take more than that many connections from the engine;</li>
 *   <li>every statement is capped at {@code statementTimeout};</li>
 *   <li>totals are counted only up to {@link #COUNT_CAP} ("10,000+"), and deep pages use a cursor.</li>
 * </ul>
 * Later, these reads can move to a read replica without changing callers.
 */
@Service
public class JobQueryService {

    public static final int MAX_PAGE_SIZE = 100;
    /** Totals above this are reported as "at least" (see {@link Page#totalCapped()}). */
    public static final long COUNT_CAP = 10_000;
    /** Page numbers only reach this far into a list; beyond, use the cursor. */
    public static final long MAX_OFFSET = 10_000;
    private static final String OPS_ONLY = "The ops console is for OPS only";

    private final JobReadRepository repo;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final Semaphore listPermits;
    private final Semaphore detailPermits;
    private final Duration queueWait;
    private final Duration statementTimeout;
    private final MeterRegistry meters;

    public JobQueryService(JobReadRepository repo, Clock clock, PlatformTransactionManager txManager,
                           MeterRegistry meters,
                           @Value("${loofie.queries.list-max-concurrent:5}") int listMaxConcurrent,
                           @Value("${loofie.queries.detail-max-concurrent:8}") int detailMaxConcurrent,
                           @Value("${loofie.queries.queue-wait:1s}") Duration queueWait,
                           @Value("${loofie.queries.statement-timeout:2s}") Duration statementTimeout) {
        this.repo = repo;
        this.clock = clock;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.listPermits = new Semaphore(listMaxConcurrent, true);
        this.detailPermits = new Semaphore(detailMaxConcurrent, true);
        this.queueWait = queueWait;
        this.statementTimeout = statementTimeout;
        this.meters = meters;
    }

    /**
     * @param page  0-based page number; only for the first {@link #MAX_OFFSET} rows
     * @param after cursor from a previous page's {@code nextCursor}; when given, {@code page} is ignored
     */
    public Page<JobSummary> list(Actor actor, JobFilter filter, int page, int size, String after) {
        actor.requireRole(ActorRole.OPS, OPS_ONLY);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Cursor cursor = parseCursor(after);
        int safePage = cursor != null ? 0 : Math.max(page, 0);
        long offset = (long) safePage * safeSize;
        if (offset >= MAX_OFFSET) {
            throw new InvalidQueryException("Page numbers go up to row " + MAX_OFFSET + "; use the nextCursor to go further");
        }
        return fenced(listPermits, () -> {
            Instant now = clock.instant();
            // One extra row tells whether there is a next page without counting.
            List<JobRow> rows = repo.find(filter, now, safeSize + 1, offset, cursor);
            boolean more = rows.size() > safeSize;
            List<JobRow> pageRows = more ? rows.subList(0, safeSize) : rows;
            List<JobSummary> items = pageRows.stream().map(r -> summary(r, now)).toList();
            long total = repo.countUpTo(filter, now, COUNT_CAP);
            String next = more ? new Cursor(pageRows.getLast().updatedAt(), pageRows.getLast().id()).encode() : null;
            return new Page<>(items, safePage, safeSize, total, total >= COUNT_CAP, next);
        });
    }

    public JobDetail detail(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.OPS, OPS_ONLY);
        return fenced(detailPermits, () -> {
            JobRow row = repo.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            Instant now = clock.instant();
            List<Deadline> deadlines = row.deadlines().entrySet().stream()
                    .map(e -> deadline(e.getKey(), e.getValue(), now))
                    .toList();
            return new JobDetail(summary(row, now), row.freeRevisionUsed(), row.deliveryIssueType(), deadlines,
                    repo.assignments(jobId), repo.shipments(jobId), repo.events(jobId));
        });
    }

    /** Runs a read inside the bulkhead, in a read-only transaction with a statement time limit. */
    private <T> T fenced(Semaphore permits, Supplier<T> read) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceBusyException("Interrupted while waiting for a read slot");
        }
        if (!acquired) {
            meters.counter("loofie.queries.rejected").increment();
            throw new ServiceBusyException("Too many ops-console reads at once; retry in a moment");
        }
        try {
            return readTx.execute(status -> {
                repo.limitStatementTime(statementTimeout);
                return read.get();
            });
        } finally {
            permits.release();
        }
    }

    private static Cursor parseCursor(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }
        try {
            return Cursor.decode(after);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("Invalid cursor: use the nextCursor from the previous page");
        }
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
