"""Unified Login (log in with Instagram, no Facebook Page) and the sync queue under load.

The fake graph.facebook.com answers Unified Login's documented calls: code -> system-user token,
/me?fields=client_business_id, /me/assigned_instagram_accounts, then the Instagram account's
profile, media and insights by Instagram account id. Switches make it fail the ways Meta does:
a 500, a rate limit (code 4, with usage headers), a dead token (190), a reused code.
"""

import threading
from collections import Counter
from datetime import UTC, datetime, timedelta
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import sessionmaker

from app.db.models import Creator, InstagramAccount, InstagramSnapshot, Post
from app.instagram import security
from app.instagram import service as ig
from app.instagram.client import InstagramClient, InstagramError, _usage_percent
from tests.helpers import FakeEmbedder
from tests.test_instagram import media_for

SECRET = "app-secret"
TOKEN_KEY = security.new_token_key()
NOW = datetime(2026, 11, 17, 10, 0, tzinfo=UTC)
REDIRECT = "https://ai.loofie.test/auth/instagram/callback"


class FakeUnified:
    """One Meta app; each login code belongs to a creator who shared some Instagram accounts."""

    def __init__(self):
        self.lock = threading.Lock()
        self.logins: dict[str, list[str]] = {}  # code -> Instagram account ids she shared
        self.accounts: dict[str, dict] = {}  # Instagram account id -> profile + media
        self.used_codes: set[str] = set()
        self.dead: set[str] = set()  # tokens Meta now rejects
        self.fail_next: list[tuple[int, dict]] = []  # queued errors for the next data calls
        self.usage_header: str | None = None
        self.calls: Counter = Counter()

    def add(self, username: str, n_media: int = 12) -> str:
        ig_id = f"1784{abs(hash(username)) % 10**10:010d}"
        self.accounts[ig_id] = {
            "id": ig_id,
            "username": username,
            "name": username.title(),
            "followers_count": 12400,
            "follows_count": 610,
            "media_count": n_media,
            "media": media_for(username, n_media, NOW - timedelta(days=1)),
        }
        return ig_id

    def login(self, code: str, *ig_ids: str) -> None:
        self.logins[code] = list(ig_ids)

    def handler(self, request: httpx.Request) -> httpx.Response:
        url = urlsplit(str(request.url))
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        path = url.path.removeprefix("/v27.0")
        assert url.netloc == "graph.facebook.com", url.netloc
        headers = {"x-business-use-case-usage": self.usage_header} if self.usage_header else {}
        with self.lock:
            self.calls[path.split("/")[1] if path.count("/") > 1 else path] += 1
            if path == "/oauth/access_token":
                assert q["client_secret"] == SECRET and "fb_exchange_token" not in q
                code = q["code"]
                if code not in self.logins or code in self.used_codes:
                    return httpx.Response(
                        400, json={"error": {"message": "This authorization code has been used", "code": 100}}
                    )
                self.used_codes.add(code)
                return httpx.Response(200, json={"access_token": f"suat|{code}", "token_type": "bearer"})
            token = q.get("access_token", "")
            if token in self.dead:
                return httpx.Response(400, json={"error": {"message": "Error validating access token", "code": 190}})
            code = token.removeprefix("suat|")
            if path == "/me":
                assert q["fields"] == "id,client_business_id"
                return httpx.Response(200, json={"id": f"sys-{code}", "client_business_id": f"biz-{code}"})
            if path == "/me/permissions":
                perms = ["instagram_basic", "instagram_manage_insights", "public_profile"]
                return httpx.Response(200, json={"data": [{"permission": p, "status": "granted"} for p in perms]})
            if path == "/me/assigned_instagram_accounts":
                shared = [{"id": i, "username": self.accounts[i]["username"]} for i in self.logins[code]]
                return httpx.Response(200, json={"data": shared, "paging": {"cursors": {"before": "a", "after": "b"}}})
            if self.fail_next:
                status, body = self.fail_next.pop(0)
                return httpx.Response(status, json=body, headers=headers)
            parts = path.strip("/").split("/")
            if len(parts) == 1 and parts[0] in self.accounts:
                a = self.accounts[parts[0]]
                assert "user_id" not in q["fields"] and "account_type" not in q["fields"]
                return httpx.Response(200, json={k: v for k, v in a.items() if k != "media"}, headers=headers)
            if len(parts) == 2 and parts[1] == "media":
                items = [
                    {k: v for k, v in m.items() if not k.startswith("_")} for m in self.accounts[parts[0]]["media"]
                ]
                return httpx.Response(200, json={"data": items}, headers=headers)
            if len(parts) == 2 and parts[1] == "insights":
                m = next(m for a in self.accounts.values() for m in a["media"] if m["id"] == parts[0])
                if m["media_type"] == "CAROUSEL_ALBUM":
                    return httpx.Response(400, json={"error": {"message": "not supported for albums", "code": 100}})
                vals = [
                    {"name": "views", "values": [{"value": m["_views"]}]},
                    {"name": "reach", "values": [{"value": 99}]},
                ]
                return httpx.Response(200, json={"data": vals}, headers=headers)
        return httpx.Response(404, json={"error": {"message": f"unexpected {path}", "code": 803}})


@pytest.fixture
def meta():
    return FakeUnified()


@pytest.fixture
def client(meta):
    return InstagramClient(
        "123", SECRET, REDIRECT, httpx.Client(transport=httpx.MockTransport(meta.handler)), "unified", "cfg-77"
    )


def state(ref: str, when: datetime = NOW) -> str:
    return security.sign_state(ref, TOKEN_KEY, 600, now=when.timestamp())


def connect(session, client, meta, username: str, ref: str | None = None, when: datetime = NOW):
    ig_id = meta.add(username)
    meta.login(f"code-{username}", ig_id)
    acct = ig.connect(session, client, f"code-{username}", state(ref or f"be-{username}", when), TOKEN_KEY, when)
    acct.status = "syncing"
    ig.enqueue(acct, when)
    session.commit()
    return acct


# ------------------------------------------------------------------ login


def test_login_link_is_unified_with_the_configuration(client):
    parts = urlsplit(client.authorize_url("STATE"))
    q = {k: v[0] for k, v in parse_qs(parts.query).items()}
    assert f"{parts.netloc}{parts.path}" == "www.facebook.com/v27.0/dialog/oauth"
    assert q["config_id"] == "cfg-77" and q["response_type"] == "code" and q["state"] == "STATE"
    assert "scope" not in q and "override_default_response_type" not in q


def test_unified_needs_a_configuration():
    with pytest.raises(ValueError, match="IG_FB_CONFIG_ID"):
        InstagramClient("123", SECRET, REDIRECT, flavor="unified", config_id="")


def test_connect_then_sync_builds_a_verified_passport_without_any_page(session, client, meta):
    acct = connect(session, client, meta, "anan")
    assert acct.business_id == "biz-code-anan" and acct.app_user_id == "sys-code-anan"
    assert acct.token_expires_at is None  # system-user token: Meta gave no expiry
    assert ig.has_insights(acct.permissions)

    assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW) == Counter(synced=1)
    session.refresh(acct)
    assert acct.status == "active" and acct.next_sync_at is None and acct.lease_until is None
    c = session.scalar(select(Creator).where(Creator.handle == "anan"))
    assert c.followers == 12400 and c.metric_sources["followers"]["source"] == "API_VERIFIED"
    posts = session.scalars(select(Post).where(Post.creator_id == c.id)).all()
    assert any(p.views for p in posts)


def test_insights_only_for_the_newest_posts(session, client, meta):
    connect(session, client, meta, "busy.creator")
    meta.accounts[next(iter(meta.accounts))]["media"] = media_for("busy.creator", 30, NOW)
    ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW)
    insight_calls = sum(n for k, n in meta.calls.items() if k not in {"/oauth/access_token", "/me", "/me/permissions"})
    assert insight_calls < 30  # profile + media + at most 12 insights, not one per post


@pytest.mark.parametrize(
    ("shared", "reason"),
    [(0, "no_instagram"), (2, "several_accounts")],
)
def test_no_account_or_several_accounts_are_refused_not_guessed(session, client, meta, shared, reason):
    ids = [meta.add(f"acc{i}") for i in range(shared)]
    meta.login("code-x", *ids)
    with pytest.raises(ig.ConnectError) as e:
        ig.connect(session, client, "code-x", state("be-x"), TOKEN_KEY, NOW)
    assert e.value.reason == reason
    assert session.scalar(select(func.count()).select_from(InstagramAccount)) == 0


def test_reconnect_with_two_accounts_keeps_her_own(session, client, meta):
    acct = connect(session, client, meta, "mira")
    other = meta.add("mira.brand")
    meta.login("code-again", acct.ig_user_id, other)
    again = ig.connect(
        session, client, "code-again", state("be-mira", NOW + timedelta(days=1)), TOKEN_KEY, NOW + timedelta(days=1)
    )
    assert again.id == acct.id and again.ig_user_id == acct.ig_user_id


def test_a_repeated_callback_is_not_a_failure(session, client, meta):
    acct = connect(session, client, meta, "twice")
    # Same code again (double tap / browser retry): Meta refuses the reused code, we still say connected.
    same = ig.connect(session, client, "code-twice", state("be-twice"), TOKEN_KEY, NOW + timedelta(seconds=5))
    assert same.id == acct.id
    # ...but a reused code long after is a real failure.
    with pytest.raises(ig.ConnectError):
        ig.connect(
            session,
            client,
            "code-twice",
            state("be-twice", NOW + timedelta(hours=1)),
            TOKEN_KEY,
            NOW + timedelta(hours=1),
        )


def test_same_instagram_for_a_second_loofie_account_is_refused(session, client, meta):
    acct = connect(session, client, meta, "solo")
    meta.login("code-steal", acct.ig_user_id)
    with pytest.raises(ig.ConnectError) as e:
        ig.connect(session, client, "code-steal", state("be-someone-else"), TOKEN_KEY, NOW)
    assert e.value.reason == "already_linked"


# ------------------------------------------------------------------ queue: failures


def test_meta_hiccup_is_retried_with_growing_delays_then_given_up(session, client, meta):
    acct = connect(session, client, meta, "flaky")
    delays = ig.instagram_config()["queue"]["retry_delays_seconds"]
    when = NOW
    for attempt, delay in enumerate(delays, 1):
        meta.fail_next = [(500, {"error": {"message": "An unexpected error has occurred", "code": 2}})]
        assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, when) == Counter(retry=1)
        session.refresh(acct)
        assert acct.status == "error" and acct.sync_attempts == attempt
        assert acct.next_sync_at == when + timedelta(seconds=delay)
        # Not due yet: nothing happens before the delay is over.
        assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, when + timedelta(seconds=delay - 1)) == Counter()
        when = when + timedelta(seconds=delay)
    meta.fail_next = [(500, {"error": {"message": "still down", "code": 2}})]
    assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, when) == Counter(failed=1)
    session.refresh(acct)
    assert acct.next_sync_at is None and acct.status == "error"  # waits for the weekly re-sync / ops button


def test_a_hiccup_then_success_clears_the_retry_count(session, client, meta):
    acct = connect(session, client, meta, "recovers")
    meta.fail_next = [(503, {"error": {"message": "Service temporarily unavailable", "code": 2}})]
    ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW)
    assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW + timedelta(minutes=2)) == Counter(synced=1)
    session.refresh(acct)
    assert (acct.status, acct.sync_attempts, acct.next_sync_at) == ("active", 0, None)


def test_rate_limit_pauses_every_worker_and_retries(session, client, meta):
    acct = connect(session, client, meta, "limited")
    meta.fail_next = [(400, {"error": {"message": "Application request limit reached", "code": 4}})]
    assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW) == Counter(retry=1)
    assert client.pause_seconds() > 200  # every worker sharing this client now waits
    session.refresh(acct)
    assert acct.next_sync_at is not None


def test_usage_headers_slow_down_before_meta_refuses(client):
    worst, regain = _usage_percent(
        httpx.Headers(
            {
                "x-business-use-case-usage": '{"biz": [{"call_count": 91, "total_time": 10, "estimated_time_to_regain_access": 0}]}'
            }
        )
    )
    assert (worst, regain) == (91, 0)
    client._note_usage(httpx.Headers({"x-app-usage": '{"call_count": 88, "total_time": 5, "total_cputime": 3}'}))
    assert client.pause_seconds() > 0
    assert _usage_percent(httpx.Headers({"x-app-usage": "not json"})) == (0, 0)  # garbage never crashes


def test_dead_token_stops_for_good(session, client, meta):
    acct = connect(session, client, meta, "revoked")
    meta.dead.add("suat|code-revoked")
    assert ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW) == Counter(expired=1)
    session.refresh(acct)
    assert (acct.status, acct.token_encrypted, acct.next_sync_at) == ("expired", None, None)


def test_error_classification():
    assert InstagramError("x", status=None).retryable  # Meta unreachable
    assert InstagramError("x", status=500, code=2).retryable
    assert InstagramError("x", status=400, code=4).throttled
    assert not InstagramError("x", status=400, code=190).retryable
    assert not InstagramError("x", status=400, code=100).retryable  # a bad request stays bad


# ------------------------------------------------------------------ queue: many at once


def test_a_lease_keeps_two_workers_off_the_same_creator(session, engine, client, meta):
    acct = connect(session, client, meta, "leased")
    other = sessionmaker(engine)()
    try:
        assert ig.claim(session, NOW) == [acct.id]
        assert ig.claim(other, NOW) == []  # leased: the second worker gets nothing
        later = NOW + timedelta(seconds=ig.instagram_config()["queue"]["lease_seconds"] + 1)
        assert ig.claim(other, later) == [acct.id]  # the first worker died: the lease ran out, work resumes
    finally:
        other.close()


def test_thirty_creators_two_workers_each_synced_exactly_once(session, engine, client, meta):
    accts = [connect(session, client, meta, f"creator{i:02d}") for i in range(30)]
    results: list[Counter] = []

    def worker():
        with sessionmaker(engine)() as s:
            results.append(ig.drain(s, FakeEmbedder(), client, TOKEN_KEY, NOW))

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=120)

    assert sum(r["synced"] for r in results) == 30 and all(not r["failed"] for r in results)
    session.expire_all()
    per_account = dict(
        session.execute(select(InstagramSnapshot.account_id, func.count()).group_by(InstagramSnapshot.account_id)).all()
    )
    assert per_account == {a.id: 1 for a in accts}  # nobody fetched twice, nobody missed
    handles = set(session.scalars(select(Creator.handle)))
    assert handles == {f"creator{i:02d}" for i in range(30)}  # every creator's data under her own name


def test_maintain_with_non_expiring_tokens_never_tries_to_refresh(session, client, meta):
    acct = connect(session, client, meta, "forever")
    ig.drain(session, FakeEmbedder(), client, TOKEN_KEY, NOW)
    counts = ig.maintain(session, FakeEmbedder(), client, TOKEN_KEY, NOW + timedelta(days=200))
    assert counts["refreshed"] == 0 and counts["synced"] == 1 and counts["expired"] == 0
    session.refresh(acct)
    assert acct.status == "active" and acct.token_expires_at is None
