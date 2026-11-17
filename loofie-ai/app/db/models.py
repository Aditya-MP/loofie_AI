"""Tables for the AI service.

Raw data (brands, creators, posts, comments) is what the data team or the Instagram API collected.
Columns prefixed `label_` and the `answer_key` / `brand_creator_links` data are human answer keys
used to test the models; AI outputs go in their own tables and never overwrite them.
"""

import uuid
from datetime import date, datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

EMBED_DIM = 384  # intfloat/multilingual-e5-small; a different model needs a migration


class Base(DeclarativeBase):
    pass


class Timestamps:
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class Brand(Timestamps, Base):
    __tablename__ = "brands"

    id: Mapped[int] = mapped_column(primary_key=True)
    handle: Mapped[str] = mapped_column(String(30), unique=True)
    external_id: Mapped[str | None] = mapped_column(String(32))
    name: Mapped[str | None] = mapped_column(String(200))
    website: Mapped[str | None] = mapped_column(Text)
    category: Mapped[str | None] = mapped_column(String(40), index=True)
    sub_niche: Mapped[str | None] = mapped_column(String(40))
    what_they_sell: Mapped[str | None] = mapped_column(Text)
    product_description: Mapped[str | None] = mapped_column(Text)
    price_min_inr: Mapped[int | None] = mapped_column(Integer)
    price_max_inr: Mapped[int | None] = mapped_column(Integer)
    ad_library_link: Mapped[str | None] = mapped_column(Text)
    running_ads_now: Mapped[bool | None] = mapped_column(Boolean)
    uses_creator_ads: Mapped[bool | None] = mapped_column(Boolean)
    data_source: Mapped[str] = mapped_column(String(40))
    collected_on: Mapped[date | None] = mapped_column(Date)
    collected_by: Mapped[str | None] = mapped_column(String(80))


class Creator(Timestamps, Base):
    __tablename__ = "creators"

    id: Mapped[int] = mapped_column(primary_key=True)
    handle: Mapped[str] = mapped_column(String(30), unique=True)
    external_id: Mapped[str | None] = mapped_column(String(32))
    display_name: Mapped[str | None] = mapped_column(String(200))
    profile_link: Mapped[str | None] = mapped_column(Text)
    city: Mapped[str | None] = mapped_column(String(80), index=True)
    followers: Mapped[int | None] = mapped_column(Integer)
    following: Mapped[int | None] = mapped_column(Integer)
    total_posts: Mapped[int | None] = mapped_column(Integer)
    bio: Mapped[str | None] = mapped_column(Text)
    bio_link: Mapped[str | None] = mapped_column(Text)
    category: Mapped[str | None] = mapped_column(String(40), index=True)
    sub_niche: Mapped[str | None] = mapped_column(String(40))
    category_raw: Mapped[str | None] = mapped_column(Text)
    primary_language: Mapped[str | None] = mapped_column(String(40))
    face_on_camera: Mapped[bool | None] = mapped_column(Boolean)
    speaks_to_camera: Mapped[bool | None] = mapped_column(Boolean)
    last_post_date: Mapped[date | None] = mapped_column(Date)
    posts_last_30d: Mapped[int | None] = mapped_column(Integer)
    public_rate_card: Mapped[bool | None] = mapped_column(Boolean)
    rate_card_link: Mapped[str | None] = mapped_column(Text)
    avg_views_reported: Mapped[int | None] = mapped_column(Integer)
    # NEW | VERIFIED (Foundation v2). Set by the backend from job history; NEW until then.
    level: Mapped[str] = mapped_column(String(20), server_default="NEW")
    label_quality: Mapped[str | None] = mapped_column(String(20))
    label_quality_reason: Mapped[str | None] = mapped_column(Text)
    # {metric: {source: API_VERIFIED | OPS_CHECKED | SELF_REPORTED | LEGACY_IMPORT, captured_on}}
    metric_sources: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    data_source: Mapped[str] = mapped_column(String(40))
    notes: Mapped[str | None] = mapped_column(Text)
    collected_on: Mapped[date | None] = mapped_column(Date)
    collected_by: Mapped[str | None] = mapped_column(String(80))


class CreatorProfile(Base):
    """AI output of the Profile Builder. Rebuilt from raw data at any time; never edited by hand."""

    __tablename__ = "creator_profiles"
    __table_args__ = (
        Index(
            "ix_creator_profiles_embedding",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )

    creator_id: Mapped[int] = mapped_column(ForeignKey("creators.id", ondelete="CASCADE"), primary_key=True)
    version: Mapped[str] = mapped_column(String(160))
    built_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    text_sources: Mapped[list[str]] = mapped_column(ARRAY(String(40)), server_default="{}")
    embedding: Mapped[list[float] | None] = mapped_column(Vector(EMBED_DIM))
    category: Mapped[str | None] = mapped_column(String(40), index=True)
    category_confidence: Mapped[float | None] = mapped_column(Float)
    sub_niche: Mapped[str | None] = mapped_column(String(40))
    sub_niche_confidence: Mapped[float | None] = mapped_column(Float)
    other_niche: Mapped[str | None] = mapped_column(String(40))
    niche_scores: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    niche_unsure: Mapped[bool | None] = mapped_column(Boolean)
    caption_language: Mapped[str | None] = mapped_column(String(40))
    language_shares: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    brands_mentioned: Mapped[list[str]] = mapped_column(ARRAY(String(30)), server_default="{}")
    stats: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    completeness: Mapped[float] = mapped_column(Float)
    missing: Mapped[list[str]] = mapped_column(ARRAY(String(40)), server_default="{}")


class CreatorQuality(Base):
    """AI output of Model 3. The human verdict stays in creators.label_quality."""

    __tablename__ = "creator_quality"

    creator_id: Mapped[int] = mapped_column(ForeignKey("creators.id", ondelete="CASCADE"), primary_key=True)
    version: Mapped[str] = mapped_column(String(80))
    assessed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    label: Mapped[str] = mapped_column(String(20), index=True)
    activity: Mapped[str] = mapped_column(String(20), server_default="unknown")
    score: Mapped[int] = mapped_column(Integer)
    summary: Mapped[str] = mapped_column(Text)
    findings: Mapped[list] = mapped_column(JSONB, server_default="[]")
    signals_used: Mapped[int] = mapped_column(Integer)


class Post(Timestamps, Base):
    __tablename__ = "posts"

    id: Mapped[int] = mapped_column(primary_key=True)
    post_url: Mapped[str] = mapped_column(Text, unique=True)
    external_id: Mapped[str | None] = mapped_column(String(32))
    creator_id: Mapped[int] = mapped_column(ForeignKey("creators.id", ondelete="CASCADE"), index=True)
    post_date: Mapped[date | None] = mapped_column(Date)
    post_type: Mapped[str | None] = mapped_column(String(20))
    views: Mapped[int | None] = mapped_column(Integer)
    likes: Mapped[int | None] = mapped_column(Integer)
    comments_count: Mapped[int | None] = mapped_column(Integer)
    caption: Mapped[str | None] = mapped_column(Text)
    paid_partnership_label: Mapped[bool | None] = mapped_column(Boolean)
    ad_hashtag: Mapped[bool | None] = mapped_column(Boolean)
    brands_tagged: Mapped[list[str]] = mapped_column(ARRAY(String(30)), server_default="{}")
    product_shown: Mapped[bool | None] = mapped_column(Boolean)
    data_source: Mapped[str] = mapped_column(String(40))
    collected_on: Mapped[date | None] = mapped_column(Date)


class BrandCreatorLink(Timestamps, Base):
    """Answer key for the matcher: proven paid collaborations."""

    __tablename__ = "brand_creator_links"
    __table_args__ = (UniqueConstraint("brand_id", "creator_id", "post_url"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    external_id: Mapped[str | None] = mapped_column(String(32))
    brand_id: Mapped[int] = mapped_column(ForeignKey("brands.id", ondelete="CASCADE"), index=True)
    creator_id: Mapped[int] = mapped_column(ForeignKey("creators.id", ondelete="CASCADE"), index=True)
    post_url: Mapped[str] = mapped_column(Text)
    evidence: Mapped[str | None] = mapped_column(String(40))
    post_date: Mapped[date | None] = mapped_column(Date)
    collected_on: Mapped[date | None] = mapped_column(Date)


class Comment(Timestamps, Base):
    __tablename__ = "comments"
    __table_args__ = (UniqueConstraint("post_id", "comment_no"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id", ondelete="CASCADE"), index=True)
    comment_no: Mapped[int] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text)
    collected_on: Mapped[date | None] = mapped_column(Date)


class Brief(Timestamps, Base):
    """origin='dataset' rows carry an answer_key for testing Model 1; origin='app' rows come from brands."""

    __tablename__ = "briefs"

    id: Mapped[int] = mapped_column(primary_key=True)
    external_id: Mapped[str | None] = mapped_column(String(64), unique=True)
    origin: Mapped[str] = mapped_column(String(20))
    brand_handle: Mapped[str | None] = mapped_column(String(30))
    source_type: Mapped[str | None] = mapped_column(String(40))
    source_link: Mapped[str | None] = mapped_column(Text)
    raw_text: Mapped[str] = mapped_column(Text)
    answer_key: Mapped[dict | None] = mapped_column(JSONB)
    written_by: Mapped[str | None] = mapped_column(String(80))


class Event(Base):
    """Append-only log of everything the models learn from (F12). Never update or delete rows."""

    __tablename__ = "events"
    # Track record looks events up by creator (migration 0008).
    __table_args__ = (
        Index("ix_events_payload_creator_ref", text("(payload->>'creator_ref')")),
        Index("ix_events_payload_creator_handle", text("(payload->>'creator_handle')")),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), index=True)
    actor_type: Mapped[str] = mapped_column(String(20))
    actor_id: Mapped[str | None] = mapped_column(String(64))
    subject_type: Mapped[str] = mapped_column(String(30))
    subject_id: Mapped[str] = mapped_column(String(64))
    event_type: Mapped[str] = mapped_column(String(60), index=True)
    payload: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    model_name: Mapped[str | None] = mapped_column(String(40))
    model_version: Mapped[str | None] = mapped_column(String(40))


class ModelVersion(Base):
    """Every trained or tuned model version. Only one per model_name is 'live' at a time."""

    __tablename__ = "model_versions"
    __table_args__ = (UniqueConstraint("model_name", "version"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    model_name: Mapped[str] = mapped_column(String(40))
    version: Mapped[str] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(20))  # candidate | live | retired | rejected
    params: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    metrics: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    promoted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class ImportRun(Base):
    __tablename__ = "import_runs"

    id: Mapped[int] = mapped_column(primary_key=True)
    source: Mapped[str] = mapped_column(Text)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    counts: Mapped[dict] = mapped_column(JSONB, server_default="{}")
    errors: Mapped[list] = mapped_column(JSONB, server_default="[]")
    warnings: Mapped[list] = mapped_column(JSONB, server_default="[]")


class InstagramAccount(Timestamps, Base):
    """A creator's Instagram Login connection (F1). The token is stored encrypted, never in plain text.

    `account_ref` is the backend's creator account id; `ig_user_id` is Instagram's, and stays the
    same when the creator renames their handle.
    """

    __tablename__ = "instagram_accounts"

    id: Mapped[int] = mapped_column(primary_key=True)
    account_ref: Mapped[str] = mapped_column(String(64), unique=True)
    ig_user_id: Mapped[str] = mapped_column(String(40), unique=True)
    app_user_id: Mapped[str | None] = mapped_column(String(40), index=True)  # id in the token response
    creator_id: Mapped[int | None] = mapped_column(ForeignKey("creators.id", ondelete="SET NULL"), unique=True)
    username: Mapped[str] = mapped_column(String(30))
    account_type: Mapped[str | None] = mapped_column(String(30))
    permissions: Mapped[list[str]] = mapped_column(ARRAY(String(60)), server_default="{}")
    token_encrypted: Mapped[str | None] = mapped_column(Text)
    token_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    token_refreshed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # connected → syncing → active; expired / revoked need the creator to log in again; error = last sync failed
    status: Mapped[str] = mapped_column(String(20), index=True)
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_error: Mapped[str | None] = mapped_column(Text)
    business_id: Mapped[str | None] = mapped_column(String(40))  # unified login: the token's business portfolio
    # The sync queue (app/instagram/service.py): due when next_sync_at <= now and no live lease.
    next_sync_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    sync_attempts: Mapped[int] = mapped_column(Integer, server_default="0", default=0)
    lease_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class InstagramSnapshot(Base):
    """Account numbers at every sync: the history that shows bought-follower jumps."""

    __tablename__ = "instagram_snapshots"
    __table_args__ = (Index("ix_instagram_snapshots_account_time", "account_id", "captured_at"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("instagram_accounts.id", ondelete="CASCADE"))
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    followers: Mapped[int | None] = mapped_column(Integer)
    following: Mapped[int | None] = mapped_column(Integer)
    media_count: Mapped[int | None] = mapped_column(Integer)
