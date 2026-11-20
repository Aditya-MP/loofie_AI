# F9 Disputes and replacement: plan

Status: **approved 2026-11-19 as recommended (all five decisions); built and tested 2026-11-20, results in section 8.**

## 1. Business purpose

Foundation v2, Feature 9: "a real answer exists for every failure, which is what agencies charge for and what
WhatsApp deals lack." A brand that paid always ends with a video, a replacement creator, or its money back; a creator
who did the work gets paid; a creator who did not is recorded fairly.

The spec's five rules:
1. Creator declines or does not accept within 48 h: ops offers the next creator **at the same or a lower rate**; the
   **brand approves the swap**.
2. Creator misses the production deadline: one 48 h extension, then `REPLACEMENT_NEEDED`; **the creator moves back
   to NEW level**.
3. Brand rejects after the free revision: `DISPUTED`. Ops reviews against the checklist within 2 working days.
   Outcome: creator paid, replacement, or refund.
4. No replacement within 10 days: full refund for that job.

## 2. What already exists (built in F5, F6, F7, refunds)

| Rule | Status |
|---|---|
| States and moves: `DISPUTED` → approve / replace / refund; `REPLACEMENT_NEEDED` → swap / refund; decline and 48 h accept timeout | Built, tested (F5) |
| Reject only after the free revision; a note of 10+ characters; checklist answers kept | Built (F5, F7) |
| One creator extension of 48 h, later ones by ops | Built (F5) |
| 2-working-day dispute review clock; `DISPUTE_REVIEW_OVERDUE` to ops | Built (F5) |
| 10-day replacement window, then automatic full refund | Built (F5 + refunds) |
| Refund amounts by case, ops approval rules, brand told | Built (refunds) |
| Dispute outcome needs a fault reason code; it reaches Backend A's track record | Built (AI events round 2) |
| Ops: list disputed / overdue jobs; see every video, the checklist answers and the brand's note; open a video with a reason | Built (F5 ops list, F7 ops view) |

## 3. Gaps

| # | Gap | Effect |
|---|---|---|
| G1 | **No swap offer.** `APPROVE_SWAP` takes any `creatorId` from the brand. Ops cannot propose a creator, the brand cannot see or decline a proposal, and nothing checks "same or lower rate", that the creator is bookable, or that it is not the creator who just failed | Rule 1 is not enforced; a brand could book anyone by id |
| G2 | **A declined job has no clock.** `DECLINED` (creator said no, or let 48 h pass) has no 10-day window and no refund path except a manual cancel | A paid brand can wait forever (rule 4) |
| G3 | **No level drop.** A missed production deadline leaves the creator VERIFIED | Rule 2 is not enforced (F5 known limit 7, waiting for creator profiles, which now exist) |
| G4 | **Outcomes are silent.** After `RESOLVE_APPROVE` or `RESOLVE_REPLACE`, neither the creator nor the brand is told; entering `REPLACEMENT_NEEDED` tells the brand nothing | People learn the outcome by checking the app |
| G5 | **The creator has no say in a dispute.** Ops sees the brand's note and checklist, never the creator's side | One-sided review; more appeals later |

## 4. Plan

| # | Change | Where | Size |
|---|---|---|---|
| F1 | **Swap offers.** Table `job_swap_offers` (V21): job, creator, rate, status OFFERED / APPROVED / DECLINED / WITHDRAWN, who and when, brand's decline reason. Ops `POST /api/core/ops/jobs/{id}/swap-offers {creatorId, note}`: job in `DECLINED` or `REPLACEMENT_NEEDED`; creator bookable; not a creator already assigned to this job; creator's listed rate ≤ the job's creator fee; one open offer at a time. Ops may withdraw it. | new `job` app service + ops controller | M |
| F2 | **Brand decides the offer.** `GET /api/core/brand/jobs/{id}/swap-offer` (the creator's public profile: handle, level, categories, rate band, sample links; no contact details). `POST .../approve` runs `APPROVE_SWAP` with the offer's creator (the brand no longer sends a creator id). `POST .../decline {reason}`; ops is told and offers another | brand controller, `JobTransitionService` guard | M |
| F3 | **10-day window after a decline too.** Entering `DECLINED` starts the same `REPLACEMENT` deadline; `REPLACEMENT_EXPIRED` and `RESOLVE_REFUND` (ops) added from `DECLINED`, both full refund through the refund flow | `TransitionTable`, `JobDeadlines`, `TimerRules`, `RefundService` | S |
| F4 | **Level back to NEW** on `PRODUCTION_EXPIRED`, in the same transaction, through a port (`CreatorStanding` in job.domain, implemented in creator.app); audited as SYSTEM with the job id; the creator is told. Ops can still set the level by hand | creator app, job domain | S |
| F5 | **Outcome notices**: `RESOLVE_APPROVE` → creator ("approved after review, payment follows") and brand; `RESOLVE_REPLACE` → creator (removed, reason code) and brand (replacement within 10 days); entering `REPLACEMENT_NEEDED` from any cause → brand; offer made → brand; offer declined → ops | `DisputeNotices`, same pattern as `DeliveryNotices` | S |
| F6 | **Creator's statement**: while `DISPUTED`, the creator may send one statement (20-2,000 characters) `POST /api/core/creator/jobs/{id}/dispute-statement`; ops sees it in the ops drafts view; ops is told | draft app + view | S |
| F7 | Docs: F9 report, ops runbook "resolving a dispute", contract notes for apps | docs | S |

## 5. Proof

- HTTP + Postgres tests per rule: offer guards (not bookable, previous creator, rate above the fee, second open
  offer, wrong state, brand cannot offer, ops cannot approve); brand approve → creator invited with that creator;
  decline → ops told, new offer possible; a brand can no longer swap by sending a creator id.
- Decline → 10 days → automatic full refund; ops refund from `DECLINED`.
- Production deadline missed → `REPLACEMENT_NEEDED`, level NEW, audit row, creator told; already NEW stays NEW.
- Every outcome notice reaches the right inbox exactly once; a statement only while disputed, only once, only by
  the job's creator, shown to ops.
- Races: two offers at once → one; approve and withdraw at once → one wins.
- Full suite, e2e, ArchUnit (no cross-feature infra), load: offer and approve endpoints within the 300 ms write gate,
  offer view within the 50 ms read gate.

## 6. Decisions needed

1. **Money when the replacement's rate is lower**: the replacement is paid the job's creator fee (recommended: the
   brand's price and the refund maths do not change; the creator accepted that fee) — or the brand gets the
   difference back.
2. **10-day window after a decline** (recommended: yes, the spec's refund promise covers every "no creator" case).
3. **Level drop**: only on a missed production deadline, as the spec says (recommended) — or also when ops decides a
   dispute was the creator's fault.
4. **Creator's statement in a dispute** (recommended: yes, one statement, text only).
5. **How many offers a brand may decline**: no limit inside the 10 days (recommended); each decline needs a reason.

## 7. Will stay open

The shortlist itself is Backend A's (F3): ops picks the next creator by hand and may paste Backend A's
`suggestion_id`. Paid revisions (founder decision) are not part of the dispute flow. Creator payout after
`RESOLVE_APPROVE` is F8.

## 8. Results (2026-11-20)

Built as planned (F1-F7). Migration `V21__f9_swap_offers_dispute_statements.sql`.

| # | What | Where |
|---|---|---|
| F1-F2 | Swap offers: ops offers (bookable, never on this job, rate ≤ the job's creator fee, one open offer, note required, optional Backend A `suggestion_id`), withdraws; the brand sees the public card (no contact, no ops note), the admin approves or declines with a reason. **The engine refuses `APPROVE_SWAP` without an open offer for that creator**, so the old "any creator id" path is closed; open offers close when the job ends | `SwapOfferService`, `SwapOfferRepository`, `JobTransitionService`, `DisputeControllers` |
| F3 | `DECLINED` starts the 10-day window; `REPLACEMENT_EXPIRED` (system, asks for the refund) and `RESOLVE_REFUND` (ops) from `DECLINED`, full refund | `TransitionTable`, `JobDeadlines`, `TimerRules` |
| F4 | `PRODUCTION_EXPIRED` drops a VERIFIED creator to NEW in the same transaction (`CreatorRoster` port, creator side `CreatorRosterAdapter`), audited as SYSTEM with the job id, creator told, profile re-sent to Backend A | `CreatorRoster`, `CreatorRosterAdapter` |
| F5 | Notices: `JOB_INVITED` (creator, on invite and swap; none existed before), `DISPUTE_RESOLVED_APPROVED` (creator + brand), `DISPUTE_RESOLVED_REPLACED` / `_REFUNDED` (the creator who left), `REPLACEMENT_STARTED` (brand) and `REPLACEMENT_NEEDED` (ops) whenever a paid job loses its creator, `SWAP_OFFERED`, `SWAP_DECLINED`, `DISPUTE_STATEMENT_ADDED`. Payloads: ids, codes, times only | `DisputeNotices` |
| F6 | Creator's statement: one per assignment, 20-2,000 characters, only while `DISPUTED`; ops reads it at `GET /api/core/ops/jobs/{id}/dispute` | `DisputeService` |
| F7 | Ops runbook `docs/ops/resolving-a-dispute.md` | docs |
| extra | The read fence moved to `common.ScreenReadFence`, shared by the F7 screens and the F9 views (one cap of 12 for all screens, so two fences can never take the 20-connection pool together) | `ScreenReadFence` |

### Proof

| Check | Result |
|---|---|
| Full suite | 366 run, 0 failures, 4 skipped (2 Backend A contract, 2 real-store) |
| `DisputeFlowTest` (9) | offer guards (brand cannot offer, note, not bookable, Rs 5,001 on a Rs 5,000 fee refused, Rs 5,000 accepted, job with a creator, second open offer, withdraw then offer); brand view as member, no contact, no ops note, other brand 404, member cannot approve (403), engine refuses a swap to an un-offered creator, admin approves → `CREATOR_INVITED` with the offered creator, offer `APPROVED`, creator gets `JOB_INVITED`; decline needs a reason, ops told, declined twice → 404, next offer allowed; 4 offers at the same moment → exactly 1; missed deadline → `REPLACEMENT_NEEDED`, VERIFIED → NEW with the job in the reason, SYSTEM audit row, creator / brand / ops told, same creator cannot be re-offered, a second miss writes nothing; decline → 10-day deadline exact, brand told, open offer withdrawn with the refund, timer asks for a FULL refund (`LOOFIE_NO_CREATOR`), lead approves → `REFUNDED`; ops refund from `DECLINED`; statement: outsider 404, too short, once only, ops told without the text, ops reads it, brand 403, not while `IN_REVIEW`; approve / replace / refund outcomes reach the right inboxes |
| Engine tests | swaps now go through an offer (`JobFixtures.swapOffered`); `DeliveryFinalPassTest` counts the new invitation notice |
| ArchUnit | passes (controllers use app views, never infra types) |
| End-to-end check | 64/64, exit 0 |

### Load (laptop, 100 rps, 2,400 measured after 600 warm-up, `scripts/perf/SwapLoadTest.java`)

| Run | Offer (write, ≤ 300 ms) | Brand view (read, ≤ 50 ms) | Approve (write, ≤ 300 ms) |
|---|---|---|---|
| 1 (no transaction around the screens) | p95 72 ms PASS | 127 ms FAIL | 631 ms FAIL |
| 2 (one transaction each) | 399 ms | 681 ms | 57 ms PASS |
| 3 | 415 ms | 927 ms | 194 ms PASS |
| 4 (shared fence) | 369 ms | 1,165 ms | 112 ms PASS |
| 5 (15 s pause between phases) | 1,115 ms | 1,050 ms, then **130 ms** on the same screen seconds later | 29 ms PASS |
| Control: F7 endpoint test, same day, unchanged code | | brand view 29 ms | review approve **431 ms FAIL** (45 ms when F7 shipped) |

p50 stayed 8-16 ms on every endpoint in every run; only the tails move, run to run, with no code change, and the
unchanged F7 control fails too. This is the intermittent database stall seen in F1 round C and F7 (cause not
identified on this laptop). **The F9 gates are not proven: re-measure on the real server** (hosting ask 8). Every
call succeeded except 15-38 quick 503s from the fence during stalls (by design).

### Still open

- Load gates on real hosting (above).
- The creator's statement is kept with the job as the dispute record; the lawyer confirms it may stay after she
  deletes her account (open ask 35).
- Brand members approving swaps stays a founder question (open ask 25); today only the admin decides.
