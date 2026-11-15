-- Kafka removed: the outbox is now sent over HTTP to Backend A (docs/features/kafka-removal.md).
-- Expand only. processed_events (Kafka consumer de-duplication) is no longer used but is dropped in a later
-- migration, after this release is live: an old instance still running during a rolling deploy deletes from it.

-- A row Backend A will never accept (422/400/404) is parked: taken out of the queue, error kept, alerted on.
ALTER TABLE outbox ADD COLUMN parked_at timestamptz;

-- Index builds scan the table once; outbox holds at most ~7 days of rows.
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '10s';

-- The queue excludes parked rows.
DROP INDEX outbox_unpublished_seq_idx;
CREATE INDEX outbox_unpublished_seq_idx ON outbox (seq) WHERE published_at IS NULL AND parked_at IS NULL;

-- For the parked gauge, retention and requeueing.
CREATE INDEX outbox_parked_idx ON outbox (parked_at) WHERE parked_at IS NOT NULL;
