SET search_path = core_test;
SET statement_timeout = 0;
SELECT id AS job, brand_org_id AS brand FROM jobs WHERE status = 'IN_PRODUCTION' LIMIT 1 \gset

\echo '=== Q1 engine: load job by id'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM jobs WHERE id = :'job';
\echo '=== Q2 engine: active assignment'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM assignments WHERE job_id = :'job' AND status = 'ACTIVE';
\echo '=== Q3 engine: shipments count'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(*) FROM shipments WHERE job_id = :'job';

\echo '=== Q4 timer: due production deadlines'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id, j.production_due_at FROM jobs j WHERE j.status = 'IN_PRODUCTION' AND j.production_due_at <= now()
ORDER BY j.production_due_at LIMIT 100;
\echo '=== Q5 timer: due 48h production reminders not yet sent'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id, j.order_id, j.version, (j.production_due_at - make_interval(secs => 172800)) AS due_at FROM jobs j
WHERE j.status = 'IN_PRODUCTION' AND j.production_due_at <= now() + interval '48 hours'
  AND NOT EXISTS (SELECT 1 FROM deadline_notices n WHERE n.job_id = j.id AND n.kind = 'PRODUCTION_DUE_48H'
                  AND n.due_at = (j.production_due_at - make_interval(secs => 172800)))
ORDER BY j.production_due_at LIMIT 100;
\echo '=== Q6 timer: due review (another rule, same shape)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id FROM jobs j WHERE j.status = 'IN_REVIEW' AND j.review_due_at <= now() ORDER BY j.review_due_at LIMIT 100;

\echo '=== Q7 relay: read batch'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT o.id, o.topic, o.message_key, o.event_type, o.envelope::text, o.attempts FROM outbox o
WHERE o.published_at IS NULL AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
  AND NOT EXISTS (SELECT 1 FROM outbox b WHERE b.published_at IS NULL AND b.next_attempt_at > now()
                  AND b.message_key = o.message_key AND b.seq < o.seq)
ORDER BY o.seq LIMIT 100;
\echo '=== Q8 monitor: backlog stats'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*), count(*) FILTER (WHERE attempts > 0), min(created_at) FROM outbox WHERE published_at IS NULL;

\echo '=== Q9 ops list: default page 1'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id, a.creator_id FROM jobs j LEFT JOIN assignments a ON a.job_id = j.id AND a.status = 'ACTIVE'
ORDER BY j.updated_at DESC, j.id DESC LIMIT 25 OFFSET 0;
\echo '=== Q10 ops list: page 2000 (offset 50000)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id, a.creator_id FROM jobs j LEFT JOIN assignments a ON a.job_id = j.id AND a.status = 'ACTIVE'
ORDER BY j.updated_at DESC, j.id DESC LIMIT 25 OFFSET 50000;
\echo '=== Q11 ops list: status filter'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id FROM jobs j WHERE j.status IN ('IN_PRODUCTION') ORDER BY j.updated_at DESC, j.id DESC LIMIT 25;
\echo '=== Q12 ops list: brand filter'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id FROM jobs j WHERE j.brand_org_id = :'brand' ORDER BY j.updated_at DESC, j.id DESC LIMIT 25;
\echo '=== Q13 ops list: overdue filter'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT j.id FROM jobs j WHERE LEAST(j.accept_due_at, j.ship_due_at, j.receipt_ops_check_at, j.auto_start_at,
  j.issue_response_due_at, j.missing_escalate_at, j.production_due_at, j.review_due_at, j.replacement_due_at) < now()
ORDER BY j.updated_at DESC, j.id DESC LIMIT 25;
\echo '=== Q14 ops list: total count, no filter'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT count(*) FROM jobs j;
\echo '=== Q15 ops list: count with overdue filter'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM jobs j WHERE LEAST(j.accept_due_at, j.ship_due_at, j.receipt_ops_check_at, j.auto_start_at,
  j.issue_response_due_at, j.missing_escalate_at, j.production_due_at, j.review_due_at, j.replacement_due_at) < now();

\echo '=== Q16 job detail: timeline'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM job_events WHERE job_id = :'job' ORDER BY job_version;
\echo '=== Q17 job detail: assignments'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM assignments WHERE job_id = :'job' ORDER BY opened_at, id;

\echo '=== Q18 retention: one outbox delete batch'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
DELETE FROM outbox WHERE id IN (SELECT id FROM outbox WHERE published_at IS NOT NULL
                                AND published_at < now() - interval '7 days' LIMIT 5000);
ROLLBACK;

\echo '=== sizes'
SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS total, n_live_tup
FROM pg_stat_user_tables WHERE schemaname = 'core_test' ORDER BY pg_total_relation_size(relid) DESC LIMIT 8;
