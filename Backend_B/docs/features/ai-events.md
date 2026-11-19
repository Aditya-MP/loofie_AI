# Events to Backend A: round 2 ("completely done" = brand motive + backend)

Status: **built (commit `c239943`, 2026-11-17); results in section 6. `rating` added with F7 (2026-11-18).**
Still open: `rebooked`, `brief_ref`, Backend A's live connection (section 4).
Round 1 (transport: outbox → HTTP `/ai/feedback`, Kafka removed) is `docs/features/kafka-removal.md`.

## 1. Business purpose

Backend A's matcher ranks creators for brands. Its **track record** score (10% of the ranking; DECISIONS.md
section 9; `app/matcher/track_record.py` at `e682442`) is built **only** from what Core sends:

| Signal the matcher reads | From | What Core sends today |
|---|---|---|
| Delivered on time / late | `job_outcome.on_time` | **nothing** (no `job_outcome` at all) |
| Extra revisions | `job_outcome.revisions` | **nothing** |
| Replaced, not her fault | `job_outcome.replaced` | **nothing** |
| Creator's own fault (withdrew, unresponsive, false claim) | `reasonCode` on `job_state_changed` | partly (see M2) |
| Rating | `rating` | nothing (no rating feature yet). **Since F7: sent** (stars only) |
| Rebooked | `rebooked` | nothing (no rebook yet) |

If these are missing or wrong, **brands are shown unreliable creators**, and **good creators are unfairly
ranked down**. Both break Loofie's promise.

## 2. Findings

| # | Finding | Who is hurt |
|---|---|---|
| M1 | **No `job_outcome` is ever sent.** I earlier listed it as "waits for F7/F8"; that was wrong for most of it: the engine already records drafts, revisions, disputes and closing. Only the *rating* waits for a rating feature. So every creator looks brand-new to the matcher, however many jobs they finished. | Brands (no signal), good creators (no credit) |
| M2 | **A creator who ghosts carries no fault.** `PRODUCTION_EXPIRED` (deadline + extension passed, no draft) is logged with **no reason code**; the matcher only counts CREATOR codes, so ghosting is invisible. | Brands |
| M3 | **Dispute outcomes carry no fault.** `RESOLVE_REPLACE` (brand rejected twice, ops replaces) takes whatever code ops sends, and ops may send none. A replacement for the creator's poor work, or for a brand being unreasonable, looks the same. | Brands / creators |
| M4 | **A wrong code cannot be corrected.** DECISIONS.md: "the latest reason code per job wins, so when ops corrects a decision (`FALSE_CLAIM` reversed) the backend sends the corrected code". Core has no way to correct a code after the fact. A creator wrongly accused keeps the penalty forever. | Creators |
| M5 | **Ops cannot fix a parked event** without SQL (an engineer). | Ops |
| M6 | **A production typo** (http URL, missing key) silently queues events; nothing fails loudly at start. | Everyone, silently |
| M7 | `processed_events` (Kafka-era) is unused; it was kept only for a rolling deploy. Nothing is live, so it can go. | — |

## 3. Plan

| # | Item | Design | Size |
|---|---|---|---|
| A1 | **`job_outcome`** (M1) | A `JobOutcome` event in the **same transaction** as the change that ends a creator's part: **closing** (`CLOSE`) → `on_time` (first draft submitted before the production deadline as it stood then, extension included; recorded on the job at `SUBMIT_DRAFT`), `revisions` (revision requests for this creator), `disputed`, `replaced: false`; **replacement** (`PRODUCTION_EXPIRED`, `CREATOR_WITHDRAW`, `OPS_RELEASE_CREATOR`, `RESOLVE_REPLACE`) → `on_time: false`, `replaced: true` plus the reason code (the matcher then counts it only if the code is the creator's fault). Cancellations send no outcome (the creator did not fail). Mapped to `job_outcome` (`creator_ref`, `job_id`, `on_time`, `revisions`, `disputed`, `replaced`). | M |
| A2 | **Ghosting is the creator's fault** (M2) | `PRODUCTION_EXPIRED` always carries `CREATOR_UNRESPONSIVE` (CREATOR). | S |
| A3 | **Dispute decisions need a code** (M3) | `RESOLVE_REPLACE` and `RESOLVE_REFUND` require ops to pick a reason code, like `OPS_RELEASE_CREATOR`. `RESOLVE_APPROVE` (creator was right) needs none. | S |
| A4 | **Correct a reason code** (M4) | New ops action `OPS_CORRECT_REASON`: any state, state and every clock unchanged, needs the new code and a note; logged in `job_events` and sent as `job_state_changed` with the same `from`/`to`, the new `reasonCode`/`faultParty` and `corrects: <event id>`. Backend A needs no change (latest code per job already wins). Setting `FALSE_CLAIM` this way is refused (an accusation only comes from resolving a claim). | S |
| A5 | **Ops fixes parked events** (M5) | `GET /api/core/ops/outbox/parked` (what and why), `POST /api/core/ops/outbox/parked/{id}/requeue` (OPS_LEAD; logged). | S |
| A6 | **Fail loudly on a bad production setting** (M6) | In the prod profile: a URL that is not `https://` or a URL without a key stops the app at start; no URL at all is allowed (events wait) and logged. | S |
| A7 | **Drop `processed_events`** (M7) | V12. | S |
| A8 | **Proof** | Outcome correctness tests (on time / late / with an extension / revisions / disputed / replaced per cause), ghosting code, correction (state and clocks untouched, FALSE_CLAIM refused), parked requeue, prod config guard; **contract test against Backend A's mock with the new events**; plus a check that Backend A's own `track_record.build` scores our events as intended (run their function on our payloads in the mock environment). | M |

## 4. Blocked (written down, not built)

| Item | Waiting on |
|---|---|
| ~~`rating` (stars, `job_id`)~~ | **Done in F7**: the brand rates after approval; `JobRating` → `rating` in `AiFeedbackMapper`; the comment stays in core |
| `rebooked` | F11 rebook |
| `brief_ref` on every job event, brief and shortlist events | F2 briefs, F3 shortlists (Backend A's features) |
| Backend A's real URL, key, capacity | Aditya |

## 5. Decisions (approved 2026-11-16)

1. Ghosting = `CREATOR_UNRESPONSIVE`, no new code. **Built.**
2. Cancellations send no `job_outcome` (the creator did not fail). **Built.**
3. A correction arrives as a same-state `job_state_changed` with `corrects`. Works with Backend A's code today (the
   contract test proves it); **Aditya still to confirm in writing.**

## 6. Results (2026-11-17)

### Built (commit `c239943`)

| # | What | Where |
|---|---|---|
| A1 | `job_outcome` on `CLOSE` and on every replacement (`PRODUCTION_EXPIRED`, `CREATOR_WITHDRAW`, `OPS_RELEASE_CREATOR`, `RESOLVE_REPLACE`, `RESOLVE_REFUND` from `DISPUTED`); `jobs.first_draft_on_time` set once at the first `SUBMIT_DRAFT`, reset for a new creator | `JobTransitionService`, `JobEventRecorder.recordOutcome`, V12 |
| A2 | `PRODUCTION_EXPIRED` carries `CREATOR_UNRESPONSIVE` | `JobTransitionService.reasonCodeFor` |
| A3 | `RESOLVE_REPLACE` and dispute refunds need a code; a refund request settling a dispute carries `job_reason_code`, passed to the job on approval | `RefundService`, `refund_requests.job_reason_code` |
| A4 | `OPS_CORRECT_REASON`: self-loop in every state (terminal ones too), state and clocks untouched, payload `corrects` + `previousReasonCode`; same code again and `FALSE_CLAIM` refused | `TransitionTable`, `JobTransitionService` |
| A5 | `GET /api/core/ops/outbox/parked`, `POST /api/core/ops/outbox/parked/{id}/requeue` (OPS_LEAD / FOUNDER) | `OutboxAdmin`, `OpsOutboxController` |
| A6 | Prod profile: `require-https: true`; an `http://` URL or a URL without a key stops startup; no URL = events wait | `AiFeedbackProperties`, `application-prod.yaml` |
| A7 | `processed_events` dropped | V12 |
| extra | Inbox `jobId` filter + `notifications_job_idx` (ops: "what was sent about this job?") | `NotificationRepository`, V12 |

### Proof

| Check | Result |
|---|---|
| Full suite | 215 tests: 214 pass, 1 skipped (the opt-in contract test, run separately below) |
| `AiEventsRound2Test` (10) | on time / late / extension / revisions / disputed / replaced per cause; ghosting code; correction keeps state and clocks, refuses `FALSE_CLAIM` and the same code; parked requeue; dispute refund needs a code |
| Mapper tests | `job_outcome` fields, `corrects` forwarded, null keys left out, no PII; prod guard refuses http and keyless |
| Contract test against Backend A's real mock | all requests 200, new events accepted |
| **Backend A's own scoring reads our events as intended** (`GET /ai/creators/{handle}` track record on their mock) | on time: 1 job, 1 on time, 0 faults · ghosting: 1 fault · brand late ship: no fault · accused only (`FALSE_CLAIM` settled for the creator): fault as Backend A defines it · corrected to courier fault: no fault |
| Query plans, 1M jobs / 2M events | revision count and last-coded-event lookup: Index Scan `job_events_job_version_idx`, 0.02–0.06 ms · inbox by job (500k notifications): Index Scan `notifications_job_idx`, 0.054 ms |
| Engine 500 rps, 60 s, realistic mix, relay on (fake Backend A, 20 ms), after warm-up | run 1: p95 4 ms, p99 41 ms; run 2: **p95 3 ms, p99 11 ms**; 0 failed both runs; every event sent (4,493), 0 waiting, 0 parked, write-to-send p95 0.49 s. Before round 2: p95 2 ms, write-to-send 0.49 s — same within laptop noise. Gate p95 ≤ 300 ms: **PASS** |

### Known limits (not hidden)

- Perf data events carry no assignment or reason code, so the plans above prove the index is used, not counts on
  busy jobs. A job has tens of events at most, so the per-job index scan stays small.
- Laptop numbers only; **production hosting not measured.**
- Backend A's real URL, key and capacity still unknown (section 4), so delivery has only been proven against their
  mock and our fake.

### Done?

**Done** for what Backend B can deliver now: it serves the matcher (a fair track record: ghosting counts, courier
and brand faults do not, wrong calls can be corrected) and meets the backend standard (tests, contract, plans, load).
Still open, each with an owner in section 4: ~~`rating`~~ (done in F7), `rebooked`, brief events, Backend A's live connection, and
Aditya's written OK on the correction format.
