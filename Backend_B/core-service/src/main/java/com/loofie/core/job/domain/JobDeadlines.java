package com.loofie.core.job.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Deadline policy. Called after every state change: clears all deadlines, then sets the ones
 * that belong to the new state.
 *
 * <p>Creator-side clocks are calendar hours (creators often shoot on Sundays). The brand's shipping
 * clock counts working days via {@link WorkingCalendar}.
 * TODO(open-q): review clock in working days needs founder sign-off; it stays 120 calendar hours.
 * TODO(open-q): the 48h tracking window and 7-day missing rule are calendar time until confirmed.
 */
public final class JobDeadlines {

    public static final Duration ACCEPT_WINDOW = Duration.ofHours(48);
    public static final int SHIP_REMINDER_WORKING_DAYS = 2;
    public static final int SHIP_WITHIN_WORKING_DAYS = 3;
    public static final Duration RECEIPT_PROMPT_AFTER = Duration.ofHours(72);
    public static final Duration RECEIPT_OPS_CHECK_AFTER = Duration.ofHours(96);
    public static final Duration AUTO_START_AFTER_DELIVERED = Duration.ofHours(24);
    /** Damage found after "Received" can be self-reported this long after tapping Received (once per job). */
    public static final Duration LATE_DAMAGE_WINDOW = Duration.ofHours(24);
    public static final Duration ISSUE_RESPONSE_WINDOW = Duration.ofHours(48);
    public static final Duration MISSING_AFTER_FIRST_SHIPPED = Duration.ofDays(7);
    public static final Duration PRODUCTION_WINDOW = Duration.ofHours(120);
    public static final Duration REVIEW_WINDOW = Duration.ofHours(120);
    public static final Duration REPLACEMENT_WINDOW = Duration.ofDays(10);
    /** Foundation v2 F9: ops reviews a dispute within 2 working days. */
    public static final int DISPUTE_REVIEW_WORKING_DAYS = 2;
    public static final Duration EXTENSION = Duration.ofHours(48);

    private JobDeadlines() {
    }

    public static void apply(Job job, Instant enteredAt, WorkingCalendar calendar) {
        apply(job, enteredAt, calendar, null);
    }

    /**
     * @param reviewWorkingDays null = the brand's review window is {@link #REVIEW_WINDOW} (120 calendar hours,
     *                          Foundation v2); a number = that many working days (DECISIONS.md: 5), once signed off
     */
    public static void apply(Job job, Instant enteredAt, WorkingCalendar calendar, Integer reviewWorkingDays) {
        Instant accept = null;
        Instant shipReminder = null;
        Instant shipDue = null;
        Instant receiptPrompt = null;
        Instant receiptOpsCheck = null;
        Instant autoStart = null;
        Instant issueResponse = null;
        Instant missingEscalate = null;
        Instant production = null;
        Instant review = null;
        Instant replacement = null;
        Instant disputeReview = null;

        switch (job.getStatus()) {
            case CREATOR_INVITED -> accept = enteredAt.plus(ACCEPT_WINDOW);
            case CREATOR_ACCEPTED -> {
                if (!job.isDigitalOnly()) {
                    // Brand-side clock: national holidays plus the brand's pickup-state holidays (F1 round B).
                    shipReminder = calendar.endOfWorkingDays(enteredAt, SHIP_REMINDER_WORKING_DAYS, job.getBrandPickupState());
                    shipDue = calendar.endOfWorkingDays(enteredAt, SHIP_WITHIN_WORKING_DAYS, job.getBrandPickupState());
                }
            }
            case PRODUCT_SHIPPED -> {
                // Counted from the latest shipment, not from "now": a courier update must not reset the clocks.
                Instant shipped = job.getLastShippedAt() != null ? job.getLastShippedAt() : enteredAt;
                if (job.getCourierDeliveredAt() != null) {
                    autoStart = job.getCourierDeliveredAt().plus(AUTO_START_AFTER_DELIVERED);
                } else {
                    receiptPrompt = shipped.plus(RECEIPT_PROMPT_AFTER);     // remind the creator
                    receiptOpsCheck = shipped.plus(RECEIPT_OPS_CHECK_AFTER); // no courier proof: ops checks, never auto-start
                }
            }
            case DELIVERY_ISSUE -> {
                Instant reported = job.getDeliveryIssueAt() != null ? job.getDeliveryIssueAt() : enteredAt;
                issueResponse = reported.plus(ISSUE_RESPONSE_WINDOW);
                // Damage found after receipt (clock paused): the product arrived, so no "missing parcel" rule.
                if (job.getProductionPausedSeconds() == null) {
                    Instant first = job.getFirstShippedAt() != null ? job.getFirstShippedAt() : enteredAt;
                    missingEscalate = first.plus(MISSING_AFTER_FIRST_SHIPPED);
                }
            }
            case IN_PRODUCTION -> {
                // Back from a paused damage report (usable after all, or a false claim): the time that was left.
                // Otherwise (a new receipt, including after a reship): the full window.
                java.time.Duration left = job.takePausedProduction();
                production = enteredAt.plus(left != null ? left : PRODUCTION_WINDOW);
            }
            case IN_REVIEW -> review = reviewWorkingDays == null ? enteredAt.plus(REVIEW_WINDOW)
                    : calendar.endOfWorkingDays(enteredAt, reviewWorkingDays, job.getBrandPickupState()); // brand-side clock
            case REPLACEMENT_NEEDED -> replacement = enteredAt.plus(REPLACEMENT_WINDOW);
            // Ops-side clock: working days, national holidays (ops is not tied to a brand state).
            case DISPUTED -> disputeReview = calendar.endOfWorkingDays(enteredAt, DISPUTE_REVIEW_WORKING_DAYS, null);
            default -> {
                // no deadline in this state
            }
        }
        job.setDeadlines(new Job.DeadlineValues(accept, shipReminder, shipDue, receiptPrompt, receiptOpsCheck,
                autoStart, issueResponse, missingEscalate, production, review, replacement, disputeReview));
    }

    /**
     * F9 extension: adds {@link #EXTENSION} to the running production deadline. The creator gets one,
     * granted automatically if asked before the deadline; any further extension is ops-only.
     */
    public static void extendProduction(Job job, Instant now) {
        job.extendProductionDue(EXTENSION, now);
    }
}
