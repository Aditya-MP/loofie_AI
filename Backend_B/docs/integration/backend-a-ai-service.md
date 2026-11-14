# Backend A (loofie-ai) integration notes

Source: `origin/backend-a`, commit `22a7a33` ("Add Loofie AI service (backend A)"), read on 2026-11-14.
Files: `loofie-ai/docs/API.md`, `loofie-ai/app/feedback/events.py`, `loofie-ai/app/db/models.py`,
`loofie-ai/app/api/main.py`, `loofie-ai/app/api/schemas.py`.

## What Backend A actually is

A Python/FastAPI **AI service** on port 8100 with its own PostgreSQL (`loofie_ai`, port 5433):
brief parsing (`/ai/brief/parse`), creator profiles and quality (`/ai/creators/...`), matching
(`/ai/match`) and the learning log (`/ai/feedback`). It calls itself a service "for the backend team".

It does **not** contain accounts/login (F1), JWT issuing, brief storage, shortlist storage or
rebook checkout. The architecture doc assumed Backend A owned those. See "Open questions" below.

## How F12 works in Backend A

- Table `events`: `id` (uuid4, server-generated), `occurred_at` (server `now()`), `actor_type`,
  `actor_id` (string), `subject_type`, `subject_id` (string), `event_type`, `payload` (jsonb),
  `model_name`, `model_version`. Append-only by convention (no DB trigger).
- Written through `POST /ai/feedback` over **HTTP**, not Kafka. Auth: `X-API-Key` shared secret.
- `actor_type` is one of `brand | creator | ops | system` (lowercase).
- `subject_type` is one of `brief | creator | shortlist | job`.
- `event_type` must be in a fixed list; unknown types are rejected with `422`.
- Creators are identified by **Instagram handle**; Backend B's own creator id travels as `creator_ref`.

Event types that come from Backend B's side of the product:

| event_type | Required payload | When Backend B sends it |
|---|---|---|
| `job_outcome` | `creator_handle`, `on_time` | Job reaches CLOSED, or the creator is replaced (production expired, dispute replacement) |

`rating` and `rebooked` belong to F11 (rebook), which is not Backend B's feature.

## What this changes in Backend B

1. **Keep `job_events` in core_db.** It is the F5 lifecycle log (every transition, ops overrides,
   timers) and is required by the state machine whatever Backend A stores. Backend A's `events`
   is an AI-learning log, not a job lifecycle log, so there is no duplication.
2. **Drop the planned `core.events.v1` Kafka forward** of every job event to Backend A. Backend A
   does not consume Kafka and would reject our event types. Instead, Backend B sends only
   `job_outcome` to `POST /ai/feedback`, from an outbox-driven HTTP forwarder (Sprint 3-4), so a
   Backend A outage never blocks a transition and the call is retried.
3. **Backend B must know the creator's Instagram handle** for `job_outcome`. Plan: snapshot it on
   the assignment when the creator is invited (needs a column `creator_handle` on `assignments`).

## Gaps to raise with Aditya

| # | Gap | Why it matters | Ask |
|---|---|---|---|
| 1 | `/ai/feedback` has no idempotency key; `id` is generated server-side | Our forwarder retries on timeouts, so one outcome can be logged twice and skew training | Accept a client `event_id` and ignore duplicates |
| 2 | `occurred_at` is set to ingest time | A retried or delayed call records the wrong time | Accept `occurred_at` from the caller |
| 3 | Creator identity is the Instagram handle | Handles change; Backend B keys on creator id | Also accept/index `creator_ref`, or confirm handle is stable enough |
| 4 | Who owns F1 accounts + JWT, brief storage, shortlist storage, F11 checkout? | Backend B's auth and order intake assume an upstream that does not exist on `backend-a` | Confirm owner; this may move work to Backend B or a third service |
| 5 | Auth is one shared `X-API-Key` | Fine for v1 service-to-service | Confirm Backend B gets its own key (rotatable) |

## Local development

Backend A runs on 5433 (the PostgreSQL 14 instance on this machine) and 8100. Backend B uses
PostgreSQL 18 on 5432. Leave PostgreSQL 14 running if Aditya's service is run locally.
