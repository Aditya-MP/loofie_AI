-- F1 round C part 2 load data, on top of creator-seed.sql: creators :from..:to get 19 more live sessions (20 in all)
-- and 2 more screenshots (3 in all), the deletion gate's worst case. Usage: psql ... -v from=45001 -v to=45500 -f ...
SET search_path = core_test;
SET statement_timeout = 0;

INSERT INTO sessions (id, token_hash, user_id, kind, created_at, expires_at, absolute_expires_at, last_seen_at,
                      user_agent)
SELECT gen_random_uuid(), sha256(convert_to('perf-del-session-' || i || '-' || k, 'UTF8')),
       md5('perf-creator-' || i)::uuid, 'FULL', now(), now() + interval '30 days', now() + interval '90 days', now(),
       'perf-seed'
FROM generate_series(:from, :to) i, generate_series(1, 19) k ON CONFLICT (token_hash) DO NOTHING;

INSERT INTO uploads (key, owner_id, purpose, content_type, max_bytes, issued_at, expires_at, used_at, size_bytes)
SELECT 'creator-evidence/' || md5('perf-creator-' || i)::uuid || '/perf-' || k || '.png',
       md5('perf-creator-' || i)::uuid, 'CREATOR_EVIDENCE', 'image/png', 5242880, now(),
       now() + interval '5 minutes', now(), 2048
FROM generate_series(:from, :to) i, generate_series(1, 2) k ON CONFLICT DO NOTHING;

INSERT INTO creator_evidence (id, creator_id, upload_key, kind, status, created_at)
SELECT md5('perf-evidence-' || i || '-' || k)::uuid, md5('perf-creator-' || i)::uuid,
       'creator-evidence/' || md5('perf-creator-' || i)::uuid || '/perf-' || k || '.png', 'INSIGHTS', 'PENDING', now()
FROM generate_series(:from, :to) i, generate_series(1, 2) k ON CONFLICT DO NOTHING;

SELECT count(*) AS live_sessions FROM sessions s
WHERE s.revoked_at IS NULL AND s.user_id IN (SELECT md5('perf-creator-' || i)::uuid FROM generate_series(:from, :to) i);
