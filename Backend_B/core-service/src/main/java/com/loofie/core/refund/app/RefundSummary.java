package com.loofie.core.refund.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.calendar.domain.WorkingDays;
import com.loofie.core.common.LeaderLease;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.refund.domain.RefundRuleException;
import com.loofie.core.refund.infra.RefundRequestRepository;
import com.loofie.core.refund.infra.RefundRequestRepository.Row;
import com.loofie.core.refund.infra.RefundRequestRepository.Status;

/** The founder's daily refund summary (DECISIONS.md section 6): count, total, who asked, who approved. */
@Service
public class RefundSummary {

    private static final Logger log = LoggerFactory.getLogger(RefundSummary.class);

    public record Line(UUID requestId, UUID jobId, UUID brandOrgId, Status status, String reason, long totalPaise,
                       UUID requestedBy, String requestedByRole, UUID leadApprovedBy, UUID founderApprovedBy,
                       UUID decidedBy) {
    }

    /** One India-time day of decisions. */
    public record Day(LocalDate date, int approved, long approvedTotalPaise, long brandCashPaise, long brandCreditPaise,
                      long creatorKillFeePaise, int rejected, int withdrawn, List<Line> lines) {
    }

    private final RefundRequestRepository repo;
    private final NotificationService notifications;
    private final Clock clock;

    public RefundSummary(RefundRequestRepository repo, NotificationService notifications, Clock clock) {
        this.repo = repo;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Day day(Actor actor, LocalDate date) {
        if (actor.role() != ActorRole.OPS_LEAD && actor.role() != ActorRole.FOUNDER) {
            throw new RefundRuleException("The refund summary is for OPS_LEAD and the founder");
        }
        return build(date);
    }

    /** Puts yesterday's summary in the founder's inbox. */
    @Transactional
    public Day sendYesterday() {
        LocalDate yesterday = clock.instant().atZone(WorkingDays.INDIA).toLocalDate().minusDays(1);
        Day d = build(yesterday);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", d.date().toString());
        payload.put("approved", d.approved());
        payload.put("approvedTotalPaise", d.approvedTotalPaise());
        payload.put("rejected", d.rejected());
        payload.put("withdrawn", d.withdrawn());
        notifications.toFounder("REFUND_DAILY_SUMMARY", null, payload, clock.instant());
        return d;
    }

    private Day build(LocalDate date) {
        Instant from = date.atStartOfDay(WorkingDays.INDIA).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(WorkingDays.INDIA).toInstant();
        List<Row> rows = repo.decidedBetween(from, to);
        List<Row> approved = rows.stream().filter(r -> r.status() == Status.APPROVED).toList();
        return new Day(date, approved.size(), approved.stream().mapToLong(Row::total).sum(),
                approved.stream().mapToLong(Row::brandCash).sum(), approved.stream().mapToLong(Row::brandCredit).sum(),
                approved.stream().mapToLong(Row::creatorKillFee).sum(),
                (int) rows.stream().filter(r -> r.status() == Status.REJECTED).count(),
                (int) rows.stream().filter(r -> r.status() == Status.WITHDRAWN).count(),
                rows.stream().map(r -> new Line(r.id(), r.jobId(), r.brandOrgId(), r.status(), r.reason().name(),
                        r.total(), r.requestedBy(), r.requestedByRole(), r.leadApprovedBy(), r.founderApprovedBy(),
                        r.decidedBy())).toList());
    }

    /** 09:00 India time, on the instance holding the lease. */
    @Component
    @ConditionalOnProperty(name = "loofie.refunds.daily-summary", havingValue = "true", matchIfMissing = true)
    static class Scheduler {

        private final RefundSummary summary;
        private final LeaderLease lease;

        Scheduler(RefundSummary summary, LeaderLease lease) {
            this.summary = summary;
            this.lease = lease;
        }

        @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
        void run() {
            try {
                if (lease.tryAcquire("refund-daily-summary", Duration.ofHours(2))) {
                    summary.sendYesterday();
                }
            } catch (RuntimeException e) {
                log.warn("Refund daily summary failed: {}", e.toString());
            }
        }
    }
}
