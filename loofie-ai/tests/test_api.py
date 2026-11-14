import json
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
    # Numbers sent by the backend are self-reported, so Model 3 won't call them "real" yet;
    # Instagram Login is the way to "real" (test_instagram.py).
    assert body["quality"]["label"] == "unsure" and body["quality"]["activity"] == "active"
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
    # The backend's creator id is stored too: handles change, creator_ref doesn't.
    assert ev.payload == {
        "creator_handle": "anu",
        "creator_ref": "be-anu",
        "reason": "too expensive",
        "suggestion_id": sid,
    }
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


def test_backend_cannot_claim_api_verified(client):
    # Only the AI service's own Instagram fetch may mark numbers API_VERIFIED (test_instagram.py).
    r = client.post("/ai/creators/profile", headers=H, json=creator("trust_me") | {"source": "instagram_api"})
    assert r.status_code == 422
    body = client.post("/ai/creators/profile", headers=H, json=creator("trust_me")).json()
    assert body["metric_sources"]["followers"]["source"] == "SELF_REPORTED"
    assert body["metric_sources"]["city"]["source"] == "SELF_REPORTED"


def test_self_reported_creator_is_unsure_not_real(client):
    body = client.post("/ai/creators/profile", headers=H, json=creator("typed_in")).json()
    assert body["metric_sources"]["followers"]["source"] == "SELF_REPORTED"
    assert body["quality"]["label"] == "unsure" and "self-reported" in body["quality"]["summary"]


# ------------------------------------------------------------------ operations


def test_request_id_and_log_line(client, caplog):
    caplog.set_level("INFO", logger="loofie.api")
    r = client.get("/health", headers={"X-Request-ID": "trace-123"})
    assert r.headers["X-Request-ID"] == "trace-123"
    line = next(json.loads(m) for m in caplog.messages if "trace-123" in m)
    assert (line["path"], line["status"]) == ("/health", 200) and line["ms"] >= 0
    assert client.get("/health").headers["X-Request-ID"]  # generated when not sent


def test_unhandled_error_returns_request_id_not_a_stack_trace(client, monkeypatch):
    import app.api.main as api

    def boom(*a, **k):
        raise RuntimeError("database exploded")

    monkeypatch.setattr(api, "parse", boom)
    r = client.post("/ai/brief/parse", headers=H, json={"text": "x"})
    assert r.status_code == 500 and r.json()["request_id"] == r.headers["X-Request-ID"]
    assert "exploded" not in r.text


# ------------------------------------------------------------------ backend contract: retries, time, ids, keys


def job_event(**over):
    body = {
        "event_type": "job_state_changed",
        "actor_type": "system",
        "subject_type": "job",
        "subject_id": "job-42",
        "payload": {"creator_ref": "be-anu", "job_id": "job-42", "from": "IN_PRODUCTION", "to": "DRAFT_SUBMITTED"},
    }
    return body | over


def test_retry_with_same_event_id_is_saved_once(client, session):
    eid = "6f1c2a52-8a7e-4c55-9d43-1b2f0f3c9a11"
    first = client.post("/ai/feedback", headers=H, json=job_event(event_id=eid))
    again = client.post("/ai/feedback", headers=H, json=job_event(event_id=eid))
    assert first.json() == {"event_id": eid, "duplicate": False}
    assert again.status_code == 200 and again.json() == {"event_id": eid, "duplicate": True}
    assert len(session.scalars(select(Event).where(Event.event_type == "job_state_changed")).all()) == 1
    # Same id, different content: a bug on the caller's side, not a retry.
    other = job_event(event_id=eid, subject_id="job-43")
    assert client.post("/ai/feedback", headers=H, json=other).status_code == 409


def test_occurred_at_is_kept_and_checked(client, session):
    when = "2026-11-10T09:30:00+05:30"
    eid = client.post("/ai/feedback", headers=H, json=job_event(occurred_at=when)).json()["event_id"]
    assert session.get(Event, eid).occurred_at.isoformat() == "2026-11-10T04:00:00+00:00"
    future = (date.today() + timedelta(days=2)).isoformat() + "T00:00:00+00:00"
    assert client.post("/ai/feedback", headers=H, json=job_event(occurred_at=future)).status_code == 422
    no_zone = client.post("/ai/feedback", headers=H, json=job_event(occurred_at="2026-11-10T09:30:00"))
    assert no_zone.status_code == 422


def test_creator_ref_identifies_the_creator(client, session):
    client.post("/ai/creators/profile", headers=H, json=creator("anu"))
    eid = client.post("/ai/feedback", headers=H, json=job_event()).json()["event_id"]
    assert session.get(Event, eid).payload["creator_handle"] == "anu"
    # Unknown to the AI service yet: still accepted and stored by the backend's id.
    unknown = job_event(payload={"creator_ref": "be-new", "job_id": "j", "from": "A", "to": "B"})
    assert client.post("/ai/feedback", headers=H, json=unknown).status_code == 200
    missing = job_event(payload={"job_id": "j", "from": "A", "to": "B"})
    r = client.post("/ai/feedback", headers=H, json=missing)
    assert r.status_code == 422 and "creator_ref" in r.json()["detail"]


def test_payload_values_and_personal_data_are_checked(client):
    perf = {
        "event_type": "video_performance",
        "actor_type": "brand",
        "subject_type": "job",
        "subject_id": "job-42",
        "payload": {"creator_ref": "be-anu", "job_id": "job-42", "result": "amazing"},
    }
    r = client.post("/ai/feedback", headers=H, json=perf)
    assert r.status_code == 422 and "winner" in r.json()["detail"]
    perf["payload"]["result"] = "winner"
    assert client.post("/ai/feedback", headers=H, json=perf).status_code == 200
    leak = job_event(payload={"creator_ref": "x", "job_id": "j", "from": "A", "to": "B", "upi_id": "a@b"})
    r = client.post("/ai/feedback", headers=H, json=leak)
    assert r.status_code == 422 and "upi_id" in r.json()["detail"]


def test_named_keys_rotation_and_caller_in_log(client, monkeypatch, caplog):
    monkeypatch.setenv("AI_API_KEY", "")
    monkeypatch.setenv("AI_API_KEYS", "backend:old-key-123, backend:new-key-456,ops:ops-key-789")
    get_settings.cache_clear()
    caplog.set_level("INFO", logger="loofie.api")
    for k in ("old-key-123", "new-key-456", "ops-key-789"):
        assert client.post("/ai/feedback", headers={"X-API-Key": k}, json=job_event()).status_code == 200
    assert client.post("/ai/feedback", headers={"X-API-Key": KEY}, json=job_event()).status_code == 401
    callers = [json.loads(r.message)["caller"] for r in caplog.records if r.message.startswith("{")]
    assert callers[-4:] == ["backend", "backend", "ops", None]
