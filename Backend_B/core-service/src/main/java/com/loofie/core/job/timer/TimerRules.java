package com.loofie.core.job.timer;

import java.time.Duration;
import java.util.List;

import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadline;
import com.loofie.core.job.domain.JobStatus;

/**
 * What the timer worker does when a deadline passes. Two kinds:
 * <ul>
 *   <li>{@link Action}: the job moves on by itself (a SYSTEM transition through the normal engine);</li>
 *   <li>{@link Notice}: someone is reminded or alerted; the job state does not change.</li>
 * </ul>
 * Both read a {@link JobDeadline} stored on the job, so the worker never holds timers in memory: a
 * restart simply picks up whatever is due.
 */
final class TimerRules {

    private TimerRules() {
    }

    /** A deadline after which the job moves on by itself. */
    record Action(JobStatus status, JobDeadline deadline, JobAction action) {
    }

    /** Who a notice is for. Delivery (email, WhatsApp, push) is the notification service's job. */
    enum Audience { BRAND, CREATOR, OPS }

    /**
     * A reminder or alert, sent once per job per due time: {@code before} ahead of {@code deadline}
     * (zero = at the deadline).
     */
    record Notice(String kind, JobStatus status, JobDeadline deadline, Duration before, List<Audience> audience) {
    }

    static final List<Action> ACTIONS = List.of(
            new Action(JobStatus.CREATOR_INVITED, JobDeadline.ACCEPT, JobAction.ACCEPT_TIMEOUT),
            new Action(JobStatus.PRODUCT_SHIPPED, JobDeadline.AUTO_START, JobAction.AUTO_START_PRODUCTION),
            new Action(JobStatus.IN_PRODUCTION, JobDeadline.PRODUCTION, JobAction.PRODUCTION_EXPIRED),
            new Action(JobStatus.IN_REVIEW, JobDeadline.REVIEW, JobAction.AUTO_APPROVE),
            new Action(JobStatus.REPLACEMENT_NEEDED, JobDeadline.REPLACEMENT, JobAction.REPLACEMENT_EXPIRED));

    private static final Duration AT_DEADLINE = Duration.ZERO;

    static final List<Notice> NOTICES = List.of(
            // F6 shipping
            new Notice("SHIP_REMINDER", JobStatus.CREATOR_ACCEPTED, JobDeadline.SHIP_REMINDER, AT_DEADLINE,
                    List.of(Audience.BRAND)),
            new Notice("SHIP_LATE", JobStatus.CREATOR_ACCEPTED, JobDeadline.SHIP_DUE, AT_DEADLINE,
                    List.of(Audience.BRAND, Audience.CREATOR, Audience.OPS)), // creator may now withdraw
            new Notice("RECEIPT_PROMPT", JobStatus.PRODUCT_SHIPPED, JobDeadline.RECEIPT_PROMPT, AT_DEADLINE,
                    List.of(Audience.CREATOR)),
            new Notice("RECEIPT_OPS_CHECK", JobStatus.PRODUCT_SHIPPED, JobDeadline.RECEIPT_OPS_CHECK, AT_DEADLINE,
                    List.of(Audience.OPS)),
            new Notice("ISSUE_RESPONSE_OVERDUE", JobStatus.DELIVERY_ISSUE, JobDeadline.ISSUE_RESPONSE, AT_DEADLINE,
                    List.of(Audience.BRAND, Audience.OPS)),
            new Notice("PARCEL_MISSING", JobStatus.DELIVERY_ISSUE, JobDeadline.MISSING_ESCALATE, AT_DEADLINE,
                    List.of(Audience.CREATOR, Audience.OPS)), // ops steps in; creator may withdraw
            // F9: ops reviews a dispute within 2 working days. No automatic move: the outcome is ops' decision.
            new Notice("DISPUTE_REVIEW_OVERDUE", JobStatus.DISPUTED, JobDeadline.DISPUTE_REVIEW, AT_DEADLINE,
                    List.of(Audience.OPS)),
            // F5: reminders 48h and 24h before the production and review deadlines
            new Notice("PRODUCTION_DUE_48H", JobStatus.IN_PRODUCTION, JobDeadline.PRODUCTION, Duration.ofHours(48),
                    List.of(Audience.CREATOR)),
            new Notice("PRODUCTION_DUE_24H", JobStatus.IN_PRODUCTION, JobDeadline.PRODUCTION, Duration.ofHours(24),
                    List.of(Audience.CREATOR)),
            new Notice("REVIEW_DUE_48H", JobStatus.IN_REVIEW, JobDeadline.REVIEW, Duration.ofHours(48),
                    List.of(Audience.BRAND)),
            new Notice("REVIEW_DUE_24H", JobStatus.IN_REVIEW, JobDeadline.REVIEW, Duration.ofHours(24),
                    List.of(Audience.BRAND)));
}
