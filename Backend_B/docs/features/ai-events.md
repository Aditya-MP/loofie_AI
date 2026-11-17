# Events to Backend A: round 2 ("completely done" = brand motive + backend)

Status: **steps 1–11 done (research and plan), 2026-11-16. Waiting for approval before building.**
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
| Rating | `rating` | nothing (no rating feature yet) |
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
| `rating` (stars, `job_id`) | A rating feature (F7/F8 review screens) |
| `rebooked` | F11 rebook |
| `brief_ref` on every job event, brief and shortlist events | F2 briefs, F3 shortlists |
| Backend A's real URL, key, capacity | Aditya |

## 5. Decisions needed

1. **Ghosting = `CREATOR_UNRESPONSIVE`** (recommended), not a new code?
2. **Cancellations send no `job_outcome`** (recommended: the creator did not fail; a kill-fee job is not her record)?
3. **Aditya to confirm** a correction arrives as a same-state `job_state_changed` with `corrects` (his code already
   takes the latest code per job, so it works today).
