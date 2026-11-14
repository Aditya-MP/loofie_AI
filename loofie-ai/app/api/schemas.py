"""Request and response shapes. This file is the contract with the backend team: change it only
together with docs/API.md."""

from datetime import date, datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, Field

# ------------------------------------------------------------------ brief


class BriefParseIn(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    as_of: date | None = Field(
        None, description="Date the brief was written; defaults to today. Used for 'by 5th dec'."
    )
    brief_ref: str | None = Field(None, description="Backend's brief id, stored with the event")


class FieldOut(BaseModel):
    value: Any = None
    confidence: Literal["high", "low", "fixed"] | None = None
    evidence: str | None = Field(None, description="The words in the text the value was read from")


class BriefParseOut(BaseModel):
    version: str
    event_id: UUID
    fields: dict[str, FieldOut]


# ------------------------------------------------------------------ creators


class PostIn(BaseModel):
    post_url: str
    post_date: date | None = None
    post_type: Literal["reel", "photo", "carousel"] | None = None
    views: int | None = Field(None, ge=0)
    likes: int | None = Field(None, ge=0)
    comments: int | None = Field(None, ge=0)
    caption: str | None = Field(None, max_length=5000)
    paid_partnership_label: bool | None = None
    brands_tagged: list[str] = []


class CreatorIn(BaseModel):
    handle: str
    creator_ref: str | None = Field(None, description="Backend's creator id")
    # Only the AI service's own Instagram fetch (/auth/instagram/*) may mark numbers API_VERIFIED;
    # numbers the backend sends are what the creator typed in.
    source: Literal["manual_profile"] = "manual_profile"
    display_name: str | None = None
    city: str | None = None
    followers: int | None = Field(None, ge=0)
    following: int | None = Field(None, ge=0)
    total_posts: int | None = Field(None, ge=0)
    bio: str | None = Field(None, max_length=1000)
    bio_link: str | None = None
    primary_language: Literal["English", "Kannada", "Hindi", "Tamil", "Telugu", "Malayalam", "Other"] | None = None
    level: Literal["NEW", "VERIFIED"] | None = None
    posts: list[PostIn] = Field([], max_length=50, description="Latest posts, newest first is fine but not required")


class QualityOut(BaseModel):
    label: Literal["real", "unsure", "suspicious"]
    activity: Literal["active", "slow", "inactive", "unknown"]
    score: int
    summary: str
    findings: list[dict]
    version: str


class ProfileOut(BaseModel):
    handle: str
    creator_ref: str | None
    category: str | None
    sub_niche: str | None
    niche_confidence: float | None
    niche_unsure: bool | None
    caption_language: str | None
    brands_mentioned: list[str]
    stats: dict
    completeness: float
    missing: list[str]
    version: str
    quality: QualityOut | None
    metric_sources: dict[str, dict] = Field(
        {}, description="Per metric: {source: API_VERIFIED | OPS_CHECKED | SELF_REPORTED | LEGACY_IMPORT, captured_on}"
    )
    update_notes: list[str] = Field(
        [], description="Values in this update that were not saved because a more trusted one is stored"
    )


# ------------------------------------------------------------------ match


class MatchIn(BaseModel):
    text: str = Field(min_length=1, max_length=5000, description="Product description or the confirmed brief text")
    category: Literal["beauty_skincare", "personal_care"] | None = None
    sub_niche: str | None = None
    city: str | None = Field(None, description="None or 'any' = anywhere")
    languages: list[str] = []
    budget_per_creator_inr: int | None = Field(None, gt=0)
    min_followers: int | None = Field(None, ge=0)
    max_followers: int | None = Field(None, ge=0)
    exclude_handles: list[str] = Field([], description="Already picked or already rejected for this brief")
    top_k: int = Field(10, ge=1, le=50)
    brief_ref: str | None = None
    brand_handle: str | None = Field(
        None, description="The brand's Instagram handle; enables the competitor-conflict rule"
    )


class MatchItem(BaseModel):
    rank: int
    handle: str
    creator_ref: str | None
    score: float
    why: str
    parts: dict[str, float]
    estimated_rate_inr: dict[str, int] | None = Field(
        None, description="{low, high}: an estimate from public numbers, not a quote"
    )
    quality_label: str | None
    activity: str | None
    rank_before_rerank: int | None = Field(
        None, description="Position the ranker alone gave; differs when a business rule moved it"
    )
    rerank_note: str | None = Field(None, description="Why a business rule moved this creator (show to ops)")


class MatchOut(BaseModel):
    version: str
    suggestion_id: UUID = Field(description="Send this back with every feedback event about this shortlist")
    results: list[MatchItem]
    left_out: dict[str, int]


# ------------------------------------------------------------------ feedback


class FeedbackIn(BaseModel):
    event_type: str
    actor_type: Literal["brand", "creator", "ops", "system"]
    actor_id: str | None = None
    subject_type: Literal["brief", "creator", "shortlist", "job"]
    subject_id: str
    suggestion_id: UUID | None = Field(None, description="The suggestion_id or event_id this reacts to")
    payload: dict = {}
    event_id: UUID | None = Field(
        None, description="Your own id for this event. Sending the same id again (a retry) is ignored."
    )
    occurred_at: AwareDatetime | None = Field(
        None, description="When it happened, with a timezone. Default: when the AI service received it."
    )


class FeedbackOut(BaseModel):
    event_id: UUID
    duplicate: bool = Field(False, description="True: this event_id was already saved; nothing new was stored")


# ------------------------------------------------------------------ Instagram Login


class InstagramLoginOut(BaseModel):
    url: str = Field(description="Send the creator's browser here; valid for a few minutes")
    expires_in: int


class InstagramAccountOut(BaseModel):
    account_ref: str
    handle: str
    status: Literal["connected", "syncing", "active", "error", "expired", "revoked"]
    insights_allowed: bool = Field(description="False: views/reach can't be fetched (creator didn't allow it)")
    token_expires_at: datetime | None
    last_synced_at: datetime | None
    last_error: str | None
