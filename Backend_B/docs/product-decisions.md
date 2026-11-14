# Product decisions (answers to Backend B's open questions)

Answered by Aditya on 2026-11-14. The founder has not confirmed these yet. Points marked **Open** still need an answer.

## Ownership

| Area | Owner |
|---|---|
| App signup/login (phone OTP, accounts, brand teams, roles) | Backend B. Shreya will guide the auth design. |
| Instagram Login | Backend A (done) |
| Payments, invoices, refunds, payouts, TDS, PAN/bank | Backend B (finance-service). The AI service never touches money, PAN or bank details. |
| Briefs, shortlists, rebook (screens' APIs, storage, checkout) | Backend B |
| Brief reader, matcher, quality score, rebook nudge | Backend A |

## Events

- `job_events` (Backend B) is the source of truth for operations. Backend A's `events` table is the AI's learning log.
- Send to `/ai/feedback` with an `event_id`: `job_state_changed`, `job_outcome`, shortlist picks/rejects, and brief edits.

## Product delivery (F6)

Source: `loofie-ai/docs/DECISIONS.md` section 5 (commit `b50390b`), plus Aditya's follow-up on 2026-11-14.

**Shipping**
- The brand must ship within **3 working days** of the creator accepting, with a reminder after 2.
  If it misses that, the job is marked late and the creator may drop out with no penalty.
- "Shipped" requires a **courier name and tracking number**. Foundation v2 says these are optional, so this is a change.

**Working days**
- Monday to Saturday, skipping holidays from a table that ops can edit.
- Each holiday has a **state** column; blank means a national holiday. The shipping clock uses the brand's **pickup state**.
- Brand-side clocks count working days: **shipping** and **video review**.
- Creator-side clocks stay in **calendar hours**: the 120-hour shooting clock (creators often shoot on Sundays).

**The creator's three buttons**
- **Received**, with a photo: the 120-hour clock starts.
- **Not received**: the job moves to `DELIVERY_ISSUE` (type `NOT_RECEIVED`), the clock stays stopped, and the brand is asked for tracking.
- **Damaged / wrong item**: the job moves to `DELIVERY_ISSUE` (type `DAMAGED` / `WRONG_ITEM`), the clock stays stopped, and the brand is asked to reship.

**Auto-start**
- The clock starts by itself only when the courier shows **"Delivered"** and the creator reports no problem within 24 hours.
- Without courier proof it never starts by itself: the creator is reminded, then ops checks.
- In the pilot, ops marks "Delivered" by hand. A tracking service comes at about 50 jobs a month.

**Missing parcel**
- The brand has 48 hours to show tracking or reship at its own cost.
- If the parcel is still missing **7 days after "Shipped"**, ops steps in. The creator may withdraw with no penalty, and the slot is replaced or refunded.
- **False "not received":** courier "Delivered" (plus the delivery OTP where the courier uses one) is the proof. **Only ops** may set `FALSE_CLAIM`.

**Reason codes.** Disputes and drop-outs carry a code, never free text. Each code has a fault party:

| Code | Fault |
|---|---|
| `FALSE_CLAIM` (ops only), `CREATOR_UNRESPONSIVE`, `CREATOR_WITHDREW` | CREATOR |
| `BRAND_LATE_SHIP`, `WRONG_ITEM_SENT` | BRAND |
| `COURIER_LOST`, `DAMAGED_IN_TRANSIT` | COURIER |
| `OTHER` | NONE |

The reliability score counts only CREATOR-fault codes. If `OTHER` goes above about 10% in a month, add a new code. Ops notes stay in the backend log only.

**Brand ships late and the creator drops out:** a free replacement creator is offered first. If the brand wants a refund, the creator's amount comes back in cash and the Loofie fee as **credit** for its next booking. GST on the credit needs the CA.

**Built (core-service, migration V3).** Until the open points below are answered, these defaults apply:
- The review clock stays **120 calendar hours**. The 48h tracking window and the 7-day missing rule are **calendar** time.
- Shipping deadlines skip **national** holidays only, because the brand pickup state is not known yet. The holiday table already has the state column.
- The holiday table is seeded only with fixed-date national holidays (26 Jan, 15 Aug, 2 Oct, for 2026–27). **Ops must add Diwali, Holi and state holidays**, because their dates change every year.
- The receipt photo is a reference string until F7 upload exists.
- Default reason code when a creator withdraws: brand late → `BRAND_LATE_SHIP`; after `NOT_RECEIVED` → `COURIER_LOST`; after `DAMAGED` → `DAMAGED_IN_TRANSIT`; after `WRONG_ITEM` → `WRONG_ITEM_SENT`. Ops can pick another code with `OPS_RELEASE_CREATOR`.
- Timer worker (`core-service` `timer` package, every 30s): moves jobs on when a deadline passes (accept timeout, auto-start after courier "Delivered", production expired, auto-approve, replacement expired → refund) and sends reminders and alerts once each as `JobDeadlineNotice` events. **Nobody delivers those notices yet:** a notification service (email / WhatsApp) still has to be built and must read them.

**Open**
- **Founder sign-off:** Foundation v2 says production and review windows are both "120 calendar hours", and that the clock auto-starts 24 hours after the 3-day receipt reminder. The new rules change both (review in working days; auto-start only on courier "Delivered").
- **Review clock length:** "5 working days"? And which state's holidays apply: the brand's?
- **Units:** are the 48 hours (brand shows tracking) and the 7 days (missing parcel) calendar or working time?
- **Fee credit vs "no cashback wallet in v1"** (product brief section 5): a fee credit is a stored balance. Who confirms that's allowed?
- The brand's **pickup state** needs a brand pickup address (F1 brand profile, not built yet).

## 48-hour production extension (F9)

- The system grants it automatically, **once**, if the creator asks before the production deadline.
- Any later extension is granted by ops only.

## Closing a job

- The job closes right after the creator is paid (`PAYOUT_COMPLETED` → `CLOSED`).
- The brand's rating stays open for 7 days and does not block closing.
- Send `job_outcome` on close, and send the rating separately when it arrives.

## Brand cancels after paying (per creator slot)

| When | Refund to brand | Creator |
|---|---|---|
| Before the creator accepts | Full | — |
| After accept, before the product ships | Refund minus the Loofie fee (if the brand shipped *late* and the creator dropped out: free replacement, or creator amount in cash + fee as credit; see F6) | **Open:** does the creator get anything? |
| After the product is received, or the shoot has started | No refund for that slot | 50% kill fee |

- Exact numbers will be confirmed in the brand terms. Finance and tax treatment need the CA.
- **Open:** the product is shipped but not yet received. Which row applies?
- **Open:** does the brand cancel self-serve (BRAND_ADMIN), or by asking ops?

## Approvals

- Only BRAND_ADMIN can approve, because approval releases payment.
- BRAND_MEMBER can view, comment and request revisions.
- An "approver" role may come later.
- **Open:** may a BRAND_MEMBER request a *paid* revision (25%), since that charges the brand?
