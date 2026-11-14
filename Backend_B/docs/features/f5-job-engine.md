# F5 job engine: production-readiness research (steps 1-11)

Scope: the job state machine, its event log and outbox, the timer worker, and the transition API.
Date: 2026-11-15. Status: **research done, plan awaiting approval. Not production-ready.**

---

## 1. Business purpose

From Foundation v2 (authoritative):

- **F5.** One job = one creator x one deliverable. Fixed states from `DRAFT_BRIEF` to `CLOSED`, plus side states.
  Every transition has a named action, is written to the events log, and is rejected if not allowed from the
  current state. Production and review timers (120h each) run in a background worker, with reminders at 48h and 24h.
- **F12.** Append-only log of state changes, **timers fired**, revisions, payouts, ops minutes. It is the AI
  training data: anything not logged is lost.
- **F9 (engine parts).** 48h accept window; one extension, then `REPLACEMENT_NEEDED`; dispute reviewed by ops
  **within 2 working days**; refund if no replacement within 10 days.
- **Business-critical rules** (engineering philosophy, section 6) that live in this engine: payment before
  invitation; no payout without `APPROVED`; illegal transitions rejected; timers survive restarts; every
  important transition in the append-only log; access by role and ownership.

## 2. What exists

| Layer | Classes |
|---|---|
| api | `JobTransitionController` (one endpoint for every role) |
| app | `JobTransitionService` (single entry point), `JobIntakeService`, `JobEventRecorder`, `JobQueryService`, `job.timer.DeadlineWorker` |
| domain | `TransitionTable` (45 rules), `Job`, `Assignment`, `Shipment`, `JobDeadlines`, `JobDeadline`, `Actor`, `ReasonCode` |
| infra | `JobRepository`, `AssignmentRepository`, `ShipmentRepository`, `JobReadRepository`, `JobDeadlineRepository` |
| plumbing | `Outbox` / `OutboxRelay` / `OutboxMonitor`, `JobEventLog`, `Idempotency`, `LeaderLease`, `RetentionJob` |
| schema | V1 (jobs, assignments, job_events + append-only trigger, outbox, inbox), V2-V5 |

## 3. Architecture and dependencies

- Depends on: calendar (working days, via the `WorkingCalendar` interface), messaging (outbox), events (log).
- Used by: ops console (queries and transitions), order intake (creates jobs), timers.
- Future callers: finance-service (`PAYMENT_CONFIRMED`, `PAYOUT_*`), F7 upload (`SUBMIT_DRAFT`, `PREVIEW_READY`).
  **There is no contract for them yet**: today these actions can only be called in-process as SYSTEM.
- Layer rules are enforced by ArchUnit (see `docs/architecture-layers.md`).

## 4. Functional gaps against Foundation v2

| # | Gap | Impact | Proposal |
|---|---|---|---|
| G1 | Reminders and alerts (`JobDeadlineNotice`) go to Kafka only, not to `job_events`. F12 says "timers fired" must be logged. | AI and audit lose every reminder: no way to learn "brand reviews only after the 24h reminder". | Write each notice to `job_events` as `TimerFired` (no state change), same transaction as the notice. |
| G2 | `DISPUTED` has no clock. F9 says ops reviews within **2 working days**. | Disputes can sit forever unnoticed. | `dispute_review_due_at` = 2 working days; `DISPUTE_REVIEW_OVERDUE` alert to ops. No automatic move: the outcome is ops' decision. |
| G3 | `OPS_EXTEND` on a job with no production deadline gives a 500. | Only reachable with bad data, but a 500 is wrong. | Guard: 422 "no production deadline to extend". |
| G4 | "Creator moved back to NEW level" after a missed deadline (F9). | No creator profiles exist yet. | Out of scope: the `PRODUCTION_EXPIRED` event already carries the assignment, so the creator feature can act on it. Recorded as a dependency. |
| G5 | F6 conflicts with Foundation v2 (tracking required, auto-start only on courier proof). | Built from Aditya's decisions, not signed off by the founder. | Provisional until the founder confirms. Handled in the F6 phase. |
| G6 | Paid revision (25%) and checklist-based revision (F7). | Not built. | F7 phase. |

## 5. Security

| # | Finding | Severity | Proposal |
|---|---|---|---|
| S1 | No real authentication: production refuses every request (`DenyAllActorResolver`). | **Blocker for launch** | F1. Until then the engine's permission logic is tested, but authentication is not. |
| S2 | Authorization is correct and tested: role per action, brand must own the job, creator must be the active assignee, only OPS sets reason codes, only RESOLVE_FALSE_CLAIM sets FALSE_CLAIM. | OK | Keep. Add tests for the combinations not yet covered (below). |
| S3 | Ops are all-powerful: one ops user can refund, cancel or release a creator alone. | Medium (money) | **Decision needed:** require a second ops approval for refunds above an amount? Or a senior-ops role? Not built until decided. |
| S4 | `Idempotency-Key` is optional. Without it, a retried `OPS_EXTEND` applies twice. | Medium | Make the key **required** for all transition calls under the `prod` profile (configurable), once the apps send it. Until then, required for `OPS_EXTEND` only (the one non-idempotent self-loop). |
| S5 | A brand calling with another brand's job id gets 403 "does not belong to this brand", which confirms the job exists. | Low (ids are UUIDv7 with 74 random bits) | Return 404 for jobs outside the caller's brand or assignment. |
| S6 | No rate limiting. | Medium | At the gateway (hosting). In-app per-user limit only if the gateway cannot do it. |
| S7 | Free text from brands and creators (`reason`, up to 1000 chars) is stored in `job_events.reason`. | Low | Stays internal (removed from Kafka in Phase 1). Document as personal data for retention and deletion requests. |

## 6. Performance (measured, not guessed)

Data: 1M jobs, 1M assignments, 2M events, 2M outbox rows, 400k notices (`scripts/perf`). Machine: 16-thread
laptop running app + PostgreSQL 18 + load generator together, so numbers are a lower bound.

**Queries (`EXPLAIN ANALYZE`):**

| Query | Time | Verdict |
|---|---|---|
| Load job by id, active assignment, timeline, assignments | 0.06-0.25 ms | Index scans. Fine. |
| Timer: due production deadlines (100) | 3 ms | Fine. |
| Timer: due reviews after a 15k backlog | 162 ms | Acceptable: only during catch-up after an outage. |
| **Timer: 48h reminder not yet sent** | **659 ms** | **Problem.** Scans every job in the reminder window, including ones already reminded and overdue ones, on every run (10 rules every 30s). Grows with volume. |
| Relay read batch, monitor | 0.6 ms, 1.6 ms | Fine. |
| Ops list page 1, status filter, brand filter, overdue page | 0.4-5.9 ms | Fine (Phase 1 indexes used). |
| **Ops list total `count(*)`** (runs on **every** list call) | **282 ms** | **Problem.** |
| **Ops list count with the overdue filter** | **1,101 ms** | **Problem.** Full scan. |
| Ops list page 2,000 (OFFSET 50,000) | 464 ms | Problem only for deep paging. |
| Retention: one 5,000-row delete | 235 ms | Fine (background, small batches). |

**API load test (open loop):**

| Mix | Rate | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|
| Detail 85% + transitions 15% | 200 rps | 3 ms | 9 ms | 24 ms | 0 |
| Detail 85% + transitions 15% | 500 rps | 3 ms | 8 ms | 34 ms | 0 |
| Detail 85% + transitions 15% | 1,000 rps | 3 ms | 8 ms | 252 ms | 0 |
| **With 25% job-list calls** | **50 rps** | 2.2 s | 5.9 s | 10 s | **23% (503 + timeouts)** |

**Conclusion:** the engine is far above the 500 rps target (p95 8 ms at 1,000 rps on one laptop). **The ops job
list takes the whole service down at 50 rps**: its count queries hold the 20 pooled connections, and every other
request then waits 5s and gets a 503. One slow screen must never take down the engine.

## 7. Scalability and reliability

| # | Finding | Proposal |
|---|---|---|
| R1 | **No isolation between slow reads and the engine** (proven above). | Fix the slow queries (section 11). Then make read-only list queries use a **separate, small connection pool** (and later a read replica), so a slow screen can only slow itself. |
| R2 | Each instance holds up to 20 connections. PostgreSQL allows 100: above 4 instances the database refuses connections. | PgBouncer (transaction mode) in front of PostgreSQL. All Phase 1 code is pooler-safe (leases use a table, not session locks). Hosting task. |
| R3 | Relay and timer throughput with a real broker is not measured. Timer: about 7 ms per job, so ~140 jobs/s on one leader. | Measure the relay with Kafka running. 140 jobs/s is ~12M a day: enough. |
| R4 | Database failover: in-flight transactions fail and give 503 + `Retry-After`; Hikari reconnects; the idempotency key makes the client's retry safe. Timers and relay retry next run. | Correct by design, **not yet tested**: add a test that stops PostgreSQL mid-load. |
| R5 | Deploy during a run: graceful shutdown waits up to 25s for scheduled batches, then releases leases. Each job move is one transaction, so a cut run leaves nothing half-done. | Correct by design, **not yet tested**: kill test. |

## 8. Database

**Good:** status `CHECK`, append-only trigger on `job_events`, one active creator per job (partial unique index),
unique `(job_id, job_version)` in the log, `creator_fee_paise >= 0`, `UNIQUE(job_id, attempt)` for shipments,
optimistic locking on every aggregate, all times `timestamptz` in UTC.

| # | Finding | Proposal |
|---|---|---|
| D1 | Nothing stops a deadline column being set in the wrong state (only the app enforces it). | Leave as is: a `CHECK` across 11 columns and 20 states would be brittle. The engine is the only writer (ArchUnit) and `JobDeadlines.apply` clears every column on every move. |
| D2 | Migrations have no rollback (Flyway Community has no undo). | Policy: **forward-fix only, expand-then-contract** (add column, deploy, backfill, then drop in a later release), so the previous app version always works with the new schema. To be written into `docs/architecture-layers.md` (plan item 11). |
| D3 | `job_events` and `jobs` grow forever by design. At 1M jobs: about 1.5 GB. | Fine for years. Monthly partitioning of `job_events` when it passes about 50M rows. |
| D4 | Isolation level is READ COMMITTED everywhere, which is correct with optimistic locking. The race test proves one winner. | Keep. |
| D5 | Backups and point-in-time recovery. | Hosting (managed PostgreSQL). |

## 9. Failure scenarios (philosophy, section 5)

| Scenario | What happens today | Verified? |
|---|---|---|
| 1,000 users at once | Engine: fine at 1,000 rps. **The job list collapses the service at 50 rps.** | Measured |
| Database briefly down | 503 + `Retry-After`; timers and relay retry next run; no partial writes. | Not tested |
| Same request several times | With `Idempotency-Key`: exactly once (20 parallel retries tested). Without: state checks stop most duplicates, but not `OPS_EXTEND`. | Tested |
| Payment webhook twice | `PAYMENT_CONFIRMED` is legal once; the second gets 409. Finance-service must treat 409 "already PAID" as success. | Engine tested; webhook not built |
| Worker crashes mid-batch | One transaction per job; notices are idempotent (primary key); outbox is at-least-once. | Not tested by a kill |
| Two users change one job | Optimistic lock: one wins, the other gets 409 `concurrent-update`. | Tested |
| Timeout after commit | Client retries with the same key and gets the stored answer. | Tested |
| Kafka down | Outbox stores events, relay backs off, nothing lost, users unaffected. | Partly (missing topic, not a dead broker) |
| Deploy while timers run | Graceful shutdown + leases. | Not tested |
| Attacker | Wrong role, brand or creator: refused (tested). No real auth yet (S1). Another brand's job confirms it exists (S5). | Partly |
| Migration fails | PostgreSQL rolls back the failed migration; the new version does not start; old instances keep running if the migration was expand-only. | Policy only |
| Restart with timers running | Deadlines live in the database; the next run acts on whatever is due. | By design; not tested with a real restart |

## 10. Test coverage

**Covered (110 tests):** every legal path from order to close; every illegal action from every state; roles,
brand and creator ownership; races (auto-approve vs click); free revision; extensions; F6 paths; idempotency
(including 20 parallel); stale version; relay order, backoff and per-key blocking; leases; retention; timers,
notices, backoff; layer rules; append-only log.

**Missing:**
1. A load test in CI with an agreed threshold (today it is a manual script).
2. Database down mid-request, and recovery.
3. Kafka broker fully down (only a missing topic is tested).
4. Killing the app mid-batch, then restart.
5. That the `prod` profile never activates the dev header login.
6. A brand member trying every brand-admin-only action (only APPROVE is tested).
7. `JobDeadlineNotice` and `TimerFired` in `job_events` (after G1).
8. Dispute review clock (after G2).

## 11. Improvement plan (awaiting approval)

Ranked by damage. Each item names the problem it solves.

| # | Change | Solves | Size |
|---|---|---|---|
| 1 | **Job list without exact totals:** return a total capped at 10,000 ("10,000+"), computed with `LIMIT 10001`, and add `hasMore`. Add keyset paging (`?after=<cursor>`) for deep pages; keep `page` for the first pages. | Count is 282 ms to 1.1 s on every call (P1) | M |
| 2 | **Overdue index:** expression index on the earliest real deadline, partial to live jobs only (about 25% of rows). | Overdue count does a full scan (P3) | S |
| 3 | **Separate read pool** (5 connections, 2 s statement timeout) for `JobQueryService`. | A slow screen took the engine down (R1) | M |
| 4 | **Reminder query bounded:** "before" reminders only while the deadline is still ahead, at-deadline alerts only within 7 days of being due. Re-measure. | 659 ms per rule per run, growing (P4) | S |
| 5 | **Timer and notice log in `job_events`** (`TimerFired`). | F12 gap (G1) | S |
| 6 | **Dispute review clock**, 2 working days, ops alert. | F9 gap (G2) | S |
| 7 | **404 instead of 403** for jobs outside the caller's scope; guard for `OPS_EXTEND` without a deadline. | S5, G3 | S |
| 8 | **`Idempotency-Key` required for `OPS_EXTEND`** now, and for every transition under `prod` behind a switch. | S4 | S |
| 9 | **Failure tests:** database stop and restart, broker down, kill mid-batch, `prod` profile never uses dev login, brand-member matrix. | Section 10, items 2-6 | M |
| 10 | **Load test as a repeatable gate:** script plus thresholds (p95 under 300 ms at 500 rps, 0% errors) and re-measure after 1-4. | Section 10, item 1 | S |
| 11 | **Docs:** expand-then-contract migration policy; known limits. | D2, step 15 | S |

**Decisions needed from you or the founder:**
1. S3: second-ops approval (or a senior-ops role) for refunds and cancellations?
2. Item 1: is a capped total ("10,000+ jobs") acceptable on the ops screen? Exact totals for big lists cost a full scan each time.
3. Item 8: may the `prod` profile require `Idempotency-Key` once the apps send it?

**Will stay "not verified" after this plan:** behaviour on production hosting (load at scale, failover, PgBouncer,
alerts) and real authentication (F1).
