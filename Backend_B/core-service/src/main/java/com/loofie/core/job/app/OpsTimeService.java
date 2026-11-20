package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.common.ScreenReadFence;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.OpsTimeRepository;
import com.loofie.core.job.infra.OpsTimeRepository.Entry;
import com.loofie.core.job.infra.OpsTimeRepository.ReportRow;

/**
 * F10: ops minutes per job or per order (campaign), so the margin covers the hours a job really took. Append-only: a
 * wrong entry is reversed by a correction (its exact negative, once, by the person who logged it or an ops lead /
 * founder), then logged again correctly. Totals therefore never go below zero.
 */
@Service
public class OpsTimeService {

    public static final Set<String> ACTIVITIES = Set.of("SHORTLIST", "VERIFICATION", "COORDINATION", "DELIVERY",
            "DISPUTE", "REPLACEMENT", "REFUND", "OTHER");
    public static final int MAX_MINUTES = 480;
    static final int MAX_REPORT_ROWS = 5000;

    public record EntryView(UUID id, UUID jobId, UUID orderId, int minutes, String activity, String note,
                            UUID correctsId, UUID loggedBy, String loggedRole, Instant loggedAt) {
        static EntryView of(Entry e) {
            return new EntryView(e.id(), e.jobId(), e.orderId(), e.minutes(), e.activity(), e.note(), e.correctsId(),
                    e.loggedBy(), e.loggedRole(), e.loggedAt());
        }
    }

    public record TimeSheet(UUID jobId, UUID orderId, long totalMinutes, List<EntryView> entries) {
    }

    public record ReportLine(UUID jobId, UUID orderId, UUID brandOrgId, String status, long creatorFeePaise,
                             long minutes) {
    }

    private final OpsTimeRepository time;
    private final JobRepository jobs;
    private final ScreenReadFence fence;
    private final Clock clock;

    public OpsTimeService(OpsTimeRepository time, JobRepository jobs, ScreenReadFence fence, Clock clock) {
        this.time = time;
        this.jobs = jobs;
        this.fence = fence;
        this.clock = clock;
    }

    /** Logs minutes on a job (its order is filled in) or on an order as a whole (campaign work before any job). */
    @Transactional
    public EntryView log(Actor actor, UUID jobId, UUID orderId, Integer minutes, String activity, String note) {
        requireOps(actor);
        if (minutes == null || minutes < 1 || minutes > MAX_MINUTES) {
            throw new TransitionGuardException("minutes must be 1-" + MAX_MINUTES + " (log a long day as several "
                    + "entries)");
        }
        String act = activity == null ? "" : activity.strip().toUpperCase();
        if (!ACTIVITIES.contains(act)) {
            throw new TransitionGuardException("activity must be one of " + ACTIVITIES.stream().sorted().toList());
        }
        UUID order = orderOf(jobId, orderId);
        Entry e = new Entry(Ids.newId(), jobId, order, minutes, act, note(note), null, actor.userId(),
                actor.role().name(), clock.instant());
        time.insert(e);
        return EntryView.of(e);
    }

    /** Reverses one entry exactly (once). Then log the right minutes as a new entry. */
    @Transactional
    public EntryView correct(Actor actor, UUID entryId, String reason) {
        requireOps(actor);
        String why = note(reason);
        if (why == null || why.length() < 5) {
            throw new TransitionGuardException("Say what was wrong (reason, at least 5 characters)");
        }
        Entry original = time.find(entryId).orElseThrow(() -> new JobNotFoundException(entryId));
        if (original.correctsId() != null) {
            throw new TransitionGuardException("This entry is itself a correction; log the minutes again instead");
        }
        boolean senior = actor.role() == ActorRole.OPS_LEAD || actor.role() == ActorRole.FOUNDER;
        if (!senior && !original.loggedBy().equals(actor.userId())) {
            throw new ActorNotAllowedException("Only the person who logged it, an ops lead or a founder corrects it");
        }
        Entry fix = new Entry(Ids.newId(), original.jobId(), original.orderId(), -original.minutes(),
                original.activity(), why, original.id(), actor.userId(), actor.role().name(), clock.instant());
        if (!time.insert(fix)) {
            throw new TransitionGuardException("This entry was already corrected");
        }
        return EntryView.of(fix);
    }

    public TimeSheet forJob(Actor actor, UUID jobId) {
        requireOps(actor);
        return fence.read("time", () -> {
            Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            List<EntryView> entries = time.forJob(jobId).stream().map(EntryView::of).toList();
            return new TimeSheet(jobId, job.getOrderId(), entries.stream().mapToLong(EntryView::minutes).sum(), entries);
        });
    }

    /** The campaign's sheet: its own entries and every job's. */
    public TimeSheet forOrder(Actor actor, UUID orderId) {
        requireOps(actor);
        return fence.read("time", () -> {
            List<EntryView> entries = time.forOrder(orderId).stream().map(EntryView::of).toList();
            return new TimeSheet(null, orderId, entries.stream().mapToLong(EntryView::minutes).sum(), entries);
        });
    }

    /** Margin review: jobs worked on in [from, to), each with its creator fee and all its ops minutes. */
    public List<ReportLine> report(Actor actor, Instant from, Instant to) {
        if (actor.role() != ActorRole.OPS_LEAD && actor.role() != ActorRole.FOUNDER) {
            throw new ActorNotAllowedException("The time report is for ops leads and founders");
        }
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).toDays() > 366) {
            throw new TransitionGuardException("Give from < to, at most a year apart");
        }
        return fence.read("time", () -> time.report(from, to, MAX_REPORT_ROWS).stream()
                .map(r -> new ReportLine(r.jobId(), r.orderId(), r.brandOrgId(), r.status(), r.creatorFeePaise(),
                        r.minutes())).toList());
    }

    /** For the overview: average ops minutes per job closed since {@code since}. */
    public Double minutesPerClosedJob(Instant since) {
        return time.minutesPerClosedJob(since);
    }

    private UUID orderOf(UUID jobId, UUID orderId) {
        if (jobId != null) {
            Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            if (orderId != null && !orderId.equals(job.getOrderId())) {
                throw new TransitionGuardException("This job belongs to another order");
            }
            return job.getOrderId();
        }
        if (orderId == null) {
            throw new TransitionGuardException("Say which job or order (campaign) the time was spent on");
        }
        if (!time.orderExists(orderId)) {
            throw new JobNotFoundException(orderId);
        }
        return orderId;
    }

    private static String note(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static void requireOps(Actor actor) {
        actor.requireRole(ActorRole.OPS, "Ops only");
    }
}
