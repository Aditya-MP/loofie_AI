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

## Product not received (F6)

- The 120-hour production clock starts only when the creator marks the product as received.
- If the creator reports **not received**:
  1. The brand has 48 hours to share tracking or reship at its own cost.
  2. If there is no tracking, or the product is still missing 7 days after the report, ops steps in.
  3. The creator can then withdraw with no penalty, and that slot is replaced or refunded.
- **Open:** Foundation v2 says that if the creator stays silent, the clock starts automatically 24 hours after the 3-day reminder (`RECEIPT_FALLBACK`). Does that fallback still apply when the creator does not respond at all? Aditya's rule only covers a creator who actively reports "not received".

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
| After accept, before the product ships | Refund minus the Loofie fee | **Open:** does the creator get anything? |
| After the product is received, or the shoot has started | No refund for that slot | 50% kill fee |

- Exact numbers will be confirmed in the brand terms. Finance and tax treatment need the CA.
- **Open:** the product is shipped but not yet received. Which row applies?
- **Open:** does the brand cancel self-serve (BRAND_ADMIN), or by asking ops?

## Approvals

- Only BRAND_ADMIN can approve, because approval releases payment.
- BRAND_MEMBER can view, comment and request revisions.
- An "approver" role may come later.
- **Open:** may a BRAND_MEMBER request a *paid* revision (25%), since that charges the brand?
