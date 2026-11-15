# Loofie: product decisions

What the AI service and the backend team agreed, and why. When a decision changes, edit it here
and add a line to the change log at the bottom. Items marked **[CONFIRM]** are recommendations
still waiting for sign-off (brand terms, CA, or first real campaigns).

## The flow in one paragraph

A creator joins and connects Instagram (F1). A brand pastes rough notes, the AI turns them into
a brief the brand confirms (F2). The matcher builds a shortlist, ops checks it, the brand picks
(F3). The brand pays Loofie up front (F4). Each creator gets the product, shoots, uploads (F5,
F6), the brand approves (F7), and only then is that creator paid (F8). Problems go to disputes
(F9), ad rights are tracked (F13), the brand rebooks (F11). Every step is logged so the models
learn (F12).

## 1. Who builds what

| Area | AI service | Backend |
|---|---|---|
| App signup / login (phone OTP, accounts, brand teams) | no | **yes**, one owner for accounts |
| Instagram Login (OAuth, token storage, refresh, data deletion) | **yes**, done | calls `/ai/instagram/login-url`, shows the button |
| Creator passport numbers, quality score | **yes** | shows them |
| Briefs: screens, storage | no | **yes** |
| Briefs: reading notes into fields, locked disclosure | **yes** (Model 1) | calls `/ai/brief/parse` |
| Shortlists: screens, storage, ops edits | no | **yes** |
| Shortlists: retrieve, rank, re-rank | **yes** (Model 2) | calls `/ai/match` |
| Payments, invoices, payouts, TDS, refunds | **never** | **yes** |
| Jobs, product clock, upload, review screens, disputes, ad rights | no | **yes** |
| Rebook: checkout | no | **yes** |
| Rebook: nudge timing | **yes** (planned) | shows it |
| Events log for AI learning, reports, promote / rollback | **yes** | sends events |

The AI service never receives money, PAN, Aadhaar, bank, UPI, phone or email data. `/ai/feedback`
refuses those keys.

## 2. Event logs

There are **two logs, on purpose**:

- **Backend log:** the source of truth for operations (payments, job states, audit).
- **AI log (`/ai/feedback`):** only what the models learn from. The backend sends a copy of the
  relevant events with its own `event_id` (UUID), so retries are never saved twice.

Events the backend sends: `brief_field_edited`, `brief_confirmed`, `shortlist_shown`,
`shortlist_pick` / `maybe` / `reject` (with reason), `ops_added_creator`, `ops_removed_creator`,
`quality_override`, `niche_override`, `job_state_changed` (every job status change),
`job_outcome` (when a job closes or a creator is replaced), `video_performance`, `rating`,
`rebooked`, `job_timer_fired` (every TimerFired row). Field rules are in [API.md](API.md).

## 3. Creator verification: Instagram Login, not screenshots

- **Decided:** creators tap "Continue with Instagram". The AI service fetches followers, posts
  and views from Meta itself and marks them `API_VERIFIED`.
- **Rejected:** screenshots (easy to fake) and a code in the bio (extra work for the creator,
  and it proves only ownership, not numbers).
- The backend can no longer send numbers as Instagram-verified: anything it sends is
  `SELF_REPORTED`. Trust order: `API_VERIFIED` > `OPS_CHECKED` > `SELF_REPORTED` >
  `LEGACY_IMPORT`.
- Creators type only what Instagram can't know: rate, city, languages.
- Numbers refresh every 7 days by themselves (`loofie instagram maintain`, run daily).
- Only professional accounts (Business or Creator) can connect. Personal accounts get a clear
  message to switch.
- Meta verification (developer app, App Review) costs nothing. Until App Review passes, only
  testers added in the Meta app can log in.

## 4. Money: how it flows and why holding it is legal

**Setup:** Loofie is **the seller** (an agency model). The brand buys a finished video service
from Loofie, gets **one GST invoice** from Loofie, and Loofie pays each creator as an
independent contractor.

```
Brand pays the full amount ──► Loofie's company current account
                                     │
                 video approved? ────┤
                   yes               │            no (creator ghosted, no replacement)
                    ▼                │               ▼
      creator paid (minus TDS)       │      that slot refunded to the brand
```

Example: 3 creators at ₹5,000, Loofie fee 15%:
`(15,000 + 2,250) × 1.18 GST = ₹20,355`. If one slot fails, the refund is that slot's share:
`(5,000 + 750) × 1.18 = ₹6,785`.

Rules:

- **Never a personal account.** Money goes to a Pvt Ltd / LLP current account with a GSTIN.
  A personal account makes every payment look like personal income, and brands need a
  business invoice.
- **Holding the money is legal in this setup**: it is Loofie's revenue (an advance for a
  service), and paying the creator after delivery is normal subcontracting. No RBI licence is
  needed.
- **The other setup (Loofie as a marketplace holding the creator's money) is not allowed
  without an RBI-licensed payment aggregator** (for example Razorpay Route or Cashfree split
  settlements, which keep the money in escrow). Switching to it would also bring marketplace
  tax rules (TCS under GST section 52, TDS under 194-O).
- **Keep brand money aside.** Legally it is Loofie's, but it is owed to creators or as refunds.
  Keep it in a separate account and don't spend it.
- **GST** is due when the payment arrives, on the full amount. A refund comes with a **credit
  note**. **[CONFIRM]** with the CA.
- **TDS** is cut only when a creator is actually paid, so a refunded slot needs no TDS change.
  The section (194J or 194C) is the CA's call. **[CONFIRM]**
- **Risk Loofie accepts:** if a brand disputes after a creator is paid, Loofie absorbs it. The
  brand terms need a dispute window.

## 5. Job rules

| Situation | Decision |
|---|---|
| **Brand ships on time** | The brand must ship within **3 working days** of the creator accepting. Reminder after 2 days. Missed → the job is marked late, and the creator may drop out with no penalty. |
| **Working days** | **Monday to Saturday** (couriers pick up Mon–Sat, not on public holidays), minus a **holiday table ops can edit** with a `state` column: blank = national (26 Jan, 15 Aug, 2 Oct, Diwali, Holi), a state code = that state only (e.g. Karnataka Rajyotsava 1 Nov, Durga Puja in West Bengal, Onam in Kerala, Pongal in Tamil Nadu). The shipping clock uses the **brand's pickup state**. |
| **Which clocks use working days** | **Brand-side clocks** (3-day shipping, 5-day video review before auto-approval) count working days: brands are offices. **The creator's shooting clock** stays **120 calendar hours**: creators often shoot on Sundays. |
| **Brand ships late, creator drops out** | First offer a **free replacement creator** (full value kept). If the brand wants money back: **the creator's amount in cash + the Loofie fee as credit** for its next booking. Loofie keeps the revenue, the brand doesn't feel penalised, and it has a reason to book again. The creator's reliability is not affected (`BRAND_LATE_SHIP`). **[CONFIRM]** GST treatment of the credit with the CA. |
| **"Shipped" needs proof** | The brand can't click "Shipped" without a **courier name and tracking number**. That's what settles every later "not received" dispute. |
| **Courier list** | A **table ops can edit** (like holidays), not hard-coded: Delhivery, Blue Dart, DTDC, Ecom Express, Xpressbees, Shadowfax, India Post, Ekart, Shiprocket (aggregator: its AWB is fine), **Local delivery** (Porter / Rapido / own staff, for brands in the creator's city: no tracking number, a **handover photo** instead), and **Other** (courier name + tracking link both required, so ops can still check it). Each row stores a tracking-link pattern, so ops opens tracking in one click and a tracking service can plug in later. Top "Other" names each month get their own row. |
| **Duplicate tracking number** | Compare after cleaning (uppercase, no spaces or dashes). **Same brand, same creator:** allowed (one parcel for two jobs). **Same brand, different creator:** flag to ops, don't block (usually a copy-paste mistake). **Different brand:** block with "This tracking number is already used. Check it or contact support" (a typo or fraud; one parcel can't go to two brands' creators). |
| **Product clock** | The 5-day (120-hour) shooting clock starts when the creator taps **"Received"** with a photo of the product. Digital-only briefs (no product) skip the clock. |
| **Creator doesn't tap anything** | The clock starts by itself **only when the courier shows "Delivered"** and the creator hasn't reported a problem within 24 hours. Without courier proof it never starts by itself: the creator is reminded, then ops checks. A slow courier to a small town never makes the creator late. |
| **Creator's three buttons** | **Received** (clock starts) · **Not received** (clock stays stopped, brand asked for tracking) · **Damaged / wrong item** (clock stays stopped, brand asked to reship). |
| **Damage found after "Received"** | Allowed **once per job, within 24 hours** of tapping Received (a leaking pump or a broken seal often shows only when the box is opened properly). Needs a photo or a short video. The job goes back to `DELIVERY_ISSUE`, **the clock stops**, and the time already used is not lost: if the brand reships, the 120 hours start again when the new product is received; if ops decides it's usable, the clock resumes with the hours that were left. After 24 hours: no self-serve report; the creator raises it with ops, and the clock keeps running unless ops pauses it. |
| **Reships** | **1 reship** goes through normally. The 2nd delivery problem on the same job goes to **ops only** (ops reships or releases the creator). Each reship adds about a week for the creator, and a second problem usually means a packing or address issue a person should look at. |
| **Who decides damage fault** | Ops never picks a fault party directly: **ops picks the reason code and the fault follows from the table below**, so they can't contradict each other. Guide for ops: outer box crushed or wet in the receipt photo → `DAMAGED_IN_TRANSIT` (COURIER); outer box fine but the product is broken, leaking, expired or badly packed → `DEFECTIVE_PRODUCT` (BRAND); wrong product or shade → `WRONG_ITEM_SENT` (BRAND); can't tell → `OTHER` (NONE). Brand-fault damage: the brand reships at its own cost. |
| **Job state for problems** | One state, `DELIVERY_ISSUE`, with `issue_type`: `NOT_RECEIVED` / `DAMAGED` / `WRONG_ITEM`. The clock is stopped while a job is in it. |
| **Reason codes** | Disputes and drop-outs carry a **reason code, never free text**, in `job_state_changed` as `reasonCode`, with its fixed **fault party** as `faultParty` (table below; field names match core-service). Ops notes stay in the backend log only (free text can hold personal details). New codes: add them here first. If `OTHER` is above ~10% in a month, add a code. |
| **Who sets `FALSE_CLAIM`** | **Ops only**, never the system: it accuses a creator of lying. |

| Reason code | Fault | Counts against creator reliability? |
|---|---|---|
| `FALSE_CLAIM` | CREATOR | yes |
| `CREATOR_UNRESPONSIVE` | CREATOR | yes |
| `CREATOR_WITHDREW` | CREATOR | yes |
| `BRAND_LATE_SHIP` | BRAND | no |
| `WRONG_ITEM_SENT` | BRAND | no |
| `DEFECTIVE_PRODUCT` | BRAND | no |
| `COURIER_LOST` | COURIER | no |
| `DAMAGED_IN_TRANSIT` | COURIER | no |
| `OTHER` | NONE | no |

| Situation | Decision |
|---|---|
| **Product not received** | The brand gets 48 hours to show tracking or reship at its own cost. Still missing after 7 days from "Shipped" → ops steps in, the creator may withdraw with no penalty, the slot is replaced or refunded. **[CONFIRM]** the 7 days against the first real shipments. |
| **False "not received"** | Courier "Delivered" (plus the delivery OTP where the courier uses one) is the proof. Ops decides. A creator found making a false claim gets a `job_state_changed` event with the reason, which lowers their reliability score. |
| **How tracking is checked** | **Pilot:** ops opens the tracking link by hand. **From about 50 jobs a month:** a multi-courier tracking service (for example Ship24, about $9/month for 200 shipments) follows the brand's own tracking numbers and tells us on "Delivered". Brands keep their own courier; nothing to switch. **[CONFIRM]** accuracy with 5 real Indian tracking numbers first. |
| **Deadline extension** | The system gives one 48-hour extension automatically if the creator asks before the deadline. Any further extension is ops only. |
| **Creator ghosts** | After the deadline plus the extension, the job becomes `REPLACEMENT_NEEDED`. Ops offers the next creator from the shortlist. No one can deliver within 10 days → refund for that slot. |
| **Brand silent on a draft** | Auto-approved after 5 working days, so creators never wait forever. |
| **Who approves a video** | The brand admin only, for now, because approval releases payment. Team members can view, comment and request revisions. An "approver" role may come later. |
| **When a job closes** | After the creator is paid. The rating stays open for 7 days but doesn't block closing. The backend sends `job_outcome` on close and `rating` whenever it arrives. |

## 6. Brand cancels after paying

Refunds are worked out **per creator slot**, not per campaign:

| When the brand cancels | Refund for that slot |
|---|---|
| Before the creator accepts | Full refund |
| After acceptance, before the product ships | Refund minus the Loofie fee |
| After the product is received or the shoot starts | No refund; the creator is paid a 50% kill fee |

**[CONFIRM]** the exact numbers in the brand terms and with the CA.

The fee rule for **Loofie's own failure** (creator ghosts, no replacement): the brand gets the
slot back **including** Loofie's fee. Keeping the fee when Loofie didn't deliver would make
brands feel cheated.

### Who approves money going out (refunds and cancellations)

Two steps, **request → approve**, and the money moves only after the approval. Nothing is paid
out when the request is made, and an approved request can't be edited (a change means a new request).

| Case | Who may approve |
|---|---|
| Any refund or cancellation **after a creator has accepted** (someone loses money or work) | `OPS_LEAD`, and **not the person who made the request** |
| Refund of a slot **no creator has accepted yet** (full refund, nobody else affected), up to ₹10,000 | One `OPS_LEAD` may request and approve it |
| Any refund **above ₹25,000** in one request, or more than 3 refunds to one brand in 30 days | Two people: an `OPS_LEAD` plus the founder |

- **Pilot reality:** if there's only one ops person, the founder is the second approver. Keep it;
  it takes a minute on a phone and stops the most common loss: a mistaken or social-engineered
  refund ("the brand called and asked").
- Every request carries a **reason code** and shows the brand's refund history to the approver.
- **Daily refund summary** to the founder: count, total, who requested, who approved.
- The ₹10,000 / ₹25,000 limits are pilot numbers: review them after the first 30 orders.

## 7. Status

| | State |
|---|---|
| Model 1 brief reader, Model 2 matcher, Model 3 quality, profile builder | Built, tested |
| Events log, report, promote / rollback | Built, tested |
| Instagram Login | Built, tested against a fake Meta server; needs a real tester login |
| Named API keys with rotation, feedback contract (`event_id`, `occurred_at`, `creator_ref`) | Built, tested |
| F2 brief checks (too few matches, price sanity, risky claims) | Next |
| F7 draft checker (ticks the video checklist from speech and on-screen text, on our GPU) | Planned |
| Reliability score | Planned; needs `job_outcome` events |
| Rebook nudge | Planned; needs real campaign results |
| Automatic retraining | After real feedback exists |
| Real data test | Waiting for the creator sheet |
| Public server with HTTPS | Not set up; Instagram Login needs it |

Outside the code: company registration, current account, GST, brand and creator terms, privacy
policy (needed for Meta App Review), one hour with a CA on refunds and TDS.

## Change log

- 2026-11-15: Courier table (+ local delivery, Other), duplicate tracking rules, 1 reship then
  ops, damage report within 24 h of receipt, ops picks codes not fault parties
  (`DEFECTIVE_PRODUCT` added), refund approvals (request → approve, four-eyes), `job_timer_fired`.
- 2026-11-14: State holidays, working days only for brand-side clocks, fault party per reason
  code (+ `OTHER`, ops-only `FALSE_CLAIM`), late-ship refund as cash + fee credit.
- 2026-11-14: Working days Mon–Sat minus a holiday table, DELIVERY_ISSUE state with issue_type,
  reason codes, late-ship refund rule.
- 2026-11-14: Shipping rules: brand ships in 3 working days, tracking number required, clock
  auto-starts only on courier "Delivered", damaged/wrong-item button, tracking service plan.
- 2026-11-14: First version: ownership split, two event logs, Instagram Login, agency money
  model, job rules, cancellation refunds, approvals.
