"""F1 Instagram Login: login link → callback → fetch → API_VERIFIED passport, kept fresh, deletable.

Meta is replaced by FakeInstagram (an httpx MockTransport) that answers in Meta's documented
shapes, so the whole flow runs without a Meta app. A real-account check is still needed once the
Meta app exists (see docs/OPERATIONS.md).
"""

import logging
from datetime import UTC, datetime, timedelta
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import sessionmaker

from app.api.main import app, get_session
from app.config import get_settings
from app.db.models import Creator, CreatorQuality, Event, InstagramAccount, InstagramSnapshot, Post
from app.instagram import ingest, security
from app.instagram import service as ig
from app.instagram.client import InstagramClient, InstagramError, instagram_config
from tests.helpers import FakeEmbedder

KEY = "test-key"
H = {"X-API-Key": KEY}
SECRET = "app-secret"
TOKEN_KEY = security.new_token_key()
NOW = datetime(2026, 11, 14, 10, 0, tzinfo=UTC)


# ------------------------------------------------------------------ fake Meta


def media_for(username: str, n: int, start: datetime, views: int = 4000) -> list[dict]:
    kinds = [("VIDEO", "REELS"), ("VIDEO", "REELS"), ("IMAGE", "FEED"), ("CAROUSEL_ALBUM", "FEED"), ("VIDEO", "STORY")]
    out = []
    for i in range(n):
        media_type, product = kinds[i % len(kinds)]
        out.append(
            {
                "id": f"{abs(hash(username)) % 10**8}{i:03d}",
                "caption": f"my sunscreen routine for oily skin, day {i}" if i % 7 else None,
                "media_type": media_type,
                "media_product_type": product,
                "timestamp": (start - timedelta(days=3 * i)).strftime("%Y-%m-%dT%H:%M:%S+0000"),
                "permalink": f"https://www.instagram.com/{'reel' if media_type == 'VIDEO' else 'p'}/{username[:4]}{i}/?igsh=x",
                "like_count": 300 + i,
                "comments_count": 25 + i % 5,
                "_views": views + 10 * i,
            }
        )
    return out


class FakeInstagram:
    def __init__(self):
        self.users: dict[str, dict] = {}
        self.dead: set[str] = set()  # usernames whose tokens Meta now rejects
        self.calls: list[str] = []
        self.refreshes = 0

    def add(self, username, followers=12400, account_type="MEDIA_CREATOR", n_media=12, insights=True, **kw):
        self.users[username] = dict(
            user_id=f"ig{abs(hash(username)) % 10**9}",
            username=username,
            account_type=account_type,
            followers_count=followers,
            follows_count=610,
            media_count=n_media,
            media=media_for(username, n_media, NOW - timedelta(days=1)),
            insights=insights,
            **kw,
        )

    def _user(self, token: str) -> dict:
        name = token.split("-")[1]
        if name in self.dead:
            raise _Err(
                400, {"error": {"message": "Error validating access token", "type": "OAuthException", "code": 190}}
            )
        return self.users[name]

    def handler(self, request: httpx.Request) -> httpx.Response:
        try:
            return httpx.Response(200, json=self._route(request))
        except _Err as e:
            return httpx.Response(e.status, json=e.body)

    def _route(self, request: httpx.Request) -> dict:
        url = urlsplit(str(request.url))
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        self.calls.append(f"{request.method} {url.netloc}{url.path}")
        if url.path == "/oauth/access_token":
            form = {k: v[0] for k, v in parse_qs(request.content.decode()).items()}
            assert form["client_secret"] == SECRET and form["grant_type"] == "authorization_code"
            name = form["code"].removeprefix("code-")
            if "#" in name or name not in self.users:
                raise _Err(
                    400, {"error_type": "OAuthException", "code": 400, "error_message": "Matching code was not found"}
                )
            perms = "instagram_business_basic" + (
                ",instagram_business_manage_insights" if self.users[name]["insights"] else ""
            )
            return {"data": [{"access_token": f"short-{name}", "user_id": f"app{name}", "permissions": perms}]}
        if url.path == "/access_token":
            assert q["grant_type"] == "ig_exchange_token" and q["client_secret"] == SECRET
            return {
                "access_token": f"long-{q['access_token'].split('-')[1]}-0",
                "token_type": "bearer",
                "expires_in": 5184000,
            }
        if url.path == "/refresh_access_token":
            u = self._user(q["access_token"])
            self.refreshes += 1
            return {
                "access_token": f"long-{u['username']}-{self.refreshes}",
                "token_type": "bearer",
                "expires_in": 5184000,
            }
        u = self._user(q["access_token"])
        if url.path.endswith("/me"):
            fields = q["fields"].split(",")
            return {"data": [{f: u[f] for f in fields if f in u}]}
        if url.path.endswith("/me/media"):
            start = int(q.get("after", 0))
            limit = int(q["limit"])
            page = u["media"][start : start + limit]
            items = [{k: v for k, v in m.items() if not k.startswith("_")} for m in page]
            paging = {"cursors": {"before": str(start), "after": str(start + limit)}}
            return {"data": items, "paging": paging if start + limit < len(u["media"]) else {}}
        if url.path.endswith("/insights"):
            media_id = url.path.split("/")[-2]
            m = next(m for m in u["media"] if m["id"] == media_id)
            if m["media_type"] == "CAROUSEL_ALBUM":
                raise _Err(400, {"error": {"message": "not supported for albums", "code": 100}})
            return {
                "data": [
                    {"name": "views", "period": "lifetime", "values": [{"value": m["_views"]}]},
                    {"name": "reach", "period": "lifetime", "values": [{"value": m["_views"] // 2}]},
                ]
            }
        raise _Err(404, {"error": {"message": f"unknown path {url.path}", "code": 803}})


class _Err(Exception):
    def __init__(self, status, body):
        self.status, self.body = status, body


@pytest.fixture
def meta():
    return FakeInstagram()


@pytest.fixture
def ig_client(meta):
    return InstagramClient(
        "123",
        SECRET,
        "https://ai.loofie.test/auth/instagram/callback",
        httpx.Client(transport=httpx.MockTransport(meta.handler)),
        flavor="instagram",  # this fake is Meta's Instagram Login; the Facebook flavor has its own tests
    )


@pytest.fixture
def client(session, engine, monkeypatch, ig_client):
    for k, v in {
        "AI_API_KEY": KEY,
        "IG_APP_ID": "123",
        "IG_APP_SECRET": SECRET,
        "IG_REDIRECT_URI": "https://ai.loofie.test/auth/instagram/callback",
        "IG_TOKEN_KEY": TOKEN_KEY,
        "APP_RETURN_URL": "",
    }.items():
        monkeypatch.setenv(k, v)
    get_settings.cache_clear()
    app.state.embedder = FakeEmbedder()
    app.state.instagram = ig_client
    app.state.session_factory = sessionmaker(engine)
    app.dependency_overrides[get_session] = lambda: session
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    app.state.embedder = app.state.instagram = app.state.session_factory = None
    get_settings.cache_clear()


def log_in(client, meta, username, account_ref=None, **user):
    """The whole creator journey: ask for the link, 'approve' on Instagram, land on the callback."""
    if username not in meta.users:
        meta.add(username, **user)
    r = client.get("/ai/instagram/login-url", headers=H, params={"account_ref": account_ref or f"be-{username}"})
    assert r.status_code == 200
    state = parse_qs(urlsplit(r.json()["url"]).query)["state"][0]
    return client.get("/auth/instagram/callback", params={"code": f"code-{username}#_", "state": state})


# ------------------------------------------------------------------ the happy path


def test_login_fetches_and_builds_a_verified_passport(client, meta, session):
    r = log_in(client, meta, "mira.skin")
    assert r.status_code == 200 and r.json() == {
        "instagram": "connected",
        "account_ref": "be-mira.skin",
        "handle": "mira.skin",
    }

    acct = client.get("/ai/instagram/accounts/be-mira.skin", headers=H).json()
    assert acct["status"] == "active" and acct["insights_allowed"] and acct["last_error"] is None

    profile = client.get("/ai/creators/mira.skin", headers=H).json()
    src = profile["metric_sources"]
    assert {src[m]["source"] for m in ("followers", "following", "total_posts", "avg_views_reported")} == {
        "API_VERIFIED"
    }
    assert profile["quality"]["label"] == "real"  # verified numbers: Model 3 can now say "real"
    assert profile["creator_ref"] == "be-mira.skin"

    session.expire_all()
    c = session.scalar(select(Creator).where(Creator.handle == "mira.skin"))
    assert c.followers == 12400 and c.posts_last_30d is not None and c.last_post_date is not None
    posts = session.scalars(select(Post).where(Post.creator_id == c.id)).all()
    # 12 media: stories skipped, permalinks cleaned, views only where Meta gives insights
    assert len(posts) == 10 and all("?" not in p.post_url for p in posts)
    assert {p.post_type for p in posts} == {"reel", "photo", "carousel"}
    assert all(p.views is not None for p in posts if p.post_type != "carousel")
    assert all(p.views is None for p in posts if p.post_type == "carousel")
    assert session.scalar(select(InstagramSnapshot.followers)) == 12400


def test_token_is_stored_encrypted_and_never_logged(client, meta, session, caplog):
    caplog.set_level(logging.DEBUG)
    log_in(client, meta, "mira.skin")
    session.expire_all()
    acct = session.scalar(select(InstagramAccount))
    assert "long-mira.skin" not in acct.token_encrypted
    assert security.decrypt(acct.token_encrypted, TOKEN_KEY) == "long-mira.skin-0"
    assert "long-mira.skin" not in caplog.text and SECRET not in caplog.text


def test_self_reported_numbers_cannot_overwrite_verified_ones(client, meta):
    log_in(client, meta, "mira.skin")
    typed = {"handle": "mira.skin", "followers": 99000, "city": "Mysuru"}
    body = client.post("/ai/creators/profile", headers=H, json=typed).json()
    assert body["update_notes"] == ["@mira.skin followers: kept 12400 (API_VERIFIED) over 99000 (SELF_REPORTED)"]
    assert body["metric_sources"]["city"]["source"] == "SELF_REPORTED"  # descriptions: the creator's to set


def test_no_insights_permission_still_connects(client, meta, session):
    log_in(client, meta, "no.insights", insights=False)
    assert client.get("/ai/instagram/accounts/be-no.insights", headers=H).json()["insights_allowed"] is False
    assert not any(c.endswith("/insights") for c in meta.calls)
    session.expire_all()
    c = session.scalar(select(Creator).where(Creator.handle == "no.insights"))
    assert c.followers == 12400 and c.avg_views_reported is None


# ------------------------------------------------------------------ refusals


def test_creator_cancels_or_link_is_forged(client, meta):
    r = client.get("/auth/instagram/callback", params={"error": "access_denied", "error_reason": "user_denied"})
    assert r.status_code == 400 and r.json()["reason"] == "denied"
    meta.add("mira.skin")
    r = client.get("/auth/instagram/callback", params={"code": "code-mira.skin", "state": "forged.state"})
    assert r.json()["reason"] == "expired_link"


def test_expired_login_link():
    state = security.sign_state("be-1", TOKEN_KEY, ttl_seconds=600, now=NOW.timestamp())
    assert security.read_state(state, TOKEN_KEY, now=NOW.timestamp() + 599) == "be-1"
    with pytest.raises(security.SecurityError, match="expired"):
        security.read_state(state, TOKEN_KEY, now=NOW.timestamp() + 601)
    body, sig = state.split(".")
    with pytest.raises(security.SecurityError, match="signature"):
        security.read_state(body + "x." + sig, TOKEN_KEY, now=NOW.timestamp())


def test_personal_account_is_refused(client, meta):
    r = log_in(client, meta, "personal.acc", account_type="PERSONAL")
    assert r.json() == {"instagram": "failed", "reason": "not_professional"}


def test_instagram_account_cannot_be_claimed_by_a_second_loofie_account(client, meta):
    log_in(client, meta, "mira.skin", account_ref="be-mira")
    r = log_in(client, meta, "mira.skin", account_ref="be-impostor")
    assert r.json()["reason"] == "already_linked"
    assert client.get("/ai/instagram/accounts/be-impostor", headers=H).status_code == 404


def test_logging_in_again_just_renews(client, meta, session):
    log_in(client, meta, "mira.skin")
    meta.users["mira.skin"]["followers_count"] = 12900
    log_in(client, meta, "mira.skin")
    session.expire_all()
    assert session.scalar(select(Creator.followers).where(Creator.handle == "mira.skin")) == 12900
    assert len(session.scalars(select(InstagramAccount)).all()) == 1


def test_not_configured_is_503(client, monkeypatch):
    monkeypatch.setenv("IG_APP_ID", "")
    get_settings.cache_clear()
    assert client.get("/ai/instagram/login-url", headers=H, params={"account_ref": "x"}).status_code == 503


def test_login_url_needs_the_api_key(client):
    assert client.get("/ai/instagram/login-url", params={"account_ref": "x"}).status_code == 401


# ------------------------------------------------------------------ keeping fresh


def connected(session, ig_client, meta, username="mira.skin", when=NOW):
    meta.add(username)
    state = security.sign_state(f"be-{username}", TOKEN_KEY, 600, now=when.timestamp())
    acct = ig.connect(session, ig_client, f"code-{username}", state, TOKEN_KEY, when)
    ig.sync(session, FakeEmbedder(), ig_client, acct, TOKEN_KEY, when)
    session.commit()
    return acct


def test_maintain_refreshes_near_expiry_and_resyncs_weekly(session, ig_client, meta):
    acct = connected(session, ig_client, meta)
    # Day 3: nothing due.
    assert ig.maintain(session, FakeEmbedder(), ig_client, TOKEN_KEY, NOW + timedelta(days=3)) == {
        "refreshed": 0,
        "synced": 0,
        "expired": 0,
        "failed": 0,
    }
    # Day 8: weekly re-fetch picks up new followers; a snapshot per sync builds the history.
    meta.users["mira.skin"]["followers_count"] = 13100
    counts = ig.maintain(session, FakeEmbedder(), ig_client, TOKEN_KEY, NOW + timedelta(days=8))
    assert counts["synced"] == 1 and counts["refreshed"] == 0
    assert [s.followers for s in session.scalars(select(InstagramSnapshot).order_by(InstagramSnapshot.id))] == [
        12400,
        13100,
    ]
    # Day 52: within 10 days of the 60-day expiry → renewed.
    counts = ig.maintain(session, FakeEmbedder(), ig_client, TOKEN_KEY, NOW + timedelta(days=52))
    assert counts["refreshed"] == 1
    session.refresh(acct)
    assert security.decrypt(acct.token_encrypted, TOKEN_KEY) == "long-mira.skin-1"
    assert acct.token_expires_at > NOW + timedelta(days=100)


def test_dead_token_marks_expired_and_others_continue(session, ig_client, meta):
    dead = connected(session, ig_client, meta, "gone.creator")
    alive = connected(session, ig_client, meta, "mira.skin")
    meta.dead.add("gone.creator")
    counts = ig.maintain(session, FakeEmbedder(), ig_client, TOKEN_KEY, NOW + timedelta(days=8))
    assert counts == {"refreshed": 0, "synced": 1, "expired": 1, "failed": 0}
    session.refresh(dead)
    session.refresh(alive)
    assert (dead.status, dead.token_encrypted) == ("expired", None)
    assert alive.status == "active"
    # The creator's data stays, but it ages: past 30 days a weaker source may update it again.
    assert session.scalar(select(Event).where(Event.event_type == "instagram_sync_failed")) is not None


def test_token_past_expiry_is_marked_without_calling_meta(session, ig_client, meta):
    acct = connected(session, ig_client, meta)
    meta.calls.clear()
    counts = ig.maintain(session, FakeEmbedder(), ig_client, TOKEN_KEY, NOW + timedelta(days=61))
    assert counts["expired"] == 1 and meta.calls == []
    session.refresh(acct)
    assert acct.status == "expired"


def test_handle_rename_keeps_the_creators_history(session, ig_client, meta):
    acct = connected(session, ig_client, meta, "mira.skin")
    creator_id = acct.creator_id
    meta.users["mira.glow"] = {**meta.users["mira.skin"], "username": "mira.glow"}
    meta.users["mira.glow"]["media"] = media_for("mira.glow", 4, NOW)
    # The token is tied to the Instagram account, not the name: point it at the renamed user.
    acct.token_encrypted = security.encrypt("long-mira.glow-0", TOKEN_KEY)
    notes = ig.sync(session, FakeEmbedder(), ig_client, acct, TOKEN_KEY, NOW + timedelta(days=8))
    session.commit()
    assert notes[0] == "renamed @mira.skin → @mira.glow"
    assert session.get(Creator, creator_id).handle == "mira.glow" and acct.creator_id == creator_id


# ------------------------------------------------------------------ disconnect and Meta's deletion callback


def test_creator_disconnects_keeps_data(client, meta, session):
    log_in(client, meta, "mira.skin")
    r = client.delete("/ai/instagram/accounts/be-mira.skin", headers=H)
    assert r.json() == {"status": "revoked", "confirmation_code": None}
    session.expire_all()
    acct = session.scalar(select(InstagramAccount))
    assert (acct.status, acct.token_encrypted) == ("revoked", None)
    assert client.post("/ai/instagram/accounts/be-mira.skin/sync", headers=H).status_code == 409


def test_meta_data_deletion_removes_everything_from_instagram(client, meta, session):
    log_in(client, meta, "mira.skin")
    client.post("/ai/creators/profile", headers=H, json={"handle": "mira.skin", "city": "Bengaluru"})
    uid = meta.users["mira.skin"]["user_id"]

    signed = security.make_signed_request({"user_id": uid, "algorithm": "HMAC-SHA256"}, SECRET)
    r = client.post(
        "/auth/instagram/data-deletion",
        content=f"signed_request={signed}",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["url"].startswith("https://ai.loofie.test/auth/instagram/deletion-status?code=")

    session.expire_all()
    c = session.scalar(select(Creator).where(Creator.handle == "mira.skin"))
    assert c.followers is None and c.avg_views_reported is None
    assert c.city == "Bengaluru"  # what the creator typed into Loofie isn't Instagram's data
    assert session.scalars(select(Post)).all() == []
    assert session.scalars(select(InstagramAccount)).all() == []
    assert session.scalars(select(InstagramSnapshot)).all() == []
    assert session.scalar(select(CreatorQuality)) is None

    status = client.get(urlsplit(body["url"]).path, params={"code": body["confirmation_code"]}).json()
    assert status["status"] == "completed"


def test_forged_meta_callback_is_refused(client, meta):
    log_in(client, meta, "mira.skin")
    signed = security.make_signed_request({"user_id": meta.users["mira.skin"]["user_id"]}, "not-our-secret")
    r = client.post(
        "/auth/instagram/deauthorize",
        content=f"signed_request={signed}",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    assert r.status_code == 400
    assert client.get("/ai/instagram/accounts/be-mira.skin", headers=H).json()["status"] == "active"


def test_meta_deauthorize_revokes(client, meta):
    log_in(client, meta, "mira.skin")
    signed = security.make_signed_request({"user_id": "appmira.skin"}, SECRET)  # the app-scoped id works too
    client.post(
        "/auth/instagram/deauthorize",
        content=f"signed_request={signed}",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    assert client.get("/ai/instagram/accounts/be-mira.skin", headers=H).json()["status"] == "revoked"


# ------------------------------------------------------------------ pieces


def test_client_unwraps_meta_shapes_and_strips_hash(ig_client, meta):
    meta.add("mira.skin")
    g = ig_client.exchange_code("code-mira.skin#_")
    assert g.access_token == "short-mira.skin"
    assert g.permissions == ["instagram_business_basic", "instagram_business_manage_insights"]
    assert len(ig_client.media("long-mira.skin-0", limit=30)) == 12  # pages followed to the end


def test_insights_failure_is_soft_but_dead_token_is_not(ig_client, meta):
    meta.add("mira.skin")
    album = next(m for m in meta.users["mira.skin"]["media"] if m["media_type"] == "CAROUSEL_ALBUM")
    assert ig_client.insights("long-mira.skin-0", album["id"]) == {}
    meta.dead.add("mira.skin")
    with pytest.raises(InstagramError) as e:
        ig_client.insights("long-mira.skin-0", album["id"])
    assert e.value.token_dead


def test_ingest_mapping():
    me = {"username": "Mira.Skin", "name": " Mira ", "followers_count": 5000, "follows_count": 300, "media_count": 40}
    today = NOW.date()
    media = [
        {
            "id": "1",
            "media_type": "VIDEO",
            "media_product_type": "REELS",
            "timestamp": "2026-11-12T08:00:00+0000",
            "permalink": "https://www.instagram.com/reel/AAA/?igsh=1",
            "like_count": 10,
            "comments_count": 1,
            "insights": {"views": 1000},
        },
        {
            "id": "2",
            "media_type": "VIDEO",
            "media_product_type": "REELS",
            "timestamp": "2026-09-01T08:00:00+0000",
            "permalink": "https://www.instagram.com/reel/BBB/",
            "insights": {"views": 3000},
        },
        {
            "id": "3",
            "media_type": "VIDEO",
            "media_product_type": "STORY",
            "timestamp": "2026-11-13T08:00:00+0000",
            "permalink": "https://www.instagram.com/stories/x/3/",
        },
        {
            "id": "4",
            "media_type": "IMAGE",
            "media_product_type": "FEED",
            "timestamp": "2026-11-01T08:00:00+0000",
            "permalink": "https://www.instagram.com/p/CCC/",
            "caption": "  ",
        },
    ]
    creator, posts = ingest.to_rows(me, media, "be-1", today, instagram_config())
    assert creator["handle"] == "mira.skin" and creator["display_name"] == "Mira"
    assert creator["avg_views_reported"] == 2000 and creator["posts_last_30d"] == 2
    assert creator["last_post_date"].isoformat() == "2026-11-12"
    assert [p["post_url"] for p in posts] == [
        "https://www.instagram.com/reel/AAA/",
        "https://www.instagram.com/reel/BBB/",
        "https://www.instagram.com/p/CCC/",
    ]
    assert posts[2]["caption"] is None and posts[2]["post_type"] == "photo"
    # No posts seen → don't claim "inactive"
    creator, posts = ingest.to_rows(me, [], "be-1", today, instagram_config())
    assert creator["posts_last_30d"] is None and creator["last_post_date"] is None and posts == []
