# core-service: production readiness research

Written 2026-11-14 after a full read of every file in `core-service`. For each feature: what it does,
what breaks in production, how established systems solve it, and what we do. Ranked by damage.

> **2026-11-15: Kafka removed.** Nothing read it. The outbox now goes to Backend A's `/ai/feedback` over HTTP
> with the same guarantees (no transaction while sending, per-job order, backoff, stop when unreachable).
> Section 2 below is kept as the record of what was found and fixed; see `docs/features/kafka-removal.md`.

**Load assumption.** "1 lakh requests" is taken as **per day**: about 1-2 requests a second on
average, 30-50 at peak. One instance handles that. We still design for **at least 2 instances**
(so a crash or a deploy is not an outage) and for growth to roughly 100x without a rewrite.
At this size the risks are not speed. They are: **duplicate requests, a dependency going down,
two instances doing the same work, and failures nobody sees.**

Status legend: **P1** = fixed in Phase 1 (this round). **P2/P3** = later phases. **Open** = needs a decision.

---

## 1. Job state machine (F5): `JobTransitionService`

**What it does well already:** one entry point, a transition table as data, optimistic locking
(`@Version`), event row and outbox row in the same transaction, timer actions guarded by their deadline.

| # | Problem | What happens in production | How it is solved elsewhere | Fix | Phase |
|---|---|---|---|---|---|
| 1.1 | No idempotency on `POST /transitions` | Phone loses network after the server committed; the app retries. Self-loop actions (`OPS_EXTEND`) apply twice: +96h instead of +48h. | Stripe-style `Idempotency-Key` header (IETF draft `draft-ietf-httpapi-idempotency-key-header`): store key + request hash + response; a replay returns the stored response; same key with a different body is rejected. | `Idempotency-Key` header, stored in the **same transaction** as the transition, so a crash cannot store one without the other. 24h retention. | P1 |
| 1.2 | No stale-screen check | Brand looks at an old screen and acts on a job that has moved on. Mostly caught by the state check, not always (self-loops). | HTTP `If-Match` / an expected version in the body (optimistic concurrency at the API). | Optional `expectedVersion` in the request; mismatch gives 409. | P1 |
| 1.3 | Follow-up events can be published in the wrong order | `CONFIRM_RECEIVED` and its automatic `START_PRODUCTION` are written in the same millisecond. The outbox is ordered by `created_at, id`, and UUIDv7 is random inside one millisecond, so about half the time the follow-up goes out **before** its cause. | Order the outbox by a database sequence, never by time. | `outbox.seq` identity column; the relay orders by it. | P1 |
| 1.4 | Free-text reason goes to Kafka | `docs/product-decisions.md`: "ops notes stay in the backend log only". The note is in the Kafka payload, where other services read it (it may hold a phone number). | Keep free text out of event payloads; events carry codes. | Remove `reason` from the event payload. It stays in `job_events.reason` for audit. | P1 |
| 1.5 | Race on the unique "one active creator" index gives a 500 | Two ops invite at the same moment: database unique violation, generic 500. | Map constraint violations to 409. | 409 `conflict` for `DataIntegrityViolationException`. | P1 |
| 1.6 | Unexpected errors have no id | A user reports "it failed"; nothing links that to a log line. | Return an error id, log it with the stack trace. | 500 problem with `errorId`, logged at ERROR. Database pool exhausted gives 503 + `Retry-After`. | P1 |
| 1.7 | Per-action business metrics missing | Nobody knows how many jobs auto-approve, how many 409s. | Counters per action and outcome. | `loofie.job.transitions{action}` and `loofie.api.errors{type}`. | P1 |

## 2. Outbox relay and Kafka (`OutboxRelay`)

| # | Problem | What happens in production | How it is solved elsewhere | Fix | Phase |
|---|---|---|---|---|---|
| 2.1 | A database transaction is held open during Kafka sends | Kafka down: one run waits 10s per row x 100 rows, holding a connection and row locks for about 16 minutes. Pool exhaustion, then the whole API fails. | Never do network I/O inside a DB transaction. Read, send, then mark in a short transaction. | No transaction while sending. Short read, async sends, one short update. | P1 |
| 2.2 | Sends are one at a time | About 200-500 events/s at best; a backlog after an outage drains slowly. | Async sends with the producer batching (`linger.ms`). | Rows for different keys are sent in parallel. Rows for the **same key** are sent one after another, so order is kept. | P1 |
| 2.3 | Kafka down: every run blocks on metadata | `max.block.ms` defaults to 60s per send call. | Fail fast and stop the run (circuit breaker). | `max.block.ms` 5s; the first broker failure stops that run. | P1 |
| 2.4 | A failing row is retried every 500ms | Hammers the broker and the log. | Exponential backoff per message. | `next_attempt_at` with 1s, 2s, 4s... capped at 60s. Later rows of that key wait behind it, so order is kept. | P1 |
| 2.5 | Two instances reorder events | `SKIP LOCKED` splits one key's rows across instances. | Single active relay via a leader lock (ShedLock, or a lease table). | `scheduler_leases` table: one leader per job (relay, timers, cleanup). Uses database time, so clock skew does not matter. Works through PgBouncer, unlike session advisory locks. | P1 |
| 2.6 | The outbox grows forever | Every event is kept; table and index bloat. | Delete published rows after a retention window. | Cleanup job: published rows older than 7 days, in small batches. | P1 |
| 2.7 | Inbox table grows forever | Same. | Keep dedup rows longer than any redelivery can happen. | Delete `processed_events` older than 30 days. | P1 |
| 2.8 | No visibility of backlog | Events stop flowing and nobody notices. | Alert on oldest unpublished age. | Gauges: `loofie.outbox.backlog`, `loofie.outbox.oldest.age.seconds`; counters for published and failed. | P1 |
| 2.9 | Consumers must not trust order alone | Even with all of the above, at-least-once delivery means duplicates. | Consumers de-duplicate on `eventId` and ignore `aggregateVersion` lower than what they have. | Documented in `docs/integration/backend-a-ai-service.md`. | P1 |
| 2.10 | Topics are created by hand | Missing topic in a new environment: events pile up. | Declare topics as `NewTopic` beans or in infra code. | With the deployment work. | P3 |

## 3. Timer worker (`DeadlineWorker`)

| # | Problem | What happens in production | Fix | Phase |
|---|---|---|---|---|
| 3.1 | Head-of-line blocking | It always takes the oldest 100 due jobs. 100 jobs that keep failing are picked first every run; nothing else ever auto-approves or times out. | A job that fails is put on backoff (excluded from the query for 5 minutes) and logged at ERROR. | P1 |
| 3.2 | Reminder queries cannot use indexes | `production_due_at - interval '48 hours' <= now` is a full table scan, 10 times every 30 seconds. | Rewrite as `production_due_at <= now + 48h`, which uses the index. | P1 |
| 3.3 | Every instance does the same work | Correct (optimistic lock and primary key), but wasteful and noisy. | Leader lease, as for the relay. | P1 |
| 3.4 | No lag metric | If the worker stops, jobs silently stop moving. | `loofie.timer.oldest.due.age.seconds`, counters for moves and notices. | P1 |
| 3.5 | Notices are not delivered | `JobDeadlineNotice` events exist, but nobody sends email or WhatsApp. | Notification service. **Open:** who builds it. | Open |

## 4. Database

| # | Problem | Fix | Phase |
|---|---|---|---|
| 4.1 | No statement, lock or idle-transaction timeouts | One stuck query or forgotten transaction holds a connection forever. Set `statement_timeout` 10s, `lock_timeout` 5s, `idle_in_transaction_session_timeout` 60s on every pooled connection. A long migration lifts the limit for itself only, with `SET LOCAL`. | P1 |
| 4.2 | Pool defaults | Hikari 20 connections, 5s connection timeout (fail fast with 503, do not queue forever). | P1 |
| 4.3 | Missing indexes | `assignments(job_id)` (timeline query); ops list ordering `jobs(updated_at, id)`, `(status, updated_at)`, `(brand_org_id, updated_at)`. | P1 |
| 4.4 | Ops list uses OFFSET + `count(*)` | Fine up to about 1 lakh jobs with the indexes above. Switch to keyset (cursor) paging when the jobs table passes that. | P3 |
| 4.5 | `job_events` grows forever | Intended: it is the audit log. Partition by month when it passes about 10M rows. | Later |
| 4.6 | Backups and point-in-time recovery | Depends on hosting. Managed Postgres (RDS / Cloud SQL) gives both. **Open:** hosting. | Open |

## 5. API, runtime and operations

| # | Problem | Fix | Phase |
|---|---|---|---|
| 5.1 | No request id in logs | Filter that accepts or creates `X-Request-Id`, puts it in every log line (MDC), and returns it. Used as the correlation id of the transition. | P1 |
| 5.2 | Plain-text logs | JSON logs (ECS format, built into Spring Boot) in the `prod` profile, so a log platform can search them. | P1 |
| 5.3 | No metrics endpoint | Prometheus endpoint on a **separate management port** (not public). Liveness and readiness probes. | P1 |
| 5.4 | Shutdown can cut a batch | Graceful shutdown: finish in-flight requests and scheduled runs (30s). | P1 |
| 5.5 | Thread-per-request limits | Java 25 virtual threads; the database pool is the real limit. | P1 |
| 5.6 | No production profile | `application-prod.yaml` that takes database and Kafka settings from environment variables only. | P1 |
| 5.7 | No real login | Every request is refused in production until F1 auth exists. **Biggest blocker for launch.** | F1 |
| 5.8 | No rate limiting | Belongs at the load balancer or gateway (per-IP, per-user). Depends on hosting. | P3 |
| 5.9 | No CI | Tests only run on one laptop. GitHub Actions with a Postgres service container. | P3 |
| 5.10 | No load or failure tests | Phase 1 adds concurrency tests in JUnit. A k6 load script and chaos runs come with deployment. | P1 / P3 |

## 6. F6 product delivery (Phase 2)

- Holiday edits and deletes are not audited (who, when, old value). Add an audit table.
- No limit on reships. **Open:** after how many reships does ops take over?
- `courier` is free text. A fixed courier list is needed before a tracking API can be plugged in.
- Photo is a string until F7 upload. When upload lands: content type, size limit, virus scan, signed URLs.
- Brand pickup state is not known yet (needs the brand profile from F1).

## 7. F10 ops console (Phase 3)

- All ops users have equal power. **Open:** should refunds and releases need a senior ops role?
- Ops actions are in `job_events`, but holiday changes are not (see section 6).
- Keyset paging at scale (4.4).

## 8. F9 extension, approvals, closing

Logic is sound and covered by tests. Open product questions are listed in `docs/product-decisions.md`.
Phase 1 idempotency removes the double-extension risk (1.1).

---

## Phase 1 result (2026-11-14)

Built: everything marked P1 above. Migration `V5__production_hardening.sql`. 106 tests pass (15 new).

Verified by tests:
- 20 parallel retries with one `Idempotency-Key` give 20 identical 200s and exactly one change.
- Same key with a different body gives 422; a stale `expectedVersion` gives 409 and changes nothing.
- A cause and its automatic follow-up always reach Kafka in order (8 jobs, same millisecond).
- A row that keeps failing backs off (1 attempt per backoff window) and holds back only its own job's rows.
- A job whose timer action keeps failing is tried once per 5 minutes; other jobs keep moving.
- Leases: a second instance cannot take a job until the first's lease expires or is released.
- Retention deletes only rows past their window; unpublished outbox rows are never deleted.
- The ops note never reaches Kafka; it stays in `job_events.reason`.

Verified by running the app: readiness and liveness probes are UP, `/actuator/prometheus` shows every
`loofie_*` metric, and an error response carries the same `requestId` as the `X-Request-Id` header.

Decision made while building: the relay keeps a job's events in order **across topics** (grouped by job
key, not by topic + key). This is stricter than Kafka needs and matches the old relay.

Not done in Phase 1, still open:
- A k6 load test and a "kill Kafka / kill the database" run against a deployed instance (needs hosting).
- Alert rules (for example `loofie_outbox_oldest_age_seconds > 60`, `loofie_timer_last_run_age_seconds > 120`)
  belong in the monitoring tool, which depends on hosting.
- Clients (app and ops console) must start sending `Idempotency-Key` and `expectedVersion`; both are optional
  today so nothing breaks.

## Phase 1 scope (as planned)

1.1-1.7, 2.1-2.9, 3.1-3.4, 4.1-4.3, 5.1-5.6, plus concurrency tests:
20 parallel identical requests with one idempotency key give exactly one event; parallel moves on many
jobs all succeed; follow-up events always publish in order; a failing row backs off without blocking other keys.
