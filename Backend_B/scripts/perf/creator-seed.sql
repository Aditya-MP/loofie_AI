-- F1 round C load data (core_test schema): :creators creators, each with a live session
-- (cookie "perf-creator-session-<i>-padpad"), a complete manual passport (2 self-reported numbers, 1 Insights
-- screenshot). Usage: psql ... -v creators=50000 -f creator-seed.sql
SET search_path = core_test;
SET statement_timeout = 0;

INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at, onboarding_completed,
                   created_at, updated_at)
SELECT md5('perf-creator-' || i)::uuid, 'CREATOR', 'ACTIVE', '+9190' || lpad(i::text, 8, '0'), now(), now(), true,
       now(), now()
FROM generate_series(1, :creators) i ON CONFLICT DO NOTHING;

INSERT INTO sessions (id, token_hash, user_id, kind, created_at, expires_at, absolute_expires_at, last_seen_at,
                      user_agent)
SELECT gen_random_uuid(), sha256(convert_to('perf-creator-session-' || i || '-padpad', 'UTF8')),
       md5('perf-creator-' || i)::uuid, 'FULL', now(), now() + interval '30 days', now() + interval '90 days', now(),
       'perf-seed'
FROM generate_series(1, :creators) i ON CONFLICT (token_hash) DO NOTHING;

INSERT INTO creator_profiles (user_id, display_name, city, state, languages, categories, formats,
                              rate_per_reel_rupees, partnership_ads, turnaround_days, bio, sample_links,
                              instagram_handle, review_status, review_requested_at, completed_at, created_at,
                              updated_at)
SELECT md5('perf-creator-' || i)::uuid, 'Perf Creator ' || i, 'Pune', 'MH', '{Hindi,Marathi}', '{beauty_skincare}',
       '{REEL,STORY}', 1000 + (i % 40) * 100, true, 5, 'Skincare reels', '{https://www.instagram.com/reel/abc/}',
       'perf.creator.' || i, 'PENDING', now(), now(), now(), now()
FROM generate_series(1, :creators) i ON CONFLICT DO NOTHING;

INSERT INTO creator_metrics (creator_id, metric, source, value, captured_on, set_by, updated_at)
SELECT md5('perf-creator-' || i)::uuid, m, 'SELF_REPORTED', 1000 + i, current_date, md5('perf-creator-' || i)::uuid,
       now()
FROM generate_series(1, :creators) i, unnest(ARRAY['FOLLOWERS', 'AVG_VIEWS_10']) m ON CONFLICT DO NOTHING;

INSERT INTO uploads (key, owner_id, purpose, content_type, max_bytes, issued_at, expires_at, used_at, size_bytes)
SELECT 'creator-evidence/' || md5('perf-creator-' || i)::uuid || '/perf.png', md5('perf-creator-' || i)::uuid,
       'CREATOR_EVIDENCE', 'image/png', 5242880, now(), now() + interval '5 minutes', now(), 2048
FROM generate_series(1, :creators) i ON CONFLICT DO NOTHING;

INSERT INTO creator_evidence (id, creator_id, upload_key, kind, status, created_at)
SELECT md5('perf-evidence-' || i)::uuid, md5('perf-creator-' || i)::uuid,
       'creator-evidence/' || md5('perf-creator-' || i)::uuid || '/perf.png', 'INSIGHTS', 'PENDING', now()
FROM generate_series(1, :creators) i ON CONFLICT DO NOTHING;

ANALYZE users;
ANALYZE sessions;
ANALYZE creator_profiles;
ANALYZE creator_metrics;
ANALYZE creator_evidence;

SELECT (SELECT count(*) FROM creator_profiles WHERE completed_at IS NOT NULL) AS complete_profiles,
       (SELECT count(*) FROM creator_evidence) AS evidence;
