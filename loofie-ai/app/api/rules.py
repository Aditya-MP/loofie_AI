"""Checks every /ai/feedback event must pass, with no database. The real API and the mock server
(app/mock.py) both run these, so the backend can't build against rules the live service doesn't have."""

from datetime import UTC, datetime, timedelta

from fastapi import HTTPException

from app.api.schemas import FeedbackIn
from app.feedback.events import FEEDBACK_TYPES, bad_values, missing_keys

# Brief fields the brand may not change (ASCI disclosure, Foundation v2 F2).
LOCKED_BRIEF_FIELDS = {"disclosure"}
FUTURE_SLACK = timedelta(minutes=5)  # clocks drift; more than this ahead is a bug on the caller's side


def check_feedback(body: FeedbackIn) -> None:
    if body.event_type not in FEEDBACK_TYPES:
        raise HTTPException(422, f"unknown event_type {body.event_type!r}; allowed: {sorted(FEEDBACK_TYPES)}")
    missing = missing_keys(body.event_type, body.payload)
    if missing:
        raise HTTPException(422, f"{body.event_type} needs payload keys: {missing}")
    errors = bad_values(body.event_type, body.payload)
    if errors:
        raise HTTPException(422, f"{body.event_type}: {'; '.join(errors)}")
    if body.occurred_at and body.occurred_at > datetime.now(UTC) + FUTURE_SLACK:
        raise HTTPException(422, "occurred_at is in the future")
    if body.event_type == "brief_field_edited" and body.payload.get("field") in LOCKED_BRIEF_FIELDS:
        raise HTTPException(422, f"{body.payload['field']} is required on every brief and cannot be edited")
