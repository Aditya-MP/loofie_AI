-- Events to Backend A, round 2 (docs/features/ai-events.md).

-- A1: was this creator's first draft in before the production deadline (as it stood then, extension included)?
-- Set once per creator at SUBMIT_DRAFT; reported in job_outcome.
ALTER TABLE jobs ADD COLUMN first_draft_on_time boolean;

-- A3: a refund that settles a dispute says whose fault it was (same codes as job_events.reason_code).
ALTER TABLE refund_requests ADD COLUMN job_reason_code varchar(32) CHECK (job_reason_code IS NULL OR job_reason_code IN (
    'FALSE_CLAIM', 'CREATOR_UNRESPONSIVE', 'CREATOR_WITHDREW', 'BRAND_LATE_SHIP', 'WRONG_ITEM_SENT',
    'DEFECTIVE_PRODUCT', 'COURIER_LOST', 'DAMAGED_IN_TRANSIT', 'OTHER'));

-- A7: Kafka-era consumer de-duplication. Unused since Kafka was removed; nothing is live, so no rolling-deploy
-- reader can still need it.
DROP TABLE processed_events;

-- Inbox filtered by job ("what was sent about this job?"), e.g. in the shared ops inbox.
CREATE INDEX notifications_job_idx ON notifications (job_id, created_at DESC) WHERE job_id IS NOT NULL;
