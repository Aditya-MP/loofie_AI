# F10 Ops console: plan

Status: **approved 2026-11-20 as recommended (all five decisions); built and tested, results in section 8.**

## 1. Business purpose

Foundation v2, Feature 10: "this is the concierge. Without it the team runs the business from WhatsApp and
spreadsheets and loses the data." The product brief makes it a Phase 1 must-have: ops sees every job and its
history, works its queues, holds or releases payouts with the right permissions, and **logs time per job**, because
"staff time is the biggest cost and isn't in either column. Log ops minutes per campaign in the events log from
campaign 1. If one campaign takes 3 hours, the margin has to cover 3 hours of salary."

Backend B builds the console's APIs; the screens are the web app's.

## 2. What already exists

| Brief's list (product brief, ops console) | Status |
|---|---|
| All jobs by state, overdue timers highlighted, full history of a job | Built (F5: `GET /api/core/ops/jobs` with status / brand / order / overdue filters, keyset paging; job detail with events, assignments, shipments) |
| Creator verification queue, evidence review, fast-track (level) | Built (F1 round C: review queue, evidence queue, audited links, OPS_CHECKED numbers, level with reason) |
| Invitations and declines | Built (job list by state; F9 replacement offers) |
| Disputes, replacements | Built (F9) |
| Refund queue with approvals | Built (refunds) |
| Event history, audit log | Built (job events, audit log for FOUNDER / OPS_LEAD) |
| Shortlist builder | Backend A (F3: matching is a judgement, Backend A's) |

## 3. Gaps

| # | Gap | Effect |
|---|---|---|
| G1 | **No time tracking.** Nothing records ops minutes | The margin question ("does a job cover its ops hours?") cannot be answered; the brief wants it from campaign 1 |
| G2 | **No payout hold.** `APPROVED → PAYOUT_QUEUED` has no way to stop a payout (a complaint after approval, a suspicious account) | When F8 pays creators, ops has no brake |
| G3 | **No creator search for ops.** F9 needs a creator id to offer, but ops can only open review queues or a known id | Ops copies ids from elsewhere; swaps are slow |
| G4 | **No overview.** The queues live on separate endpoints; nothing shows "what needs a person now" or how ops is performing | Ops starts each day by opening ten screens; no performance numbers |

## 4. Plan

| # | Change | Where | Size |
|---|---|---|---|
| T1 | **Time log**, append-only table `ops_time_entries` (V22): job or order (campaign), minutes 1-480, activity (SHORTLIST, VERIFICATION, COORDINATION, DELIVERY, DISPUTE, REPLACEMENT, REFUND, OTHER), optional note ≤ 200, who, when. Never edited or deleted: a mistake is fixed by a **correction entry** (negative minutes, pointing at the entry, by the same person or an ops lead; never below zero in total). `POST /api/core/ops/time` and `GET` per job / per order (entries + total) | new `ops` app service | M |
| T2 | Ops minutes on the job detail (total) and per order; a CSV-ready export `GET /api/core/ops/time/report?from&to` (per job: brand, state, minutes, creator fee) for the margin review | ops app | S |
| P1 | **Payout hold** on an `APPROVED` job (decision 2): any ops places it with a reason; the engine refuses `PAYOUT_QUEUED` while held; release by an **OPS_LEAD or FOUNDER who is not the person who placed it**, with a reason; both audited; the creator is not told the reason. `GET /api/core/ops/payouts?held=true` lists the payout queue (APPROVED / PAYOUT_PENDING jobs with hold state) | job domain + engine guard, ops controller | M |
| C1 | **Ops creator search** `GET /api/core/ops/creators?category&city&state&language&maxRate&level&bookable&q` (q = name or handle prefix), keyset paged, using the F1 round C filter indexes. A directory, not a ranking: ranking stays Backend A's | creator app + repository | M |
| O1 | **Overview** `GET /api/core/ops/overview`: jobs per state; overdue per clock; queue sizes (profile reviews, evidence, disputes and overdue disputes, jobs waiting for a creator, open swap offers, refund requests, payout holds, parked outbox); last 30 days: disputes decided within 2 working days (%), median days to a replacement, ops minutes per closed job. Computed at most every 30 s and served from memory with its `asOf` time, so the console never scans 1M jobs per click | ops app service | M |
| D1 | Docs: F10 report, ops runbook "the daily console", contract notes | docs | S |

## 5. Proof

- Time log: entries by job and by order, totals, correction rules (only own or lead, never below zero, no edit or
  delete route exists, a database trigger refuses UPDATE / DELETE), brand / creator refused, minutes bounds.
- Payout hold: engine refuses `PAYOUT_QUEUED` while held; release refused for the placer and for plain OPS;
  released → `PAYOUT_QUEUED` works; audit rows; the queue lists held jobs; hold on a job in another state refused.
- Creator search: each filter, bookable only, paging stable under inserts, no contact details for ops lists beyond
  what the review queue shows today.
- Overview: numbers match a hand-built fixture; cache respects 30 s; ops only.
- Query plans on 1M jobs / 100k creators for search and overview; load: search and overview p95 ≤ 50 ms, time and
  hold writes ≤ 300 ms (laptop numbers flagged as such; the F9 stalls make a real-server re-run necessary).

## 6. Decisions needed

1. **Time targets**: job or order (campaign), as the brief says (recommended) — or also per creator (verification
   time).
2. **Payout hold states**: `APPROVED` only, before the payout is queued (recommended: once F8 hands money to the
   bank, a hold cannot stop it) — or also `PAYOUT_PENDING`.
3. **Who releases a hold**: an OPS_LEAD or FOUNDER other than the person who placed it (recommended, the
   maker-checker the research suggested for open ask 29) — or any ops.
4. **Ops creator search in core**: yes, as a plain directory with filters (recommended; ranking stays Backend A's)
   — or wait for Backend A's shortlist builder.
5. **Overview freshness**: up to 30 s old (recommended) — or live on every request (slower, a full count each time).

## 7. Will stay open

The shortlist builder (Backend A, F3). The payout queue's money side (F8, waiting for the tax model). The console
screens (web app).

## 8. Results (2026-11-20)

Built as planned (T1, T2, P1, C1, O1, D1). Migration `V22__f10_ops_time_and_payout_holds.sql`.

| # | What | Where |
|---|---|---|
| T1-T2 | Time log: `ops_time_entries`, append-only (database triggers refuse UPDATE, DELETE, TRUNCATE); job or order; 1-480 minutes; 8 activities; a correction is the entry's exact negative, once, by its author or an ops lead / founder. Sheets per job and per order; margin report for leads and founders | `OpsTimeService`, `OpsTimeRepository`, `OpsConsoleController` |
| P1 | Payout hold on `APPROVED` jobs; **the engine refuses `PAYOUT_QUEUED` while held**; release only by an OPS_LEAD / FOUNDER who did not place it (also a database check); both audited; payout queue with held filter, keyset paging | `PayoutHoldService`, `PayoutHoldRepository`, `JobTransitionService` |
| C1 | Ops creator search: city, state, category, language, max rate, level, name / handle prefix, bookable (default) or all; cheapest first; no phone or email | `OpsCreatorSearch`, `CreatorProfileRepository.search`, `GET /api/core/ops/creators` |
| O1 | Overview: jobs per state, overdue per clock, nine queue sizes, last-30-days dispute on-time %, replacements and median days, ops minutes per closed job; refreshed every 30 s in the background, served from memory | `OpsOverviewService`, `OpsJobStats`, `OpsStatsRepository` |
| D1 | Runbook `docs/ops/daily-console.md` | docs |

### Proof

| Check | Result |
|---|---|
| `OpsConsoleTest` (5) | time: job fills its order, order-level entry, totals, every bound, brand refused, correction rules (author / lead / not twice / not a correction), database refuses UPDATE and DELETE; report: plain ops refused, bad range refused, fee and minutes per job. Holds: not on IN_REVIEW, reason, one open, engine refuses the payout, plain ops and the placer cannot release, another lead can, payout then queues, audit rows in order, brand 403. Search: bookable only, cheapest first, all, max rate, level, category + state + language + `@handle` prefix, paging, no phone, brand 403, 2-character minimum. Overview: snapshot kept 30 s, then disputes, overdue disputes, holds and waiting jobs each +1; a dispute opened and decided through the engine counts in the 30-day numbers; creator 403 |
| Full suite | 371 run, 0 failures, 4 skipped (2 Backend A contract, 2 real-store); ArchUnit passes |
| End-to-end check | 64/64, exit 0 (a first attempt timed out: the app took over 60 s to start on the laptop, 34 s on the rerun) |

### Query plans (1M jobs, 2M events, 100k creators, 200k time entries; `scripts/perf/ops-seed.sql`)

| Query | Before | After |
|---|---|---|
| Search by city + category + max rate | 37 ms (bitmap over the F3 index, then sort) | **0.35 ms**: new `creator_profiles_ops_city_idx` (lower(city), rate, user_id), read in order, stops at 25 |
| Search by handle prefix | 164 ms (full scan) | **0.9 ms**: `text_pattern_ops` indexes on lower(handle) and lower(name) |
| Search as the app runs it (reused generic plan) | 26 ms: the whole city read and sorted | **1 ms**: `SET LOCAL plan_cache_mode = force_custom_plan` for this query only |
| Overdue per clock | 455 ms (one scan of active jobs) | **1-3 ms each**: index-only counts on the existing due-date partial indexes (a due column is set only in its state) |
| Jobs per state | 478 ms (scan of 1M jobs) | unchanged: runs only in the 30 s background refresh, never per click |
| 30-day disputes / swaps / closings | | < 0.1 ms on the new `job_events_ops_perf_idx` |
| Payout queue, time sheet | | < 0.3 ms |

### Load (laptop, 100 rps, 3,000 per phase after 1,000 warm-up per phase, `scripts/perf/OpsLoadTest.java`)

| Run | Search (read ≤ 50) | Overview (≤ 50) | Payouts (≤ 50) | Time log (write ≤ 300) |
|---|---|---|---|---|
| 1 (before the search fixes) | p95 1,439 ms FAIL | 10 ms | 7 ms | 26 ms |
| 2 | 436 ms FAIL | 5 ms | 17 ms | 15 ms |
| 3 (custom plan) | 213 ms FAIL | 32 ms | 6 ms | 8 ms |
| 4 (search also run last) | **22 ms PASS** (19 ms when last) | **1 ms** | **2 ms** | **4 ms** |

All four gates pass in run 4. Runs 1-3 show how much the laptop's tails move; the search fixes are measured above in
the plans, not only in these runs. **Re-measure on the real server** (hosting ask 8), as for F7 and F9.

### Still open

- Load gates on real hosting.
- Jobs-per-state is a full count every 30 s per instance (about 0.5 s of database time on 1M jobs); if that matters
  at scale, keep a counter table instead.
- Ops time by creator (verification work) was decision 1's alternative; not built.
- The payout queue's money side is F8.
