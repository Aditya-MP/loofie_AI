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
`rebooked`. Field rules are in [API.md](API.md).

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
| **Product clock** | The brand clicks "Shipped" (courier tracking number optional). The 5-day (120-hour) shooting clock starts when the creator taps "Product received" with a photo. Fallback: no confirmation 3 days after "Shipped" → the creator is asked to confirm or report non-delivery; still no answer 24 hours later → the clock starts by itself and ops is alerted. Digital-only briefs (no product) skip the clock. Automatic courier tracking (Shiprocket, read-only on the brand's own tracking numbers) is Phase 2, built only when brands ask for it. |
| **Product not received** | The brand gets 48 hours to share tracking or reship at its own cost. No tracking, or still missing after 7 days → ops steps in, the creator may withdraw with no penalty, the slot is replaced or refunded. **[CONFIRM]** the 7 days against real shipping times. |
| **Deadline extension** | The system gives one 48-hour extension automatically if the creator asks before the deadline. Any further extension is ops only. |
| **Creator ghosts** | After the deadline plus the extension, the job becomes `REPLACEMENT_NEEDED`. Ops offers the next creator from the shortlist. No one can deliver within 10 days → refund for that slot. |
| **Brand silent on a draft** | Auto-approved after 5 days, so creators never wait forever. |
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

- 2026-11-14: First version: ownership split, two event logs, Instagram Login, agency money
  model, job rules, cancellation refunds, approvals.
