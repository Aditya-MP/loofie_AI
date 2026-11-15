-- F5 plan items 2 and 6 (docs/features/f5-job-engine.md).

-- Item 6: Foundation v2 F9 says ops reviews a dispute within 2 working days. DISPUTED had no clock.
ALTER TABLE jobs ADD COLUMN dispute_review_due_at timestamptz;   -- DISPUTED + 2 working days (ops alert)
CREATE INDEX jobs_dispute_review_due_idx ON jobs (dispute_review_due_at) WHERE dispute_review_due_at IS NOT NULL;

-- Item 2: the ops "overdue" filter compared the earliest deadline of every job: a full scan
-- (1.1 s on 1M jobs). This index holds that earliest deadline for live jobs only (the others have
-- no deadline, so the partial index stays small).
-- MUST match JobReadRepository.EARLIEST_DEADLINE exactly (same columns, same order), or PostgreSQL will
-- not use it. JobReadRepositoryPlanTest fails if they drift apart.
CREATE INDEX jobs_earliest_deadline_idx ON jobs ((LEAST(accept_due_at, ship_due_at, receipt_ops_check_at,
        auto_start_at, issue_response_due_at, missing_escalate_at, production_due_at, review_due_at,
        replacement_due_at, dispute_review_due_at)))
    WHERE LEAST(accept_due_at, ship_due_at, receipt_ops_check_at, auto_start_at, issue_response_due_at,
        missing_escalate_at, production_due_at, review_due_at, replacement_due_at, dispute_review_due_at) IS NOT NULL;
