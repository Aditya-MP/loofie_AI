"""Connect, sync, keep fresh, disconnect: the Instagram connect lifecycle.

connect  : login code → token (encrypted) → account row                   (about 1 s, in the callback)
queue    : every fetch goes through a queue kept in the database (next_sync_at), so nothing is lost
           on a restart and any number of workers can share the work:
             claim  — lease one due account (lease_until); two workers never take the same one
             run    — sync it; success clears the queue entry, a Meta hiccup or rate limit is
                      retried later with growing delays, a dead token stops for good
sync     : fetch profile + posts + insights → creator/posts rows (API_VERIFIED)
           → profile + quality rebuilt                                    (10–30 s per creator)
maintain : daily job; renews tokens before they die, queues weekly re-syncs, drains the queue
forget   : creator disconnected, or Meta asked us to delete their data
"""

import logging
import secrets
import threading
from collections import Counter
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db.models import Creator, CreatorProfile, CreatorQuality, InstagramAccount, InstagramSnapshot, Post
from app.feedback.events import log_event
from app.importer import provenance
from app.importer.upsert import upsert
from app.instagram import ingest, security
from app.instagram.client import (
    InstagramClient,
    InstagramError,
    NoLinkedInstagram,
    SeveralInstagramAccounts,
    instagram_config,
)
from app.profile.builder import build_and_save
from app.quality.service import assess_and_save

INSIGHTS_SCOPE = "instagram_business_manage_insights"  # Instagram Login
INSIGHTS_SCOPES = {INSIGHTS_SCOPE, "instagram_manage_insights"}  # ...or Facebook Login


def has_insights(permissions: list[str] | None) -> bool:
    return bool(INSIGHTS_SCOPES & set(permissions or []))


PROFESSIONAL = {"BUSINESS", "MEDIA_CREATOR", "CREATOR", "PROFESSIONAL"}
LIVE = ("connected", "syncing", "active", "error")  # statuses that still hold a usable token
log = logging.getLogger(__name__)


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
    except InstagramError as e:
        # The same callback can arrive twice (a double tap, a browser retry): the code works only
        # once, so the second exchange fails although the first one connected her. Don't report a
        # failure for a login that just succeeded.
        done = session.scalar(select(InstagramAccount).where(InstagramAccount.account_ref == account_ref))
        recent = now - timedelta(minutes=instagram_config()["state_ttl_minutes"])
        if done is not None and done.status in LIVE and done.token_refreshed_at and done.token_refreshed_at >= recent:
            return done
        raise ConnectError("instagram_error", str(e)) from e
    mine = session.scalar(select(InstagramAccount).where(InstagramAccount.account_ref == account_ref))
    try:
        token, expires_in = client.long_lived(grant)
        me = client.me(token, prefer=mine.ig_user_id if mine else None)  # a reconnect keeps her account
    except SeveralInstagramAccounts as e:
        raise ConnectError("several_accounts", str(e)) from e
    except NoLinkedInstagram as e:
        reason = "no_instagram" if client.unified else "no_page"
        raise ConnectError(reason, f"{e}; permissions granted: {sorted(grant.permissions)}") from e
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
    acct.business_id = grant.business_id
    acct.username = me["username"].lower()
    acct.account_type = me.get("account_type")
    acct.permissions = grant.permissions
    acct.token_encrypted = security.encrypt(token, token_key)
    acct.token_expires_at = now + timedelta(seconds=expires_in) if expires_in else None  # None = never
    acct.token_refreshed_at = now
    acct.status = "connected"
    acct.last_error = None
    try:
        session.flush()
    except IntegrityError as e:
        # Two creators connecting the same Instagram account at the same moment: the database's
        # unique ig_user_id lets exactly one win. The other gets the same answer as above.
        session.rollback()
        raise ConnectError("already_linked", f"@{me.get('username')} was just connected to another account") from e
    _event(session, "instagram_connected", acct, username=acct.username, permissions=grant.permissions)
    return acct


# ------------------------------------------------------------------ sync


def _fetch(client: InstagramClient, acct: InstagramAccount, token: str) -> tuple[dict, list[dict]]:
    me = client.me(token, prefer=acct.ig_user_id)
    media = client.media(token, ig_user_id=str(me.get("user_id") or acct.ig_user_id))
    if has_insights(acct.permissions):
        # One insights call per post is most of a sync's Meta calls: only the newest posts need views
        # (average views use the last 10 reels), so older ones are skipped.
        recent = [m for m in media if ingest.post_type(m) in {"reel", "photo"}]  # albums have none
        for m in recent[: instagram_config()["queue"]["insights_last_n"]]:
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


# ------------------------------------------------------------------ the sync queue


def _q() -> dict:
    return instagram_config()["queue"]


def enqueue(acct: InstagramAccount, now: datetime, fresh: bool = True) -> None:
    """Ask for a fetch. fresh=True (a login, an ops button): start counting retries from zero."""
    acct.next_sync_at = now
    if fresh:
        acct.sync_attempts = 0


def _due(now: datetime):
    return (
        InstagramAccount.status.in_(LIVE),
        InstagramAccount.token_encrypted.is_not(None),
        InstagramAccount.next_sync_at.is_not(None),
        InstagramAccount.next_sync_at <= now,
        or_(InstagramAccount.lease_until.is_(None), InstagramAccount.lease_until < now),
    )


def claim(session: Session, now: datetime, limit: int = 1, account_id: int | None = None) -> list[int]:
    """Lease up to `limit` due accounts (oldest first) and commit, so other workers skip them.
    SKIP LOCKED: workers racing for the same rows never block or double-claim. A worker that dies
    mid-sync loses its lease after lease_seconds, and the account is picked up again."""
    q = select(InstagramAccount.id).where(*_due(now))
    if account_id is not None:
        q = q.where(InstagramAccount.id == account_id)
    ids = list(
        session.scalars(
            q.order_by(InstagramAccount.next_sync_at, InstagramAccount.id)
            .limit(limit)
            .with_for_update(skip_locked=True)
        )
    )
    if ids:
        lease = now + timedelta(seconds=_q()["lease_seconds"])
        session.execute(update(InstagramAccount).where(InstagramAccount.id.in_(ids)).values(lease_until=lease))
    session.commit()
    return ids


def run(session: Session, embedder, client: InstagramClient, account_id: int, token_key: str, now: datetime) -> str:
    """Sync one leased account. Returns synced | retry | expired | failed. Always releases the lease
    and commits, whatever happens, so one bad account never blocks the queue."""
    acct = session.get(InstagramAccount, account_id)
    if acct is None:
        return "failed"
    try:
        sync(session, embedder, client, acct, token_key, now)
        acct.next_sync_at, acct.sync_attempts, acct.lease_until = None, 0, None
        session.commit()
        return "synced"
    except InstagramError as e:
        # sync() already set status / last_error and logged the event; decide what happens next.
        delays = _q()["retry_delays_seconds"]
        acct.lease_until = None
        if e.retryable and acct.sync_attempts < len(delays):
            acct.next_sync_at = now + timedelta(seconds=delays[acct.sync_attempts])
            acct.sync_attempts += 1
            outcome = "retry"
        else:
            acct.next_sync_at = None
            outcome = "expired" if acct.status == "expired" else "failed"
        session.commit()
        return outcome
    except Exception:
        log.exception("instagram sync crashed for account %s", account_id)
        session.rollback()
        acct = session.get(InstagramAccount, account_id)
        acct.status, acct.last_error = "error", "internal error during sync"
        acct.next_sync_at, acct.lease_until = None, None
        session.commit()
        return "failed"


def drain(
    session: Session,
    embedder,
    client: InstagramClient,
    token_key: str,
    now: datetime | None = None,
    max_jobs: int = 10_000,
) -> Counter:
    """Work through everything due right now in this process (daily job, tests). Workers running at
    the same time share the queue safely: each account is leased by exactly one of them."""
    outcomes: Counter = Counter()
    for _ in range(max_jobs):
        when = now or datetime.now(UTC)
        ids = claim(session, when)
        if not ids:
            break
        outcomes[run(session, embedder, client, ids[0], token_key, when)] += 1
    return outcomes


def work_forever(session_factory, embedder, client: InstagramClient, token_key: str, stop: threading.Event) -> None:
    """One worker thread. Start several (IG_WORKERS) in the API, or run `loofie instagram worker`
    on more machines: the queue lives in the database, so they all share it."""
    idle = _q()["poll_seconds"]
    while not stop.is_set():
        pause = client.pause_seconds()
        if pause:  # Meta said slow down: every worker waits, nobody burns the limit further
            stop.wait(min(pause, 60))
            continue
        try:
            with session_factory() as s:
                now = datetime.now(UTC)
                ids = claim(s, now)
                if not ids:
                    stop.wait(idle)
                    continue
                run(s, embedder, client, ids[0], token_key, now)
        except Exception:  # the database blinked: wait and carry on, never kill the worker
            log.exception("instagram worker loop error")
            stop.wait(idle * 5)


# ------------------------------------------------------------------ keep fresh


def maintain(session: Session, embedder, client: InstagramClient, token_key: str, now: datetime | None = None):
    """Run daily. Renews tokens close to expiry (Meta: ≥24 h old, before day 60), queues accounts not
    synced for a week, then works through the queue. One account failing never stops the others."""
    now = now or datetime.now(UTC)
    cfg = instagram_config()
    counts = {"refreshed": 0, "synced": 0, "expired": 0, "failed": 0}
    accounts = session.scalars(
        select(InstagramAccount).where(InstagramAccount.status.in_(LIVE)).order_by(InstagramAccount.id)
    ).all()
    for acct in accounts:
        try:
            if acct.token_expires_at and acct.token_expires_at <= now:
                acct.status, acct.token_encrypted, acct.next_sync_at = "expired", None, None
                _event(session, "instagram_token_expired", acct)
                counts["expired"] += 1
                session.commit()
                continue
            if acct.token_expires_at and client.can_refresh:  # None = the token doesn't expire
                renew_at = acct.token_expires_at - timedelta(days=cfg["refresh_when_days_left"])
                old_enough = now - acct.token_refreshed_at >= timedelta(hours=cfg["min_token_age_hours"])
                if now >= renew_at and old_enough:
                    token, expires_in = client.refresh(security.decrypt(acct.token_encrypted, token_key))
                    acct.token_encrypted = security.encrypt(token, token_key)
                    acct.token_expires_at, acct.token_refreshed_at = now + timedelta(seconds=expires_in), now
                    counts["refreshed"] += 1
            weekly = acct.last_synced_at is None or now - acct.last_synced_at >= timedelta(days=cfg["sync_every_days"])
            if weekly and acct.next_sync_at is None:
                enqueue(acct, now, fresh=False)
            session.commit()
        except InstagramError as e:
            if e.token_dead and acct.status != "expired":
                acct.status, acct.token_encrypted, acct.last_error = "expired", None, str(e)[:500]
                acct.next_sync_at = None
            counts["expired" if acct.status == "expired" else "failed"] += 1
            session.commit()  # keep the status that explains the failure
    done = drain(session, embedder, client, token_key, now)
    counts["synced"] += done["synced"]
    counts["expired"] += done["expired"]
    counts["failed"] += done["failed"]
    if done["retry"]:
        counts["retrying"] = done["retry"]  # Meta hiccups: retried automatically later
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
