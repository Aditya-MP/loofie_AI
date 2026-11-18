# F1 round B: brand organisations (steps 1-15)

Date: 2026-11-18. Status: **done: built, tested (300 tests pass) and load gate met (section 14); plan approved 2026-11-18 with E1-E4 as recommended. Not yet committed.**
Agreed flow: https://claude.ai/artifact/MPwtSeCJnBt35soCa1AcsK (flows 4 and 5; answers Q5-Q10, 2026-11-17).
Round A (identity core) is done: `docs/features/f1-identity-round-a.md`, commit `888ff98`.

## 1. Business purpose

Brands are the paying side. Today a brand can sign up and log in, but gets 403 on every brand action, because the
engine identifies a brand by its **organisation** (`Actor.orgId`), and organisations do not exist yet. Round B lets a
brand:

- create its company (legal name, billing address, pickup state; GSTIN before its first brief);
- bring in teammates as BRAND_ADMIN or BRAND_MEMBER, always with at least one admin;
- act on its jobs, orders, refunds and inbox with a real login.

It also lets **state holidays** take effect: the brand's pickup state is the missing input behind calendar G4 and F6 G8.

## 2. What exists

| Piece | State |
|---|---|
| `Actor(role, userId, orgId)` | Brand roles require `orgId`. Ownership is checked against `jobs.brand_org_id` (engine, queries, intake), notifications go to the `BRAND_ORG` inbox by org id, refunds carry `brand_org_id`. |
| `jobs`, `orders`, `refund_requests` `.brand_org_id` | UUIDs with no table behind them (comment still says "Backend A org id"). Production has no rows. |
| `SessionAuthenticator.actor` | `BRAND` → 403 "Set up your company first". |
| `users.onboarding_completed` | Never set yet. |
| `staff_invites` | Round A: email, role, token hash, 7 days, single use, bound to the invited email. Staff only. |
| Calendar | `HolidayCalendar.endOfWorkingDays(from, days, stateCode)` already takes a state; `JobDeadlines` passes `null` (TODO). `STATE_HOLIDAYS_IN_USE = false`, shown in two API answers and asserted by 2 tests. |
| Orders | `OrderSubmissionService` + `JobIntakeService`. **No HTTP API yet** (F4). No brief API (F2). |
| `audit_log` | `target_user_id` only. |

## 3. Architecture and dependencies

- Organisations live in the **identity** package (the brief: Identity owns "organisation membership"). Company
  details (GSTIN, billing address, pickup state) live with them for now; if Identity is split out later, the company
  profile moves to core with a copy of the id. No other package reads identity tables (ArchUnit rule from round A).
- Other packages get what they need through two small ports implemented by identity:
  - `ActorResolver` (exists): the session now yields `BRAND_ADMIN` / `BRAND_MEMBER` with the org id.
  - `BrandDirectory` (new, in identity.app): `pickupState(orgId)` and `readyForBriefs(orgId)` (has a GSTIN).
- Job engine: the pickup state is **copied onto the job when it is created** (like the price snapshot), and every
  brand-side working-day clock on that job uses it. A brand moving its warehouse changes new orders only.

## 4. Functional gaps

| # | Gap | Flow |
|---|---|---|
| G1 | `organizations` with legal name, GSTIN (optional until first brief), billing address, pickup state | 4 |
| G2 | `org_members` (user, org, role); one active org per user in v1, enforced by an index (agencies later = drop the index) | 4, Q6 |
| G3 | Brand company setup after signup → org created, creator of it = BRAND_ADMIN, `onboarding_completed` = true | 4 |
| G4 | Brand roles in the session: `Actor(BRAND_ADMIN or BRAND_MEMBER, userId, orgId)` | 7 |
| G5 | Teammate invites (email, role, 7 days, single use, bound to the email); accept as a new user or as an existing brand user without a company | 5 |
| G6 | Team management: list, change role, remove, leave; never zero admins (Q7) | 5, Q7 |
| G7 | GSTIN: format + checksum; one GSTIN = one org; ops can allow a shared GSTIN with a reason (Q9) | Q9 |
| G8 | Pickup state on each new job; brand-side clocks (ship, review in working days) use it; state holidays switched on | calendar G4, F6 G8 |
| G9 | Ops view of an organisation; audit of team and company changes | Q14 |

## 5. Security and abuse

| Threat | Defence |
|---|---|
| A member does admin things | Role read from `org_members` on every request (not from the cookie), so a demotion or removal works on the next request |
| Removed person keeps acting for the brand | Same: no membership row → brand actions 403 at once (they stay logged in as a brand user without a company) |
| Two admins demote each other at once → no admin | Org row locked (`FOR UPDATE`) for every role change / removal / leave; the "at least one admin" count is read under the lock |
| Squatting a big brand's GSTIN | Format + checksum only proves it is well-formed. One GSTIN per org; a second claim gets 409 and is told to ask for an invite or contact support; ops can allow a shared GSTIN with a recorded reason. Real GST-portal verification is a later provider choice |
| Invite token leaks | Bound to the invited email (must pass that email's OTP); single use; 7 days; listed and revocable by admins |
| Invite to someone already in another company | 409 (one company per person in v1), told to leave the other first |
| Cross-brand access | Unchanged engine rule (`brand_org_id = actor.orgId`), now fed by real membership; tested end to end with two real brands |
| Enumeration via GSTIN | The 409 says the GSTIN is in use, which is public information anyway (GST portal) |

## 6. Performance

- The per-request check gains one join (`org_members` by user, partial unique index). Target unchanged: login adds
  **≤ 5 ms p95 at 500 rps**, now measured for brand sessions too.
- The engine's existing brand load (F6 `MARK_SHIPPED`, delivery view) is run once with **real brand sessions** instead
  of dev headers, at the same rate as before (300 rps), gate p95 ≤ 300 ms.
- Pickup state: read once when jobs are created (one row by primary key), never per request.

## 7. Scalability and reliability

Stateless; everything in Postgres. Team changes are short transactions on one org row. No new background work.

## 8. Database (V15)

| Change | Notes |
|---|---|
| `organizations` (id, legal_name, gstin, gstin_shared_reason, gstin_set_by, billing address fields, pickup_state, created_by, created_at, updated_at) | Unique `gstin` where not shared-approved; pickup_state checked against the state code list in the app |
| `org_members` (org_id, user_id, role BRAND_ADMIN/BRAND_MEMBER, added_by, added_at) | PK (org_id, user_id); unique (user_id) = one company per person in v1 |
| `staff_invites` → **`invites`** + `org_id` (null for staff) + role check widened to the brand roles | Shreya's "one invites table" (Q3 answer); round A data moves as is |
| `jobs.brand_pickup_state` varchar(2) null | Copied at creation; null = national holidays only (as today) |
| `audit_log.target_org_id` | Team and company changes |

## 9. Failure scenarios

| Scenario | Expected |
|---|---|
| Two admins remove each other at once | One succeeds; the other is refused ("at least one admin") |
| Two brands claim one GSTIN at once | Unique index: one wins, the other gets 409 |
| Invite accepted twice at once | Invite row locked; one membership |
| Removed member has a request in flight | That request finishes; the next is refused |
| Pickup state changed while a job's ship clock runs | That job keeps its state; new jobs use the new one |
| Org row missing for an old job's brand_org_id (test data, Backend A ids) | Pickup state null → national holidays only, as today |

## 10. Test coverage (to add)

HTTP + Postgres: company setup and the brand acting on a real endpoint with only its cookie; member vs admin rights
(the existing role matrix, now through real sessions); invites (new user, existing brand user, wrong email, used,
expired, already in another company); role change / remove / leave and the last-admin rule including a race; GSTIN
format, checksum, duplicate, ops-approved share; cross-brand isolation with two real brands; pickup state copied to
the job and a state holiday moving the ship deadline; state holidays now reported as in use. Unit: GSTIN checksum
against known-valid numbers. Load: brand sessions on the inbox at 500 rps and F6 shipping at 300 rps.

## 11. Improvement plan (approved 2026-11-18)

| # | Item | Size |
|---|---|---|
| Q1 | V15 schema (section 8) | M |
| Q2 | Company setup (`POST /api/core/brand/company`), edit by admin (name, billing address, pickup state), `GET` for members; GSTIN format + checksum; `onboarding_completed` | M |
| Q3 | Brand roles in the session (`Actor` with org id), membership read on every request | S |
| Q4 | Invites in one table: brand teammate invites, accept as new user (`POST /auth/brand/accept`) or existing brand user; list / revoke | M |
| Q5 | Team: list, change role, remove, leave, at least one admin under a row lock | M |
| Q6 | GSTIN rules: one per org, 409 with next step, ops sets a shared GSTIN with reason; ops org view; audit | S |
| Q7 | Pickup state: `BrandDirectory`, copied onto new jobs, used by brand-side working-day clocks; state holidays switched on (`STATE_HOLIDAYS_IN_USE = true`, notes and 2 tests updated) | M |
| Q8 | Tests, brand-session load runs, doc results, checklist | L |

Decisions needed:

- **E1 One invites table** (rename `staff_invites` to `invites`, add `org_id`). Recommended: yes, as Shreya proposed.
- **E2 Pickup state copied onto each job at creation.** Recommended: yes (a move changes new orders only, like the
  price snapshot). Alternative: read it live at each clock, so a move also shifts running clocks.
- **E3 "GSTIN before the first brief".** There is no brief or checkout API yet (F2, F4). Recommended: round B builds
  the rule (`BrandDirectory.readyForBriefs`) and F2/F4 call it when their APIs exist; enforcing it inside today's
  order service would break the tests that submit orders for made-up brands. Listed as an open item on F2 and F4.
- **E4 GSTIN check = format + checksum only.** Recommended for v1. Checking it against the GST portal needs a paid
  verification provider (e.g. the same KYC provider planned for PAN in F8); later.

Not in round B (agreed elsewhere): creator onboarding and Instagram (round C), account deletion (round C), several
companies per person (later, a rule change), suspending a whole company (not asked; ops suspends people).

## 12-13. What was built (2026-11-18)

| # | Item | Where |
|---|---|---|
| Q1 | V15: `organizations`, `org_members` (one company per person by index), `staff_invites` renamed `invites` with `org_id`, `revoked_at` and a role check per kind, `jobs.brand_pickup_state`, `audit_log.target_org_id` | `V15__f1_brand_organisations.sql` |
| Q2 | Company setup `POST /api/core/brand/company` (session, no company yet → creator becomes BRAND_ADMIN, onboarding complete); `GET` for members (admins also see open invites); `PUT` for admins; GSTIN once by the admin (`PUT /company/gstin`) | `CompanyService`, `BrandController` |
| Q3 | Session lookup joins `org_members`: brand users act as `Actor(BRAND_ADMIN / BRAND_MEMBER, user, org)`, read on every request; `/auth/me` and login answers show `role` and `orgId` | `SessionRepository`, `SessionAuthenticator`, `AuthController` |
| Q4 | One invites table. Brand invites: admin only, 7 days, single use, bound to the email, a new invite replaces the older one; accept as a new person (`POST /auth/invites/accept` after the email OTP; `/auth/staff/accept` still works) or as a logged-in brand user without a company (`POST /brand/invites/accept`); admins list and revoke | `TeamService`, `AuthService.acceptInvite` |
| Q5 | Change role, remove, leave; at least one admin, read under a lock on the company row; removal or leave clears onboarding so the company form shows again | `TeamService` |
| Q6 | GSTIN format + mod-36 check character; one per company (partial unique index), 409 `gstin-taken` with the next step; ops sets / corrects / allows a shared GSTIN with a reason (`PUT /ops/organizations/{id}/gstin`); ops company view (audited); audit log filter by company | `Gstin`, `CompanyService`, `OpsIdentityController` |
| Q7 | `BrandProfiles` port (job domain) implemented by `BrandDirectory`; intake copies the pickup state onto each job; ship reminder, ship deadline and the working-day review window use it; `STATE_HOLIDAYS_IN_USE = true`, holiday notes say which brands a state holiday affects; `readyForBriefs` for F2 / F4 | `BrandDirectory`, `JobIntakeService`, `JobDeadlines`, `HolidayAdminService`, `HolidayYearImport` |
| — | Found by the load test: a busy pool during the session lookup in the security filters answered **500**. `SessionPreloadFilter` now looks the session up first and answers **503 + Retry-After** like the rest of the API | `security/SessionPreloadFilter` |

## 14. Validation

**Tests: 300 run, 0 failures, 2 skipped (Backend A contract test).** New in round B (plus `QueryWarmupTest` (3), from the load finding):

| Test | Proves |
|---|---|
| `GstinTest` (3) | The published sample `27AAPFU0939F1ZV` passes; lower case / spaces normalised; wrong check character, wrong shape, non-Z 14th character and unknown state refused; **every single-character change in positions 3-14 is caught** |
| `BrandFlowTest` (11, HTTP + Postgres) | Company setup and acting with the cookie alone (role, orgId, onboarding in `/me`; inbox 200; second company refused; audited) · form checks (PIN, state, GSTIN, name) · one GSTIN per company, ops-approved share with reason, audited · GSTIN set once by the admin · new person joins by invite as member; member cannot invite or edit · existing brand user accepts while logged in, same session gets the new role · invite rules (another company → 409, staff role refused, bound to email, replaced, revoked) · always one admin; demotion and removal take effect on the next request; removed person sees the company form again; audit trail · **two admins demoting each other at once leave exactly one admin** · a brand cannot see another brand's job (404) · **new jobs carry the pickup state, and a WB-only holiday moves a WB brand's ship deadline by one day, not an MH brand's** |
| `SessionPreloadFilterTest` (2) | Pool exhausted during the session lookup → 503 + Retry-After, request not passed on; no cookie → no lookup |
| `HolidayAdminTest`, `OpsJobApiTest`, `CalendarFinalPassTest` (updated) | State holidays now reported as in use, with the right note |

**Load: gate NOT met yet, environment.** Runs on 2026-11-18 around 01:20-01:50 IST (`scripts/perf/BrandLoadTest.java`,
`brand-seed.sql`: 2,000 companies with admin sessions, 16,000 accepted jobs):

| Observation | Value |
|---|---|
| Control: round A creator inbox, dev headers (code path unchanged by round B), 500 rps | p95 **1 ms** at 2026-11-18 morning; **1,698 ms** in this window |
| Postgres itself during the window | `SELECT 1` 0.07 ms; inbox query 0.03 ms execution |
| Gentle warm-up (50 rps inbox, 10 rps shipping) | inbox p95 12 ms (session) / 42 ms (dev headers); shipping p50 42 ms, p95 6 s while the app was cold |
| Measured 500 rps inbox + 300 rps shipping | collapsed (timeouts) for dev headers and sessions alike |
| Thread dump during the stall | request threads loading classes from the 62 MB jar (serialised, slow on the loaded machine) while holding transactions; others waiting for the pool. No lock waits in Postgres, no wait inside identity code |

Conclusion: the machine was far slower than in the morning (a camera / call app was running), and the same untouched
code path degraded by three orders of magnitude, so these numbers say nothing about round B. **The brand load run must
be repeated on a quiet machine** (`scratchpad` runner: seed, gentle warm-up, 20 s pause, measured) before the
performance line below can be ticked. Functional correctness under concurrency is proven by the tests above.

**Rerun 2026-11-18 10:20-10:50 IST (jar rebuilt, fresh job ranges, GC + safepoint logging): gate still NOT met.**

| Run | Brand inbox, session (500 rps) | Brand inbox, dev headers (500 rps) | Shipping, session + CSRF (300 rps) |
|---|---|---|---|
| A | p95 **6 ms**, all 200 | collapsed (connect timeouts) | p95 7.3 s, 1,032 × 503 |
| B | p95 2.0 s, all 200 | collapsed | p95 12.6 s |
| C (first) | p95 4.3 s, all 200 | collapsed | p95 11.2 s |
| C (second, same warm app) | p95 3.3 s, 167 connect refused | p95 **349 ms**, all 200 | p95 5.1 s, 663 × 503 |

What the logs show:

1. **Real finding, not round B code: cold query parse under a burst.** On a freshly started app, the first concurrent
   "Shipped" requests each parse the same Spring Data derived query (`JobTransitionService.activeAssignment`, via
   Hibernate's HQL parser) **while holding a database connection**. The parser serialises on a shared lock, so on this
   laptop the first parse took seconds; 20 such requests fill the pool and the rest get 503 after 5 s. Captured in a
   virtual-thread dump (15 threads in `StandardHqlTranslator` / ANTLR, 28-31 waiting for the pool; Postgres shows 19
   pooled connections idle after `COMMIT`). It happens even at 10 rps, only in the first seconds after a start, and not
   every time (2 of 5 cold runs). Once parsed the query is cached and the same 10 rps run gives p95 32-62 ms. In
   production this is a few seconds of 503 + Retry-After after each deploy under traffic. **Proposed fix (not built):
   warm the hot job queries once at startup, before the app reports ready.**
2. **The 500 rps collapses are the machine.** In the first 30 s of each measured run the JVM needed **2.0-3.6 s just to
   reach a GC safepoint** (threads runnable but not scheduled by the OS); outside those windows it needs under 25 ms.
   The load generator, the app and Postgres share one laptop; which phase collapses changes from run to run on the
   same code. These numbers cannot pass or fail round B.

Only the session-inbox result of run A (p95 6 ms with every request answered) is a clean reading, and one clean
reading is not a pass. The gate needs a run where the load generator is on a different machine, or a quiet one.

**Fix and final run, 2026-11-18 11:00-11:30 IST: gate MET.**

Fix: `common/QueryWarmup` (an `ApplicationRunner`) runs every Spring Data query method once at startup with throwaway
arguments in a read-only transaction. Spring Boot reports `/actuator/health/readiness` UP only after runners finish, so
no traffic reaches a cold query. Methods are discovered from the repositories, not listed by hand; a failure is logged
and never blocks startup. Startup cost: 6 queries in 316-394 ms. `QueryWarmupTest` (3) proves all 6 are warmed with none
skipped or failed, and that the real calls of a "Shipped" request add **no** new parse afterwards. The load scripts now
wait for readiness = 200 (as the platform does), not just any answer.

Cold-start check (fresh app each time, shipping at 10 rps right after readiness): **5 of 5 pass**, 0 × 503, p95 25-33 ms
(before the fix: 2 of 5 stalled, p95 6.8 s).

Full gate, same laptop (on battery, same apps open as before), measured set run twice:

| Phase | Set 1 | Set 2 | Gate |
|---|---|---|---|
| Brand inbox, dev headers, 500 rps × 15,000 | p95 1 ms, all 200 | p95 1 ms, all 200 | baseline |
| Brand inbox, session cookie, 500 rps × 15,000 | p95 1 ms, all 200 | p95 1 ms, all 200 | added 0 ms ≤ 5 ms **PASS** |
| "Shipped" with cookie + CSRF, 300 rps × 6,000 | p50 3 ms, p95 **7 ms**, p99 167 ms, all 200 | p50 3 ms, p95 **6 ms**, p99 8 ms, all 200 | p95 ≤ 300 ms **PASS** |

Data check: 15,300 jobs moved to shipped = 300 warm-up + 2 × 6,000 + 5 × 600 cold checks, exactly; every shipment row
written once. Safepoint log for the whole run: worst time-to-safepoint under 1 ms (the earlier 2-3.6 s did not recur).

**End-to-end flow check, 2026-11-18 (`scripts/e2e/FullFlowCheck.java`, real app, real cookies and CSRF, codes from
the fake sender's log): 52 of 52 pass, 0 errors in the app log.** Covers: creator phone signup (wrong code, terms
refused, cookie-only `/me`, logout needs CSRF, 401 after) · existing creator logs in without signup, used code refused ·
brand email signup, no-company 403, company form (CSRF, PIN 422), BRAND_ADMIN in `/me`, GSTIN check 422, one company per
person · invite (wrong email refused, used once), member rights (403 on invite / edit) · promote, demote, last admin
cannot leave, removal → company form again · first founder: authenticator enrolment, no ops access before it, wrong
code refused, recovery codes, ops company view and audit trail, brand user 403 on ops, log out everywhere · 4th code to
one number in 15 min → 429. Run it with the app on the local profile and `LOOFIE_BOOTSTRAP_FOUNDER_EMAIL` set.

Correction to the earlier reading: with the cold-parse stall removed, the "machine" collapses did not recur under the
same or worse conditions. They were most likely knock-on effects of the stall (thousands of requests piling up behind
an exhausted pool), not the laptop alone.

### Definition of Done

| Item | State |
|---|---|
| Company setup, edit, GSTIN rules | **Done, tested** |
| Brand roles in the session, member vs admin rights | **Done, tested** |
| Invites, role changes, removal, leave, always one admin (with race) | **Done, tested** |
| Pickup state on jobs, state holidays in brand clocks | **Done, tested** |
| Ops company view, GSTIN override, audit | **Done, tested** |
| 503 instead of 500 when the pool is busy during login check | **Done, tested** (found by load) |
| Brand session cost ≤ 5 ms at 500 rps; brand shipping p95 ≤ 300 ms at 300 rps | **Done, measured** (0 ms added; shipping p95 6-7 ms, all 200, twice) |
| Shipping right after a start: first burst could exhaust the pool for ~5-7 s (cold query parse, pre-existing in F5/F6) | **Fixed, tested** (`QueryWarmup`; 5 of 5 cold starts clean) |
| "GSTIN before first brief" enforced | **Rule built; F2 / F4 must call it** (no brief or checkout API exists yet) |

## 15. Known limits

| Limit | Unblocked by |
|---|---|
| Load numbers for round B | A quiet laptop (or hosting); rerun the brand runner |
| GSTIN not checked against the GST portal | A verification provider (with PAN, F8) |
| One company per person | Drop `org_members_one_org_uq` and add a company switch when agencies come |
| No way for a company to close itself | Support / ops for now; a "close company" flow if asked |
| `jobs.brand_org_id` has no foreign key to `organizations` | Existing test data uses made-up companies; add the key once tests create real companies |
| Running jobs keep the pickup state they started with (E2) | By design |
