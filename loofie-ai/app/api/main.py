"""Loofie AI service. Run: uvicorn app.api.main:app --port 8100

The backend calls these endpoints with the X-API-Key header. Interactive docs at /docs.
"""

import hmac
import json
import logging
import time
import uuid
from contextlib import asynccontextmanager
from datetime import UTC, date, datetime
from typing import Annotated
from urllib.parse import parse_qs, urlencode, urlsplit

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.rules import check_feedback
from app.api.schemas import (
    BriefParseIn,
    BriefParseOut,
    CreatorIn,
    FeedbackIn,
    FeedbackOut,
    FieldOut,
    InstagramAccountOut,
    InstagramLoginOut,
    MatchIn,
    MatchItem,
    MatchOut,
    ProfileOut,
    QualityOut,
)
from app.brief.extract import Field, brief_config, parse
from app.config import get_settings
from app.db.models import Creator, CreatorProfile, CreatorQuality, Event, InstagramAccount, Post
from app.db.session import SessionLocal
from app.feedback.events import log_event
from app.importer import parse as p
from app.importer import provenance
from app.importer.upsert import upsert
from app.instagram import security
from app.instagram import service as ig_service
from app.instagram.client import InstagramClient, InstagramError, instagram_config
from app.matcher.features import BriefQuery, matcher_config
from app.matcher.service import match as run_match
from app.profile.builder import build_and_save
from app.profile.niche import ZeroShotNiche
from app.quality.rules import quality_config
from app.quality.service import assess_and_save


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load the model onto the GPU once, not per request. Tests put a fake embedder here first.
    if getattr(app.state, "embedder", None) is None:
        from app.ml.embed import get_embedder

        app.state.embedder = get_embedder()
    app.state.niche = ZeroShotNiche(app.state.embedder)
    yield


app = FastAPI(title="Loofie AI", version="1", lifespan=lifespan)

log = logging.getLogger("loofie.api")
if not logging.getLogger().handlers:
    logging.basicConfig(level=logging.INFO, format="%(message)s")


@app.middleware("http")
async def request_log(request: Request, call_next):
    """One JSON line per call: what monitoring tools read. Never logs bodies (briefs, creator data)."""
    request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:16]
    request.state.request_id = request_id
    start = time.perf_counter()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
    except Exception:
        log.exception(json.dumps({"request_id": request_id, "path": request.url.path, "error": "unhandled"}))
        response = JSONResponse({"detail": "internal error", "request_id": request_id}, status_code=500)
    response.headers["X-Request-ID"] = request_id
    log.info(
        json.dumps(
            {
                "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "request_id": request_id,
                "method": request.method,
                "path": request.url.path,
                "caller": getattr(request.state, "caller", None),
                "status": status,
                "ms": round((time.perf_counter() - start) * 1000, 1),
            }
        )
    )
    return response


def get_session():
    with SessionLocal() as session:
        yield session


SessionDep = Annotated[Session, Depends(get_session)]


def require_key(request: Request, x_api_key: str = Header(default="")) -> str:
    """Returns the caller's name (from AI_API_KEYS), which goes into the request log."""
    keys = get_settings().api_keys()
    if not keys:
        raise HTTPException(503, "no API key is configured on the AI service (AI_API_KEYS)")
    caller = None
    for name, key in keys:  # check every key, so timing doesn't reveal which one matched
        if hmac.compare_digest(x_api_key.encode(), key.encode()):
            caller = name
    if caller is None:
        raise HTTPException(401, "missing or wrong X-API-Key")
    request.state.caller = caller
    return caller


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


def resolve_creator(session: Session, payload: dict) -> dict:
    """Store both ids: creator_ref (the backend's, stable) and creator_handle (ours, can change)."""
    out = dict(payload)
    ref, handle = out.get("creator_ref"), out.get("creator_handle")
    if ref and not handle:
        found = session.scalar(select(Creator.handle).where(Creator.external_id == str(ref)))
        if found:
            out["creator_handle"] = found
    elif handle and not ref:
        found = session.scalar(select(Creator.external_id).where(Creator.handle == clean_handle(handle)))
        if found:
            out["creator_ref"] = found
    return out


@app.post("/ai/feedback", response_model=FeedbackOut, dependencies=[Depends(require_key)])
def feedback(body: FeedbackIn, session: SessionDep):
    check_feedback(body)
    if body.suggestion_id and session.get(Event, body.suggestion_id) is None:
        raise HTTPException(404, f"suggestion_id {body.suggestion_id} not found")
    payload = resolve_creator(session, body.payload)
    if body.suggestion_id:
        payload["suggestion_id"] = str(body.suggestion_id)
    fields = dict(
        event_type=body.event_type,
        actor_type=body.actor_type,
        actor_id=body.actor_id,
        subject_type=body.subject_type,
        subject_id=body.subject_id,
        payload=jsonable_encoder(payload),
    )

    def same_as_saved(ev: Event) -> FeedbackOut:
        # A retry carries the same event_id and the same content: answer as before, store nothing.
        if any(getattr(ev, k) != v for k, v in fields.items()):
            raise HTTPException(409, f"event_id {ev.id} was already used for a different event")
        return FeedbackOut(event_id=ev.id, duplicate=True)

    if body.event_id and (saved := session.get(Event, body.event_id)) is not None:
        return same_as_saved(saved)
    event_id = log_event(session, **fields, event_id=body.event_id, occurred_at=body.occurred_at)
    try:
        session.commit()
    except IntegrityError:  # two copies of the same retry arrived at the same moment
        session.rollback()
        return same_as_saved(session.get(Event, body.event_id))
    return FeedbackOut(event_id=event_id)


# ------------------------------------------------------------------ Instagram Login (F1)
#
# /ai/instagram/*   : the backend calls these (X-API-Key), like every other /ai endpoint.
# /auth/instagram/* : PUBLIC. Instagram sends the creator's browser to the callback, and Meta
#                     calls deauthorize / data-deletion. They're protected by the signed `state`
#                     and Meta's `signed_request` instead of the API key.


def session_factory(app: FastAPI):
    # Background syncs outlive the request, so they open their own session. Tests swap this.
    return getattr(app.state, "session_factory", None) or SessionLocal


def instagram(request: Request) -> InstagramClient:
    s = get_settings()
    if not (s.ig_app_id and s.ig_app_secret and s.ig_redirect_uri and s.ig_token_key):
        raise HTTPException(503, "Instagram login is not configured on the AI service")
    if getattr(request.app.state, "instagram", None) is None:
        request.app.state.instagram = InstagramClient(s.ig_app_id, s.ig_app_secret, s.ig_redirect_uri)
    return request.app.state.instagram


InstagramDep = Annotated[InstagramClient, Depends(instagram)]


def account_or_404(session: Session, account_ref: str) -> InstagramAccount:
    acct = session.scalar(select(InstagramAccount).where(InstagramAccount.account_ref == account_ref))
    if acct is None:
        raise HTTPException(404, f"no Instagram connection for account {account_ref!r}")
    return acct


def account_out(a: InstagramAccount) -> InstagramAccountOut:
    return InstagramAccountOut(
        account_ref=a.account_ref,
        handle=a.username,
        status=a.status,
        insights_allowed=ig_service.INSIGHTS_SCOPE in (a.permissions or []),
        token_expires_at=a.token_expires_at,
        last_synced_at=a.last_synced_at,
        last_error=a.last_error,
    )


@app.get("/ai/instagram/login-url", response_model=InstagramLoginOut, dependencies=[Depends(require_key)])
def instagram_login_url(account_ref: Annotated[str, Query(min_length=1, max_length=64)], ig: InstagramDep):
    """Step 1: the backend asks for a login link for one creator and sends their browser there."""
    url = ig_service.login_url(ig, account_ref, get_settings().ig_token_key)
    return InstagramLoginOut(url=url, expires_in=instagram_config()["state_ttl_minutes"] * 60)


def _back_to_app(outcome: dict):
    base = get_settings().app_return_url
    if not base:  # no creator app page configured (local testing): show the outcome as JSON
        return JSONResponse(outcome, status_code=200 if outcome["instagram"] == "connected" else 400)
    joiner = "&" if "?" in base else "?"
    return RedirectResponse(base + joiner + urlencode(outcome), status_code=303)


def sync_in_background(app: FastAPI, account_id: int) -> None:
    key = get_settings().ig_token_key
    with session_factory(app)() as s:
        acct = s.get(InstagramAccount, account_id)
        if acct is None or acct.status != "syncing":
            return
        try:
            ig_service.sync(s, app.state.embedder, app.state.instagram, acct, key, datetime.now(UTC))
            s.commit()
        except InstagramError:
            s.commit()  # the account's status/last_error explain it
        except Exception:
            log.exception(json.dumps({"job": "instagram_sync", "account_id": account_id, "error": "unhandled"}))
            s.rollback()
            acct = s.get(InstagramAccount, account_id)
            acct.status, acct.last_error = "error", "internal error during sync"
            s.commit()


@app.get("/auth/instagram/callback")
def instagram_callback(
    request: Request,
    session: SessionDep,
    ig: InstagramDep,
    background: BackgroundTasks,
    code: str | None = None,
    state: str | None = None,
    error: str | None = None,
):
    """Step 2: Instagram sends the creator back here. Save the token, reply at once, fetch in the background."""
    if error or not code or not state:
        return _back_to_app({"instagram": "failed", "reason": "denied" if error else "bad_request"})
    try:
        acct = ig_service.connect(session, ig, code, state, get_settings().ig_token_key, datetime.now(UTC))
    except ig_service.ConnectError as e:
        session.rollback()
        log.info(json.dumps({"job": "instagram_connect", "failed": e.reason}))
        return _back_to_app({"instagram": "failed", "reason": e.reason})
    acct.status = "syncing"
    session.commit()
    background.add_task(sync_in_background, request.app, acct.id)
    return _back_to_app({"instagram": "connected", "account_ref": acct.account_ref, "handle": acct.username})


@app.get(
    "/ai/instagram/accounts/{account_ref}", response_model=InstagramAccountOut, dependencies=[Depends(require_key)]
)
def instagram_account(account_ref: str, session: SessionDep):
    """Step 3: poll until status is `active` (or `error` / `expired`), then GET /ai/creators/{handle}."""
    return account_out(account_or_404(session, account_ref))


@app.post(
    "/ai/instagram/accounts/{account_ref}/sync",
    response_model=InstagramAccountOut,
    dependencies=[Depends(require_key)],
)
def instagram_sync_now(account_ref: str, request: Request, session: SessionDep, ig: InstagramDep):
    """Re-fetch now (ops button, or right after the creator posts new work)."""
    acct = account_or_404(session, account_ref)
    if acct.token_encrypted is None:
        raise HTTPException(409, f"account is {acct.status}: the creator has to log in with Instagram again")
    try:
        ig_service.sync(session, request.app.state.embedder, ig, acct, get_settings().ig_token_key, datetime.now(UTC))
    except InstagramError:
        session.commit()
        raise HTTPException(502, f"Instagram refused the fetch; account is now {acct.status}") from None
    session.commit()
    return account_out(acct)


@app.delete("/ai/instagram/accounts/{account_ref}", dependencies=[Depends(require_key)])
def instagram_disconnect(account_ref: str, session: SessionDep, delete_data: bool = False):
    """The creator disconnected in the app. delete_data=true also removes everything fetched from Instagram."""
    acct = account_or_404(session, account_ref)
    code = ig_service.forget(session, acct, delete_data, reason="creator_request")
    session.commit()
    return {"status": "deleted" if delete_data else "revoked", "confirmation_code": code}


async def meta_signed_request(request: Request) -> dict:
    form = parse_qs((await request.body()).decode())
    signed = (form.get("signed_request") or [""])[0]
    secret = get_settings().ig_app_secret
    if not secret:
        raise HTTPException(503, "Instagram login is not configured on the AI service")
    try:
        return security.parse_signed_request(signed, secret)
    except security.SecurityError as e:
        raise HTTPException(400, str(e)) from e


MetaPayload = Annotated[dict, Depends(meta_signed_request)]


def _account_for_meta_user(session: Session, payload: dict) -> InstagramAccount | None:
    uid = str(payload.get("user_id", ""))
    if not uid:
        return None
    return session.scalar(
        select(InstagramAccount).where((InstagramAccount.ig_user_id == uid) | (InstagramAccount.app_user_id == uid))
    )


@app.post("/auth/instagram/deauthorize")
def instagram_deauthorize(session: SessionDep, payload: MetaPayload):
    """Meta calls this when a creator removes Loofie from their Instagram settings."""
    acct = _account_for_meta_user(session, payload)
    if acct is not None:
        ig_service.forget(session, acct, delete_data=False, reason="meta_deauthorize")
        session.commit()
    return {"ok": True}


@app.post("/auth/instagram/data-deletion")
def instagram_data_deletion(session: SessionDep, payload: MetaPayload):
    """Meta's data-deletion callback (required for App Review). Deletes at once and returns the
    confirmation code + status URL Meta shows the creator."""
    acct = _account_for_meta_user(session, payload)
    code = None
    if acct is not None:
        code = ig_service.forget(session, acct, delete_data=True, reason="meta_data_deletion")
        session.commit()
    code = code or NOTHING_STORED
    origin = urlsplit(get_settings().ig_redirect_uri)
    status_url = f"{origin.scheme}://{origin.netloc}/auth/instagram/deletion-status?" + urlencode({"code": code})
    return {"url": status_url, "confirmation_code": code}


NOTHING_STORED = "nothing-stored"


@app.get("/auth/instagram/deletion-status")
def instagram_deletion_status(code: Annotated[str, Query(max_length=40)], session: SessionDep):
    if code == NOTHING_STORED:
        return {"status": "completed", "detail": "Loofie held no Instagram data for this account."}
    found = session.scalar(
        select(Event.occurred_at).where(
            Event.event_type == "instagram_data_deleted", Event.payload["confirmation_code"].astext == code
        )
    )
    if found is None:
        raise HTTPException(404, "unknown confirmation code")
    return {"status": "completed", "deleted_at": found.isoformat()}
