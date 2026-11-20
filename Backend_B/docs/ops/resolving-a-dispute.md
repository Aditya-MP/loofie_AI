# Resolving a dispute and replacing a creator (F9) — for ops

## A brand rejected a video (`DISPUTED`)

You have **2 working days** (`DISPUTE_REVIEW_OVERDUE` reaches the ops inbox when they run out).

1. Find the queue: `GET /api/core/ops/jobs?status=DISPUTED` (add `&overdue=true` for the late ones).
2. Read both sides:
   - the videos, the brand's note and its checklist answers: `GET /api/core/ops/jobs/{id}/drafts`; open a video with
     a reason: `POST /api/core/ops/jobs/{id}/drafts/{draftId}/open`;
   - the creator's statement (she may send one, `DISPUTE_STATEMENT_ADDED` tells you): `GET /api/core/ops/jobs/{id}/dispute`.
3. Decide against the checklist, never on taste:
   - **The video meets the brief**: `RESOLVE_APPROVE` (`POST /api/core/jobs/{id}/transitions`, with a reason). The
     creator is paid as for any approval; both sides are told.
   - **It does not, and a new creator should make it**: `RESOLVE_REPLACE` with a fault reason code. The creator is
     told; the brand learns a replacement is coming within 10 days.
   - **The brand should get its money back**: a refund request (`POST /api/core/ops/jobs/{id}/refund-requests`,
     action `RESOLVE_REFUND`, with the fault reason code). It runs once approved (refund rules).

The reason code goes into the creator's track record at Backend A. If it was wrong, correct it later with
`OPS_CORRECT_REASON`.

## A job has no creator (`DECLINED` or `REPLACEMENT_NEEDED`)

The brand paid, so the **10-day promise** runs: after 10 days without a creator the job is refunded in full (the
system asks for the refund; an ops lead approves it). `REPLACEMENT_NEEDED` in the ops inbox tells you when it starts.

1. Pick the next creator (Backend A's shortlist, or by hand). She must be **bookable** (complete, approved, listed,
   account active), **never on this job before**, and her **rate at or below the job's creator fee**.
2. Offer her: `POST /api/core/ops/jobs/{id}/swap-offers` `{creatorId, note, suggestionId?}`. One open offer at a
   time; the note stays inside Loofie. The brand gets `SWAP_OFFERED`.
3. The brand admin approves (she is invited at once, with the usual 48 h to accept) or declines with a reason
   (`SWAP_DECLINED` reaches you; offer someone else). Withdraw an offer yourself with
   `POST /api/core/ops/jobs/{id}/swap-offers/withdraw` `{reason}`.
4. If the brand would rather have the money back now: a refund request with `RESOLVE_REFUND`.

The replacement is paid the job's creator fee, whatever her own rate (F9 decision 1).

## A creator missed the production deadline

The system moves the job to `REPLACEMENT_NEEDED` and drops the creator to **NEW** level (audited, she is told).
Raise her again only with a reason: `PUT /api/core/ops/creators/{id}/level`.
