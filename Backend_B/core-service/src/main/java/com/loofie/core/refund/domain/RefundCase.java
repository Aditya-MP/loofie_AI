package com.loofie.core.refund.domain;

import java.util.Set;

import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;

/** How much goes back, by the job's stage (DECISIONS.md section 6). [CONFIRM] the numbers with the CA. */
public enum RefundCase {
    /** Before acceptance, a dispute upheld, or Loofie's own failure: the whole slot including the fee. */
    FULL,
    /** Cancelled after acceptance, before the product ships: the slot minus the Loofie fee. */
    MINUS_FEE,
    /** The brand shipped late and the creator dropped out: the creator's amount in cash, the fee as credit. */
    LATE_SHIP,
    /** Cancelled after receipt or once the shoot started: no refund; the creator gets a 50% kill fee. */
    KILL_FEE;

    private static final Set<JobStatus> AFTER_RECEIPT = Set.of(JobStatus.PRODUCT_RECEIVED, JobStatus.IN_PRODUCTION,
            JobStatus.DRAFT_SUBMITTED, JobStatus.IN_REVIEW, JobStatus.REVISION_REQUESTED);

    /**
     * The case for a money action from a state.
     *
     * @param lastReasonCode the job's latest reason code (BRAND_LATE_SHIP when the creator dropped out for that)
     * @throws IllegalArgumentException when the action does not move money from that state
     */
    public static RefundCase of(JobAction action, JobStatus from, String lastReasonCode) {
        return switch (action) {
            case CANCEL -> switch (from) {
                case PAID, CREATOR_INVITED, DECLINED -> FULL;
                case CREATOR_ACCEPTED -> MINUS_FEE;
                default -> {
                    if (AFTER_RECEIPT.contains(from)) {
                        yield KILL_FEE;
                    }
                    throw new IllegalArgumentException("No refund case for cancelling from " + from);
                }
            };
            case RESOLVE_REFUND, REPLACEMENT_EXPIRED -> from == JobStatus.REPLACEMENT_NEEDED
                    && "BRAND_LATE_SHIP".equals(lastReasonCode) ? LATE_SHIP : FULL;
            default -> throw new IllegalArgumentException(action + " does not move money");
        };
    }
}
