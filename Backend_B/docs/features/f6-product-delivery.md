# F6 product delivery: production readiness (steps 1-15)

Scope: from "creator accepted" to "production clock running": shipping, the creator's three buttons, courier
proof, missing and damaged parcels, reships, withdrawal, reason codes.
Date: 2026-11-15, final pass 2026-11-17. Status: **done for everything Backend B can deliver today** (see "Final pass" at the end); founder rules, courier link patterns, F1 / F7 inputs still open. Laptop-measured only.
F6 runs on the F5 engine, so everything verified in `f5-job-engine.md` (locking, idempotency, scope 404s,
outbox, timers, failure tests) applies here too. This document covers what is specific to F6.

---

## 1. Business purpose

**Foundation v2 (authoritative):**
- The brand clicks "Shipped" (**optional** AWB number).
- The creator taps "Product received" and uploads a photo, and the 120h production clock starts.
- Fallback: no confirmation 3 days after "Shipped" → the creator is prompted to confirm or report
  non-delivery. **No response for another 24h → the clock starts** and ops is alerted.
- Digital-only briefs skip straight to `IN_PRODUCTION` on acceptance.

**Later decisions (Aditya, `product-decisions.md`), built but not founder-approved:**
- The brand ships within 3 working days, with a reminder after 2.
- **Courier name and tracking number are required.**
- Three buttons: Received (photo), Not received, Damaged / wrong item.
- The clock auto-starts **only** 24h after courier "Delivered" proof; without proof it never auto-starts.
- 48h for the brand to answer an issue; ops steps in 7 days after the first "Shipped"; only ops sets `FALSE_CLAIM`.
- Reason codes carry a fault party; the reliability score counts only creator-fault codes.

**Why it matters:** this is where a creator's reputation (late, false claim) and a brand's money (reships,
refunds) are decided. A wrong rule here wrongly punishes a creator or costs a brand money.

## 2. What exists

| Layer | F6 parts |
|---|---|
| api | the shared transition endpoint (`courier`, `trackingNumber`, `photoRef`, `issueType`, `reasonCode`) |
| app | `JobTransitionService`: guards (`checkGuards`), delivery facts (`applyDeliveryFacts`), withdrawal reason codes |
| domain | 14 F6 rules in `TransitionTable`, `Shipment`, `DeliveryIssueType`, `ReasonCode` / `FaultParty`, F6 clocks in `JobDeadlines` |
| infra | `ShipmentRepository`, `shipments` table (V3), `holidays` (calendar feature) |
| timers | `SHIP_REMINDER`, `SHIP_LATE`, `RECEIPT_PROMPT`, `RECEIPT_OPS_CHECK`, `ISSUE_RESPONSE_OVERDUE`, `PARCEL_MISSING`; action `AUTO_START_PRODUCTION` |
| tests | `ProductDeliveryTest` (12), plus F6 parts of the timer, ops API and engine tests |

## 3. Architecture and dependencies

- Depends on: the F5 engine, the working-day calendar (shipping clock), the timer worker.
- Missing upstream inputs: **brand pickup state** (needs a brand profile, F1), **photo upload** (F7), and a
  **courier tracking service** (later; ops marks "Delivered" by hand in the pilot).
- Downstream: finance (reship cost, refund or fee credit when the brand is late), creator reliability score
  (reason codes), notifications (all F6 notices).

## 4. Functional gaps

| # | Gap | Impact | Proposal |
|---|---|---|---|
| G1 | **Foundation v2 conflicts** (tracking optional vs required; auto-start after 3d + 24h vs only on courier proof). | We run rules the founder has not approved. | Founder decision. Code supports both today only by editing rules. Keep the Aditya rules (safer for creators) until the founder answers. |
| G2 | **"Delivered" is stamped with the time ops clicks, not the courier's delivery time.** | Ops marks it a day late, so auto-start is a day late; or the creator's 24h window is measured from the wrong moment. | Accept the courier's `deliveredAt` (not in the future, not before shipping); default now. |
| G3 | **No limit on reships.** Not received → reship → not received → ... forever. | Cost and time for the brand, and an endless loop with no one deciding. | After N reships, `RESHIP` is ops-only. **Decision:** N (my pick: 2). |
| G4 | **Damage found after "Received"** (box looked fine, product inside broken or wrong shade): no path. | The creator must shoot a broken product or quit with a penalty. | Allow `REPORT_DELIVERY_ISSUE` from `IN_PRODUCTION` within 24h of receipt, clock paused. **Decision needed.** |
| G5 | `DAMAGED` always maps to courier fault (`DAMAGED_IN_TRANSIT`). | Bad packaging is the brand's fault but would never show as such. | Ops may pick the code when resolving; default stays courier. **Decision:** confirm. |
| G6 | Tracking numbers are not checked for reuse across jobs. | One brand can enter the same AWB for 10 creators: "shipped" with one parcel. Fraud, or a typo. | Flag to ops (`DUPLICATE_TRACKING` alert) when an active shipment elsewhere has the same courier + number. Block if it belongs to another brand. |
| G7 | No brand-side or creator-side read of shipments (only ops detail). | Apps cannot show "your parcel" screens. | Part of the brand and creator read APIs (later feature); noted. |
| G8 | Brand pickup state unknown: only national holidays count. | A Bengal brand's clock ignores Durga Puja. | Needs F1 brand profile. Known limit. |

## 5. Security and abuse

| # | Finding | Severity | Proposal |
|---|---|---|---|
| S1 | **`photoRef` accepts any text up to 512 chars**, for example `javascript:alert(1)` or a link to another site. Ops will open it. | **High** (stored XSS or phishing in the ops console) | Accept only an object-storage key pattern (`receipts/<uuid>.<jpg/png/heic>`) until F7; F7 issues the keys. |
| S2 | `courier` and `trackingNumber` accept any characters (64 max). | Medium (shown in ops and brand screens; also junk like "na") | Courier from a fixed list (+ `OTHER` with a name). Tracking: letters, digits, dashes, 6-40 chars, uppercased, spaces removed. |
| S3 | Ops can mark "Delivered" with no evidence field; that starts the creator's clock automatically. | Medium (one wrong click makes a creator look late) | Require an evidence note (courier status text or screenshot key) for `COURIER_DELIVERED` by ops. It is already an ops action with a mandatory reason; make the reason mention the source, enforced by a `source` field (`TRACKING_PAGE`, `COURIER_CALL`, `TRACKING_API`). |
| S4 | A brand member can ship and reship (by design). | OK | Keep: shipping is warehouse work. |
| S5 | `FALSE_CLAIM` only by ops, only via `RESOLVE_FALSE_CLAIM`. | OK, tested | Keep. |

## 6. Performance

F6 actions are engine transitions: a few index lookups, one shipment insert, one holiday range query. The engine
measured p95 8 ms at 1,000 rps (F5). **F6 actions were not part of the load mix**, so their numbers are not
measured yet. The shipping-clock holiday query reads a 120-day range of a table with a few dozen rows; it is not a
risk, but it runs on every acceptance and could be cached in memory (holidays change a few times a year).

Plan: add `MARK_SHIPPED` / `CONFIRM_RECEIVED` / `REPORT_DELIVERY_ISSUE` to the load test, then decide on caching
from the numbers, not before.

## 7. Scalability and reliability

| # | Finding | Proposal |
|---|---|---|
| R1 | Race: in `DELIVERY_ISSUE`, the brand reships while the creator taps "Received" (the parcel turned up). Optimistic lock: one wins. If the reship wins, the creator's "Received" then lands on the **new** shipment. | Record which shipment the creator confirmed (`shipmentAttempt` in the request, default latest). If it is an older attempt, mark that one received and tell ops, so the brand can stop the reship. |
| R2 | Auto-start depends on ops marking "Delivered" by hand. If ops is slow, every job waits; nothing alerts on it except `RECEIPT_OPS_CHECK` at 96h. | Measured by `loofie_timer_*`; add the count of `PRODUCT_SHIPPED` jobs without courier proof after 72h to the ops overview. A tracking-service integration later. |
| R3 | Holiday edits do not change clocks already running (documented). | Fine: changing a deadline that someone was already told about is worse. Keep and show it in the holiday API response. |

## 8. Database

**Good:** one row per shipment attempt (evidence never overwritten), `UNIQUE(job_id, attempt)`, non-blank checks,
FK to the assignment, `delivery_issue_type` check, all clocks indexed.

| # | Finding | Proposal |
|---|---|---|
| D1 | No index to find a tracking number across jobs (needed for G6). | `(courier_code, tracking_number)` index on `shipments`. |
| D2 | `courier` is free text: "Delhivery", "delhivery", "DELHIVERY " are three couriers. | `courier_code` column (enum list), keep the free-text name for `OTHER`. Expand-then-contract migration. |
| D3 | `receipt_photo_ref` free text (S1). | `CHECK` on the key pattern once S1 is decided. |

## 9. Failure scenarios (philosophy, section 5)

| Scenario | Today | Verified? |
|---|---|---|
| Brand clicks "Shipped" twice (network retry) | Second is illegal from `PRODUCT_SHIPPED` (409); with an `Idempotency-Key` it is replayed. No second shipment row. | Engine tested |
| Brand never ships | Reminder at 2 working days, late alert at 3; the creator may withdraw without penalty (`BRAND_LATE_SHIP`). | Tested |
| Courier loses the parcel | Not received → brand has 48h → 7 days after the first "Shipped" ops steps in, creator may withdraw (`COURIER_LOST`). | Tested |
| Creator falsely says "not received" | Ops resolves with courier proof → `FALSE_CLAIM` (creator fault). | Tested |
| Ops marks "Delivered" wrongly | Auto-start after 24h; the creator can still report an issue within those 24h. After that, only ops can fix it. | Partly (S3, G4) |
| Reship + "Received" at the same time | One wins; may record the wrong shipment (R1). | Not tested |
| Same AWB for many creators | Accepted silently (G6). | Gap |
| Timer worker down for days | Clocks catch up on restart; at-deadline alerts older than 7 days are dropped (F5 design). | Tested |

## 10. Test coverage

**Covered:** all three buttons, shipping clock with Saturday and holidays, reminders and late alerts, reship
keeps the 7-day count from the first shipment, parcel turning up after a report, auto-start only after courier
proof + 24h, withdraw rules and codes, ops release with a code, false claim ops-only, digital-only skip.

**Missing:** tracking and photo validation (after S1/S2), duplicate tracking (G6), reship cap (G3), courier
delivered time (G2), the reship-vs-received race (R1), damage after receipt (G4), and F6 actions under load.

## 11. Improvement plan (awaiting approval)

| # | Change | Solves | Size | Needs a decision? |
|---|---|---|---|---|
| 1 | **Validate `photoRef`** as a storage key (`receipts/<uuid>.<ext>`); reject everything else | S1 (stored XSS/phishing) | S | No |
| 2 | **Courier list + tracking format** (`courier_code`, normalised tracking 6-40 chars) | S2, D2 | M | Which couriers (my list: Delhivery, Blue Dart, DTDC, Ecom Express, Xpressbees, Shadowfax, India Post, Ekart, Shiprocket, OTHER) |
| 3 | **Duplicate tracking check**: flag to ops, block across brands | G6, D1 | S | Confirm "flag same brand, block other brand" |
| 4 | **Courier delivered time + source** on `COURIER_DELIVERED` | G2, S3 | S | No |
| 5 | **Reship cap**: after N reships, ops only | G3 | S | N (my pick: 2) |
| 6 | **Which shipment was received** (attempt in the request; older attempt → ops alert) | R1 | S | No |
| 7 | **Damage after receipt** within 24h, clock paused | G4 | M | **Yes** (new rule) |
| 8 | **Ops can choose the fault** when resolving damage (courier vs brand packaging) | G5 | S | Confirm |
| 9 | **F6 actions in the load test**, then decide holiday caching on the numbers | Section 6 | S | No |
| 10 | Docs: F6 limits, Foundation v2 conflict list for the founder | G1, step 15 | S | No |

**I can build 1, 4, 6, 9 and 10 now (no decisions needed). Items 2, 3, 5, 7 and 8 wait for your answers.**

**Will stay unverified after this plan:** the Foundation v2 conflicts until the founder decides; pickup-state
holidays (needs F1); real photo upload (F7); tracking via a courier API (later).

---

## 12-13. What was built (2026-11-15)

Migration `V7__f6_delivery_evidence.sql`. 133 tests pass (4 new F6 tests, several updated).

| # | Built |
|---|---|
| 1 | `photoRef` must match `receipts/<uuid>.<jpg/jpeg/png/heic/webp>`, checked in the engine **and** as a database `CHECK`. Links, scripts, path tricks, wrong extensions and padded values are refused (tested). |
| 4 | `COURIER_DELIVERED` takes the courier's `deliveredAt` (not in the future beyond 5 min of clock difference, not before shipping, also a database `CHECK`) and a `deliverySource`: ops must say `TRACKING_PAGE` or `COURIER_CALL`; `TRACKING_API` is for SYSTEM only. The creator's 24h window counts from the courier's time. Recorded time and source are kept on the shipment and in the event. |
| 6 | `CONFIRM_RECEIVED` may say which `shipmentAttempt` arrived. An older parcel after a reship is marked received (not the one still on its way), and the event carries `olderShipmentReceived: true` so ops can stop the reship. Unknown or already-confirmed attempts are refused. |
| 9 | `scripts/perf/F6LoadTest.java`: brands ship, then creators confirm, with the same gate. |
| 10 | This section, the contract notes in `docs/integration/backend-a-ai-service.md`, and the founder list below. |

## 14. Validation

**F6 actions under load** (1M jobs, warm, laptop, open loop):

| Action | Rate | p50 | p95 | p99 | Errors | Gate |
|---|---|---|---|---|---|---|
| MARK_SHIPPED (holiday lookup, shipment insert, clocks) | 100 rps | 8 ms | 14 ms | 18 ms | 0 | PASS |
| CONFIRM_RECEIVED (shipment update, follow-up to production) | 100 rps | 9 ms | 14 ms | 17 ms | 0 | PASS |
| MARK_SHIPPED | 300 rps | 4 ms | 12 ms | 79 ms | 0 | PASS |
| CONFIRM_RECEIVED | 300 rps | 4 ms | 11 ms | 15 ms | 0 | PASS |

**Decision from the numbers:** the holiday lookup does **not** need a cache (p95 12 ms at 300 shipments a second;
real volume will be a tiny fraction of that). Not adding one.

The first 300 requests after start-up had p95 279 ms (cold start), the same pattern seen in F5.

### Definition of Done

| Criterion | Status |
|---|---|
| Business rules | Built per Aditya's decisions; **Foundation v2 conflicts unresolved** (below) |
| Security: photo reference, delivery evidence, false claim ops-only | Done, tested |
| Security: courier / tracking input | **Open** (item 2 waits for the courier list) |
| Abuse: duplicate tracking, endless reships | **Open** (items 3, 5 wait for decisions) |
| Concurrency: reship vs received | Done (attempt recorded), tested |
| Performance | Met: p95 12 ms at 300 rps |
| Failure scenarios | Covered by the F5 engine tests (locks, retries, Backend A down, restart) |
| Damage found after receipt, fault choice | **Open** (items 7, 8 wait for decisions) |

## 15. Known limits and decisions needed

**For you (blocks items 2, 3, 5, 7, 8):**
1. Courier list: Delhivery, Blue Dart, DTDC, Ecom Express, Xpressbees, Shadowfax, India Post, Ekart, Shiprocket, OTHER?
2. Duplicate tracking number: flag if the same brand, block if another brand?
3. Reship cap: after 2 reships, ops only?
4. Damage found after "Received": allow a report within 24h, clock paused?
5. Damage fault: may ops pick courier or brand packaging?

**For the founder (Foundation v2 says otherwise; we run Aditya's rules until answered):**
1. Tracking number: Foundation v2 "optional"; we require it.
2. Auto-start: Foundation v2 starts the clock 24h after the 3-day prompt with no answer; we start it only 24h
   after courier "Delivered" proof, never without proof (so a slow courier never makes a creator look late).
3. Shipping deadline: Foundation v2 has none; we give the brand 3 working days, after which the creator may
   withdraw without penalty.

**Other limits:** pickup-state holidays need the brand profile (F1); real photo upload is F7 (it must issue keys in
the `receipts/<uuid>.<ext>` format); courier tracking is by hand until a tracking service is integrated.

---

## Round 2 (2026-11-16): the five decisions answered (DECISIONS.md section 5, backend-a `e682442`)

### Plan

| # | Decision | Design | Layers |
|---|---|---|---|
| A | `DEFECTIVE_PRODUCT` = BRAND | Enum value; `job_events.reason_code` CHECK widened (V10) | domain, DB |
| B | Duplicate tracking number | Normalised number (upper case, no spaces or dashes) stored on the shipment and indexed. On ship/reship: other brand → **422 block** with the agreed message; same brand, other creator → allowed, `tracking_reuse = 'SAME_BRAND_OTHER_CREATOR'` on the shipment (ops detail) and `trackingReused` in the event; same brand and creator → allowed. Checked under a transaction-scoped advisory lock on the number, so two brands shipping the same number at the same moment cannot both pass | app, infra, DB |
| C | Reships | Brand may `RESHIP` once (2 shipments in total). After that, **only ops** can reship (ops enters the details) or release. Ops added to `RESHIP` | domain, app |
| D | Courier table (OTHER also needs the number: "Shipped" always does) | `couriers` (code, name, kind TRACKED / LOCAL / OTHER, tracking-link pattern with `{tracking}`, active), ops-editable (`/api/core/ops/couriers`, OPS only), active list for brands (`GET /api/core/couriers`). Shipments get `courier_code`, `tracking_url` (Other), `handover_photo_ref` (Local). Rules: TRACKED needs a tracking number; LOCAL needs a handover photo key (`handovers/<uuid>.<jpg|jpeg|png|heic|webp>`), no number; OTHER needs a name, the number and an `https://` tracking link. `tracking_number` becomes nullable (Local), with a CHECK that a shipment has a number or a handover photo. Old clients sending only `courier` text are matched by name, else 422. **Seeded with the agreed names and no link patterns: ops adds each courier's pattern** (not written from memory). Detail view shows a one-click tracking link | all |
| E | Damage after "Received" | Creator may report `DAMAGED` from `IN_PRODUCTION` **once per job, within 24 h of the received timestamp**, with an evidence key (`damage/<uuid>.<jpg|jpeg|png|heic|webp|mp4|mov>`). The job goes to `DELIVERY_ISSUE`; the remaining production time is stored (`production_paused_seconds`) and the clock stops. No "missing parcel" timer (the product arrived). Exits: brand/ops `RESHIP` → the 120 h restart on the new receipt (stored time cleared); new ops action `OPS_RESUME_PRODUCTION` ("usable") → `IN_PRODUCTION` with the remaining time; `RESOLVE_FALSE_CLAIM` also resumes with the remaining time (a false claim must not earn a fresh 120 h); `OPS_RELEASE_CREATOR` with a code. After 24 h, or after the one report: creator refused ("ask ops"); **ops may pause the same way at any time** | domain, app, DB |

Out of scope: refund approvals (own feature), tracking service, notifications to ops (events and the detail view
carry the flags; delivery to a person needs the notification service).

### Built, tested, measured (2026-11-16)

All five built as planned, with one correction found by a test: **"Other" also needs the tracking number** (the
first design made it optional, which would have broken the "every shipment has a number or a handover photo" rule;
DECISIONS.md says "Shipped" always needs a number).

| Proof | Result |
|---|---|
| `DeliveryRulesRound2Test` (15) | Defective product = BRAND from the code; other brand's number blocked even typed as " dl-12 34 "; same brand + creator allowed; same brand, other creator flagged (shipment, event, ops detail); **6 brands entering one number at the same moment: exactly 1 succeeds**; brand reships once, ops after; LOCAL needs a handover photo key, OTHER a name + number + https link; tracking link URL-encoded into the courier pattern; unknown / switched-off courier refused, old clients matched by name; only ops edits couriers, LOCAL/OTHER fixed; damage within 24 h pauses the clock exactly, two days of ops review cost the creator nothing, "usable" resumes with the time left; reship restarts the full 120 h; false damage claim resumes the time left; once per job, 24 h, evidence required, only damage after receipt; ops may pause any time; evidence and paused time visible to ops |
| Contract test vs Backend A's mock (`e682442`) | 15 events incl. late damage, `DEFECTIVE_PRODUCT`, resume: all accepted, resends duplicates |
| Full suite | **177 of 177** (contract test included) |
| F6 load, 1M jobs, after a warm-up, 300 rps, 6,000 each | `MARK_SHIPPED` (now with courier lookup, lock, duplicate check) **p95 5 ms**, `CONFIRM_RECEIVED` p95 3 ms, 0 errors, PASS |

**Known limits:** courier tracking-link patterns are empty until ops adds them (not written from memory); the
"flagged to ops" duplicate and a second delivery problem reach ops through the job detail and events only, until the
notification service exists; a job that auto-started (no "Received" tap) has no self-serve late-damage window (ops
only); handover photos and damage evidence are keys only until F7 upload exists.

---

## Final pass (2026-11-17): research and plan (approved 2026-11-17, P3 = yes)

### Findings

| # | Finding | Who it hurts |
|---|---|---|
| F1 | **Delivery state changes notify nobody.** Only timer notices (`SHIP_REMINDER`, `SHIP_LATE`, `RECEIPT_PROMPT`, ...) reach the inboxes. When a creator reports "Not received" or "Damaged", the brand's 48 h window starts but **the brand is not told**; the creator is not told the product shipped (or its tracking link), that a reship is coming, that ops resumed her clock, or that her claim was ruled false. Duplicate-number flags and "older parcel arrived, stop the reship" reach ops only through the job detail. | Brand (misses its 48 h, pays a refund), creator (clock starts without her knowing), ops |
| F2 | **Brands and creators cannot read a job's delivery at all.** The only read API is the ops console. The brand cannot see the issue type and evidence it must answer; the creator cannot see the courier and tracking link. | Brand, creator |
| F3 | A job that **auto-started** (courier proof + 24 h, no "Received" tap) has no self-serve late-damage window: the creator must ask ops. | Creator |
| F4 | Load test covers only ship and receive; issue, reship, courier delivered and the new inbox writes are not measured. Edge cases not tested: issue reported just before / after the 48 h mark, reship with a courier ops has switched off, a second late-damage report after resume. | Proof |
| OK | Courier link patterns: ops already sets them per courier (`PUT /api/core/ops/couriers/{code}`, https + `{tracking}` checked). About 10 rows: **no import endpoint needed**. Holiday import belongs to the calendar final pass. | – |

### Plan

| # | Change | Design | Size |
|---|---|---|---|
| P1 | **Delivery notifications**, in the same transaction as the change | `MARK_SHIPPED` → creator (courier, tracking link) · `RESHIP` → creator · `CONFIRM_RECEIVED` / `AUTO_START_PRODUCTION` → brand + creator (production due) · `REPORT_NOT_RECEIVED` / `REPORT_DELIVERY_ISSUE` (incl. late damage) → brand + ops (issue type, answer by) · `COURIER_DELIVERED` → creator (tap Received or report within 24 h) · `OPS_RESUME_PRODUCTION` → creator (time left) · `RESOLVE_FALSE_CLAIM` → creator · withdrawal for `BRAND_LATE_SHIP` / `COURIER_LOST` → brand · duplicate number (same brand, other creator) and older parcel received → ops (and brand for the older parcel). Ids, codes and times only, no addresses or notes. | M |
| P2 | **Delivery view** `GET /api/core/jobs/{jobId}/delivery` | For the job's brand org (admin or member) and the job's **current** creator; anyone else 404. Status, ship-by, each shipment of the current creator (courier name, number, tracking link, shipped / delivered / received times, issue type and evidence key), answer-by, production due or paused time left. No ops notes, no other creator's shipments. | M |
| P3 | **Late damage after auto-start** | Same rule as after a tap: once per job, 24 h from the auto-start time, evidence required. **Decision: yes?** (recommended: the creator who never tapped is otherwise worse off) | S |
| P4 | **Edge tests** | Section F4 list, plus notification content and P2 scoping (other brand, old creator, member). | S |
| P5 | **Final load** | Full delivery mix at 300 rps on 1M jobs (ship, receive, issue, reship, courier delivered) with inbox writes; gate p95 ≤ 300 ms, 0 errors; recheck the engine at 500 rps. | S |
| P6 | **Docs** | Results, Definition of Done, blocked list. | S |

### Stays blocked (written down, not built)

| Item | Who unblocks |
|---|---|
| Tracking number required, auto-start only on courier proof, 3-working-day ship deadline (Foundation v2 differs) | Founder |
| Courier tracking-link patterns | Ops (enter them in the courier table) |
| Brand pickup-state holidays | F1 brand profile |
| Real photo and evidence upload (keys only today) | F7 |
| Automatic courier tracking | Later (a tracking service) |
| Push, SMS, email for notices (in-app inbox only) | Notification channels feature |

### Built, tested, measured (2026-11-17)

| # | Built | Where |
|---|---|---|
| P1 | Delivery notices, same transaction as the change, sent after the automatic follow-ups so they carry the new clocks: `PRODUCT_SHIPPED` / `PRODUCT_RESHIPPED` → creator (courier, number, link, receipt prompt time) · `COURIER_DELIVERED` → creator (delivered at, auto-start at) · `PRODUCTION_STARTED` → brand (and the creator when it started on its own) · `DELIVERY_ISSUE_REPORTED` → brand + ops (type, answer by, after receipt) · `PRODUCTION_RESUMED` / `DELIVERY_CLAIM_REJECTED` → creator + brand (new deadline) · `CREATOR_WITHDREW` → brand + ops · `TRACKING_NUMBER_REUSED` → ops · `OLDER_PARCEL_RECEIVED` → brand + ops | `DeliveryNotices`, `JobTransitionService.transition` |
| P2 | `GET /api/core/jobs/{jobId}/delivery`: status, ship by, issue and answer by, auto-start, production due or paused time, the current creator's parcels (courier, number, link, times, receipt / handover / damage keys). Brand admin or member of the job's brand, its current creator, ops; anyone else 404. No ops-only facts (delivery source, duplicate flag). Own bulkhead (`loofie.queries.delivery-max-concurrent`, 16) so it cannot starve the engine | `JobDeliveryController`, `JobQueryService.delivery`, `JobReadRepository.currentCreatorShipments` |
| P3 | Late damage after the automatic start: same rule (once, 24 h, evidence) counting from `jobs.auto_started_at`; recorded on the latest parcel; cleared by a reship and for a new creator | V13, `Job`, `JobTransitionService.damageAfterReceipt` |
| P5 | `F6LoadTest` now runs the whole flow (ship, receive, not received, courier proof, damage, reship, delivery screen) | `scripts/perf/F6LoadTest.java` |

| Proof | Result |
|---|---|
| `DeliveryFinalPassTest` (16) | each notice to the right inboxes with the right content; listed courier link built from its pattern; refused change (other brand's number, switched-off courier on reship) tells nobody and leaves the state; notices carry no evidence or receipt keys and no notes; delivery screen same for brand member / creator / ops, 404 for another brand, a stranger, SYSTEM, a missing job and a released creator, brand sees no parcels of a released creator; evidence and paused clock shown; late damage after auto-start in time pauses, after 24 h refused; auto-start time cleared by a reship |
| Full suite | **232 run, 0 failed, 2 skipped** (the opt-in Backend A contract tests; no event to Backend A changed in this pass) |
| F6 load, 1M jobs, after a warm-up, 300 rps each phase | `MARK_SHIPPED` p95 6 ms (6,000) · `CONFIRM_RECEIVED` p95 5 ms (3,600) · not received p95 3 ms (1,200) · `COURIER_DELIVERED` p95 4 ms (1,200) · damage p95 4 ms (600) · `RESHIP` p95 4 ms (1,200) · delivery screen p95 2 ms (6,000) · **0 errors, all PASS** |
| Notices written under load (warm-up + measured) | exactly as expected: shipped 7,000, reshipped 1,400, production started 4,200, courier delivered 1,400, issue reported 4,200 (2,100 reports × brand + ops) |
| Engine 500 rps, realistic mix, relay on | p95 3 ms, p99 13 ms, 0 failed; 4,593 events all sent, 0 waiting, 0 parked (unchanged) |
| Delivery screen query plan | index scans on `shipments (job_id, attempt)` and `assignments` primary key, 0.1 ms |

### Definition of Done (final)

| Criterion | Status |
|---|---|
| Brand: knows when to act (issue, answer-by, older parcel) and sees what was reported | **Done** (in-app inbox + delivery screen) |
| Creator: knows the parcel is coming, its link, when her clock starts, resumes, or her claim is rejected; may report late damage either way | **Done** |
| Ops: told about reports, reused numbers, older parcels, withdrawals | **Done** |
| Security: scope 404s, storage keys only, no notes or evidence keys in notices | Done, tested |
| Abuse: duplicate numbers, reship cap, evidence required | Done (round 2) |
| Performance | Met: every delivery action p95 ≤ 6 ms at 300 rps; engine unchanged |
| Failure: a refused change tells nobody (same transaction) | Tested |

**F6 is done for everything Backend B can deliver today.** Still open, each with its owner in "Stays blocked" above:
the founder's three Foundation v2 rules, courier link patterns (ops), pickup-state holidays (F1), real upload (F7),
automatic tracking (later), push / SMS / email (notification channels). Laptop numbers only; production hosting not
measured.
