"""Connect, sync, keep fresh, disconnect: the Instagram Login lifecycle.

connect  : login code → long-lived token (encrypted) → account row        (seconds, in the callback)
sync     : fetch profile + posts + insights → creator/posts rows (API_VERIFIED)
           → profile + quality rebuilt                                    (background, ~10–30 s)
maintain : daily job; renews tokens before they die, re-syncs weekly
forget   : creator disconnected, or Meta asked us to delete their data
"""

import secrets
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.db.models import Creator, CreatorProfile, CreatorQuality, InstagramAccount, InstagramSnapshot, Post
from app.feedback.events import log_event
from app.importer import provenance
from app.importer.upsert import upsert
from app.instagram import ingest, security
from app.instagram.client import InstagramClient, InstagramError, instagram_config
from app.profile.builder import build_and_save
from app.quality.service import assess_and_save

INSIGHTS_SCOPE = "instagram_business_manage_insights"
PROFESSIONAL = {"BUSINESS", "MEDIA_CREATOR", "CREATOR"}
LIVE = ("connected", "syncing", "active", "error")  # statuses that still hold a usable token


class ConnectError(Exception):
    """`reason` is a short code the creator app turns into a message."""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(detail or reason)
        self.reason = reason


def _event(session, event_type: str, acct: InstagramAccount, **payload) -> None:
    log_event(
        session,
        event_type=event_type,
        actor_type="system",
        subject_type="creator_account",
        subject_id=acct.account_ref,
        payload=payload,
        model_name="instagram",
        model_version=instagram_config()["version"],
    )


def login_url(client: InstagramClient, account_ref: str, token_key: str) -> str:
    ttl = instagram_config()["state_ttl_minutes"] * 60
    return client.authorize_url(security.sign_state(account_ref, token_key, ttl))


# ------------------------------------------------------------------ connect


def connect(session: Session, client: InstagramClient, code: str, state: str, token_key: str, now: datetime):
    try:
        account_ref = security.read_state(state, token_key, now.timestamp())
    except security.SecurityError as e:
        raise ConnectError("expired_link", str(e)) from e
    try:
        grant = client.exchange_code(code)
        token, expires_in = client.long_lived(grant.access_token)
        me = client.me(token)
    except InstagramError as e:
        raise ConnectError("instagram_error", str(e)) from e
    if str(me.get("account_type", "")).upper() not in PROFESSIONAL:
        raise ConnectError("not_professional", f"account type {me.get('account_type')!r}")

    ig_user_id = str(me.get("user_id") or grant.user_id)
    acct = session.scalar(select(InstagramAccount).where(InstagramAccount.ig_user_id == ig_user_id))
    if acct is not None and acct.account_ref != account_ref:
        # This Instagram account already belongs to another Loofie creator: never hand it over.
        raise ConnectError("already_linked", f"@{me.get('username')} is connected to another Loofie account")
    if acct is None:
        acct = session.scalar(select(InstagramAccount).where(InstagramAccount.account_ref == account_ref))
        if acct is not None:  # same Loofie creator, switching to a different Instagram account
            acct.creator_id = None
        else:
            acct = InstagramAccount(account_ref=account_ref)
            session.add(acct)

    acct.ig_user_id = ig_user_id
    acct.app_user_id = grant.user_id
    acct.username = me["username"].lower()
    acct.account_type = me.get("account_type")
    acct.permissions = grant.permissions
    acct.token_encrypted = security.encrypt(token, token_key)
    acct.token_expires_at = now + timedelta(seconds=expires_in)
    acct.token_refreshed_at = now
    acct.status = "connected"
    acct.last_error = None
    session.flush()
    _event(session, "instagram_connected", acct, username=acct.username, permissions=grant.permissions)
    return acct


# ------------------------------------------------------------------ sync


def _fetch(client: InstagramClient, acct: InstagramAccount, token: str) -> tuple[dict, list[dict]]:
    me = client.me(token)
    media = client.media(token)
    if INSIGHTS_SCOPE in (acct.permissions or []):
        for m in media:
            # Albums have no insights; stories / ads are skipped by the ingester anyway.
            if ingest.post_type(m) in {"reel", "photo"}:
                m["insights"] = client.insights(token, m["id"])
    return me, media


def _rename(session: Session, acct: InstagramAccount, new_handle: str) -> str | None:
    """The creator changed their Instagram handle: carry their history to the new name."""
    if acct.creator_id is None:
        return None
    c = session.get(Creator, acct.creator_id)
    if c is None or c.handle == new_handle:
        return None
    taken = session.scalar(select(Creator.id).where(Creator.handle == new_handle))
    if taken:
        return f"@{c.handle} is now @{new_handle}, but @{new_handle} already exists: ops should merge them"
    old, c.handle = c.handle, new_handle
    c.profile_link = f"https://www.instagram.com/{new_handle}/"
    session.flush()
    return f"renamed @{old} → @{new_handle}"


def sync(session: Session, embedder, client: InstagramClient, acct: InstagramAccount, token_key: str, now: datetime):
    """Fetch everything and rebuild the passport. Returns notes. On an Instagram failure the
    account's status explains it and the error is re-raised; nothing else is written."""
    cfg = instagram_config()
    try:
        me, media = _fetch(client, acct, security.decrypt(acct.token_encrypted, token_key))
    except InstagramError as e:
        acct.status = "expired" if e.token_dead else "error"
        acct.last_error = str(e)[:500]
        if e.token_dead:
            acct.token_encrypted = None  # useless now; the creator has to log in again
        _event(session, "instagram_sync_failed", acct, error=acct.last_error, token_dead=e.token_dead)
        raise

    today = now.date()
    creator_row, posts = ingest.to_rows(me, media, acct.account_ref, today, cfg)
    handle = creator_row["handle"]
    notes = [n for n in [_rename(session, acct, handle)] if n]
    notes += provenance.apply(session, [creator_row], today)
    upsert(session, Creator, [creator_row], ["handle"])
    creator_id = session.scalar(select(Creator.id).where(Creator.handle == handle))
    if posts:
        upsert(session, Post, [{**x, "creator_id": creator_id} for x in posts], ["post_url"])
    session.add(InstagramSnapshot(account_id=acct.id, captured_at=now, **ingest.snapshot(me)))

    acct.creator_id, acct.username = creator_id, handle
    session.flush()
    build_and_save(session, embedder, handles=[handle], as_of=today)
    assess_and_save(session, handles=[handle], as_of=today)

    acct.status, acct.last_synced_at, acct.last_error = "active", now, None
    _event(
        session,
        "instagram_synced",
        acct,
        handle=handle,
        followers=me.get("followers_count"),
        posts=len(posts),
        with_views=sum(x["views"] is not None for x in posts),
        notes=notes,
    )
    return notes


# ------------------------------------------------------------------ keep fresh


def maintain(session: Session, embedder, client: InstagramClient, token_key: str, now: datetime | None = None):
    """Run daily. Renews tokens close to expiry (Meta: ≥24 h old, before day 60) and re-syncs
    accounts not synced for a week. One account failing never stops the others."""
    now = now or datetime.now(UTC)
    cfg = instagram_config()
    counts = {"refreshed": 0, "synced": 0, "expired": 0, "failed": 0}
    accounts = session.scalars(
        select(InstagramAccount).where(InstagramAccount.status.in_(LIVE)).order_by(InstagramAccount.id)
    ).all()
    for acct in accounts:
        try:
            if acct.token_expires_at and acct.token_expires_at <= now:
                acct.status, acct.token_encrypted = "expired", None
                _event(session, "instagram_token_expired", acct)
                counts["expired"] += 1
                session.commit()
                continue
            renew_at = acct.token_expires_at - timedelta(days=cfg["refresh_when_days_left"])
            old_enough = now - acct.token_refreshed_at >= timedelta(hours=cfg["min_token_age_hours"])
            if now >= renew_at and old_enough:
                token, expires_in = client.refresh(security.decrypt(acct.token_encrypted, token_key))
                acct.token_encrypted = security.encrypt(token, token_key)
                acct.token_expires_at, acct.token_refreshed_at = now + timedelta(seconds=expires_in), now
                counts["refreshed"] += 1
            if acct.last_synced_at is None or now - acct.last_synced_at >= timedelta(days=cfg["sync_every_days"]):
                sync(session, embedder, client, acct, token_key, now)
                counts["synced"] += 1
            session.commit()
        except InstagramError as e:
            if e.token_dead and acct.status != "expired":
                acct.status, acct.token_encrypted, acct.last_error = "expired", None, str(e)[:500]
            counts["expired" if acct.status == "expired" else "failed"] += 1
            session.commit()  # keep the status that explains the failure
    return counts


# ------------------------------------------------------------------ disconnect / delete


def forget(session: Session, acct: InstagramAccount, delete_data: bool, reason: str) -> str | None:
    """Disconnect: drop the token. delete_data (Meta's data-deletion request, or the creator asked):
    also remove everything that came from Instagram: posts, snapshots, verified numbers and the
    profile/quality built from them. Returns a confirmation code when data was deleted."""
    acct.token_encrypted, acct.status = None, "revoked"
    if not delete_data:
        _event(session, "instagram_disconnected", acct, reason=reason)
        return None

    code = secrets.token_hex(8)
    if acct.creator_id:
        c = session.get(Creator, acct.creator_id)
        session.execute(delete(Post).where(Post.creator_id == c.id, Post.data_source == ingest.SOURCE))
        sources = dict(c.metric_sources or {})
        for metric, meta in list(sources.items()):
            if meta.get("source") == provenance.SOURCE_OF[ingest.SOURCE]:
                setattr(c, metric, None)
                sources.pop(metric)
        c.metric_sources = sources
        session.execute(delete(CreatorProfile).where(CreatorProfile.creator_id == c.id))
        session.execute(delete(CreatorQuality).where(CreatorQuality.creator_id == c.id))
    _event(session, "instagram_data_deleted", acct, reason=reason, confirmation_code=code)
    session.delete(acct)  # snapshots go with it (ON DELETE CASCADE)
    return code
