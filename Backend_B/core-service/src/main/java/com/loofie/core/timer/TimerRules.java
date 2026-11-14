package com.loofie.core.timer;

import java.util.List;

import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;

/**
 * What the timer worker does when a deadline passes. Two kinds:
 * <ul>
 *   <li>{@link Action}: the job moves on by itself (a SYSTEM transition through the normal engine);</li>
 *   <li>{@link Notice}: someone is reminded or alerted; the job state does not change.</li>
 * </ul>
 * The due time is a SQL expression over the {@code jobs} row ({@code j}), so the worker never
 * holds timers in memory: a restart simply picks up whatever is due.
 */
final class TimerRules {

    private TimerRules() {
    }

    /** A deadline after which the job moves on by itself. */
    record Action(JobStatus status, String dueColumn, JobAction action) {
    }

    /** Who a notice is for. Delivery (email, WhatsApp, push) is the notification service's job. */
    enum Audience { BRAND, CREATOR, OPS }

    /** A reminder or alert, sent once per job per due time. */
    record Notice(String kind, JobStatus status, String dueSql, List<Audience> audience) {
    }

    static final List<Action> ACTIONS = List.of(
            new Action(JobStatus.CREATOR_INVITED, "accept_due_at", JobAction.ACCEPT_TIMEOUT),
            new Action(JobStatus.PRODUCT_SHIPPED, "auto_start_at", JobAction.AUTO_START_PRODUCTION),
            new Action(JobStatus.IN_PRODUCTION, "production_due_at", JobAction.PRODUCTION_EXPIRED),
            new Action(JobStatus.IN_REVIEW, "review_due_at", JobAction.AUTO_APPROVE),
            new Action(JobStatus.REPLACEMENT_NEEDED, "replacement_due_at", JobAction.REPLACEMENT_EXPIRED));

    static final List<Notice> NOTICES = List.of(
            // F6 shipping
            new Notice("SHIP_REMINDER", JobStatus.CREATOR_ACCEPTED, "j.ship_reminder_at", List.of(Audience.BRAND)),
            new Notice("SHIP_LATE", JobStatus.CREATOR_ACCEPTED, "j.ship_due_at",
                    List.of(Audience.BRAND, Audience.CREATOR, Audience.OPS)), // creator may now withdraw
            new Notice("RECEIPT_PROMPT", JobStatus.PRODUCT_SHIPPED, "j.receipt_prompt_at", List.of(Audience.CREATOR)),
            new Notice("RECEIPT_OPS_CHECK", JobStatus.PRODUCT_SHIPPED, "j.receipt_ops_check_at", List.of(Audience.OPS)),
            new Notice("ISSUE_RESPONSE_OVERDUE", JobStatus.DELIVERY_ISSUE, "j.issue_response_due_at",
                    List.of(Audience.BRAND, Audience.OPS)),
            new Notice("PARCEL_MISSING", JobStatus.DELIVERY_ISSUE, "j.missing_escalate_at",
                    List.of(Audience.CREATOR, Audience.OPS)), // ops steps in; creator may withdraw
            // F5: reminders 48h and 24h before the production and review deadlines
            new Notice("PRODUCTION_DUE_48H", JobStatus.IN_PRODUCTION, "j.production_due_at - interval '48 hours'",
                    List.of(Audience.CREATOR)),
            new Notice("PRODUCTION_DUE_24H", JobStatus.IN_PRODUCTION, "j.production_due_at - interval '24 hours'",
                    List.of(Audience.CREATOR)),
            new Notice("REVIEW_DUE_48H", JobStatus.IN_REVIEW, "j.review_due_at - interval '48 hours'",
                    List.of(Audience.BRAND)),
            new Notice("REVIEW_DUE_24H", JobStatus.IN_REVIEW, "j.review_due_at - interval '24 hours'",
                    List.of(Audience.BRAND)));
}
