# Working-day calendar: production readiness

Status: **done for everything Backend B can deliver today** (first pass 2026-11-16, final pass 2026-11-17, results at the end). The real holiday list (ops) and state holidays (F1) are still open.

## 1. Business purpose

Brand-side and ops-side clocks count **working days**, not hours, so a brand is never marked late because of a
Sunday or Diwali. A wrong calendar has real effects:

- **Too few holidays:** the brand's shipping clock runs through a closure, the brand gets a "late" alert and the
  creator may withdraw without penalty (`BRAND_LATE_SHIP`). Unfair to the brand; a lost job.
- **Too many holidays, or a typo:** the creator waits longer than promised.

Clocks that use it today:

| Clock | Rule | Source |
|---|---|---|
| Ship reminder | end of 2nd working day after acceptance | F6 (our proposal, Foundation v2 has none) |
| Ship due (late alert, creator may withdraw) | end of 3rd working day | F6 (same) |
| Dispute review (ops) | end of 2nd working day after `DISPUTED` | Foundation v2 F9 "within 2 working days" |

Everything creator-side (accept 48 h, production 120 h, review 120 h) stays in calendar hours, as Foundation v2
row 12 says. No conflict with Foundation v2 found.

## 2. What exists

| Layer | Code |
|---|---|
| domain | `WorkingDays` (pure: Monday–Saturday, minus holidays, India time, end = midnight after the Nth day, 120-day lookahead), `Holiday` (date, state code, name; '' = national) |
| app | `HolidayCalendar` implements the job engine's `WorkingCalendar`; `HolidayAdminService` (OPS only: list, upsert, delete) |
| infra | `HolidayRepository`, `holidays` table (V3): PK (date, state_code), `created_by`, `created_at`. Seeded: Republic Day, Independence Day, Gandhi Jayanti for 2026 and 2027 only |
| api | `OpsHolidayController`: `GET/PUT/DELETE /api/core/ops/holidays` |
| tests | 4 calendar tests (Saturday counts, India time, seeded holiday, state holiday) + 1 API test |

Performance is already measured (F6): one indexed range query per acceptance, p95 12 ms for the whole
`MARK_SHIPPED` at 300 rps. **No cache needed.**

## 3. Architecture

`job.domain.WorkingCalendar` (interface) ← `calendar.app.HolidayCalendar` → `calendar.infra.HolidayRepository`.
The job feature never touches the holidays table (ArchUnit rule). Layering is correct; no change.

## 4. Gaps (functional)

| # | Gap | What happens | Fix |
|---|---|---|---|
| G1 | **The calendar runs out.** Only 2026–2027 are seeded, and only the 3 fixed-date holidays. Nothing tells anyone. | From 1 Jan 2028 no holiday is skipped, silently. Moving holidays (Holi, Diwali, Eid) are never there unless ops remembers. | **Coverage check:** a daily metric of national holidays in the next 365 days, and a warning when the next 120 days (the lookahead) have fewer than expected. Alert rule when hosting exists. |
| G2 | **Holiday edits leave no history.** Rename overwrites; delete erases. | "Why was this brand marked late on 3 Nov?" cannot be answered: was 2 Nov a holiday when the clock started? This decides who is at fault, and money. | Append-only `holiday_changes` (who, when, action, date, state, old and new name), written in the same transaction. |
| G3 | **Any letters pass as a state code.** `^[A-Za-z]{0,8}$`. | A typo ("WBB", "Bengal") is stored and silently never matches a brand. | Accept only the official Indian state/UT codes (ISO 3166-2:IN, 36 codes; verified at build time, not from memory). 422 otherwise. |
| G4 | **State holidays have no effect yet.** The brand's pickup state is always `null` (no brand profile until F1). | Ops adds Durga Puja for WB and believes Bengal brands get the day; they do not. | Say so: the upsert response states whether state holidays are in use. Kept until F1. |
| G5 | **Ops cannot see the impact of an edit.** Running clocks are not recalculated (decision R3 in F6). | Ops adds a holiday tomorrow; brands already counting are not helped, and ops does not know it. | The upsert response returns how many running working-day clocks span that date. Whether to extend them is decision D1. |
| G6 | **Test gaps:** consecutive holidays (Diwali), holiday on a Sunday (must not count twice), year boundary, start on a holiday, lookahead exhausted, non-OPS refused on every holiday endpoint, invalid state code, dispute clock across a holiday. | Edge cases in the rule that sets deadlines and fault. | Pure `WorkingDays` tests (no database) + API tests. |

## 5. Security and abuse

- OPS only, checked in the app layer (every entry point). Not tested for every endpoint and role: add (G6).
- A holiday edit moves deadlines for every brand: G2 gives the audit trail for that.
- Input: date, 100-char name (validated), state code (G3).

## 6. Performance

Measured in F6: no cache, no change. The coverage check (G1) is one count query per day.

## 7. Scalability and reliability

- Database down: the transition that needs a deadline fails with 503 like any other write; nothing half-done.
- Empty holiday table: deadlines still skip Sundays (safe direction: never earlier than Monday–Saturday counting).
- Lookahead exhausted (120 days without N working days): `IllegalStateException`, the transition fails. Only
  reachable by mis-entering months of holidays; G6 tests it and the coverage check flags absurd counts.
- Several instances: no in-memory state, nothing to keep in sync.

## 8. Database (V9, expand only)

- `holiday_changes` (id, changed_at, changed_by, action INSERT/RENAME/DELETE, holiday_date, state_code, old_name,
  new_name), append-only trigger like `job_events`. A few rows a year.
- `holidays.state_code` CHECK against the state list (G3). Existing rows are all '' (national), so it applies cleanly.

## 9. Failure scenarios

| Scenario | Today | After |
|---|---|---|
| Next year's holidays never entered | Silent | Metric + warning (alert with hosting) |
| Typo in a state code | Stored, never matches | 422 |
| Holiday added after clocks started | Not applied, ops not told | Not applied (unless D1 says so); ops told how many clocks span the date |
| "Was this a holiday when the clock started?" | Unanswerable | `holiday_changes` |
| Two ops edit the same day at once | Last write wins, no record | Same, both recorded |

## 10. Test coverage

Covered: Saturday counts, Sunday skipped, India time, seeded national holiday, state holiday, API happy path.
Missing: G6 list.

## 11. Plan (awaiting approval)

| # | Item | Gap | Size | Needs a decision? |
|---|---|---|---|---|
| 1 | State codes: fixed official list, 422 otherwise; DB CHECK | G3 | S | No |
| 2 | `holiday_changes` audit, same transaction; ops can read it | G2 | S | No |
| 3 | Coverage metric + warning (national holidays in the lookahead and the next 365 days) | G1 | S | No |
| 4 | Upsert response: created/renamed, running clocks spanning the date, whether state holidays are in use | G4, G5 | S | No |
| 5 | Edge-case tests (pure + API + every role refused) | G6 | S | No |
| 6 | Extend running clocks when a holiday is added | G5 | M | **D1** |
| 7 | Seed the real 2026–2027 courier-closure list | G1 | S | **D2** (data from ops, not from memory) |

Not proposed: a cache (measured unnecessary), per-brand weekly off days or pickup state (needs F1), a bulk import
(15–20 holidays a year is fine by hand).

## Decisions needed

- **D1. A holiday added after a clock started: extend that clock?** Recommendation: **no** (keep F6 decision R3:
  moving a deadline someone was already told about is worse, and an extension also delays the creator's right to
  withdraw). Instead, item 3's alert makes ops enter each year's list early (by 1 December for the next year).
- **D2. Which days are holidays for shipping?** Gazetted holidays, or only days the couriers brands use do not
  pick up? Recommendation: courier closure days only, kept by ops; we need the 2026–2027 list from ops.
- **D3. Saturday stays a working day** (couriers pick up Saturdays). Confirm.
- **D4. Who owns the yearly list and the deadline for entering it** (recommendation: ops, by 1 December).

---

## 12–15. Built and tested (2026-11-16)

Decisions taken as the recommended defaults (no change to today's behaviour): running clocks are **not** moved by a
holiday edit (D1), no holiday dates were added from memory (D2), Saturday stays a working day (D3).

| # | Built | Where |
|---|---|---|
| 1 | Official state codes: 36 (ISO 3166-2:IN, 2023), checked against the published list on 2026-11-16; "wb", "IN-WB" and the 2023-retired codes OR/CT/TG/UT accepted and stored as the current code; anything else 422 `invalid-state-code`; the same list as a database CHECK | `StateCodes`, V9 |
| 2 | `holiday_changes`: who, when, INSERT/RENAME/DELETE, old and new name, same transaction, append-only trigger; seeded with today's rows; `GET /api/core/ops/holidays/changes?year=` (OPS) | `HolidayRepository`, V9, `OpsHolidayController` |
| 3 | Coverage alarm: `loofie.calendar.national.holidays.next365`, `.next.year`, `loofie.calendar.next.year.missing` (1 from 1 December when next year has fewer than the 3 fixed national holidays) + warning log, every 6 h | `HolidayCoverage` |
| 4 | `PUT` answers `outcome` (CREATED/RENAMED/UNCHANGED), `stateCode` as stored, `runningClocksNotChanged`, `stateHolidaysInUse` (false until F1) and a plain note | `HolidayAdminService.EditResult` |
| 5 | Tests: 16 new | `WorkingDaysTest` (7, pure), `HolidayAdminTest` (7), `OpsJobApiTest` (+2) |

Also found and fixed: `GET ?year=99999999` reached `LocalDate.of` and would have answered 500; years outside
2000–2100 are now a 400.

**Tests:** 162 run, 161 pass, 1 skipped (the opt-in Backend A contract test). Covered: consecutive holidays, a
holiday on a Sunday counted once, year boundary, a clock started on a holiday, lookahead exhausted fails loudly,
state code list and aliases, Java list = database CHECK (drift guard), history order and contents, history cannot
be updated or deleted, migration seeds the history, every non-OPS role refused on all 4 endpoints (SYSTEM gets 401:
it cannot log in over HTTP), typo 422, edit response fields, past day reaches no clock, alarm off in November and
on in December when next year is missing.

**Performance:** unchanged paths (the deadline lookup is the same query). The edit response adds two
partial-index counts, run only when ops edits a holiday (a few times a year).

### Definition of Done

| Item | State |
|---|---|
| Rule correctness (edge cases) | Done, tested |
| Bad input (state typo, absurd year) | Done, tested |
| Audit trail | Done, tested (append-only enforced by the database) |
| "List ran out" detection | Metrics and log done; **alert rule needs hosting** |
| Ops sees what an edit reaches | Done, tested |
| Real 2026–2027 holiday list | **Not done: needs D2 and the list from ops** |
| State holidays in effect | **Not done: needs brand pickup state (F1)** |

### Known limits

- The calendar is only as good as the list ops enters (D2, D4). The alarm makes a missing year visible; it cannot
  know a missing Holi.
- `runningClocksNotChanged` counts clocks that end after the day; a clock started today after midnight on that
  same day is counted too (it does not span it). Off by a few at most, on the day itself only.
- Per-brand weekly off days and pickup state: F1.

---

## Final pass (2026-11-17): research and plan (approved 2026-11-17)

### Findings

| # | Finding | Who it hurts |
|---|---|---|
| C1 | **The "list ran out" alarm reaches nobody.** It is a metric and a log line; the alert rule needs hosting, which does not exist. The ops inbox now exists (F5 round 2) but the alarm does not use it. | Brands (marked late through a holiday), ops (not told) |
| C2 | **The alarm is blind to a half-entered year.** "Missing" means fewer than the 3 fixed national days. 2027 already has those 3 seeded, so on 1 December it stays silent although Holi, Diwali, Eid and every courier closure for 2027 are missing. Once ops gives the real list (about 15-20 days), 3 is far too low a bar. | Brands |
| C3 | **The yearly list can only be entered one day per call, with no preview.** When ops sends the list (a sheet of 15-20 rows), entering it by hand risks a half-entered year (some calls fail, nobody notices) and typos. Earlier we said a bulk import was not needed; with D2 about to deliver a real list, it is the safest way to load it. | Ops, brands |
| OK | Rules, edge cases, state codes, audit trail, scope, performance: done and tested in the first pass. The delivery screen (F6) now shows the brand its ship-by date. | – |

### Plan

| # | Change | Design | Size |
|---|---|---|---|
| K1 | **Year import with preview** | `PUT /api/core/ops/holidays/years/{year}` with the full list for that year (date, state code, name). `?dryRun=true` (default) answers the diff: added, renamed, removed, unchanged, and for each the running clocks it reaches; nothing is written. `dryRun=false` applies it **all or nothing** in one transaction, each change in `holiday_changes`. Every row validated (date in that year, state code, name); one bad row → 422 listing the rows, nothing applied. OPS only. | M |
| K2 | **Alarm threshold from config** | `loofie.calendar.expected-national-per-year` (default 3 = today). Set to the real count once ops gives the list, so a half-entered year is "missing". | S |
| K3 | **Alarm to the ops inbox** | When next year is missing (from 1 December) or the next 365 days have fewer than expected: a `HOLIDAYS_MISSING` notice to the ops inbox, at most once per day across all instances (advisory lock + "already sent today" check). | S |
| K4 | **Tests** | Import preview writes nothing; apply all-or-nothing; bad row → 422, nothing applied; history rows per change; non-OPS refused; rows outside the year refused; alarm threshold; inbox notice once a day, also with two instances at once. | S |
| K5 | **Docs** | Results, Definition of Done, blocked list. | S |

No load test: the deadline path is unchanged (measured in the F6 final pass, `MARK_SHIPPED` p95 6 ms), and the import
runs a few times a year.

### Stays blocked (written down, not built)

| Item | Who unblocks |
|---|---|
| The real 2026-2027 courier-closure list (D2), and who owns it yearly (D4) | Ops |
| State holidays taking effect (brand pickup state) | F1 brand profile |
| Alert rule on the metrics (paging) | Hosting |

### Built and tested (2026-11-17)

| # | Built | Where |
|---|---|---|
| K1 | `PUT /api/core/ops/holidays/years/{year}` with the year's full list (`date`, `stateCode`, `name`). **Preview by default** (`dryRun=true`): ADDED / RENAMED / REMOVED / UNCHANGED per row with the running clocks each change does not reach, nothing saved. `dryRun=false`: all or nothing in one transaction, under a per-year lock, each change in `holiday_changes` (additions and renames first, then removals). Every row checked first (date in that year, state code, 1-100 char name, no duplicate day + state, at most 500 rows); any bad row → 422 `invalid-holiday-rows` listing every bad row, nothing applied. OPS (incl. OPS_LEAD / FOUNDER) only | `HolidayYearImport`, `OpsHolidayController`, `ApiExceptionHandler` |
| K2 | `loofie.calendar.expected-national-per-year` (default 3, allowed 3-60, in `application.yaml`); new gauge `loofie.calendar.national.holidays.expected` | `HolidayCoverage` |
| K3 | `HOLIDAYS_MISSING` notice to the ops inbox (`why` NEXT_YEAR_MISSING or NEXT_365_DAYS_SHORT, year, found, expected), at most once per India day across all instances (advisory lock + "sent today" check). A failed check is now logged as a warning, not hidden at debug | `HolidayCoverage`, `NotificationService.toOpsOncePerDay` |

| Proof | Result |
|---|---|
| `CalendarFinalPassTest` (9) | preview saves nothing and writes no history; apply replaces the year (1 added, 1 renamed with old/new name, 1 removed, 2 unchanged) with one history row per change by the right person; 5 different bad rows (wrong year, bad state, duplicate national day, blank name, no date) all listed and **nothing applied**; "wb" / "OR" stored as WB / OD with the state-holiday note; brand, creator, SYSTEM refused, OPS_LEAD allowed; two imports of the same year at once → 4 additions in total, 4 history rows; API: preview by default, 30 February → 400, bad row → 422 with the row number, brand → 403; 3 expected = 2027 looks complete, 15 expected = 2027 half-entered and ops told (with year, found, expected); setting below 3 refused at start; **4 instances checking at once + a later check the same day → 1 notice; next day → 2**; nothing missing → no notice |
| Full suite | **241 run, 0 failed, 2 skipped** (the opt-in Backend A contract tests; nothing sent to Backend A changed) |
| Performance | Deadline path unchanged (F6 final pass: `MARK_SHIPPED` p95 6 ms at 300 rps). The import and the alarm run a few times a year / every 6 h. No load test (approved plan) |

### Definition of Done (final)

| Item | State |
|---|---|
| Rules, edge cases, bad input, audit trail, scope | Done, tested (first pass) |
| Ops can load the yearly list safely (preview, all or nothing) | **Done**, tested |
| A missing or half-entered year reaches a person | **Done**: ops inbox notice, once a day; metric for paging when hosting exists |
| Real 2026-2027 courier-closure list | **Blocked: ops (D2, D4).** Load it with K1, then set `expected-national-per-year` |
| State holidays in effect | **Blocked: F1** (brand pickup state) |
| Paging on the metrics | **Blocked: hosting** |

**The working-day calendar is done for everything Backend B can deliver today.**
