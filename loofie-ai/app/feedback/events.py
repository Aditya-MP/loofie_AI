"""The events log (F12): append-only record of every AI output and every human reaction to it.
The learning step trains only on what is written here."""

import uuid
from datetime import datetime

from fastapi.encoders import jsonable_encoder
from sqlalchemy.orm import Session

from app.db.models import Event

# Stands for "creator_ref (the backend's id) or creator_handle": either one identifies the creator.
CREATOR = "creator_ref|creator_handle"

# Feedback the backend may send, and the payload keys each one must carry.
FEEDBACK_TYPES: dict[str, set[str]] = {
    "brief_field_edited": {"field", "old", "new"},  # brand changed what Model 1 filled in
    "brief_confirmed": set(),  # brand submitted the brief form
    "shortlist_shown": {"creator_handles"},  # what the brand actually saw (after ops edits)
    "shortlist_pick": {CREATOR},
    "shortlist_maybe": {CREATOR},
    "shortlist_reject": {CREATOR, "reason"},
    "ops_added_creator": {CREATOR},  # ops added someone the matcher didn't suggest
    "ops_removed_creator": {CREATOR, "reason"},
    "quality_override": {CREATOR, "label"},  # ops disagreed with Model 3
    "niche_override": {CREATOR, "sub_niche"},
    # Every job status change (invited, accepted, product received, draft submitted, revision,
    # approved, disputed, replaced...): what the reliability model and rebook timing learn from.
    "job_state_changed": {CREATOR, "job_id", "from", "to"},
    "job_outcome": {CREATOR, "job_id", "on_time"},  # when a job closes or the creator is replaced
    "video_performance": {CREATOR, "job_id", "result"},  # brand's verdict ~30 days after approval
    "rating": {CREATOR, "stars"},
    "rebooked": {CREATOR},
}
# Dispute and drop-out codes with their fixed fault party (docs/DECISIONS.md section 5; the same
# list as core-service ReasonCode.java). The reliability score counts only CREATOR codes, so a
# code sent with the wrong fault party would blame the wrong person: refused.
REASON_FAULT: dict[str, str] = {
    "FALSE_CLAIM": "CREATOR",
    "CREATOR_UNRESPONSIVE": "CREATOR",
    "CREATOR_WITHDREW": "CREATOR",
    "BRAND_LATE_SHIP": "BRAND",
    "WRONG_ITEM_SENT": "BRAND",
    "COURIER_LOST": "COURIER",
    "DAMAGED_IN_TRANSIT": "COURIER",
    "OTHER": "NONE",
}
# Fixed vocabularies inside payloads (checked only when the key is present).
PAYLOAD_CHOICES: dict[str, dict[str, set]] = {
    "video_performance": {"result": {"winner", "average", "loser"}},
    "job_outcome": {"on_time": {True, False}},
    "job_state_changed": {
        "reasonCode": set(REASON_FAULT),
        "faultParty": set(REASON_FAULT.values()),
        "issue_type": {"NOT_RECEIVED", "DAMAGED", "WRONG_ITEM"},
    },
}
# The models never need money or identity documents; refuse them so they can't leak in by accident.
FORBIDDEN_KEYS = {"pan", "aadhaar", "bank_account", "ifsc", "upi_id", "phone", "email", "payout_amount"}


def missing_keys(event_type: str, payload: dict) -> list[str]:
    out = []
    for key in FEEDBACK_TYPES[event_type]:
        if key == CREATOR:
            if not (payload.get("creator_ref") or payload.get("creator_handle")):
                out.append("creator_ref (or creator_handle)")
        elif key not in payload:
            out.append(key)
    return sorted(out)


def bad_values(event_type: str, payload: dict) -> list[str]:
    errors = [
        f"{k} must be one of {sorted(map(str, ok))}"
        for k, ok in PAYLOAD_CHOICES.get(event_type, {}).items()
        if k in payload and payload[k] not in ok
    ]
    code, fault = payload.get("reasonCode"), payload.get("faultParty")
    if code in REASON_FAULT and fault is not None and fault != REASON_FAULT[code]:
        errors.append(f"reasonCode {code} has faultParty {REASON_FAULT[code]}, not {fault}")
    if code in REASON_FAULT and fault is None:
        errors.append("reasonCode needs its faultParty (reliability counts only CREATOR codes)")
    leaked = sorted(FORBIDDEN_KEYS & {k.lower() for k in payload})
    if leaked:
        errors.append(f"don't send personal or payment data: {leaked}")
    return errors


def log_event(
    session: Session,
    *,
    event_type: str,
    actor_type: str,
    subject_type: str,
    subject_id: str,
    actor_id: str | None = None,
    payload: dict | None = None,
    model_name: str | None = None,
    model_version: str | None = None,
    event_id: uuid.UUID | None = None,
    occurred_at: datetime | None = None,
) -> uuid.UUID:
    event = Event(
        id=event_id or uuid.uuid4(),
        actor_type=actor_type,
        actor_id=actor_id,
        subject_type=subject_type,
        subject_id=subject_id,
        event_type=event_type,
        payload=jsonable_encoder(payload or {}),
        model_name=model_name,
        model_version=model_version,
    )
    if occurred_at is not None:
        event.occurred_at = occurred_at
    session.add(event)
    return event.id
