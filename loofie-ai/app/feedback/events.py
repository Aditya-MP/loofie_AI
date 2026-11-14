"""The events log (F12): append-only record of every AI output and every human reaction to it.
The learning step trains only on what is written here."""

import uuid

from fastapi.encoders import jsonable_encoder
from sqlalchemy.orm import Session

from app.db.models import Event

# Feedback the backend may send, and the payload keys each one must carry.
FEEDBACK_TYPES: dict[str, set[str]] = {
    "brief_field_edited": {"field", "old", "new"},  # brand changed what Model 1 filled in
    "brief_confirmed": set(),  # brand submitted the brief form
    "shortlist_shown": {"creator_handles"},  # what the brand actually saw (after ops edits)
    "shortlist_pick": {"creator_handle"},
    "shortlist_maybe": {"creator_handle"},
    "shortlist_reject": {"creator_handle", "reason"},
    "ops_added_creator": {"creator_handle"},  # ops added someone the matcher didn't suggest
    "ops_removed_creator": {"creator_handle", "reason"},
    "quality_override": {"creator_handle", "label"},  # ops disagreed with Model 3
    "niche_override": {"creator_handle", "sub_niche"},
    "job_outcome": {"creator_handle", "on_time"},  # delivered, late, replaced...
    "rating": {"creator_handle", "stars"},
    "rebooked": {"creator_handle"},
}


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
) -> uuid.UUID:
    event = Event(
        id=uuid.uuid4(),
        actor_type=actor_type,
        actor_id=actor_id,
        subject_type=subject_type,
        subject_id=subject_id,
        event_type=event_type,
        payload=jsonable_encoder(payload or {}),
        model_name=model_name,
        model_version=model_version,
    )
    session.add(event)
    return event.id
