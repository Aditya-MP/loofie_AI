package com.loofie.core.refund.domain;

import java.util.UUID;

import com.loofie.core.job.domain.ActorRole;

/**
 * Who must approve money going out (DECISIONS.md section 6).
 * <ul>
 *   <li>Always an OPS_LEAD (the founder may stand in). After a creator accepted: never the person who asked.</li>
 *   <li>No creator accepted yet and at most Rs 10,000: one OPS_LEAD may both ask and approve.</li>
 *   <li>Above Rs 25,000, or more than 3 refunds to the brand in 30 days: also the founder, a different person.</li>
 * </ul>
 */
public final class ApprovalPolicy {

    public static final long SINGLE_LEAD_LIMIT_PAISE = 10_000_00L;
    public static final long FOUNDER_ABOVE_PAISE = 25_000_00L;
    /** This many approved refunds to one brand in {@link #WINDOW_DAYS} days already: the next needs the founder. */
    public static final int FOUNDER_AFTER_REFUNDS = 3;
    public static final int WINDOW_DAYS = 30;

    /** Worked out once, when the request is made, and stored with it. */
    public record Required(boolean leadMaySelfApprove, boolean founderRequired) {
    }

    /** The approval step a person gives. */
    public enum Step { LEAD, FOUNDER }

    public static Required required(long total, boolean creatorAccepted, int approvedRefundsToBrandIn30Days) {
        boolean selfOk = !creatorAccepted && total <= SINGLE_LEAD_LIMIT_PAISE;
        boolean founder = total > FOUNDER_ABOVE_PAISE || approvedRefundsToBrandIn30Days >= FOUNDER_AFTER_REFUNDS;
        return new Required(selfOk, founder);
    }

    /**
     * The step this approver gives now, or why they cannot.
     *
     * @param requestedBy    null when the timer asked (nobody to exclude)
     * @param leadApprovedBy null until the OPS_LEAD step is done
     */
    public static Step nextStep(Required req, UUID requestedBy, UUID leadApprovedBy, UUID approver, ActorRole role) {
        boolean isRequester = approver.equals(requestedBy);
        if (leadApprovedBy == null) {
            if (role != ActorRole.OPS_LEAD && role != ActorRole.FOUNDER) {
                throw new RefundRuleException("Only an OPS_LEAD (or the founder) may approve a refund");
            }
            if (isRequester && !(req.leadMaySelfApprove() && role == ActorRole.OPS_LEAD)) {
                throw new RefundRuleException("You asked for this refund, so someone else must approve it");
            }
            if (role == ActorRole.FOUNDER && req.founderRequired()) {
                throw new RefundRuleException("This refund needs an OPS_LEAD and the founder: the OPS_LEAD approves first");
            }
            return Step.LEAD;
        }
        if (!req.founderRequired()) {
            throw new RefundRuleException("This refund is already approved");
        }
        if (role != ActorRole.FOUNDER) {
            throw new RefundRuleException("This refund also needs the founder's approval");
        }
        if (approver.equals(leadApprovedBy) || isRequester) {
            throw new RefundRuleException("The founder's approval must come from a different person");
        }
        return Step.FOUNDER;
    }

    private ApprovalPolicy() {
    }
}
