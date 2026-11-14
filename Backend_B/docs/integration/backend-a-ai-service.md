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

- **Instagram Login:** Backend B passes its creator id as `account_ref`; the frontend adds a button, a return page
  and a status poll. Not on `origin/backend-a` yet as of 2026-11-14.
- **F7 draft checker:** after Backend B's upload, format and watermark checks, Backend A needs a signed URL to the
  draft video. Details when Aditya starts it.

## What this changes in Backend B

1. `job_events` stays in core_db as the operational source of truth.
2. The `core.events.v1` Kafka forward is dropped. An outbox-driven HTTP forwarder sends `job_state_changed` and
   `job_outcome` to `/ai/feedback`, so a Backend A outage never blocks a transition and calls are retried.
3. Backend B now also owns accounts/auth (F1, non-AI part), briefs (F2), shortlists (F3) and rebook checkout (F11).
   Jobs need a `brief_ref`, and `job_outcome` needs revision count, dispute/replacement flags and the brand's rating.

## Local development

Backend A runs on 5433 (the PostgreSQL 14 instance on this machine) and 8100. Backend B uses PostgreSQL 18 on 5432.
Leave PostgreSQL 14 running if Aditya's service is run locally.
