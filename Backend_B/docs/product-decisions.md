# Product decisions (answers to Backend B's open questions)

Source of truth: `loofie-ai/docs/DECISIONS.md` on `backend-a` (read at `e682442`, 2026-11-16). This file is Backend
B's copy plus what each answer means for core-service. Answered by Aditya on 2026-11-14 and 2026-11-16.
**Founder sign-off:** still to be confirmed (see "Open" at the end).

## Ownership

| Area | Owner |
|---|---|
| App signup/login (phone OTP, accounts, brand teams, roles) | Backend B. Shreya will guide the auth design. |
| Instagram Login | Backend A (done) |
| Payments, invoices, refunds, payouts, TDS, PAN/bank | Backend B (finance). The AI service never touches money, PAN or bank details. |
| Briefs, shortlists, rebook (screens' APIs, storage, checkout) | Backend B |
| Brief reader, matcher, quality score, rebook nudge | Backend A |

## Events

- `job_events` (Backend B) is the source of truth for operations. Backend A's `events` table is the AI's learning log.
- Sent to `/ai/feedback` over HTTP from the outbox (built 2026-11-15): `job_state_changed`, `job_timer_fired`.
  Still to send: `job_outcome` (with `revisions`, `replaced`), `rating` (with `job_id`), shortlist and brief events.
- **Latest reason code per job wins** in the AI's track record: when ops corrects a code, send the corrected one.

## Working days and clocks

- Monday to Saturday, minus an ops-editable holiday table with a state column (blank = national). Built.
- **Brand-side clocks count working days:** shipping (3 working days, reminder after 2) and **video review
  (5 working days before auto-approval)**. Ops dispute review: 2 working days. Built except video review (still
  120 calendar hours, see Open 1).
- **Creator-side clocks stay calendar hours:** 120-hour shooting clock.
- The shipping clock uses the **brand's pickup state** (needs F1 brand profile; national only until then).

## Product delivery (F6)

**Shipping**
- Ship within **3 working days** of acceptance, reminder after 2; missed → late, the creator may drop out with no
  penalty (`BRAND_LATE_SHIP`). Built.
- "Shipped" requires a courier and a tracking number. Built (free text today; see the courier table below).

**Courier table (2026-11-16, built)** — an ops-editable table, not an enum. Each row has a **tracking-link pattern** so
ops opens tracking in one click. Rows: Delhivery, Blue Dart, DTDC, Ecom Express, Xpressbees, Shadowfax, India Post,
Ekart, Shiprocket (its AWB is fine).
- **Local delivery** (Porter / Rapido / brand's own staff): **no tracking number; a handover photo is required.**
- **Other:** courier name **and** tracking link both required (plus the tracking number, as for every shipment).
- Top "Other" names each month get their own row.

**Duplicate tracking number (2026-11-16, built)** — compare after uppercasing and removing spaces and dashes.

| Same number already used by | Result |
|---|---|
| Same brand, same creator | Allowed (one parcel, two jobs) |
| Same brand, different creator | Allowed, **flagged to ops** |
| Different brand | **Blocked**: "This tracking number is already used. Check it or contact support." |

**Reships (2026-11-16, built)** — **1 reship** goes through normally. The **2nd delivery problem on a job goes to ops
only** (ops reships or releases the creator).

**The creator's three buttons** — Received (with photo, clock starts) · Not received · Damaged / wrong item (both:
`DELIVERY_ISSUE`, clock stopped). Built.

**Damage found after "Received" (2026-11-16, built)**
- Allowed **once per job, within 24 hours** of tapping Received, with a **photo or short video**.
- The job goes back to `DELIVERY_ISSUE` and **the clock stops**.
- If the brand reships: the 120 hours **restart** when the new product is received.
- If ops decides the product is usable: the clock **resumes with the hours that were left**.
- After 24 hours: no self-serve report; the creator goes through ops, and the clock keeps running unless ops pauses it.

**Auto-start** — only on courier "Delivered" with no problem reported in 24 hours. Built.

**Missing parcel** — brand has 48 hours to show tracking or reship; still missing 7 days after "Shipped" → ops,
creator may withdraw with no penalty. **[CONFIRM]** the 7 days against real shipments. Built (calendar time).

**Who decides damage fault (2026-11-16)** — ops picks the **reason code**; the fault party always follows from the
code, never chosen separately (built that way). Guide for ops: outer box crushed or wet → `DAMAGED_IN_TRANSIT`;
outer box fine but product broken, leaking or expired → `DEFECTIVE_PRODUCT`; wrong product or shade →
`WRONG_ITEM_SENT`; can't tell → `OTHER`. Brand-fault damage: the brand reships at its own cost.

**Reason codes**

| Code | Fault | Counts against creator? |
|---|---|---|
| `FALSE_CLAIM` (ops only), `CREATOR_UNRESPONSIVE`, `CREATOR_WITHDREW` | CREATOR | yes |
| `BRAND_LATE_SHIP`, `WRONG_ITEM_SENT`, **`DEFECTIVE_PRODUCT` (new 2026-11-16)** | BRAND | no |
| `COURIER_LOST`, `DAMAGED_IN_TRANSIT` | COURIER | no |
| `OTHER` | NONE | no |

If `OTHER` goes above about 10% in a month, add a code. Ops notes stay in the backend log only.

**Brand ships late and the creator drops out** — free replacement first; if the brand wants money back: the
creator's amount in cash + the Loofie fee as credit for its next booking. **[CONFIRM]** GST with the CA.

## Extension (F9)

- One automatic 48-hour extension if the creator asks before the deadline; any further extension is ops only. Built.

## Approving a video and closing a job

- Only BRAND_ADMIN approves (it releases payment). Team members can view, comment and request revisions.
- Brand silent on a draft: auto-approved after **5 working days**.
- The job closes after the creator is paid; the rating stays open 7 days and never blocks closing.

## Brand cancels after paying (per creator slot)

| When | Refund to brand | Creator |
|---|---|---|
| Before the creator accepts | Full | — |
| After accept, before the product ships | Refund minus the Loofie fee | Open 4 |
| After the product is received, or the shoot has started | No refund | 50% kill fee |

Loofie's own failure (creator ghosts, no replacement): the slot is refunded **including** the fee.
**[CONFIRM]** the numbers in the brand terms and with the CA.

## Refund and cancellation approvals (2026-11-16, answers F5 S3)

Money moves **only after approval**, in two steps: **request → approve**. Nothing is paid at request time.

| Case | Who approves |
|---|---|
| Any refund or cancellation **after a creator has accepted** | An **`OPS_LEAD`**, and **not the person who requested it** |
| Refund of a slot **no creator has accepted yet**, up to ₹10,000 | One `OPS_LEAD` may request and approve |
| A refund **above ₹25,000**, or **more than 3 refunds to one brand in 30 days** | An `OPS_LEAD` **plus the founder** |

- An approved request **cannot be edited**; a change is a new request.
- Every request carries a **reason code**, and the approver sees the brand's refund history.
- **Daily refund summary** to the founder: count, total, who requested, who approved.
- Pilot: with one ops person, the founder is the second approver. Limits are pilot numbers, reviewed after 30 orders.

## Open

1. **Founder sign-off.** Foundation v2 (row 12) says the review window is 120 **calendar** hours and the clock
   auto-starts after the receipt reminder; DECISIONS.md says 5 **working** days and auto-start only on courier
   "Delivered". Who is "the founder" in the approval table, and do they sign these off?
2. **Units:** the 48 hours (brand shows tracking) and the 7 days (missing parcel): calendar or working time?
   (Built as calendar time.)
3. **Fee credit:** Aditya's message calls it "a manual one-time discount"; DECISIONS.md still says "credit for its
   next booking" and the product brief says "no cashback wallet in v1". Which is it, and where is it recorded?
4. **Cancel after accept, before shipping:** does the creator get anything? And the product **shipped but not
   received**: which row applies? Does the brand cancel self-serve or through ops? (Not in DECISIONS.md.)
5. **Refund reason codes:** "every request needs a reason code" — which list? The job codes above, or a refund list?
6. **A paid revision (25%) by a BRAND_MEMBER:** allowed? ("Team members can request revisions" does not say.)
7. **Backend A server:** capacity of `/ai/feedback`, a fixed production URL (today a quick tunnel whose address
   changes on restart), and Core's API key.
