# Loofie flow map

The whole product as one story, step by step: who acts, what the backend (core-service) does,
which AI call it makes, and which event it sends to `/ai/feedback`. **Both teams build against
this page.** Change an endpoint, a job state or an event name here first, in the same commit as the
code, and regenerate `contracts/examples` (see the bottom).

Every AI call has a real request and answer in [`contracts/examples`](../contracts/examples): the
`#` column below is the file number. Rules behind the steps: [DECISIONS.md](DECISIONS.md). Field
details: [API.md](API.md).

Status: ✅ built · 🟡 built, not connected yet · ❌ not built.

## Scene 2: a creator joins (F1)

| Step | Who | Backend | AI call | # | Status |
|---|---|---|---|---|---|
| Sign up with phone OTP | Creator | creates the creator account and its `creator_ref` | none | | ❌ backend F1 |
| Taps "Continue with Instagram" | Creator | asks for a link, sends the browser to it | `GET /ai/instagram/login-url?account_ref=<creator_ref>` | 07 | ✅ AI · ❌ button |
| Allows on Instagram | Creator | none: Instagram returns the browser to the AI service, which sends it on to `APP_RETURN_URL?instagram=connected` | `/auth/instagram/callback` (public) | | ✅ AI |
| Waits for the numbers | App | polls every 2-3 s until `active` / `error` / `expired` | `GET /ai/instagram/accounts/<creator_ref>` | 08 | ✅ AI · ❌ poll |
| Types rate, city, languages | Creator | saves them, forwards to AI | `POST /ai/creators/profile` | 01-05 | ✅ AI · ❌ screen |
| Passport shown | App | shows category, quality, stats | `GET /ai/creators/<handle>` | 06 | ✅ AI |

What the backend must know:
- Numbers the backend sends are always `SELF_REPORTED`. Only Instagram Login makes them `API_VERIFIED`.
- A creator with only typed-in numbers gets quality label **`unsure`** (example 01: score 100,
  label unsure). Show it as "not verified yet: connect Instagram", not as a warning.
- A creator inactive for months is kept out of shortlists (`left_out.inactive` in example 12).

## Scene 3: the brand writes a brief (F2)

| Step | Who | Backend | AI call | # | Status |
|---|---|---|---|---|---|
| Pastes rough notes | Brand | stores the draft brief (`brief_ref`), sends the text | `POST /ai/brief/parse` | 09 | ✅ AI · ❌ backend F2 |
| Sees the filled form | Brand | shows each field with its `evidence` ("15k", "blr"); `confidence: low` fields highlighted | none | | ❌ screen |
| Corrects a field | Brand | saves the edit; `suggestion_id` = the parse `event_id` | `brief_field_edited` | 10 | ❌ backend |
| `disclosure` field | none | shown locked: it can't be edited (an edit is refused with 422) | none | | ✅ AI rule |
| Confirms | Brand | brief becomes the contract | `brief_confirmed` | 11 | ❌ backend |
| Pre-payment warnings | Brand | shows them before checkout | *planned: F2 brief checks* | | ❌ AI |

## Scene 4: the shortlist (F3)

| Step | Who | Backend | AI call | # | Status |
|---|---|---|---|---|---|
| Brief confirmed | System | asks for a ranked list; stores `suggestion_id` with the shortlist | `POST /ai/match` | 12 | ✅ AI · ❌ backend F3 |
| Ops checks, swaps names | Ops | sends what was finally shown | `shortlist_shown` (+ `ops_added_creator` / `ops_removed_creator`) | 13 | ❌ backend |
| Pick / maybe / reject | Brand | saves each mark, with the reason for a reject | `shortlist_pick` / `shortlist_maybe` / `shortlist_reject` | 14, 15 | ❌ backend |
| Ops disagrees with a label | Ops | saves the override | `quality_override` / `niche_override` | | ❌ backend |

`rerank_note` on a result says why a business rule moved that creator (competitor post, near
duplicate, the slot for a NEW creator): show it to ops, not to the brand.

## Scene 4b: the creator side (Ready / Interested / Suggested)

Rules: DECISIONS.md section 8. Creators never browse or pitch: they answer at most one tap per week
and one tap per brief, and only see briefs that fit them.

| Step | Who | Backend | AI call | # | Status |
|---|---|---|---|---|---|
| Fills her creator card once (rate, niches, languages, products she won't do, videos per week) | Creator | saves the card; rate and rules are hers | `POST /ai/creators/profile` | 01 | ✅ AI · ❌ screen |
| Monday: "Available this week?" Yes / No | Creator | saves it; Ready expires next Monday | `creator_availability` | 29 | ❌ backend |
| A brief is confirmed: who hears about it | System | match with `top_k` 30, notify those creators ("fits you: ₹5,000, due 5 Dec") | `POST /ai/match` | 12 | ✅ AI · ❌ notice |
| "I'm in" / "Pass" (one tap, reason code on pass) | Creator | saves it; at most 5 "I'm in" a day | `brief_interest` | 30, 31 | ❌ backend |
| Brand's shortlist | Brand | sends `ready_creator_refs` (yes this week + capacity + brief within her rules) and `interested_creator_refs` | `POST /ai/match` → `availability` on each result | 12 | ✅ AI · ❌ backend |
| Brand picks a Ready / Interested creator | Brand | **booked at once** (no invite step); capacity goes down by 1 | `shortlist_pick` | 14 | ❌ backend |
| Brand picks a Suggested creator | Brand | invite, 24 h to accept (the old flow) | `shortlist_pick` | 14 | ❌ backend |
| Booked creator backs out | Creator | replacement rules (DECISIONS.md section 5) | `job_state_changed` + `CREATOR_WITHDREW` | 19 | ✅ AI rules |

Pilot without the app (every scene): ops records each step in the pilot log and runs `loofie pilot
import` (OPERATIONS.md "Pilot log"), so pilot jobs teach the models like app jobs will.
Also by hand: ops sends the Monday message and the "fits you" notice on WhatsApp and marks
Ready / Interested on the shortlist by hand.

## Scenes 5-10: payment and the job (F4-F9, F13)

The backend owns these steps. Its job states are in core-service `JobStatus`: `PAID`,
`CREATOR_INVITED`, `CREATOR_ACCEPTED`, `PRODUCT_SHIPPED`, `DELIVERY_ISSUE`, `PRODUCT_RECEIVED`,
`IN_PRODUCTION`, `DRAFT_SUBMITTED`, `IN_REVIEW`, `REVISION_REQUESTED`, `APPROVED`, `DISPUTED`,
`REPLACEMENT_NEEDED`, `REFUNDED`, `PAYOUT_PENDING`, `PAID_OUT`, `CLOSED`, `CANCELLED`...

| Step | Backend | AI call | # | Status |
|---|---|---|---|---|
| **Every** job state change | `POST /api/core/jobs/{jobId}/transitions`, then the outbox sends the change | `job_state_changed` with `from`, `to`, `creator_ref`, `job_id` (+ `deadline_at`) | 16, 17 | ✅ backend F5 · ❌ sender |
| Delivery problem | `REPORT_DELIVERY_ISSUE` → `DELIVERY_ISSUE` | same, + `issue_type`: `NOT_RECEIVED` / `DAMAGED` / `WRONG_ITEM` | 18 | ✅ backend F6 · ❌ sender |
| Dispute or drop-out | `OPS_RELEASE_CREATOR`, `CREATOR_WITHDRAW`, timers... | same, + `reasonCode` and its `faultParty` (must match, see DECISIONS.md) | 19 | ✅ backend · ❌ sender |
| Video draft uploaded | F7 upload, format checks, watermark | *planned: F7 draft checker gets a signed video URL* | | ❌ both |
| Job closes | `PAID_OUT` → `CLOSED` | `job_outcome`: `on_time`, `revisions`, `disputed`, `replaced` | 20 | ✅ backend · ❌ sender |
| A reminder or alert fires | timer worker writes a `TimerFired` row | `job_timer_fired` with `kind` (`SHIP_REMINDER`, `SHIP_LATE`...) | 28 | ✅ backend · ❌ sender |
| Ad rights code saved | F13 | none | | ❌ backend |

Retries are safe: send each event with its own `event_id` (core-service uses the
`job_events.event_id`). A retry with the same content answers `duplicate: true` (example 17); the
same id with different content is a bug and answers 409 (example 24).

## Scenes 11-12: rating, results, rebook (F11, F12)

| Step | Who | Backend | AI call | # | Status |
|---|---|---|---|---|---|
| Rates the creator (within 7 days) | Brand | saves the rating | `rating` (`stars`; **not** `job_rating`, example 27) | 21 | ❌ backend |
| Tags the video result (~30 days) | Brand | saves it | `video_performance`: `winner` / `average` / `loser` | 22 | ❌ backend |
| Books the same creator again | Brand | rebook checkout | `rebooked` | 23 | ❌ backend F11 |
| Price agreed for the video | System | at payment | `deal_price` | 32 | ❌ backend |
| Creator card shows past jobs | App | shows `track_record.summary` | `GET /ai/creators/<handle>` | 33 | ✅ AI · ❌ screen |
| "Book again?" nudge | System | shows it | *planned: rebook nudge* | | ❌ AI |
| Weekly check | Aditya | none | `loofie report`: are the models improving? | | ✅ AI |

These events also feed the **track record** (DECISIONS.md section 9): the next shortlist ranks a
creator who delivered on time and got rebooked a little higher, and says why. For it to be fair the
backend must send `revisions` and `replaced` on `job_outcome`, `job_id` on `rating`, and the
**corrected** reason code when ops reverses a decision.

## Mistakes the AI service refuses (all `422` unless noted)

| Mistake | # |
|---|---|
| Same `event_id`, different content | 24 (409) |
| `reasonCode` with the wrong `faultParty`, or without one | 25 |
| `rating.stars` not a whole number 1-5, `job_outcome.revisions` negative or not a whole number | tests/test_api.py |
| Personal or payment data (`upi_id`, `phone`, `pan`, `email`...) | 26 |
| Unknown event type | 27 |
| Missing creator, `occurred_at` in the future or without a timezone, editing `disclosure`, unknown `suggestion_id` (404) | tests/test_contract.py |

## Building without the GPU: the mock

```bash
cd loofie-ai
uv sync
uv run loofie mock          # http://127.0.0.1:8100, same port as the real service
```

- **Same checks as production:** request shapes, every `/ai/feedback` rule, retries and 409s. Code
  that passes against the mock passes against the real service (tests/test_contract.py proves it
  for every example and every refusal).
- `/ai/brief/parse` runs the real brief reader on any text. `/ai/match` and creator profiles answer
  from the examples (Ananya's story).
- Instagram Login has a fake "Allow" page, so the whole browser round trip works. An `account_ref`
  starting with `fail-` tests the failure path.
- Any `X-API-Key` value works unless `AI_API_KEYS` is set in `.env`; then it checks keys like
  production. State is in memory and every answer carries `X-Loofie-Mock: true`.
- Not in the mock: real ranking, real quality scoring, Meta's callbacks (deauthorize,
  data deletion). Test those against the real service.

## Changing the contract

1. Change `app/api/schemas.py` / the rules, and this page, in the same commit.
2. `uv run python scripts/make_examples.py` (needs the test database; uses the real models).
3. `uv run pytest tests/test_contract.py`, read the diff of `contracts/examples`, and tell the
   backend which files changed.
