# F5 job engine: production readiness (steps 1-15)

Scope: the job state machine, its event log and outbox, the timer worker, and the transition API.
Date: 2026-11-15. Status: **plan built and measured (sections 12-15). Code-side Definition of Done met; hosting and auth items not verified (see 14).**

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
| S1 | No real authentication: production refuses every request (`DenyAllActorResolver`). | **Blocker for launch** | F1. Until then the engine's permission logic is tested, but authentication is not. **Closed (F1 round A): `SessionActorResolver`.** |
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
| R3 | Relay and timer throughput. Timer: about 7 ms per job, so ~140 jobs/s on one leader. | 140 jobs/s is ~12M a day: enough. Relay (now HTTP, Kafka removed 2026-11-15): measured in `docs/features/kafka-removal.md`. |
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
| Backend A down (was: Kafka down) | Outbox stores events, relay stops the run and backs off, nothing lost, users unaffected. A row Backend A refuses for good is parked and alerted on, and does not block the job. | Tested (connection refused, 503, 401, 422) |
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
3. ~~Kafka broker fully down~~ Kafka removed; Backend A unreachable is tested (`OutboxRelayTest`).
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
alerts) and real authentication (F1; since closed by F1 round A).

---

## 12-13. What was built (2026-11-15)

All 11 plan items. Migration `V6__dispute_clock_and_overdue_index.sql`. 129 tests pass (19 new).

| # | Built | Where |
|---|---|---|
| 1 | Totals capped at 10,000 (`totalCapped`), one extra row instead of counting for "has more", keyset paging (`?after=` / `nextCursor`); page numbers only up to row 10,000 | `JobQueryService`, `JobReadRepository`, `JobViews.Page/Cursor` |
| 2 | Partial expression index on the earliest real deadline, plus a test that fails if the query and the index drift apart | V6, `JobReadRepositoryPlanTest` |
| 3 | Read isolation: **two bulkheads** (lists 5, single-job reads 8), 1s queue wait then 503, 2s statement limit per read transaction. Engine keeps at least 7 of 20 connections. | `JobQueryService` |
| 4 | Reminder query bounded (deadline still ahead; at-deadline alerts within 7 days) | `JobDeadlineRepository` |
| 5 | Every notice also logged in `job_events` as `TimerFired` (no `job_version`, same id as the outbox event); timeline ordered by time | `JobNoticeService` |
| 6 | Dispute review clock: 2 working days, `DISPUTE_REVIEW_OVERDUE` to ops, never moves the job | `JobDeadlines`, `TimerRules` |
| 7 | **Scope checked first**: another brand or a non-assigned creator gets the same 404 as a missing job, before state, version or rules are revealed. `OPS_EXTEND` without a deadline: 422. | `JobTransitionService` |
| 8 | `Idempotency-Key` required for `OPS_EXTEND` (428 without); `loofie.api.require-idempotency-key` switches it on for every transition (off until the apps send keys) | `JobTransitionController` |
| 9 | Failure tests: row locked past `lock_timeout` (503 + Retry-After, nothing written, the same key succeeds later); receiver unreachable (run stops fast, nothing lost or marked sent, backoff; was Kafka, now Backend A); fresh worker after a restart; `prod` and no-profile never use the dev login; brand member refused every admin-only action | `DatabaseContentionTest`, `OutboxRelayTest` (was `BrokerDownTest`), `DeadlineWorkerTest`, `ProductionAuthTest`, `BrandRoleMatrixTest` |
| 10 | Load test gate (exit code: p95 <= 300 ms and 0 failed) | `scripts/perf/LoadTest.java` |
| 11 | Migration policy (expand, then contract) | `docs/architecture-layers.md` |

## 14. Validation: measured after the changes

Same data (1M jobs), same laptop.

| Query | Before | After |
|---|---|---|
| Ops list total, no filter | 282 ms | 3 ms (capped) |
| Ops list total, overdue filter | 1,101 ms | 28 ms |
| Deep page (position 50,000) | 464 ms (OFFSET) | 1.1 ms (cursor) |
| 48h reminder query | 659 ms | 126 ms (bounded; 10k jobs in the window, far above our scale) |

| Load (open loop, warm) | Rate | p95 | Errors | Gate |
|---|---|---|---|---|
| Realistic mix: detail 83%, transitions 15%, lists 2% | 500 rps | **5 ms** | 0 | PASS |
| Heavy ops mix: 25% lists | 50 rps (collapsed before: 23% errors) | 57 ms | 0 | PASS |
| Heavy ops mix: 25% lists | 200 rps | 76 ms | 0 | PASS |
| Heavy ops mix: 25% lists (~125 list calls/s) | 500 rps | lists shed with 503 above ~80/s; **transitions p95 6 ms, detail p95 3 ms, 0 errors** | 1,334 list 503s | FAIL, by design |

**What this proves:** a slow or overloaded screen can no longer take down the engine. Lists are shed instead.
Lists top out at about 80 per second per instance. Ops staff will make a few per minute, so this is not a limit
we will meet. If it ever is: a read replica for `JobQueryService`.

**Found while measuring:** the first load test right after reloading 1M rows (cold cache, cold JIT) failed even
the realistic mix, because reads were slow until the cache warmed. A deploy or a database failover also starts
cold. Mitigation, to do with hosting: a readiness delay, or warm-up traffic before the load balancer sends
users, plus managed PostgreSQL with enough memory to hold the hot indexes.

### Definition of Done (philosophy, section 8)

| Criterion | Status |
|---|---|
| Business rules (F5, F12 timer log, F9 dispute clock) | Done, tested |
| Architecture and service boundaries | Done, enforced by ArchUnit |
| Database design (constraints, indexes justified by measurement, migration policy) | Done |
| Security: authorization, scope (404), reason codes, idempotency, no internals in errors | Done, tested |
| Security: **authentication** | Done since F1 round A (`SessionActorResolver`, cookie sessions; `DenyAllActorResolver` removed). The e2e check (`scripts/e2e/FullFlowCheck.java`) runs the job flow behind real OTP login. Still open in F1: real SMS/email provider |
| Validation and error handling (400/404/409/422/428/503/500 with requestId) | Done, tested |
| Concurrency (races, 20 parallel retries, optimistic lock, leases) | Done, tested |
| Failure and recovery (DB contention, Backend A down, restart) | Done in tests. **A real database failover and a real process kill are not verified** (needs hosting). |
| Performance against targets | **Engine: met** (p95 5 ms at 500 rps, laptop). **On production hosting: not verified.** |
| Automated tests of business behaviour | 129 tests |
| Logging and monitoring | Metrics, JSON logs, request ids. **Alert rules not set up** (needs a monitoring tool). |
| Documentation | This file, `docs/architecture-layers.md`, `docs/production-readiness.md` |

## 15. Known limits and remaining risks

1. ~~No authentication (F1).~~ Closed by F1 round A: real sessions replace the deny-all resolver. Login codes still need an SMS/email provider before launch (tracked in F1).
2. **Not verified on real infrastructure**: load at scale, PgBouncer, database failover, cold-start warm-up, alerts.
3. **Ops are all-powerful** (S3): one ops user can refund or cancel. Waiting for a decision.
4. `Idempotency-Key` is optional except for `OPS_EXTEND` until the apps send it; then switch it on.
5. An at-deadline alert more than 7 days late (worker down for a week) is not sent.
6. Ops list throughput is about 80 list calls per second per instance (see 14).
7. "Creator moved back to NEW level" (F9) waits for creator profiles.
8. The F6 rules that differ from Foundation v2 are provisional until the founder signs off.

---

## Round 2 (2026-11-16): "completely done" = brand motive + backend

Research for the last F5 work, judged by two questions: *does it keep Loofie's promise to brands, creators and
ops?* and *is it proven?* Sources: DECISIONS.md sections 5–6 (backend-a `e682442`), `docs/product-decisions.md`.

### What breaks a promise today

| # | Finding | Who is hurt | Today |
|---|---|---|---|
| M1 | **Money moves without approval.** `RESOLVE_REFUND` (ops alone) and `CANCEL` (ops alone) move the job to `REFUNDED` / `CANCELLED` at once; the timer's `REPLACEMENT_EXPIRED` refunds with **nobody** approving. DECISIONS.md: money moves only after request → approve, four-eyes after acceptance. | Loofie (a mistaken or social-engineered refund: "the brand called and asked") | One click |
| M2 | **Core cannot say how much.** It stores only the creator fee per job; no Loofie fee rate, no GST, so the ₹10,000 / ₹25,000 limits and the per-stage amounts (full / minus fee / kill fee / fee as credit) cannot be computed. | Brand (wrong amount), approver (can't apply the limits) | Not stored |
| M3 | **The cancellation table is not enforced.** `CANCEL` stops at `CREATOR_ACCEPTED`; "after receipt or shoot started: no refund, creator gets a 50% kill fee" has no path. | Creator (did work, gets nothing), brand (can't cancel) | Not possible |
| M4 | **Reminders reach nobody.** "Ship by Thursday", "review closes in 24 h" are written as events, but no brand, creator or ops person ever sees them. The 2-day reminder, the late alert and the dispute-review alert are promises we don't keep. | Brand (marked late without warning), creator, ops | Stored only |
| M5 | **Video review clock**: DECISIONS.md says 5 working days (brands are offices); Foundation v2 row 12 says 120 calendar hours. A brand with a Saturday and a holiday in its window gets less time than promised under the new rule. | Brand | 120 h (founder sign-off pending) |
| M6 | No refund history or daily summary for the founder. | Loofie | None |

### Plan (approved 2026-11-16)

| # | Item | Design | Size |
|---|---|---|---|
| R1 | **Price snapshot per slot** (M2) | At order intake store `loofie_fee_bp` (default 1500 = 15%) and `gst_bp` (1800) on the order, so every slot's value is fixed when the brand pays: `slot = (creator fee + fee) × (1 + GST)`; DECISIONS.md example ₹5,000 → ₹6,785. Amounts per case computed by one pure function, tested against the DECISIONS.md examples. | S |
| R2 | **Refund requests with approvals** (M1, M6) | New `refund` feature. `refund_requests`: job, brand, case, reason code, amounts (brand cash, fee credit, creator kill fee), requested by, required approvals (worked out once at request time), status REQUESTED → APPROVED / REJECTED / WITHDRAWN. **One open request per job; while open, the job's other actions are refused.** Rules from DECISIONS.md section 6: after acceptance → an `OPS_LEAD` who is not the requester; no acceptance and ≤ ₹10,000 → one `OPS_LEAD` may do both; > ₹25,000 or > 3 refunds to the brand in 30 days → also the founder. On final approval, the job transition (`CANCEL`, `RESOLVE_REFUND`, `REPLACEMENT_EXPIRED`) runs **in the same transaction** and a `RefundApproved` event goes to the outbox for finance. An approved row cannot be changed (database trigger). The approver sees the brand's refund history. Daily summary endpoint (count, total, who requested, who approved). | L |
| R3 | **Timer no longer refunds by itself** (M1) | `REPLACEMENT_EXPIRED` creates a refund request (requested by SYSTEM, Loofie's own failure: fee included) and alerts ops; an `OPS_LEAD` approves. | S |
| R4 | **Cancellation table** (M3) | `CANCEL` as a request from every pre-approval state, with the case taken from the state: before acceptance full; after acceptance before shipping minus fee; after receipt / shoot started no refund + 50% kill fee to the creator. Shipped-not-received waits for Open 4. | M |
| R5 | **Notification inbox** (M4) | `notifications` table written **in the same transaction** as each notice (brand org, creator or ops, per the notice's audience) and each refund decision; `GET /api/core/notifications` (only your own), mark read; kept 90 days. Brands and creators then see reminders in the app; WhatsApp/email delivery later plugs in on the same rows. | M |
| R6 | **Review clock switch** (M5) | `loofie.deadlines.review-working-days` (empty = 120 calendar hours, today's rule; 5 = DECISIONS.md). Off until the founder signs off. | S |
| R7 | **Roles** `OPS_LEAD`, `FOUNDER` | New `ActorRole` values; dev login accepts them; real login is F1 (since built: staff accounts in `StaffService`). | S |
| R8 | **Proof** | Rule tests per approval case and amount example; self-approval refused; race (two approvers at once, request vs other action); approved row immutable; load test of request + approve at 50 rps (rare action) and the engine at 500 rps unchanged; contract test for the new events. | M |

### Decisions needed before R2/R4 are final

1. **Refund reason codes** (Open 5): proposal `BRAND_CANCELLED`, `LOOFIE_NO_CREATOR` (replacement expired), `DISPUTE_UPHELD`,
   `BRAND_LATE_SHIP`, `DUPLICATE_PAYMENT`, `OTHER`.
2. **GST on refunds and the fee credit** ([CONFIRM] CA): build the amounts as DECISIONS.md writes them (credit note for
   the GST part) and mark them pending CA?
3. **Fee credit** (Open 3): store it as a one-time credit against the brand's next order (recorded, never cash)?
4. **Founder = ?** One `FOUNDER` user; if Aditya is the founder, he is that user.
5. **Who may request** a refund: any `OPS`, or only `OPS_LEAD`? (DECISIONS.md says who approves, not who requests.)

### Built, tested, measured (2026-11-16)

Decisions taken as recommended (the plan was approved with them): refund reason codes `BRAND_CANCELLED`,
`LOOFIE_NO_CREATOR`, `DISPUTE_UPHELD`, `BRAND_LATE_SHIP`, `OTHER` (`DUPLICATE_PAYMENT` belongs to payments, F4, not
to a job); amounts as DECISIONS.md writes them, **pending CA**; the late-ship fee is a credit, never cash; any ops
person may ask, only an `OPS_LEAD` (or the founder standing in) approves; the founder is a role, whoever holds it.

| # | Built |
|---|---|
| R1 | `orders.loofie_fee_bp` / `gst_bp`, fixed at payment (`loofie.pricing.*`, 1500 / 1800). |
| R2 | `refund` feature: `refund_requests` (V11) with amounts, case, required approvals, status; one open request per job (unique index) and the job **held** while it waits; approval rules in `ApprovalPolicy`; last approval runs the job action in the same transaction; decided rows frozen by a trigger; brand history and the 30-day count for the approver; `/api/core/ops/jobs/{id}/refund-requests`, `/refund-requests/{id}/approve|reject|withdraw`, list, detail, `/refunds/summary?date=`; **`Idempotency-Key` required on every write**; daily 09:00 IST summary to the founder's inbox. |
| R3 | The timer opens a refund request (SYSTEM, `LOOFIE_NO_CREATOR` or `BRAND_LATE_SHIP`) instead of refunding; held jobs are skipped by every timer. |
| R4 | `CANCEL` from every stage the table covers (full / minus fee / kill fee); `RESOLVE_REFUND` from `REPLACEMENT_NEEDED`; the plain transition API refuses every money action. Shipped-not-received still waits (Open 4). |
| R5 | `notifications` inbox: every timer notice goes to the brand / creator / ops it names, in the notice's transaction; refund events to ops, founder and brand; `GET /api/core/notifications`, mark read (404 if not yours); 90-day retention. |
| R6 | `loofie.deadlines.review-working-days` (empty = 120 h today). |
| R7 | `OPS_LEAD`, `FOUNDER`: act as ops everywhere, the audit log keeps the real role. |

| Proof | Result |
|---|---|
| `RefundRulesTest` (8, pure) | DECISIONS.md example Rs 5,000 -> Rs 6,785 exactly; every case; rounding; every limit at its edge (Rs 10,000 / 10,000.01, Rs 25,000 / 25,000.01, 3rd / 4th refund); every approval path including founder ≠ lead ≠ requester |
| `RefundFlowTest` (12) | one lead asks + approves a small pre-acceptance refund; after acceptance the asker and plain ops are refused; the hold blocks other actions, withdraw / reject release it; large refund needs lead **then** founder; 4th refund to a brand in 30 days needs the founder; kill fee; late-ship cash + credit; decided rows cannot be updated or deleted; money never moves through the plain action; the timer asks instead of refunding and is not re-triggered; **4 leads approving at the same moment: exactly one cancel**; daily summary |
| `InboxRolesAndApiTest` (5) | reminder reaches the right brand only, creator and ops inboxes, mark-read scoping, paging limit; senior roles act as ops and are logged as themselves; review clock switch; refund API needs a key (428), a retried approval is replayed, never applied twice |
| Full suite + contract test vs Backend A mock | **202 of 202** |
| Load, 1M jobs, after warm-up | refund **request** 50 rps p95 5 ms, **approve** p95 6 ms, 3,000 / 3,000 each; 8 correctly left waiting for the founder (brands past 3 in 30 days); **engine 500 rps realistic mix p95 2 ms, 0 failed** |

Found while measuring: the perf data loader did not clear `refund_requests` / `notifications`, so its job delete
failed and 801 test jobs stayed in the 1M set (0.08%, numbers unaffected). Fixed in `load-data.sql`.

**Still open (written down, not built):** shipped-not-received cancellation row, the CA's word on GST for refunds
and credits, real delivery of inbox items (WhatsApp / email), recording the fee credit against the next order (F4
order intake), founder sign-off before switching the review clock to working days, ~~real login for the new roles (F1)~~ (done in F1 round A).
