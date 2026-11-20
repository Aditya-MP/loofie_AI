-- F9 swap load seed (schema core_test): :n jobs waiting for a replacement creator, and :n bookable creators.
-- Jobs are marked by creator_fee_paise = 500009; earlier runs' marked jobs are retired first.
-- Usage: psql -v n=1200 -f swap-seed.sql
SET search_path = core_test;

UPDATE jobs SET status = 'REFUNDED', replacement_due_at = NULL WHERE creator_fee_paise = 500009
                                                               AND status <> 'REFUNDED';
UPDATE job_swap_offers SET status = 'WITHDRAWN', closed_reason = 'LOAD_RESET'
WHERE status = 'OFFERED' AND job_id IN (SELECT id FROM jobs WHERE creator_fee_paise = 500009);

CREATE TEMP TABLE seed AS
SELECT gen_random_uuid() AS job_id, gen_random_uuid() AS order_id, gen_random_uuid() AS brand_id,
       gen_random_uuid() AS creator_id, g AS i
FROM generate_series(1, :n) g;

INSERT INTO orders (id, brand_org_id, created_at, version) SELECT order_id, brand_id, now(), 0 FROM seed;
INSERT INTO jobs (id, order_id, brand_org_id, status, digital_only, creator_fee_paise, free_revision_used,
                  replacement_due_at, created_at, updated_at, version)
SELECT job_id, order_id, brand_id, 'REPLACEMENT_NEEDED', true, 500009, false, now() + interval '10 days', now(), now(), 0
FROM seed;

INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at, onboarding_completed,
                   created_at, updated_at)
SELECT creator_id, 'CREATOR', 'ACTIVE', '+9195' || lpad((abs(hashtext(creator_id::text)) % 100000000)::text, 8, '0'),
       now(), now(), true, now(), now()
FROM seed
ON CONFLICT DO NOTHING;

INSERT INTO creator_profiles (user_id, display_name, city, state, languages, categories, formats, rate_per_reel_rupees,
                              partnership_ads, turnaround_days, sample_links, instagram_handle, marketplace_status,
                              review_status, completed_at, created_at, updated_at)
SELECT s.creator_id, 'Swap Load', 'Pune', 'MH', ARRAY['Hindi'], ARRAY['other'], ARRAY['REEL'], 4000, true, 5,
       ARRAY['https://www.instagram.com/reel/x/'], 'swapload.' || left(s.creator_id::text, 12), 'LISTED', 'APPROVED',
       now(), now(), now()
FROM seed s JOIN users u ON u.id = s.creator_id;

\copy (SELECT job_id, brand_id, creator_id FROM seed s WHERE EXISTS (SELECT 1 FROM creator_profiles p WHERE p.user_id = s.creator_id) ORDER BY i) TO 'swap-jobs.csv' WITH CSV
