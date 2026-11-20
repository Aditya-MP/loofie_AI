package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.common.ScreenReadFence;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.PayoutHoldRepository;
import com.loofie.core.job.infra.PayoutHoldRepository.Hold;

/**
 * F10 payout brake: any ops places a hold on an APPROVED job (a complaint after approval, a doubtful account) and the
 * engine refuses to queue its payout; an OPS_LEAD or FOUNDER who did not place it releases it (maker-checker, F10
 * decision 3). Both steps are audited. The creator is never shown the reason.
 */
@Service
public class PayoutHoldService {

    static final int MAX_PAGE = 200;

    public record HoldView(UUID id, UUID jobId, UUID placedBy, String placedRole, Instant placedAt, String reason,
                           UUID releasedBy, String releasedRole, Instant releasedAt, String releaseReason) {
        static HoldView of(Hold h) {
            return new HoldView(h.id(), h.jobId(), h.placedBy(), h.placedRole(), h.placedAt(), h.reason(),
                    h.releasedBy(), h.releasedRole(), h.releasedAt(), h.releaseReason());
        }
    }

    public record QueueItem(UUID jobId, UUID brandOrgId, JobStatus status, long creatorFeePaise, Instant since,
                            boolean held, String holdReason, Instant heldAt) {
    }

    public record QueuePage(List<QueueItem> items, String next) {
    }

    private final PayoutHoldRepository holds;
    private final JobRepository jobs;
    private final AuditLog audit;
    private final ScreenReadFence fence;
    private final Clock clock;

    public PayoutHoldService(PayoutHoldRepository holds, JobRepository jobs, AuditLog audit, ScreenReadFence fence,
                             Clock clock) {
        this.holds = holds;
        this.jobs = jobs;
        this.audit = audit;
        this.fence = fence;
        this.clock = clock;
    }

    @Transactional
    public HoldView place(Actor actor, UUID jobId, String reason) {
        actor.requireRole(ActorRole.OPS, "Ops places payout holds");
        String why = reason(reason);
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.APPROVED) {
            throw new TransitionGuardException("A payout can be held only while the job is APPROVED (before the "
                    + "payout is queued); it is " + job.getStatus());
        }
        Hold h = new Hold(Ids.newId(), jobId, actor.userId(), actor.role().name(), clock.instant(), why, null, null,
                null, null);
        if (!holds.place(h)) {
            throw new TransitionGuardException("This job's payout is already on hold");
        }
        audit.record(actor, AuditLog.PAYOUT_HOLD_PLACED, null, job.getBrandOrgId(), why,
                Map.of("jobId", jobId.toString(), "holdId", h.id().toString()));
        return HoldView.of(h);
    }

    @Transactional
    public HoldView release(Actor actor, UUID jobId, String reason) {
        if (actor.role() != ActorRole.OPS_LEAD && actor.role() != ActorRole.FOUNDER) {
            throw new ActorNotAllowedException("An ops lead or a founder releases a payout hold");
        }
        String why = reason(reason);
        Hold open = holds.open(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (open.placedBy().equals(actor.userId())) {
            throw new ActorNotAllowedException("Someone other than the person who placed the hold releases it");
        }
        if (!holds.release(open.id(), actor.userId(), actor.role().name(), why, clock.instant())) {
            throw new JobNotFoundException(jobId); // released meanwhile
        }
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        audit.record(actor, AuditLog.PAYOUT_HOLD_RELEASED, null, job.getBrandOrgId(), why,
                Map.of("jobId", jobId.toString(), "holdId", open.id().toString()));
        return HoldView.of(holds.history(jobId).stream().filter(h -> h.id().equals(open.id())).findFirst()
                .orElseThrow());
    }

    public List<HoldView> history(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        return fence.read("payouts", () -> holds.history(jobId).stream().map(HoldView::of).toList());
    }

    /** The payout queue, oldest first; {@code after} is the previous page's {@code next}. */
    public QueuePage queue(Actor actor, boolean heldOnly, String after, int size) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        int n = Math.max(1, Math.min(size <= 0 ? 50 : size, MAX_PAGE));
        Instant afterAt = null;
        UUID afterId = null;
        if (after != null && !after.isBlank()) {
            String[] p = after.split("_", 2);
            try {
                afterAt = Instant.parse(p[0]);
                afterId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new TransitionGuardException("after must be the next value of the previous page");
            }
        }
        Instant a = afterAt;
        UUID b = afterId;
        return fence.read("payouts", () -> {
            List<QueueItem> items = holds.queue(heldOnly, a, b, n).stream()
                    .map(r -> new QueueItem(r.jobId(), r.brandOrgId(), JobStatus.valueOf(r.status()),
                            r.creatorFeePaise(), r.since(), r.holdId() != null, r.holdReason(), r.heldAt()))
                    .toList();
            String next = items.size() < n ? null
                    : items.getLast().since() + "_" + items.getLast().jobId(); // full precision: no row twice
            return new QueuePage(items, next);
        });
    }

    public long openHolds() {
        return holds.openCount();
    }

    private static String reason(String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5) {
            throw new TransitionGuardException("Say why (reason, at least 5 characters)");
        }
        return why.length() > 500 ? why.substring(0, 500) : why;
    }
}
