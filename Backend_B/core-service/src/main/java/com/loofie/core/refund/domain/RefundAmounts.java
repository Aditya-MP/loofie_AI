package com.loofie.core.refund.domain;

/**
 * The money of one refund, in paise. Worked out once, when the request is made, from the price the brand paid.
 *
 * @param brandCash      back to the brand in cash
 * @param brandCredit    kept as a one-time credit against the brand's next order (the late-ship rule)
 * @param creatorKillFee paid to the creator who already worked
 */
public record RefundAmounts(long brandCash, long brandCredit, long creatorKillFee) {

    public static final int KILL_FEE_PERCENT = 50;

    /** Everything that leaves Loofie for this request: what the approval limits are measured on. */
    public long total() {
        return Math.addExact(Math.addExact(brandCash, brandCredit), creatorKillFee);
    }

    /**
     * DECISIONS.md section 4: slot = (creator fee + Loofie fee) x (1 + GST). Example: Rs 5,000 at 15% and 18% gives
     * Rs 6,785. Each part is rounded to the paisa, half up.
     */
    public static RefundAmounts of(RefundCase c, long creatorFeePaise, int feeBp, int gstBp) {
        long fee = percent(creatorFeePaise, feeBp);
        return switch (c) {
            case FULL -> new RefundAmounts(withGst(creatorFeePaise + fee, gstBp), 0, 0);
            case MINUS_FEE -> new RefundAmounts(withGst(creatorFeePaise, gstBp), 0, 0);
            case LATE_SHIP -> new RefundAmounts(withGst(creatorFeePaise, gstBp), withGst(fee, gstBp), 0);
            case KILL_FEE -> new RefundAmounts(0, 0, percent(creatorFeePaise, KILL_FEE_PERCENT * 100));
        };
    }

    static long withGst(long amount, int gstBp) {
        return Math.addExact(amount, percent(amount, gstBp));
    }

    /** {@code amount x bp / 10,000}, rounded half up. */
    static long percent(long amount, int bp) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(amount, bp), 5_000), 10_000);
    }
}
