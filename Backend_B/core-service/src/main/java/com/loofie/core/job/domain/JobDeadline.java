package com.loofie.core.job.domain;

/**
 * Every clock a job can have running (see {@link JobDeadlines} for when each is set). Code outside
 * the database layer names deadlines with this enum, never with column names.
 */
public enum JobDeadline {
    ACCEPT,
    SHIP_REMINDER(true),
    SHIP_DUE,
    RECEIPT_PROMPT(true),
    RECEIPT_OPS_CHECK,
    AUTO_START,
    ISSUE_RESPONSE,
    MISSING_ESCALATE,
    PRODUCTION,
    REVIEW,
    REPLACEMENT;

    private final boolean reminder;

    JobDeadline() {
        this(false);
    }

    JobDeadline(boolean reminder) {
        this.reminder = reminder;
    }

    /** A reminder time, not a deadline: passing it never makes a job overdue. */
    public boolean isReminder() {
        return reminder;
    }
}
