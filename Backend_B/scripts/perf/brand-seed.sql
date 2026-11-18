-- F1 round B load data (core_test schema): :orgs brand companies, each with one BRAND_ADMIN who has a live session
-- (cookie "perf-brand-session-<i>-padpadpad"), and :jobs jobs in CREATOR_ACCEPTED spread over those companies
-- (job j belongs to company (j % :orgs) + 1). Usage: psql ... -v orgs=2000 -v jobs=8000 -f brand-seed.sql
SET search_path = core_test;
SET statement_timeout = 0;

INSERT INTO users (id, account_type, status, email, email_verified_at, adult_confirmed_at, onboarding_completed,
                   created_at, updated_at)
SELECT md5('perf-brand-' || i)::uuid, 'BRAND', 'ACTIVE', 'perf-brand-' || i || '@load.test', now(), now(), true,
       now(), now()
FROM generate_series(1, :orgs) i ON CONFLICT DO NOTHING;

INSERT INTO organizations (id, legal_name, billing_line1, billing_city, billing_state, billing_pincode, pickup_state,
                           created_by, created_at, updated_at)
SELECT md5('perf-org-' || i)::uuid, 'Perf Brand ' || i, '1 Test Road', 'Mumbai', 'MH', '400001', 'MH',
       md5('perf-brand-' || i)::uuid, now(), now()
FROM generate_series(1, :orgs) i ON CONFLICT DO NOTHING;

INSERT INTO org_members (org_id, user_id, role, added_at)
SELECT md5('perf-org-' || i)::uuid, md5('perf-brand-' || i)::uuid, 'BRAND_ADMIN', now()
FROM generate_series(1, :orgs) i ON CONFLICT DO NOTHING;

INSERT INTO sessions (id, token_hash, user_id, kind, created_at, expires_at, absolute_expires_at, last_seen_at,
                      user_agent)
SELECT gen_random_uuid(), sha256(convert_to('perf-brand-session-' || i || '-padpadpad', 'UTF8')),
       md5('perf-brand-' || i)::uuid, 'FULL', now(), now() + interval '30 days', now() + interval '90 days', now(),
       'perf-seed'
FROM generate_series(1, :orgs) i ON CONFLICT (token_hash) DO NOTHING;

INSERT INTO orders (id, brand_org_id, created_at, version)
SELECT md5('perf-order-' || j)::uuid, md5('perf-org-' || ((j % :orgs) + 1))::uuid, now(), 0
FROM generate_series(1, :jobs) j ON CONFLICT DO NOTHING;

INSERT INTO jobs (id, order_id, brand_org_id, status, digital_only, creator_fee_paise, free_revision_used,
                  brand_pickup_state, created_at, updated_at, version)
SELECT md5('perf-job-' || j)::uuid, md5('perf-order-' || j)::uuid, md5('perf-org-' || ((j % :orgs) + 1))::uuid,
       'CREATOR_ACCEPTED', false, 500000, false, 'MH', now(), now(), 0
FROM generate_series(1, :jobs) j ON CONFLICT DO NOTHING;

INSERT INTO assignments (id, job_id, creator_id, status, opened_at, version)
SELECT gen_random_uuid(), md5('perf-job-' || j)::uuid, gen_random_uuid(), 'ACTIVE', now(), 0
FROM generate_series(1, :jobs) j
WHERE NOT EXISTS (SELECT 1 FROM assignments a WHERE a.job_id = md5('perf-job-' || j)::uuid AND a.status = 'ACTIVE');

ANALYZE users; ANALYZE sessions; ANALYZE org_members; ANALYZE organizations; ANALYZE jobs; ANALYZE assignments;
SELECT (SELECT count(*) FROM organizations) AS orgs, (SELECT count(*) FROM org_members) AS members,
       (SELECT count(*) FROM jobs WHERE status = 'CREATOR_ACCEPTED') AS accepted_jobs;
