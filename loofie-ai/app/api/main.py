"""Loofie AI service. Run: uvicorn app.api.main:app --port 8100

The backend calls these endpoints with the X-API-Key header. Interactive docs at /docs.
"""

import hmac
from contextlib import asynccontextmanager
from datetime import date
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.api.schemas import (
    BriefParseIn,
    BriefParseOut,
    CreatorIn,
    FeedbackIn,
    FeedbackOut,
    FieldOut,
    MatchIn,
    MatchItem,
    MatchOut,
    ProfileOut,
    QualityOut,
)
from app.brief.extract import Field, brief_config, parse
from app.config import get_settings
from app.db.models import Creator, CreatorProfile, CreatorQuality, Event, Post
from app.db.session import SessionLocal
from app.feedback.events import FEEDBACK_TYPES, log_event
from app.importer import parse as p
from app.importer import provenance
from app.importer.upsert import upsert
from app.matcher.features import BriefQuery, matcher_config
from app.matcher.service import match as run_match
from app.profile.builder import build_and_save
from app.profile.niche import ZeroShotNiche
from app.quality.rules import quality_config
from app.quality.service import assess_and_save

# Brief fields the brand may not change (ASCI disclosure, Foundation v2 F2).
LOCKED_BRIEF_FIELDS = {"disclosure"}


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load the model onto the GPU once, not per request. Tests put a fake embedder here first.
    if getattr(app.state, "embedder", None) is None:
        from app.ml.embed import get_embedder

        app.state.embedder = get_embedder()
    app.state.niche = ZeroShotNiche(app.state.embedder)
    yield


app = FastAPI(title="Loofie AI", version="1", lifespan=lifespan)


def get_session():
    with SessionLocal() as session:
        yield session


SessionDep = Annotated[Session, Depends(get_session)]


def require_key(x_api_key: str = Header(default="")) -> None:
    expected = get_settings().ai_api_key
    if not expected:
        raise HTTPException(503, "AI_API_KEY is not configured on the AI service")
    if not hmac.compare_digest(x_api_key.encode(), expected.encode()):
        raise HTTPException(401, "missing or wrong X-API-Key")


def clean_handle(raw: str) -> str:
    try:
        h = p.handle(raw)
    except p.ParseError as e:
        raise HTTPException(422, str(e)) from e
    if h is None:
        raise HTTPException(422, "handle is required")
    return h


# ------------------------------------------------------------------ health


@app.get("/health")
def health(request: Request, session: SessionDep):
    session.execute(text("SELECT 1"))
    return {
        "status": "ok",
        "device": request.app.state.embedder.device if hasattr(request.app.state.embedder, "device") else "unknown",
        "versions": {
            "brief": brief_config()["version"],
            "quality": quality_config()["version"],
            "matcher": matcher_config()["version"],
        },
    }


# ------------------------------------------------------------------ Model 1


@app.post("/ai/brief/parse", response_model=BriefParseOut, dependencies=[Depends(require_key)])
def brief_parse(body: BriefParseIn, request: Request, session: SessionDep):
    bp = parse(
        body.text,
        as_of=body.as_of or date.today(),
        niche_model=request.app.state.niche,
        embedder=request.app.state.embedder,
    )
    fields = {k: FieldOut(**v.__dict__) for k, v in bp.__dict__.items() if isinstance(v, Field)}
    event_id = log_event(
        session,
        event_type="brief_parsed",
        actor_type="system",
        subject_type="brief",
        subject_id=body.brief_ref or "unknown",
        payload={"text": body.text, "fields": {k: f.model_dump() for k, f in fields.items()}},
        model_name="brief",
        model_version=bp.version,
    )
    session.commit()
    return BriefParseOut(version=bp.version, event_id=event_id, fields=fields)


# ------------------------------------------------------------------ Profile Builder + Model 3


def save_creator(session: Session, body: CreatorIn) -> tuple[int, list[str]]:
    h = clean_handle(body.handle)
    row = dict(
        handle=h,
        external_id=body.creator_ref,
        display_name=body.display_name,
        profile_link=f"https://www.instagram.com/{h}/",
        city=body.city,
        followers=body.followers,
        following=body.following,
        total_posts=body.total_posts,
        bio=body.bio,
        bio_link=body.bio_link,
        primary_language=body.primary_language,
        data_source=body.source,
        collected_on=date.today(),
    )
    if body.level:
        row["level"] = body.level
    notes = provenance.apply(session, [row])  # a weaker source never overwrites a stronger one
    upsert(session, Creator, [row], ["handle"])
    creator_id = session.scalar(select(Creator.id).where(Creator.handle == h))

    posts = {}
    for post in body.posts:
        try:
            url = p.post_url(post.post_url)
            tagged = p.handles(",".join(post.brands_tagged))
        except p.ParseError as e:
            raise HTTPException(422, f"post {post.post_url!r}: {e}") from e
        posts[url] = dict(
            post_url=url,
            creator_id=creator_id,
            post_date=post.post_date,
            post_type=post.post_type,
            views=post.views,
            likes=post.likes,
            comments_count=post.comments,
            caption=post.caption,
            paid_partnership_label=post.paid_partnership_label,
            brands_tagged=tagged,
            data_source=body.source,
        )
    if posts:
        upsert(session, Post, list(posts.values()), ["post_url"])
    return creator_id, notes


def profile_out(session: Session, handle: str, notes: list[str] | None = None) -> ProfileOut:
    row = session.execute(
        select(Creator, CreatorProfile, CreatorQuality)
        .join(CreatorProfile, CreatorProfile.creator_id == Creator.id)
        .outerjoin(CreatorQuality, CreatorQuality.creator_id == Creator.id)
        .where(Creator.handle == handle)
    ).first()
    if row is None:
        raise HTTPException(404, f"no profile for @{handle}")
    c, prof, q = row
    return ProfileOut(
        handle=c.handle,
        creator_ref=c.external_id,
        category=prof.category,
        sub_niche=prof.sub_niche,
        niche_confidence=prof.sub_niche_confidence or prof.category_confidence,
        niche_unsure=prof.niche_unsure,
        caption_language=prof.caption_language,
        brands_mentioned=prof.brands_mentioned or [],
        stats=prof.stats or {},
        completeness=prof.completeness,
        missing=prof.missing or [],
        metric_sources=c.metric_sources or {},
        update_notes=notes or [],
        version=prof.version,
        quality=QualityOut(
            label=q.label, activity=q.activity, score=q.score, summary=q.summary, findings=q.findings, version=q.version
        )
        if q
        else None,
    )


@app.post("/ai/creators/profile", response_model=ProfileOut, dependencies=[Depends(require_key)])
def creator_profile(body: CreatorIn, request: Request, session: SessionDep):
    """Call when a creator signs up, reconnects Instagram, or their data is refreshed."""
    _, notes = save_creator(session, body)
    h = clean_handle(body.handle)
    build_and_save(session, request.app.state.embedder, handles=[h])
    assess_and_save(session, handles=[h])
    session.commit()
    return profile_out(session, h, notes)


@app.get("/ai/creators/{handle}", response_model=ProfileOut, dependencies=[Depends(require_key)])
def get_creator(handle: str, session: SessionDep):
    return profile_out(session, clean_handle(handle))


# ------------------------------------------------------------------ Model 2


@app.post("/ai/match", response_model=MatchOut, dependencies=[Depends(require_key)])
def match(body: MatchIn, request: Request, session: SessionDep):
    exclude = [clean_handle(h) for h in body.exclude_handles]
    ids = list(session.scalars(select(Creator.id).where(Creator.handle.in_(exclude)))) if exclude else []
    brief = BriefQuery(
        text=body.text,
        category=body.category,
        sub_niche=body.sub_niche,
        city=body.city,
        languages=body.languages,
        budget_per_creator_inr=body.budget_per_creator_inr,
        min_followers=body.min_followers,
        max_followers=body.max_followers,
        exclude_creator_ids=ids,
        top_k=body.top_k,
        brand_handle=clean_handle(body.brand_handle) if body.brand_handle else None,
    )
    results, left_out = run_match(session, brief, request.app.state.embedder)
    refs = (
        dict(
            session.execute(
                select(Creator.id, Creator.external_id).where(Creator.id.in_([r.creator_id for r in results]))
            ).all()
        )
        if results
        else {}
    )
    items = [
        MatchItem(
            rank=i,
            handle=r.handle,
            creator_ref=refs.get(r.creator_id),
            score=r.score,
            why=r.why,
            parts=r.parts,
            quality_label=r.quality_label,
            activity=r.activity,
            rank_before_rerank=r.rank_before_rerank,
            rerank_note=r.rerank_note,
            estimated_rate_inr={"low": r.estimated_rate_inr[0], "high": r.estimated_rate_inr[1]}
            if r.estimated_rate_inr
            else None,
        )
        for i, r in enumerate(results, 1)
    ]
    version = matcher_config()["version"]
    suggestion_id = log_event(
        session,
        event_type="shortlist_suggested",
        actor_type="system",
        subject_type="brief",
        subject_id=body.brief_ref or "unknown",
        payload={"request": body.model_dump(), "results": [i.model_dump() for i in items], "left_out": dict(left_out)},
        model_name="matcher",
        model_version=version,
    )
    session.commit()
    return MatchOut(version=version, suggestion_id=suggestion_id, results=items, left_out=dict(left_out))


# ------------------------------------------------------------------ learning signal


@app.post("/ai/feedback", response_model=FeedbackOut, dependencies=[Depends(require_key)])
def feedback(body: FeedbackIn, session: SessionDep):
    required = FEEDBACK_TYPES.get(body.event_type)
    if required is None:
        raise HTTPException(422, f"unknown event_type {body.event_type!r}; allowed: {sorted(FEEDBACK_TYPES)}")
    missing = sorted(required - body.payload.keys())
    if missing:
        raise HTTPException(422, f"{body.event_type} needs payload keys: {missing}")
    if body.event_type == "brief_field_edited" and body.payload.get("field") in LOCKED_BRIEF_FIELDS:
        raise HTTPException(422, f"{body.payload['field']} is required on every brief and cannot be edited")
    if body.suggestion_id and session.get(Event, body.suggestion_id) is None:
        raise HTTPException(404, f"suggestion_id {body.suggestion_id} not found")
    payload = dict(body.payload)
    if body.suggestion_id:
        payload["suggestion_id"] = str(body.suggestion_id)
    event_id = log_event(
        session,
        event_type=body.event_type,
        actor_type=body.actor_type,
        actor_id=body.actor_id,
        subject_type=body.subject_type,
        subject_id=body.subject_id,
        payload=payload,
    )
    session.commit()
    return FeedbackOut(event_id=event_id)
