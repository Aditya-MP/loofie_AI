-- F1 round A load data (core_test schema): N creators, each with one live session whose cookie value is
-- "perf-session-<i>-padpadpad". Only the SHA-256 of that value is stored, as the app does.
-- Usage: psql ... -v n=100000 -f auth-seed.sql
SET search_path = core_test;
SET statement_timeout = 0;

DELETE FROM sessions WHERE user_agent = 'perf-seed';

INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at, created_at, updated_at)
SELECT md5('perf-creator-' || i)::uuid, 'CREATOR', 'ACTIVE', '+9195' || lpad(i::text, 8, '0'), now(), now(), now(), now()
FROM generate_series(1, :n) i
ON CONFLICT DO NOTHING;

INSERT INTO sessions (id, token_hash, user_id, kind, created_at, expires_at, absolute_expires_at, last_seen_at,
                      user_agent)
SELECT gen_random_uuid(), sha256(convert_to('perf-session-' || i || '-padpadpad', 'UTF8')),
       md5('perf-creator-' || i)::uuid, 'FULL', now(), now() + interval '30 days', now() + interval '90 days', now(),
       'perf-seed'
FROM generate_series(1, :n) i
ON CONFLICT (token_hash) DO NOTHING;

ANALYZE users;
ANALYZE sessions;
SELECT (SELECT count(*) FROM users) AS users, (SELECT count(*) FROM sessions) AS sessions;
