# The ops console, day to day (F10)

Every call below is staff-only (ops, ops lead, founder) and needs the staff login. Screens are the web app's; these are
the calls behind them.

## Start of the day: the overview

`GET /api/core/ops/overview` shows what needs a person now. It is at most 30 seconds old (`asOf`).

- **jobs**: jobs per state, and per clock the jobs whose deadline has passed (`ACCEPT`, `SHIP`, `ISSUE_RESPONSE`,
  `PRODUCTION`, `REVIEW`, `DISPUTE_REVIEW`, `REPLACEMENT`).
- **queues**: profiles to review, screenshots to check, disputes (and overdue ones), jobs waiting for a creator, open
  swap offers, refund requests, payout holds, events Backend A refused for good.
- **last30Days**: disputes decided, the share decided within the 2-working-day promise, replacements found and the
  median days it took; ops minutes per closed job.

Then work the queues:

| Queue | Where |
|---|---|
| Any job list | `GET /api/core/ops/jobs?status=...&overdue=true` |
| Profiles to review / screenshots to check | `GET /api/core/ops/creators/review-queue`, `/evidence-queue` |
| Disputes, jobs without a creator | `docs/ops/resolving-a-dispute.md` |
| Refund requests | `GET /api/core/ops/refund-requests` |
| Payouts | below |

## Finding a creator

`GET /api/core/ops/creators?city=&state=&category=&language=&maxRate=&level=&q=&bookable=` lists creators, cheapest
first, 25 a page (`after` = the previous page's `next`). Only bookable creators unless `bookable=false`. `q` is the
start of a name or handle (at least 2 characters, `@` allowed). No phone or email is shown. Ranking and shortlists
are Backend A's; this is the directory for offering a replacement or checking someone.

## Logging your time (every job, from the first one)

Staff time is the biggest cost. Log it as you go:
`POST /api/core/ops/time {jobId, minutes, activity, note?}`, or `{orderId, ...}` for campaign work before any job
exists. Activities: SHORTLIST, VERIFICATION, COORDINATION, DELIVERY, DISPUTE, REPLACEMENT, REFUND, OTHER. 1-480
minutes per entry.

Nothing is ever edited or deleted. A wrong entry is reversed with
`POST /api/core/ops/time/{entryId}/correct {reason}` (by you, an ops lead or a founder), then logged again correctly.

See a job's or campaign's sheet: `GET /api/core/ops/jobs/{id}/time`, `GET /api/core/ops/orders/{id}/time`.
Margin review (ops lead / founder): `GET /api/core/ops/time/report?from=...&to=...` lists each job worked on with its
creator fee and total minutes.

## Holding a payout

If something comes up after a brand approved (a complaint, a doubtful account), hold the payout before it is queued:
`POST /api/core/ops/jobs/{id}/payout-hold {reason}` (job must be APPROVED). The payout cannot be queued while held.
An **ops lead or founder who did not place the hold** releases it:
`POST /api/core/ops/jobs/{id}/payout-hold/release {reason}`. Both steps are in the audit log; the creator never sees
the reason.

The payout queue: `GET /api/core/ops/payouts` (approved and pending payouts, oldest first; `held=true` for held only).
