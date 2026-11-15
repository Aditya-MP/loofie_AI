-- Relay throughput: 60,000 waiting job events Backend A takes (JobStateChanged with a creator), on top of the
-- 1M-job data set from load-data.sql. 20,000 jobs x 3 events each, so per-job order is exercised too.
SET search_path = core_test;
SET statement_timeout = 0;
\timing on

UPDATE outbox SET published_at = now() WHERE published_at IS NULL; -- start from an empty queue

INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
SELECT gen_random_uuid(), 'core.job.v1', j.id::text, 'JobStateChanged',
       jsonb_build_object('eventId', gen_random_uuid(), 'eventType', 'JobStateChanged', 'schemaVersion', 1,
           'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), 'producer', 'core-service',
           'aggregateId', j.id, 'aggregateVersion', n, 'correlationId', gen_random_uuid(), 'causationId', null,
           'payload', jsonb_build_object('jobId', j.id, 'orderId', j.order_id, 'creatorId', a.creator_id,
               'action', 'SUBMIT_DRAFT', 'fromState', 'IN_PRODUCTION', 'toState', 'IN_REVIEW', 'actorType', 'CREATOR',
               'hasReason', false, 'reasonCode', null, 'faultParty', null, 'deliveryIssueType', null)),
       now()
FROM (SELECT id, order_id FROM jobs WHERE status = 'IN_PRODUCTION' LIMIT 20000) j
JOIN assignments a ON a.job_id = j.id AND a.status = 'ACTIVE'
CROSS JOIN generate_series(1, 3) n
ORDER BY n, j.id;

ANALYZE outbox;
SELECT count(*) AS waiting FROM outbox WHERE published_at IS NULL AND parked_at IS NULL;
