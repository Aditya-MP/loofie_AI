-- Phase 1 production hardening (docs/production-readiness.md).

-- 1.3 / 2.4: order the outbox by a sequence, never by time (rows written in the same millisecond
-- would otherwise sort randomly), and back off failing rows instead of retrying every run.
ALTER TABLE outbox
    ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN next_attempt_at timestamptz;          -- null = send now; set after a failure

DROP INDEX outbox_unpublished_idx;
CREATE UNIQUE INDEX outbox_seq_idx ON outbox (seq);
CREATE INDEX outbox_unpublished_seq_idx ON outbox (seq) WHERE published_at IS NULL;
-- "Is an earlier row of this key waiting on backoff?"
CREATE INDEX outbox_backoff_key_idx ON outbox (message_key)
    WHERE published_at IS NULL AND next_attempt_at IS NOT NULL;
-- 2.6: retention deletes
CREATE INDEX outbox_published_idx ON outbox (published_at) WHERE published_at IS NOT NULL;

-- 2.7: inbox retention
CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

-- 2.5: one active instance per background job (relay, timers, cleanup). Times come from the database.
CREATE TABLE scheduler_leases (
    name        varchar(64)  PRIMARY KEY,
    owner       varchar(128) NOT NULL,
    expires_at  timestamptz  NOT NULL
);

-- 1.1: Idempotency-Key. Stored in the same transaction as the action, so both commit or neither.
CREATE TABLE idempotency_keys (
    scope         varchar(64)  NOT NULL,             -- who sent it (role + user id): keys are per caller
    idem_key      varchar(128) NOT NULL,
    request_hash  char(64)     NOT NULL,             -- SHA-256 of the request; a reused key with a new body is refused
    response      jsonb,                             -- written before commit; never null once committed
    created_at    timestamptz  NOT NULL,
    PRIMARY KEY (scope, idem_key)
);
CREATE INDEX idempotency_keys_created_at_idx ON idempotency_keys (created_at);

-- 4.3: indexes for the job timeline and the ops list.
CREATE INDEX assignments_job_idx ON assignments (job_id, opened_at);
CREATE INDEX jobs_updated_idx ON jobs (updated_at DESC, id DESC);
CREATE INDEX jobs_status_updated_idx ON jobs (status, updated_at DESC);
CREATE INDEX jobs_brand_updated_idx ON jobs (brand_org_id, updated_at DESC);
