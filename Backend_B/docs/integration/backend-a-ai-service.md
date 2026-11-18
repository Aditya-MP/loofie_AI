# Backend A (loofie-ai) integration notes

Source: `origin/backend-a`, commit `22a7a33` ("Add Loofie AI service (backend A)"), read on 2026-11-14,
plus Aditya's written reply on 2026-11-14.

## Ownership rule (agreed with Aditya, pending founder confirmation)

> Anything that is a **record or a transaction** lives in the backend (Backend B).
> Anything that is a **judgement or a prediction** lives in the AI service (Backend A).

| Feature | Backend B (records, transactions, screens' APIs) | Backend A (AI) |
|---|---|---|
| F1 Accounts / Passport | Brand + creator signup and login (phone OTP, work email, GSTIN), roles, sessions and tokens, passport data | Instagram Login (OAuth, followers/posts/views fetch, weekly refresh, encrypted Instagram tokens), profile, quality score, niche |
| F2 Brief Builder | Storing the brief (it becomes the contract), the form, the "brand confirmed" step | `POST /ai/brief/parse`; later: pre-payment warnings |
| F3 Shortlist | Storing the shortlist and each pick / maybe / reject | `POST /ai/match` ranked list |
| F11 Rebook | Checkout flow, pre-filled order | Later: when to nudge and which creator |
| F12 Events | `job_events` = source of truth for operations | `events` = what the models learn from |

Backend B stores Backend A's `suggestion_id` with each shortlist and the parse `event_id` with each
parsed brief, so outcomes link back to what the AI suggested.

## Events Backend B sends to `POST /ai/feedback`

| event_type | When | Payload |
|---|---|---|
| `job_state_changed` | Every job status change | `job_id`, `creator_ref`, `brief_ref`, `from`, `to`, `deadline_at` (when a timer is running) |
| `job_outcome` | Job closes | `on_time`, `revisions`, `disputed`, `replaced`, `rating` |
| `job_rating` | Brand rates the creator (within 7 days of close; never blocks closing) | TBD |
| Shortlist picks / rejects | Brand marks a shortlisted creator pick / maybe / reject | TBD (include the Backend A `suggestion_id`) |
| Brief edits | Brand edits an AI-parsed brief field before confirming | TBD (include the parse `event_id`) |
| `video_performance` | Later: brand tags the video winner / average / loser ~30 days after approval | TBD |

**Never send:** payout amounts, bank details, PAN, or free-text ops reasons (they can contain personal data).

## `/ai/feedback` contract changes Aditya agreed to (not deployed yet)

- **Idempotency:** Backend B sends its own `event_id` (UUID). Same id again → original returned, nothing saved.
  Same id with a different payload → `409`. Backend B uses the `job_events.event_id`, so retries are safe.
- **Time:** Backend B sends `occurred_at` (ISO 8601 with timezone). More than 5 minutes in the future → rejected.
- **Creator id:** `creator_ref` (Backend B's creator id) on every event; `creator_handle` still accepted for old payloads.
- **Auth:** named API keys; Backend B gets its own key, sent privately. Rotation has an overlap window.

Aditya will update `docs/API.md` with exact shapes when deployed. Build the forwarder against those shapes.

## Upcoming from Backend A

- **Instagram Login:** Aditya reports it is done (2026-11-14). Backend B passes its creator id as `account_ref`; the
  frontend adds a button, a return page and a status poll. Not on `origin/backend-a` yet as of 2026-11-14.
- **F7 draft checker:** after Backend B's upload, format and watermark checks, Backend A needs a signed URL to the
  draft video. Details when Aditya starts it.

## What this changes in Backend B

1. `job_events` stays in core_db as the operational source of truth.
2. The `core.events.v1` Kafka forward is dropped (and since 2026-11-15 Kafka itself). The outbox sends
   `job_state_changed` and `job_timer_fired` to `/ai/feedback` (built; `job_outcome` with F7/F8), so a Backend A
   outage never blocks a transition and calls are retried. See "How Backend B sends events" below.
3. Backend B now also owns accounts/auth (F1, non-AI part; Shreya guides the design), briefs (F2), shortlists (F3) and rebook checkout (F11).
   Jobs need a `brief_ref`, and `job_outcome` needs revision count, dispute/replacement flags and the brand's rating.

## How Backend B sends events (since 2026-11-15: HTTP, no Kafka)

Kafka is removed: nothing read it. Every job event is saved in the outbox in the same transaction as the change,
then sent to `POST /ai/feedback` with Core's `X-API-Key` (env `AI_FEEDBACK_API_KEY`, URL `AI_FEEDBACK_URL`).

| Core event | `event_type` | Payload |
|---|---|---|
| Job state change with a creator | `job_state_changed` | `creator_ref`, `job_id`, `from`, `to`, `action`; when set: `reasonCode` + `faultParty`, `issue_type`, `shipmentAttempt`, `deliverySource`, `deliveredAt`, `olderShipmentReceived` |
| Timer notice (`TimerFired`) | `job_timer_fired` | `creator_ref`, `job_id`, `kind`, `audience`, `due_at` |
| Either, with no creator (order submitted, payment confirmed) | not sent | Backend A requires a creator |

- **At-least-once, safe retries.** `event_id` = `job_events.event_id`. A retry gets `duplicate: true`; a `409` is
  counted as "Backend A has it". `X-Request-ID` = the same event id, so both logs line up.
- **Order per job.** A job's events go one after another (the next only after the previous got 2xx). Different jobs
  in parallel, **at most 32 requests open at once** (config `loofie.outbox.relay.max-in-flight`).
- **Backend A down / 5xx / 429 / 401:** retried with backoff 1 s → 60 s; nothing lost. Down = connection refused.
- **400 / 404 / 422:** the row is parked (not retried, alert) and the job's later events continue. After a fix on
  either side it is requeued with one SQL statement.
- **Never sent:** the ops note, the acting user's id, order and assignment ids, money, contact details.
- **`occurred_at`** is UTC with `Z`.
- **Checked against your mock** (`app/mock.py`, branch `366bd24`): every event type above accepted, every resend a
  duplicate, a wrong key refused with 401. Re-run any time: `AI_CONTRACT_URL=... AI_CONTRACT_KEY=... ./mvnw test
  -Dtest=BackendAContractTest`.

**Questions for Aditya:** how many `/ai/feedback` requests per second the server takes (Core sends up to 32 at once, ~1,100 events/s if each takes 20 ms; lower `max-in-flight` if that is too many);
the production URL; Core's key (privately). `job_outcome` comes with F7/F8 (it needs revisions and rating).

## Local development

Backend A runs on 5433 (the PostgreSQL 14 instance on this machine) and 8100. Backend B uses PostgreSQL 18 on 5432.
Leave PostgreSQL 14 running if Aditya's service is run locally.

## Changes on 2026-11-15 (F5 hardening)

- **Timers fired are logged.** Each reminder or alert is now a `TimerFired` row in `job_events` (same id as its
  `JobDeadlineNotice` outbox event, `job_version` empty because it is not a state change). Forward these to
  `/ai/feedback` too: they show, for example, which brands review only after the 24h reminder.
- **New alert:** `DISPUTE_REVIEW_OVERDUE` (to ops) when a dispute is not reviewed within 2 working days.
- **Ops job list response** (for whoever builds the ops console): `total` is capped at 10,000 with
  `totalCapped: true` above that; use `nextCursor` → `?after=` for further pages (page numbers stop at row 10,000).
- **Errors:** a job outside the caller's brand or assignment now returns 404 (as if it did not exist).
  `OPS_EXTEND` needs an `Idempotency-Key` (428 without one).

## Changes on 2026-11-15 (F6 hardening)

- `CONFIRM_RECEIVED` `photoRef` must be a storage key `receipts/<uuid>.<jpg|jpeg|png|heic|webp>` (422 otherwise).
  F7 upload must issue keys in this format.
- `COURIER_DELIVERED` from ops needs `deliverySource` (`TRACKING_PAGE` or `COURIER_CALL`) and may send
  `deliveredAt` (the courier's time; default now). A tracking integration calls it as SYSTEM (`TRACKING_API`).
- `CONFIRM_RECEIVED` may send `shipmentAttempt` (which parcel arrived). Event payloads now carry
  `shipmentAttempt`, plus `deliverySource`/`deliveredAt` on courier delivery and `olderShipmentReceived: true`
  when the first parcel turned up after a reship (ops should stop or recall the reship).

## Changes on 2026-11-16 (F6 round 2: your DECISIONS.md section 5 answers)

- **`DEFECTIVE_PRODUCT`** (BRAND) is a reason code now; ops picks it and `faultParty` always follows from the code.
- **Damage found after "Received"**: a new `job_state_changed` **`IN_PRODUCTION` → `DELIVERY_ISSUE`** with
  `issue_type: DAMAGED` and **`afterReceipt: true`** (creator once within 24 h with evidence, or ops). The production
  clock is paused. It ends one of three ways: `RESHIP` (the 120 h restart on the new receipt), the new action
  **`OPS_RESUME_PRODUCTION`** (`DELIVERY_ISSUE` → `IN_PRODUCTION`, "usable", the time left resumes), or
  `OPS_RELEASE_CREATOR` with a code. A creator whose damage claim is resolved as `FALSE_CLAIM` resumes with the time
  that was left, not a fresh 120 h.
- **Reships:** the brand reships once; further reships are by ops (`actor_type: ops` on that `RESHIP`).
- All of it checked against your mock at `e682442`: 15 events (incl. the above), every one accepted, every resend
  a duplicate.

## Changes on 2026-11-16 (F5 round 2: refunds and cancellations)

- Cancellations and refunds now run only after approval, but they reach you exactly as before: a
  `job_state_changed` to `CANCELLED` / `REFUNDED`. `actor_type` is `ops` (an `OPS_LEAD` or the founder approved it) or
  `system` (a replacement window ran out). Refund amounts are never sent.
- `CANCEL` can now come from later stages too (`IN_PRODUCTION`, `IN_REVIEW`...: the creator gets a kill fee), and
  `RESOLVE_REFUND` from `REPLACEMENT_NEEDED`.

## Changes on 2026-11-16 (events round 2: what your track record reads)

- **`job_outcome` is sent now** (it was not before): when a job closes (`on_time` = first draft before the
  production deadline as it stood, extension included; `revisions`; `disputed`; `replaced: false`) and when a
  creator's part ends early (`on_time: false`, `replaced: true`, plus `reasonCode` / `faultParty` of the cause).
  Cancellations send none. One per creator per job.
- **Ghosting carries a fault:** `PRODUCTION_EXPIRED` → `reasonCode: CREATOR_UNRESPONSIVE` (CREATOR).
- **Dispute decisions carry a fault:** `RESOLVE_REPLACE` and a dispute `RESOLVE_REFUND` always have a code.
- **Corrections:** a same-state `job_state_changed` (`from` = `to`) with the corrected `reasonCode` / `faultParty`
  and `corrects: <event_id of the decision it replaces>`; `creator_ref` is the creator who was blamed, even if they
  have left the job. Your "latest code per job wins" handles it as is. **Please confirm** that is how you want it.
- Checked with your mock's own scoring (`track_record.build`) at `e682442`: on time → 1 job / 1 on time / 0 faults;
  ghosted → 1 fault; replaced for brand late shipping → nothing; `FALSE_CLAIM` → 1 fault, corrected → cleared.
- Still not sent: `rating`, `rebooked` (no rating or rebook feature yet), `brief_ref` (no briefs yet).

## F7 additions (2026-11-18)

- **`rating` is now sent** (your contract example 21): `actor_type: brand`, payload `{creator_ref, job_id, stars}`,
  once per job, when the brand rates within 7 days of approval. The brand's comment stays in core (free text never
  leaves). Still not sent: `rebooked`, `brief_ref`.
- **Draft events need nothing new from you:** a delivered video is the usual `job_state_changed` pair
  `IN_PRODUCTION → DRAFT_SUBMITTED` (actor creator) and `DRAFT_SUBMITTED → IN_REVIEW` (system), now sent only after the
  video passed its checks and has its preview. Failed uploads send nothing.
- **Ask (D1, part of the F4 handover):** when a job is created, send us a copy of what the video is checked against:
  the checklist items (`key`, `label`: hook, each must-say point, CTA / code, disclosure), `min_seconds`,
  `max_seconds`, and `ad_rights_days`. Until then ops enters it (`PUT /api/core/ops/jobs/{id}/brief`) or the defaults
  apply (5 standard items, 15-90 s, 90 days).
