-- F10 perf data on top of load-data.sql (schema core_test): 100k creators (85% bookable), 200k ops time entries,
-- 50 open payout holds. Writes ops-jobs.csv (job ids for time logging) and ops-cities.csv (search terms).
-- Usage: psql -f ops-seed.sql
SET search_path = core_test;
SET statement_timeout = 0;
\timing on

INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at, onboarding_completed,
                   created_at, updated_at)
SELECT ('00000000-0000-4000-9000-' || lpad(g::text, 12, '0'))::uuid, 'CREATOR', 'ACTIVE',
       '+9193' || lpad(g::text, 8, '0'), now(), now(), true, now(), now()
FROM generate_series(1, 100000) g
ON CONFLICT DO NOTHING;

INSERT INTO creator_profiles (user_id, display_name, city, state, languages, categories, formats, rate_per_reel_rupees,
                              partnership_ads, turnaround_days, sample_links, instagram_handle, marketplace_status,
                              review_status, completed_at, level, level_reason, created_at, updated_at)
SELECT ('00000000-0000-4000-9000-' || lpad(g::text, 12, '0'))::uuid, 'Perf Creator ' || g,
       (ARRAY['Mumbai', 'Delhi', 'Bengaluru', 'Pune', 'Jaipur', 'Kolkata', 'Chennai', 'Hyderabad'])[1 + g % 8],
       (ARRAY['MH', 'DL', 'KA', 'MH', 'RJ', 'WB', 'TN', 'TS'])[1 + g % 8],
       CASE g % 4 WHEN 0 THEN ARRAY['Hindi', 'English'] WHEN 1 THEN ARRAY['English'] WHEN 2 THEN ARRAY['Hindi']
                  ELSE ARRAY['Marathi', 'Hindi'] END,
       CASE g % 4 WHEN 0 THEN ARRAY['beauty_skincare'] WHEN 1 THEN ARRAY['personal_care'] WHEN 2 THEN ARRAY['other']
                  ELSE ARRAY['beauty_skincare', 'other'] END,
       ARRAY['REEL'], 500 + (g * 37) % 20000, true, 5, ARRAY['https://www.instagram.com/reel/x/'],
       'perf.creator.' || g,
       CASE WHEN g % 20 = 0 THEN 'HIDDEN' ELSE 'LISTED' END,
       CASE WHEN g % 10 = 0 THEN 'PENDING' ELSE 'APPROVED' END,
       now(), CASE WHEN g % 5 = 0 THEN 'VERIFIED' ELSE 'NEW' END,
       CASE WHEN g % 5 = 0 THEN 'perf data' END, now(), now()
FROM generate_series(1, 100000) g
ON CONFLICT DO NOTHING;
ANALYZE users;
ANALYZE creator_profiles;

INSERT INTO ops_time_entries (id, job_id, order_id, minutes, activity, note, logged_by, logged_role, logged_at)
SELECT gen_random_uuid(), j.id, j.order_id, 5 + (random() * 60)::int,
       (ARRAY['COORDINATION', 'DELIVERY', 'DISPUTE', 'SHORTLIST'])[1 + (random() * 3)::int], NULL,
       '00000000-0000-4000-8000-000000000001', 'OPS', now() - random() * interval '365 days'
FROM (SELECT id, order_id FROM jobs ORDER BY random() LIMIT 200000) j;
ANALYZE ops_time_entries;

INSERT INTO payout_holds (id, job_id, placed_by, placed_role, placed_at, reason)
SELECT gen_random_uuid(), id, '00000000-0000-4000-8000-000000000001', 'OPS', now(), 'perf hold'
FROM jobs WHERE status = 'APPROVED' LIMIT 50
ON CONFLICT DO NOTHING;
ANALYZE payout_holds;

\copy (SELECT id, order_id FROM jobs WHERE status IN ('IN_PRODUCTION', 'IN_REVIEW') LIMIT 5000) TO 'ops-jobs.csv' WITH CSV
