"""Instagram through Facebook Login (the only login Meta offers apps created after 2025).

A fake graph.facebook.com answers in Meta's documented shapes: code -> token, long-lived exchange,
/me/accounts with the Instagram account linked to a Page, then profile, posts and insights.
"""

from datetime import UTC, datetime, timedelta
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest
from sqlalchemy import select

from app.db.models import Creator, InstagramAccount, Post
from app.instagram import security
from app.instagram import service as ig
from app.instagram.client import InstagramClient, InstagramError, NoLinkedInstagram
from tests.helpers import FakeEmbedder
from tests.test_instagram import media_for

SECRET = "app-secret"
TOKEN_KEY = security.new_token_key()
NOW = datetime(2026, 11, 17, 10, 0, tzinfo=UTC)
REDIRECT = "https://ai.loofie.test/auth/instagram/callback"


class FakeFacebook:
    def __init__(self, pages: list[dict], perms: list[str] | None = None):
        self.pages = pages
        self.perms = perms or ["instagram_basic", "instagram_manage_insights", "pages_show_list", "public_profile"]
        self.calls: list[str] = []
        self.media = media_for("anan", 12, NOW - timedelta(days=1))

    def handler(self, request: httpx.Request) -> httpx.Response:
        url = urlsplit(str(request.url))
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        self.calls.append(f"{url.netloc}{url.path}")
        assert url.netloc == "graph.facebook.com"
        if url.path.endswith("/oauth/access_token"):
            assert q["client_secret"] == SECRET
            if q.get("grant_type") == "fb_exchange_token":
                return httpx.Response(
                    200, json={"access_token": "long-fb", "token_type": "bearer", "expires_in": 5183999}
                )
            if q["code"] != "good-code":
                return httpx.Response(
                    400, json={"error": {"message": "This authorization code has expired", "code": 100}}
                )
            return httpx.Response(200, json={"access_token": "short-fb", "token_type": "bearer"})
        if q.get("access_token") not in {"short-fb", "long-fb"}:
            return httpx.Response(400, json={"error": {"message": "Invalid OAuth access token", "code": 190}})
        if url.path.endswith("/me/permissions"):
            data = [{"permission": p, "status": "granted"} for p in self.perms] + [
                {"permission": "pages_manage_posts", "status": "declined"}
            ]
            return httpx.Response(200, json={"data": data})
        if url.path.endswith("/me/accounts"):
            assert "instagram_business_account{" in q["fields"] and "user_id" not in q["fields"]
            return httpx.Response(200, json={"data": self.pages})
        if url.path.endswith("/me"):
            return httpx.Response(200, json={"id": "fb-user-1"})
        if url.path.endswith("/17841400000000001/media"):
            return httpx.Response(
                200, json={"data": [{k: v for k, v in m.items() if not k.startswith("_")} for m in self.media]}
            )
        if url.path.endswith("/insights"):
            media_id = url.path.split("/")[-2]
            m = next(m for m in self.media if m["id"] == media_id)
            if m["media_type"] == "CAROUSEL_ALBUM":
                return httpx.Response(400, json={"error": {"message": "not supported for albums", "code": 100}})
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"name": "views", "values": [{"value": m["_views"]}]},
                        {"name": "reach", "values": [{"value": m["_views"] // 2}]},
                    ]
                },
            )
        if url.path.endswith("/17841400000000001"):
            fields = q["fields"].split(",")
            assert "user_id" not in fields and "account_type" not in fields
            return httpx.Response(200, json={"id": "17841400000000001", "username": "anan"})
        return httpx.Response(404, json={"error": {"message": f"unexpected {url.path}", "code": 803}})


LINKED = {
    "id": "page-1",
    "name": "Loofie Test",
    "instagram_business_account": {
        "id": "17841400000000001",
        "username": "Anan",
        "name": "Ananya",
        "followers_count": 12400,
        "follows_count": 610,
        "media_count": 12,
    },
}


def client_for(fake: FakeFacebook, config_id: str = "") -> InstagramClient:
    return InstagramClient(
        "123", SECRET, REDIRECT, httpx.Client(transport=httpx.MockTransport(fake.handler)), "facebook", config_id
    )


def test_login_link_uses_facebook_and_the_configuration():
    url = client_for(FakeFacebook([LINKED]), config_id="999").authorize_url("STATE")
    parts = urlsplit(url)
    q = {k: v[0] for k, v in parse_qs(parts.query).items()}
    assert f"{parts.netloc}{parts.path}" == "www.facebook.com/v27.0/dialog/oauth"
    assert q["config_id"] == "999" and q["state"] == "STATE" and q["redirect_uri"] == REDIRECT
    assert "scope" not in q  # the configuration carries the permissions
    bare = parse_qs(urlsplit(client_for(FakeFacebook([LINKED])).authorize_url("S")).query)
    assert "instagram_manage_insights" in bare["scope"][0] and "config_id" not in bare


def test_code_to_long_lived_token_and_profile():
    fake = FakeFacebook([{"id": "page-0", "name": "No Instagram here"}, LINKED])
    c = client_for(fake)
    grant = c.exchange_code("good-code")
    assert grant.user_id == "fb-user-1" and "instagram_manage_insights" in grant.permissions
    assert "pages_manage_posts" not in grant.permissions  # declined permissions don't count
    token, expires = c.long_lived(grant)
    assert token == "long-fb" and expires == 5183999
    me = c.me(token)
    assert me["user_id"] == "17841400000000001" and me["followers_count"] == 12400
    assert me["account_type"] == "PROFESSIONAL"  # Page-linked accounts are always professional


def test_a_bad_or_used_code_is_an_error_not_a_crash():
    with pytest.raises(InstagramError) as e:
        client_for(FakeFacebook([LINKED])).exchange_code("old-code")
    assert "expired" in str(e.value)


def test_no_page_means_no_instagram_and_says_so():
    with pytest.raises(NoLinkedInstagram):
        client_for(FakeFacebook([{"id": "page-0", "name": "Page without Instagram"}])).me("long-fb")
    with pytest.raises(NoLinkedInstagram):
        client_for(FakeFacebook([])).me("long-fb")


def test_the_whole_connect_and_sync_with_real_data_shapes(session):
    fake = FakeFacebook([LINKED])
    c = client_for(fake)
    acct = ig.connect(
        session, c, "good-code", security.sign_state("be-9", TOKEN_KEY, 600, now=NOW.timestamp()), TOKEN_KEY, NOW
    )
    session.commit()
    assert acct.username == "anan" and acct.ig_user_id == "17841400000000001"
    assert acct.app_user_id == "fb-user-1" and ig.has_insights(acct.permissions)
    assert acct.token_expires_at == NOW + timedelta(seconds=5183999)

    ig.sync(session, FakeEmbedder(), c, acct, TOKEN_KEY, NOW)
    session.commit()
    creator = session.scalar(select(Creator).where(Creator.handle == "anan"))
    assert creator.followers == 12400 and creator.metric_sources["followers"]["source"] == "API_VERIFIED"
    posts = session.scalars(select(Post).where(Post.creator_id == creator.id)).all()
    assert posts and any(p.views for p in posts)  # insights came through
    assert acct.status == "active"
    assert "graph.facebook.com/v27.0/17841400000000001/media" in " ".join(fake.calls)


def test_a_creator_without_a_page_gets_a_clear_reason(session):
    c = client_for(FakeFacebook([{"id": "page-0", "name": "No IG"}]))
    with pytest.raises(ig.ConnectError) as e:
        ig.connect(
            session, c, "good-code", security.sign_state("be-9", TOKEN_KEY, 600, now=NOW.timestamp()), TOKEN_KEY, NOW
        )
    assert e.value.reason == "no_page"
    assert session.scalar(select(InstagramAccount)) is None


def test_renewing_a_facebook_token_exchanges_it_again():
    c = client_for(FakeFacebook([LINKED]))
    token, expires = c.refresh("long-fb")
    assert token == "long-fb" and expires > 0
