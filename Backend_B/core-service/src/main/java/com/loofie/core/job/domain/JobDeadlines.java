package com.loofie.core.job.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Deadline policy. Called after every state change: clears all deadlines, then sets the ones
 * that belong to the new state. Calendar hours, not business days (spec section 1 item 12).
 */
public final class JobDeadlines {

    public static final Duration ACCEPT_WINDOW = Duration.ofHours(48);
    public static final Duration RECEIPT_PROMPT_AFTER = Duration.ofHours(72);
    public static final Duration RECEIPT_FALLBACK_AFTER = Duration.ofHours(96);
    public static final Duration PRODUCTION_WINDOW = Duration.ofHours(120);
    public static final Duration REVIEW_WINDOW = Duration.ofHours(120);
    public static final Duration REPLACEMENT_WINDOW = Duration.ofDays(10);

    private JobDeadlines() {
    }

    public static void apply(Job job, Instant enteredAt) {
        Instant accept = null;
        Instant receiptPrompt = null;
        Instant receiptFallback = null;
        Instant production = null;
        Instant review = null;
        Instant replacement = null;

        switch (job.getStatus()) {
            case CREATOR_INVITED -> accept = enteredAt.plus(ACCEPT_WINDOW);
            case PRODUCT_SHIPPED -> {
                receiptPrompt = enteredAt.plus(RECEIPT_PROMPT_AFTER);
                receiptFallback = enteredAt.plus(RECEIPT_FALLBACK_AFTER);
            }
            // TODO(open-q): one 48h production extension; who grants it is not decided yet.
            case IN_PRODUCTION -> production = enteredAt.plus(PRODUCTION_WINDOW);
            case IN_REVIEW -> review = enteredAt.plus(REVIEW_WINDOW);
            case REPLACEMENT_NEEDED -> replacement = enteredAt.plus(REPLACEMENT_WINDOW);
            default -> {
                // no deadline in this state
            }
        }
        job.setDeadlines(accept, receiptPrompt, receiptFallback, production, review, replacement);
    }
}
