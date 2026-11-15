# Loofie AI service: API contract

For the backend team. The AI service runs separately with its own database. You call it over
HTTP; neither side touches the other's tables. Live, clickable docs: `http://<host>:8100/docs`.

- **Which call at which step:** [FLOW.md](FLOW.md), the whole story mapped to endpoints and events.
- **Real requests and answers:** [`contracts/examples`](../contracts/examples), one JSON file per call,
  produced by the real service and checked by tests on every change.
- **No GPU? Build against the mock:** `uv run loofie mock` (same port, same request checks, same
  `/ai/feedback` rules). Details in FLOW.md.

## Basics

- **Auth:** every `/ai/` call needs the header `X-API-Key: <your key>`. Missing or wrong → `401`.
  The backend has its own key. When we rotate it, the old and new keys both work for a while:
  switch to the new one, tell us, and we remove the old one.
- **Creators:** send your own creator id as `creator_ref` everywhere you can; we store it and return
  it unchanged. Instagram handles (lowercase, no `@`) also work, but they change.
- **Errors:** `422` = bad input (the `detail` says which field), `404` = unknown creator or suggestion.
- **Every AI result carries a `version`.** Store it with whatever you show the user.
- **The AI only suggests.** A person confirms every brief field and every shortlist pick. Tell us
  what they did through `/ai/feedback`: that is the only way the models improve.

## When to call what

| Moment in the app | Call |
|---|---|
| Creator taps "Continue with Instagram" | `GET /ai/instagram/login-url` (see Instagram Login) |
| Creator fills in rate, city, languages, or uses the manual path | `POST /ai/creators/profile` |
| Ops opens a creator | `GET /ai/creators/{handle}` |
| Brand pastes notes into "Paste your notes" | `POST /ai/brief/parse` |
| Brand edits a field the AI filled | `POST /ai/feedback` `brief_field_edited` |
| Brand submits the brief | `POST /ai/feedback` `brief_confirmed`, then `POST /ai/match` |
| Ops edits the shortlist | `POST /ai/feedback` `ops_added_creator` / `ops_removed_creator` |
| Brand sees the shortlist | `POST /ai/feedback` `shortlist_shown` |
| Brand marks pick / maybe / reject | `POST /ai/feedback` `shortlist_pick` / `_maybe` / `_reject` |
| Job ends, rating given, rebook | `POST /ai/feedback` `job_outcome` / `rating` / `rebooked` |
| Ops disagrees with a quality flag or niche | `POST /ai/feedback` `quality_override` / `niche_override` |

## `POST /ai/brief/parse`

```json
{ "text": "need 3 girls blr for sunscreen reels, 15k total, by 5th dec",
  "as_of": "2026-11-14", "brief_ref": "your-brief-id" }
```
Response: one entry per field: `value`, `confidence` (`high` / `low` / null) and `evidence`
(the words it was read from).
```json
{ "version": "brief-rules-v1", "event_id": "uuid",
  "fields": {
    "creators_needed": {"value": 3, "confidence": "high", "evidence": "3 girls"},
    "budget_total_inr": {"value": 15000, "confidence": "high", "evidence": "15k"},
    "deadline": {"value": "2026-12-05", "confidence": "high", "evidence": "5th dec"},
    "...": "category, sub_niche, budget_per_creator_inr, city, languages, format, must_say, min_followers, max_followers"
  } }
```
**UI rule:** fill the form, highlight `low` fields, leave `null` fields empty for the brand. Never
submit without the brand confirming.

**`disclosure` is always present** with `confidence: "fixed"`: the ASCI disclosure rule, added to
every brief whatever the brand writes. Show it as a locked line on the brief and put it on the
review checklist (F7). It can't be edited: a `brief_field_edited` event for `disclosure` returns `422`.

## `POST /ai/creators/profile`

```json
{ "handle": "mira.skin", "creator_ref": "your-creator-id", "source": "manual_profile",
  "followers": 12400, "following": 610, "bio": "...", "city": "Bengaluru",
  "primary_language": "Kannada", "level": "NEW",
  "posts": [{ "post_url": "https://www.instagram.com/reel/ABC/", "post_date": "2026-11-10",
              "post_type": "reel", "views": 4200, "likes": 430, "comments": 41,
              "caption": "...", "paid_partnership_label": true, "brands_tagged": ["dewdrop.skin"] }] }
```
Send the latest 10+ posts. A field left out or `null` never erases what we already have.
`level` is yours to set (`NEW` / `VERIFIED`) from job history.

Everything sent here is `SELF_REPORTED` (`source` accepts only `manual_profile`): Instagram
numbers become `API_VERIFIED` only through Instagram Login, which the AI service fetches itself. Every metric is stored with its source and date (`metric_sources` in the response).
**Numbers** (followers, following, posts, views, posting dates) are never overwritten by a less
trusted source unless the stored one is over 30 days old; the response's `update_notes` lists what
was kept. **Descriptions** (city, bio, language...) always take the latest value.

Response: the profile plus the quality check.
```json
{ "handle": "mira.skin", "creator_ref": "your-creator-id", "category": "beauty_skincare",
  "sub_niche": "skincare", "niche_confidence": 0.71, "caption_language": "English",
  "brands_mentioned": ["dewdrop.skin"], "completeness": 1.0, "missing": [],
  "metric_sources": { "followers": {"source": "API_VERIFIED", "captured_on": "2026-11-14"} },
  "update_notes": [],
  "stats": { "median_views": 4200, "views_to_followers": 0.34, "engagement_rate": 0.038, "...": "..." },
  "quality": { "label": "real", "activity": "active", "score": 100,
               "summary": "numbers look normal for this account size", "findings": [] } }
```
- `quality.label`: `real` / `unsure` / `suspicious`. **`suspicious` goes to the ops queue; never auto-reject.**
- `quality.activity`: `active` / `slow` / `inactive` / `unknown`. Not posting is not fraud.
- A creator whose followers are only self-reported is never `real`: they get `unsure` with
  "verify with an insights screenshot or Instagram connect". Show brands which numbers are verified.

## Instagram Login (F1): `Continue with Instagram`

The AI service does the whole login and fetch. The backend only needs a button and a status poll.
Only Creator / Business Instagram accounts can log in (Meta's rule). Personal accounts: show the
"switch to a free Creator account" guide, or the manual profile path.

```
Creator app                  Backend                         AI service                     Instagram
───────────                  ───────                         ──────────                     ─────────
taps "Continue with      →   GET /ai/instagram/login-url
Instagram"                       ?account_ref=<creator id>  →  signed 10-min link
                         ←   302 to that url
approves on Instagram    ─────────────────────────────────────────────────────────────→  /oauth/authorize
                         ←────────────────────────── 303 to APP_RETURN_URL  ←  /auth/instagram/callback
lands on the app page        (?instagram=connected&account_ref=..&handle=..           (fetch runs in
                              or ?instagram=failed&reason=..)                          the background)
"Fetching your numbers…" →   GET /ai/instagram/accounts/{account_ref}  every 2 s until status = active
                             then GET /ai/creators/{handle}
```

`reason` on failure: `denied` (creator cancelled), `expired_link` (link older than 10 minutes:
start again), `not_professional` (Personal account), `already_linked` (this Instagram account is
connected to a different Loofie account: send to support), `instagram_error`, `bad_request`.

`GET /ai/instagram/accounts/{account_ref}`:
```json
{ "account_ref": "be-123", "handle": "mira.skin", "status": "active", "insights_allowed": true,
  "token_expires_at": "2027-01-13T10:00:00Z", "last_synced_at": "2026-11-14T10:00:05Z", "last_error": null }
```
| `status` | Meaning | Show the creator |
|---|---|---|
| `syncing` | fetching (10–30 s) | spinner |
| `active` | done, numbers are `API_VERIFIED` | "Instagram connected ✓" |
| `error` | last fetch failed; retried automatically | nothing yet; ops sees `last_error` |
| `expired` / `revoked` | the login no longer works | "Reconnect Instagram" button (same flow) |

`insights_allowed: false`: the creator unticked views/reach on Instagram's screen; followers and
posts are verified but views aren't. Ask them to reconnect and allow it.

Other calls:
- `POST /ai/instagram/accounts/{account_ref}/sync`: re-fetch now (ops button). `409` if the login expired.
- `DELETE /ai/instagram/accounts/{account_ref}`: creator disconnected in the app (data kept).
  `?delete_data=true`: also delete everything fetched from Instagram.

The AI service also answers Meta directly at `/auth/instagram/deauthorize`,
`/auth/instagram/data-deletion` and `/auth/instagram/deletion-status` (required by Meta's App
Review). These and the callback are public URLs; everything under `/ai/` still needs the API key.

After login, the AI service re-fetches every creator **weekly** and renews their token before it
expires (daily job), so the backend never has to resend Instagram numbers.

## `POST /ai/match`

```json
{ "text": "confirmed brief text or product description", "category": "beauty_skincare",
  "sub_niche": "skincare", "city": "Bengaluru", "languages": ["Kannada"],
  "budget_per_creator_inr": 5000, "min_followers": 5000, "max_followers": 50000,
  "exclude_handles": ["already_picked"], "top_k": 10, "brief_ref": "your-brief-id" }
```
Response:
```json
{ "version": "matcher-v1", "suggestion_id": "uuid",
  "results": [{ "rank": 1, "handle": "mira.skin", "creator_ref": "your-creator-id", "score": 0.82,
                "why": "skincare creator · speaks Kannada · based in Bengaluru · est. ₹3,000–₹7,000 fits budget",
                "estimated_rate_inr": {"low": 3000, "high": 7000},
                "quality_label": "real", "activity": "active", "parts": {"similarity": 1.0, "...": 0.6} }],
  "left_out": { "flagged suspicious": 3, "different category": 41 } }
```
- Show `why` on the creator card. Label `estimated_rate_inr` as an estimate, never as a price.
- **Keep `suggestion_id`** and send it with every feedback event about this shortlist.
- Suspicious and inactive creators are never returned.
- **Ready / Interested** (DECISIONS.md section 8): send `ready_creator_refs` (said yes this week,
  have capacity, and this brief is within their rules; **you** check capacity and rules) and
  `interested_creator_refs` (tapped "I'm in" on this brief). Each result then carries
  `availability`: `ready` / `interested` (picking books at once) or `suggested` (picking sends an
  invite). They get a small boost (`ready` +0.04, `interested` +0.03): it breaks near-ties, it
  never lifts a weak fit over a clearly better one. Unknown refs are ignored.
- **Who gets the "this brief fits you" notice:** call `/ai/match` for the brief with `top_k` 30 and
  notify those creators. No separate endpoint: the same filters decide who can see a brief.

## `POST /ai/feedback`

```json
{ "event_type": "job_state_changed", "actor_type": "system", "subject_type": "job", "subject_id": "job-42",
  "event_id": "6f1c2a52-8a7e-4c55-9d43-1b2f0f3c9a11",
  "occurred_at": "2026-11-14T15:30:00+05:30",
  "payload": { "creator_ref": "your-creator-id", "job_id": "job-42", "brief_ref": "your-brief-id",
               "from": "IN_PRODUCTION", "to": "DRAFT_SUBMITTED", "deadline_at": "2026-11-16T10:00:00+05:30" } }
```
`actor_type`: `brand` / `creator` / `ops` / `system`. `subject_type`: `brief` / `creator` / `shortlist` / `job`.

- **`event_id` (send it):** your own UUID for the event. Retrying with the same `event_id` saves
  nothing new and returns `{"event_id": ..., "duplicate": true}`. Same `event_id` with different
  content → `409` (that's a bug, not a retry). Without it we generate one, and a retry is saved twice.
- **`occurred_at` (send it):** when it happened, with a timezone. Missing → the time we received it.
  More than 5 minutes in the future, or no timezone → `422`.
- **Creator:** `creator_ref` (your id) **or** `creator_handle`. Prefer `creator_ref`: handles change.
  We store both when we know the creator.
- `suggestion_id`: the id from `/ai/match` this reacts to (shortlist events).

| event_type | required payload keys (`creator` = `creator_ref` or `creator_handle`) |
|---|---|
| `brief_field_edited` | `field`, `old`, `new` |
| `brief_confirmed` | none |
| `shortlist_shown` | `creator_handles` |
| `shortlist_pick`, `shortlist_maybe` | `creator` |
| `shortlist_reject` | `creator`, `reason` |
| `ops_added_creator` | `creator` |
| `ops_removed_creator` | `creator`, `reason` |
| `quality_override` | `creator`, `label` |
| `niche_override` | `creator`, `sub_niche` |
| `job_state_changed` | `creator`, `job_id`, `from`, `to` (+ `brief_ref`, `deadline_at` when known; `issue_type` for `DELIVERY_ISSUE`; `reasonCode` + `faultParty` on disputes and drop-outs, codes from `DECISIONS.md` section 5) |
| `job_outcome` | `creator`, `job_id`, `on_time` (true/false) (+ `revisions`, `disputed`, `replaced`, `rating`) |
| `video_performance` | `creator`, `job_id`, `result`: `winner` / `average` / `loser` |
| `rating` | `creator`, `stars` |
| `rebooked` | `creator` |
| `job_timer_fired` | `creator`, `job_id`, `kind` (core-service notice name: `SHIP_REMINDER`, `SHIP_LATE`, `RECEIPT_PROMPT`, `DISPUTE_REVIEW_OVERDUE`...; new names need no AI change) (+ `audience`, `due_at`) |
| `creator_availability` | `creator`, `available` (true/false) (+ `capacity_per_week`) |
| `brief_interest` | `creator`, `answer`: `in` / `pass` (+ `pass_reason`: `BUSY`, `RATE_TOO_LOW`, `PRODUCT_NOT_FOR_ME`, `DEADLINE_TOO_SHORT`, `OTHER`) |

**Send `job_state_changed` on every job status change** (invited, accepted, declined, product
received, draft submitted, revision requested, approved, auto-approved, disputed, replaced...), and
`job_outcome` when a job closes or the creator is replaced. The reliability score and rebook timing
learn from how jobs go, not just how they end.

**Never send personal or payment data** (PAN, Aadhaar, bank account, IFSC, UPI id, phone, email,
payout amount): the models don't need it, and those keys are refused with `422`.

Unknown type, missing key or a value outside its list → `422`. Unknown `suggestion_id` → `404`.
Extra payload keys are kept. **Reject reasons matter most:** "too expensive", "wrong vibe", "too
small" each teach the matcher something different.

## `GET /health`

No key. `{"status": "ok", "device": "cuda", "versions": {...}}`. Use it for uptime checks.
