from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.api.main import app, get_session
from app.config import get_settings
from app.db.models import Event
from tests.helpers import FakeEmbedder

KEY = "test-key"
H = {"X-API-Key": KEY}
TODAY = date.today()


@pytest.fixture
def client(session, monkeypatch):
    monkeypatch.setenv("AI_API_KEY", KEY)
    get_settings.cache_clear()
    app.state.embedder = FakeEmbedder()
    app.dependency_overrides[get_session] = lambda: session
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    app.state.embedder = None
    get_settings.cache_clear()


def creator(handle, followers=12000, views=3500, likes=350, comments=30, days=2, bio="honest skincare reviews"):
    return {
        "handle": handle,
        "creator_ref": f"be-{handle}",
        "followers": followers,
        "following": 500,
        "city": "Bengaluru",
        "bio": bio,
        "primary_language": "Kannada",
        "posts": [
            {
                "post_url": f"https://www.instagram.com/reel/{handle}{i}/?utm_source=x",
                "post_date": str(TODAY - timedelta(days=days + 3 * i)),
                "post_type": "reel",
                "views": views,
                "likes": likes,
                "comments": comments,
                "caption": f"sunscreen routine {i}",
            }
            for i in range(6)
        ],
    }


# ------------------------------------------------------------------ auth and health


def test_key_required(client):
    assert client.post("/ai/brief/parse", json={"text": "x"}).status_code == 401
    assert client.post("/ai/brief/parse", json={"text": "x"}, headers={"X-API-Key": "wrong"}).status_code == 401


def test_unconfigured_key_refuses_everything(client, monkeypatch):
    monkeypatch.setenv("AI_API_KEY", "")
    get_settings.cache_clear()
    assert client.post("/ai/brief/parse", json={"text": "x"}, headers=H).status_code == 503


def test_health_needs_no_key(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["status"] == "ok"
    assert set(r.json()["versions"]) == {"brief", "quality", "matcher"}


# ------------------------------------------------------------------ brief


def test_brief_parse_returns_fields_and_logs_event(client, session):
    r = client.post(
        "/ai/brief/parse",
        headers=H,
        json={
            "text": "need 3 girls blr for sunscreen reels, 15k total, by 5th dec",
            "as_of": "2026-11-14",
            "brief_ref": "br-9",
        },
    )
    assert r.status_code == 200
    f = r.json()["fields"]
    assert f["creators_needed"] == {"value": 3, "confidence": "high", "evidence": "3 girls"}
    assert f["deadline"]["value"] == "2026-12-05"
    assert f["city"]["value"] == "Bengaluru"
    ev = session.get(Event, r.json()["event_id"])
    assert (ev.event_type, ev.subject_id, ev.model_name) == ("brief_parsed", "br-9", "brief")


def test_brief_parse_rejects_empty_text(client):
    assert client.post("/ai/brief/parse", headers=H, json={"text": ""}).status_code == 422


# ------------------------------------------------------------------ creators


def test_creator_profile_builds_profile_and_quality(client):
    r = client.post("/ai/creators/profile", headers=H, json=creator("@Mira.Skin"))
    assert r.status_code == 200, r.text
    body = r.json()
    # The handle is cleaned; the backend's own id is stored exactly as sent.
    assert body["handle"] == "mira.skin" and body["creator_ref"] == "be-@Mira.Skin"
    assert body["stats"]["median_views"] == 3500 and body["missing"] == []
    assert body["quality"]["label"] == "real" and body["quality"]["activity"] == "active"
    assert client.get("/ai/creators/mira.skin", headers=H).json()["handle"] == "mira.skin"


def test_creator_update_replaces_profile_and_keeps_old_values(client):
    client.post(
        "/ai/creators/profile", headers=H, json=creator("glow", followers=41000, views=400, likes=2200, comments=3)
    )
    first = client.get("/ai/creators/glow", headers=H).json()
    assert first["quality"]["label"] == "suspicious"
    update = {"handle": "glow", "followers": 41000}  # no city, no posts: nothing stored is erased
    r = client.post("/ai/creators/profile", headers=H, json=update).json()
    assert r["stats"]["median_views"] == 400 and r["quality"]["label"] == "suspicious"


def test_creator_bad_input(client):
    assert (
        client.post("/ai/creators/profile", headers=H, json={"handle": "https://instagram.com/reel/x/"}).status_code
        == 422
    )
    bad_post = creator("ok_handle")
    bad_post["posts"][0]["post_url"] = "https://modash.io/x"
    assert client.post("/ai/creators/profile", headers=H, json=bad_post).status_code == 422
    assert client.post("/ai/creators/profile", headers=H, json={"handle": "x", "followers": -5}).status_code == 422


def test_unknown_creator_is_404(client):
    assert client.get("/ai/creators/nobody_here", headers=H).status_code == 404


# ------------------------------------------------------------------ match + feedback loop


def seed(client):
    client.post("/ai/creators/profile", headers=H, json=creator("sara"))
    client.post("/ai/creators/profile", headers=H, json=creator("anu"))
    client.post(
        "/ai/creators/profile", headers=H, json=creator("glow", followers=41000, views=400, likes=2200, comments=3)
    )
    client.post("/ai/creators/profile", headers=H, json=creator("sleepy", days=150))


def test_match_filters_ranks_and_returns_suggestion_id(client, session):
    seed(client)
    r = client.post(
        "/ai/match",
        headers=H,
        json={
            "text": "gel sunscreen for oily skin",
            "category": "beauty_skincare",
            "city": "Bangalore",
            "languages": ["Kannada"],
            "budget_per_creator_inr": 5000,
            "brief_ref": "br-1",
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert {i["handle"] for i in body["results"]} == {"sara", "anu"}
    assert body["left_out"] == {"flagged suspicious": 1, "inactive": 1}
    top = body["results"][0]
    assert top["rank"] == 1 and top["creator_ref"].startswith("be-")
    assert top["estimated_rate_inr"] == {"low": 3000, "high": 7000}
    assert "speaks Kannada" in top["why"]
    ev = session.get(Event, body["suggestion_id"])
    assert ev.event_type == "shortlist_suggested" and len(ev.payload["results"]) == 2


def test_match_excludes_already_picked(client):
    seed(client)
    r = client.post(
        "/ai/match", headers=H, json={"text": "sunscreen", "category": "beauty_skincare", "exclude_handles": ["@sara"]}
    ).json()
    assert [i["handle"] for i in r["results"]] == ["anu"]


def test_feedback_links_to_suggestion(client, session):
    seed(client)
    sid = client.post("/ai/match", headers=H, json={"text": "sunscreen", "category": "beauty_skincare"}).json()[
        "suggestion_id"
    ]
    r = client.post(
        "/ai/feedback",
        headers=H,
        json={
            "event_type": "shortlist_reject",
            "actor_type": "brand",
            "actor_id": "brand-7",
            "subject_type": "shortlist",
            "subject_id": "br-1",
            "suggestion_id": sid,
            "payload": {"creator_handle": "anu", "reason": "too expensive"},
        },
    )
    assert r.status_code == 200, r.text
    ev = session.get(Event, r.json()["event_id"])
    assert ev.payload == {"creator_handle": "anu", "reason": "too expensive", "suggestion_id": sid}
    assert len(session.scalars(select(Event)).all()) == 2


def test_feedback_validation(client):
    base = {"actor_type": "brand", "subject_type": "brief", "subject_id": "b1"}
    assert client.post("/ai/feedback", headers=H, json=base | {"event_type": "liked_it"}).status_code == 422
    r = client.post(
        "/ai/feedback", headers=H, json=base | {"event_type": "shortlist_reject", "payload": {"creator_handle": "x"}}
    )
    assert r.status_code == 422 and "reason" in r.json()["detail"]
    r = client.post(
        "/ai/feedback",
        headers=H,
        json=base | {"event_type": "brief_confirmed", "suggestion_id": "00000000-0000-0000-0000-000000000000"},
    )
    assert r.status_code == 404
    assert client.post("/ai/feedback", headers=H, json=base | {"event_type": "brief_confirmed"}).status_code == 200


# ------------------------------------------------------------------ Foundation v2 rules


def test_disclosure_is_returned_and_locked(client):
    f = client.post("/ai/brief/parse", headers=H, json={"text": "need 2 creators, skip the #ad"}).json()["fields"]
    assert f["disclosure"]["confidence"] == "fixed" and "#ad" in f["disclosure"]["value"]
    r = client.post(
        "/ai/feedback",
        headers=H,
        json={
            "event_type": "brief_field_edited",
            "actor_type": "brand",
            "subject_type": "brief",
            "subject_id": "b1",
            "payload": {"field": "disclosure", "old": f["disclosure"]["value"], "new": ""},
        },
    )
    assert r.status_code == 422 and "cannot be edited" in r.json()["detail"]


def test_metric_sources_and_trust_order_through_the_api(client):
    body = client.post("/ai/creators/profile", headers=H, json=creator("trust_me")).json()
    assert body["metric_sources"]["followers"]["source"] == "API_VERIFIED"
    assert body["quality"]["label"] == "real"

    typed = {"handle": "trust_me", "source": "manual_profile", "followers": 99000, "city": "Mysuru"}
    body = client.post("/ai/creators/profile", headers=H, json=typed).json()
    assert body["update_notes"] == ["@trust_me followers: kept 12000 (API_VERIFIED) over 99000 (SELF_REPORTED)"]
    assert body["metric_sources"]["followers"]["source"] == "API_VERIFIED"
    # A description is the creator's to correct: latest wins, source recorded.
    assert client.get("/ai/creators/trust_me", headers=H).json()["metric_sources"]["city"]["source"] == "SELF_REPORTED"


def test_self_reported_creator_is_unsure_not_real(client):
    body = client.post(
        "/ai/creators/profile", headers=H, json=creator("typed_in") | {"source": "manual_profile"}
    ).json()
    assert body["metric_sources"]["followers"]["source"] == "SELF_REPORTED"
    assert body["quality"]["label"] == "unsure" and "self-reported" in body["quality"]["summary"]
