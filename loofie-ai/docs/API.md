# Loofie AI service: API contract

For the backend team. The AI service runs separately with its own database. You call it over
HTTP; neither side touches the other's tables. Live, clickable docs: `http://<host>:8100/docs`.

## Basics

- **Auth:** every call except `/health` needs the header `X-API-Key: <shared secret>`. Missing or wrong → `401`.
- **Creators are identified by Instagram handle** (lowercase, no `@`). Send your own creator id as
  `creator_ref`; we store it and return it, unchanged.
- **Errors:** `422` = bad input (the `detail` says which field), `404` = unknown creator or suggestion.
- **Every AI result carries a `version`.** Store it with whatever you show the user.
- **The AI only suggests.** A person confirms every brief field and every shortlist pick. Tell us
  what they did through `/ai/feedback`: that is the only way the models improve.

## When to call what

| Moment in the app | Call |
|---|---|
| Creator signs up, reconnects Instagram, or data refreshes | `POST /ai/creators/profile` |
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
{ "handle": "mira.skin", "creator_ref": "your-creator-id", "source": "instagram_api",
  "followers": 12400, "following": 610, "bio": "...", "city": "Bengaluru",
  "primary_language": "Kannada", "level": "NEW",
  "posts": [{ "post_url": "https://www.instagram.com/reel/ABC/", "post_date": "2026-11-10",
              "post_type": "reel", "views": 4200, "likes": 430, "comments": 41,
              "caption": "...", "paid_partnership_label": true, "brands_tagged": ["dewdrop.skin"] }] }
```
Send the latest 10+ posts. A field left out or `null` never erases what we already have.
`level` is yours to set (`NEW` / `VERIFIED`) from job history.

`source` matters (Foundation v2, F1): `instagram_api` → `API_VERIFIED`, `manual_profile` →
`SELF_REPORTED`. Every metric is stored with its source and date (`metric_sources` in the response).
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

## `POST /ai/feedback`

```json
{ "event_type": "shortlist_reject", "actor_type": "brand", "actor_id": "brand-7",
  "subject_type": "shortlist", "subject_id": "your-brief-id",
  "suggestion_id": "uuid from /ai/match", "payload": { "creator_handle": "mira.skin", "reason": "too expensive" } }
```
`actor_type`: `brand` / `creator` / `ops` / `system`. `subject_type`: `brief` / `creator` / `shortlist` / `job`.

| event_type | required payload keys |
|---|---|
| `brief_field_edited` | `field`, `old`, `new` |
| `brief_confirmed` | none |
| `shortlist_shown` | `creator_handles` |
| `shortlist_pick`, `shortlist_maybe` | `creator_handle` |
| `shortlist_reject` | `creator_handle`, `reason` |
| `ops_added_creator` | `creator_handle` |
| `ops_removed_creator` | `creator_handle`, `reason` |
| `quality_override` | `creator_handle`, `label` |
| `niche_override` | `creator_handle`, `sub_niche` |
| `job_outcome` | `creator_handle`, `on_time` |
| `rating` | `creator_handle`, `stars` |
| `rebooked` | `creator_handle` |

Unknown type or missing key → `422`. Unknown `suggestion_id` → `404`. Extra payload keys are kept.
**Reject reasons matter most:** "too expensive", "wrong vibe", "too small" each teach the matcher something different.

## `GET /health`

No key. `{"status": "ok", "device": "cuda", "versions": {...}}`. Use it for uptime checks.
