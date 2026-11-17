-- Perf data set in core_test (wiped by the next test run). Sizes ~ several years at target load.
SET search_path = core_test;
SET statement_timeout = 0;
\timing on

-- refund_requests and notifications first: they reference jobs (a row trigger blocks DELETE, TRUNCATE is fine).
TRUNCATE refund_requests, notifications, deadline_notices, shipments, assignments, idempotency_keys,
    scheduler_leases CASCADE;
DELETE FROM outbox;
ALTER TABLE job_events DISABLE TRIGGER job_events_no_truncate;
TRUNCATE job_events;
ALTER TABLE job_events ENABLE TRIGGER job_events_no_truncate;
DELETE FROM jobs; DELETE FROM orders;

-- 250k orders x 4 jobs = 1M jobs, 2,000 brands. ANALYZE after each bulk load, or FK checks plan as if tables were empty.
INSERT INTO orders (id, brand_org_id, created_at, version)
SELECT gen_random_uuid(), ('00000000-0000-4000-8000-' || lpad((g % 2000)::text, 12, '0'))::uuid,
       now() - (random() * interval '730 days'), 0
FROM generate_series(1, 250000) g;

ANALYZE orders;
INSERT INTO jobs (id, order_id, brand_org_id, status, digital_only, creator_fee_paise, free_revision_used,
                  created_at, updated_at, version)
SELECT gen_random_uuid(), o.id, o.brand_org_id, 'CLOSED',
       random() < 0.3, 500000, false, o.created_at, o.created_at + random() * interval '30 days', 5
FROM orders o CROSS JOIN generate_series(1, 4) n;

ANALYZE jobs; -- without statistics the UPDATE below joins 1M x 1M rows (it ran for 17+ minutes)
-- Realistic status mix. Done as an UPDATE with a per-row random() in a subquery: a random() inside a
-- CASE in the INSERT above was evaluated once for all rows (twice tried, every job came out CLOSED).
UPDATE jobs SET status = CASE WHEN r < 0.60 THEN 'CLOSED' WHEN r < 0.65 THEN 'IN_PRODUCTION' WHEN r < 0.68 THEN 'IN_REVIEW'
            WHEN r < 0.71 THEN 'CREATOR_INVITED' WHEN r < 0.74 THEN 'CREATOR_ACCEPTED'
            WHEN r < 0.77 THEN 'PRODUCT_SHIPPED' WHEN r < 0.78 THEN 'DELIVERY_ISSUE' WHEN r < 0.80 THEN 'PAID'
            WHEN r < 0.82 THEN 'REPLACEMENT_NEEDED' WHEN r < 0.85 THEN 'CANCELLED' WHEN r < 0.88 THEN 'REFUNDED'
            WHEN r < 0.90 THEN 'DISPUTED' ELSE 'PAID_OUT' END
FROM (SELECT id, random() AS r FROM jobs) x WHERE x.id = jobs.id;
VACUUM ANALYZE jobs;
SELECT status, count(*) FROM jobs GROUP BY 1 ORDER BY 2 DESC; -- check: about 60% CLOSED, 5% IN_PRODUCTION, ...

ANALYZE jobs;
-- Deadlines consistent with status, spread around now (some overdue)
UPDATE jobs SET accept_due_at = now() + (random() * 96 - 48) * interval '1 hour' WHERE status = 'CREATOR_INVITED';
UPDATE jobs SET production_due_at = now() + (random() * 240 - 120) * interval '1 hour' WHERE status = 'IN_PRODUCTION';
UPDATE jobs SET review_due_at = now() + (random() * 240 - 120) * interval '1 hour' WHERE status = 'IN_REVIEW';
UPDATE jobs SET replacement_due_at = now() + (random() * 20 - 10) * interval '1 day' WHERE status = 'REPLACEMENT_NEEDED';
UPDATE jobs SET ship_reminder_at = now() + (random() * 4 - 2) * interval '1 day',
                ship_due_at = now() + (random() * 6 - 2) * interval '1 day'
WHERE status = 'CREATOR_ACCEPTED' AND NOT digital_only;
UPDATE jobs SET receipt_prompt_at = now() + (random() * 6 - 3) * interval '1 day',
                receipt_ops_check_at = now() + (random() * 8 - 3) * interval '1 day'
WHERE status = 'PRODUCT_SHIPPED';
UPDATE jobs SET dispute_review_due_at = now() + (random() * 4 - 2) * interval '1 day' WHERE status = 'DISPUTED';
UPDATE jobs SET issue_response_due_at = now() + (random() * 4 - 2) * interval '1 day',
                missing_escalate_at = now() + (random() * 14 - 7) * interval '1 day'
WHERE status = 'DELIVERY_ISSUE';

ANALYZE jobs;
-- One assignment per job past invitation (active for live jobs, closed otherwise)
INSERT INTO assignments (id, job_id, creator_id, status, close_reason, opened_at, closed_at, version)
SELECT gen_random_uuid(), j.id, gen_random_uuid(),
       CASE WHEN j.status IN ('CLOSED','CANCELLED','REFUNDED','REPLACEMENT_NEEDED') THEN 'CLOSED' ELSE 'ACTIVE' END,
       CASE WHEN j.status IN ('CLOSED','CANCELLED','REFUNDED','REPLACEMENT_NEEDED') THEN 'CLOSE' END,
       j.created_at,
       CASE WHEN j.status IN ('CLOSED','CANCELLED','REFUNDED','REPLACEMENT_NEEDED') THEN j.updated_at END, 0
FROM jobs j WHERE j.status <> 'PAID';

ANALYZE assignments;
-- 2M events: 2 per job (real jobs have ~12; enough to size the indexes)
INSERT INTO job_events (event_id, event_type, order_id, job_id, job_version, actor_type, action, from_state, to_state,
                        payload, occurred_at)
SELECT gen_random_uuid(), 'JobStateChanged', j.order_id, j.id, v, 'SYSTEM', 'X', 'A', 'B',
       '{"orderId":"x","jobId":"y","action":"X","fromState":"A","toState":"B"}'::jsonb, j.created_at
FROM jobs j CROSS JOIN generate_series(1, 2) v;

ANALYZE job_events;
-- Outbox: 2M published (inside the 7-day retention it would be far fewer; worst case), 2,000 waiting
INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at, published_at, attempts)
SELECT e.event_id, 'core.job.v1', e.job_id::text, 'JobStateChanged', e.payload, e.occurred_at, e.occurred_at, 1
FROM job_events e;
INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
SELECT gen_random_uuid(), 'core.job.v1', j.id::text, 'JobStateChanged', '{}'::jsonb, now()
FROM (SELECT id FROM jobs WHERE status = 'IN_PRODUCTION' LIMIT 2000) j;
-- 50 failing rows on backoff
UPDATE outbox SET attempts = 3, next_attempt_at = now() + interval '30 seconds'
WHERE id IN (SELECT id FROM outbox WHERE published_at IS NULL LIMIT 50);

-- 500k notices already sent
INSERT INTO deadline_notices (job_id, kind, due_at, event_id, sent_at)
SELECT id, 'PRODUCTION_DUE_48H', coalesce(production_due_at, now()) - interval '48 hours', gen_random_uuid(), now()
FROM jobs WHERE status IN ('IN_PRODUCTION', 'CLOSED') LIMIT 500000;

VACUUM ANALYZE;
SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname = 'core_test' ORDER BY n_live_tup DESC;
