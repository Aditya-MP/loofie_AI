package com.loofie.core.job.infra;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.loofie.core.job.domain.JobDeadline;

/**
 * The one place that maps a {@link JobDeadline} to its column on {@code jobs}. Columns are only ever
 * taken from here, never from input, so concatenating them into SQL is safe.
 */
public final class DeadlineColumns {

    private static final Map<JobDeadline, String> COLUMNS = new EnumMap<>(JobDeadline.class);

    static {
        COLUMNS.put(JobDeadline.ACCEPT, "accept_due_at");
        COLUMNS.put(JobDeadline.SHIP_REMINDER, "ship_reminder_at");
        COLUMNS.put(JobDeadline.SHIP_DUE, "ship_due_at");
        COLUMNS.put(JobDeadline.RECEIPT_PROMPT, "receipt_prompt_at");
        COLUMNS.put(JobDeadline.RECEIPT_OPS_CHECK, "receipt_ops_check_at");
        COLUMNS.put(JobDeadline.AUTO_START, "auto_start_at");
        COLUMNS.put(JobDeadline.ISSUE_RESPONSE, "issue_response_due_at");
        COLUMNS.put(JobDeadline.MISSING_ESCALATE, "missing_escalate_at");
        COLUMNS.put(JobDeadline.PRODUCTION, "production_due_at");
        COLUMNS.put(JobDeadline.REVIEW, "review_due_at");
        COLUMNS.put(JobDeadline.REPLACEMENT, "replacement_due_at");
        COLUMNS.put(JobDeadline.DISPUTE_REVIEW, "dispute_review_due_at");
        if (COLUMNS.size() != JobDeadline.values().length) {
            throw new IllegalStateException("Every JobDeadline needs a column");
        }
    }

    /** All deadline columns, in the order of {@link JobDeadline} (display order). */
    public static final List<String> ALL = List.copyOf(COLUMNS.values());

    private DeadlineColumns() {
    }

    public static String of(JobDeadline deadline) {
        return COLUMNS.get(deadline);
    }

    /** Column name back to its deadline (for read models keyed by column). */
    public static JobDeadline byColumn(String column) {
        for (Map.Entry<JobDeadline, String> e : COLUMNS.entrySet()) {
            if (e.getValue().equals(column)) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("Not a deadline column: " + column);
    }
}
