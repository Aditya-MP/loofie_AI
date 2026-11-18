-- F7 load test data (schema core_test). Jobs are marked creator_fee_paise = 707 so the client can list them.
--   :inprod   jobs in IN_PRODUCTION (creator uploads + submits)
--   :inreview jobs in IN_REVIEW, each with a READY video (brand views and approves)
-- Run: psql -v inprod=1000 -v inreview=1000 -f draft-seed.sql
SET search_path = core_test;

-- Earlier runs' jobs are retired, not deleted (job_events is append-only), so the client never lists them again.
-- Their videos still waiting for processing are retired too (their files are gone), so the worker never takes them.
UPDATE job_drafts SET status = 'SUPERSEDED', last_error = 'perf run retired', lease_until = NULL
WHERE status IN ('CHECKING', 'PREVIEWING') AND job_id IN (SELECT id FROM jobs WHERE creator_fee_paise IN (707, 708));
UPDATE jobs SET creator_fee_paise = 708 WHERE creator_fee_paise = 707;
DELETE FROM outbox;

CREATE TEMP TABLE perf_jobs AS
SELECT gen_random_uuid() AS job_id, gen_random_uuid() AS order_id, gen_random_uuid() AS brand, gen_random_uuid() AS creator,
       CASE WHEN g <= :inprod THEN 'IN_PRODUCTION' ELSE 'IN_REVIEW' END AS status
FROM generate_series(1, :inprod + :inreview) g;

INSERT INTO orders (id, brand_org_id, created_at, version) SELECT order_id, brand, now(), 0 FROM perf_jobs;
INSERT INTO jobs (id, order_id, brand_org_id, status, digital_only, creator_fee_paise, free_revision_used, created_at,
                  updated_at, version, production_due_at, review_due_at)
SELECT job_id, order_id, brand, status, true, 707, false, now(), now(), 0,
       CASE WHEN status = 'IN_PRODUCTION' THEN now() + interval '5 days' END,
       CASE WHEN status = 'IN_REVIEW' THEN now() + interval '5 days' END
FROM perf_jobs;
INSERT INTO assignments (id, job_id, creator_id, status, opened_at, version)
SELECT gen_random_uuid(), job_id, creator, 'ACTIVE', now(), 0 FROM perf_jobs;
INSERT INTO job_drafts (id, job_id, creator_id, attempt, kind, master_key, master_bytes, preview_key, status, probe,
                        submitted_at, ready_at, next_try_at, updated_at)
SELECT gen_random_uuid(), job_id, creator, 1, 'FIRST', 'drafts/' || creator || '/' || job_id || '.mp4', 1000,
       'previews/' || creator || '/' || job_id || '.mp4', 'READY',
       '{"width":1080,"height":1920,"seconds":30.0,"hasAudio":true}', now(), now(), now(), now()
FROM perf_jobs WHERE status = 'IN_REVIEW';

ANALYZE jobs; ANALYZE assignments; ANALYZE job_drafts;
