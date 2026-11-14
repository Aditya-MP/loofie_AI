"""The contract with the backend: contracts/examples/*.json.

1. Every example fits the schemas in app/api/schemas.py.
2. The real service, replaying the story, answers with the same status and the same shape.
3. The mock server (app/mock.py) answers every example the same way, and enforces the same
   /ai/feedback rules, so code written against the mock works against the real service.

A failure here after a schema change means: run `uv run python scripts/make_examples.py`, read
the diff, and tell the backend what changed.
"""

import json

import pytest
from fastapi.testclient import TestClient

from app.api import schemas
from app.api.main import app as real_app
from app.api.main import get_session
from app.config import ROOT, get_settings
from app.mock import State
from app.mock import app as mock_app
from tests.helpers import FakeEmbedder

EXAMPLES = sorted((ROOT / "contracts" / "examples").glob("*.json"))
STORY = [json.loads(f.read_text(encoding="utf-8")) for f in EXAMPLES]
KEY = "example-key"
H = {"X-API-Key": KEY}
IG_ENV = {
    "IG_APP_ID": "1234567890",
    "IG_APP_SECRET": "example-secret",
    "IG_REDIRECT_URI": "https://ai.loofie.example/auth/instagram/callback",
    "IG_TOKEN_KEY": "Kx6l9v2wq0mH1p3s5u7y9A0cE2gI4kM6oQ8sU0wY2a4=",
    "APP_RETURN_URL": "",
}
# Which schema answers which call (by path prefix), and which schema checks the request body.
RESPONSE = {
    "POST /ai/brief/parse": schemas.BriefParseOut,
    "POST /ai/creators/profile": schemas.ProfileOut,
    "GET /ai/creators/": schemas.ProfileOut,
    "POST /ai/match": schemas.MatchOut,
    "POST /ai/feedback": schemas.FeedbackOut,
    "GET /ai/instagram/login-url": schemas.InstagramLoginOut,
    "GET /ai/instagram/accounts/": schemas.InstagramAccountOut,
}
REQUEST = {
    "POST /ai/brief/parse": schemas.BriefParseIn,
    "POST /ai/creators/profile": schemas.CreatorIn,
    "POST /ai/match": schemas.MatchIn,
    "POST /ai/feedback": schemas.FeedbackIn,
}
# Data-dependent dicts: their keys vary with the creator or brief, so only their presence is compared.
FREE_FORM = {"stats", "parts", "findings", "metric_sources", "left_out", "estimated_rate_inr", "payload"}


def schema_for(table: dict, call: str):
    return next(v for k, v in table.items() if call.split("?")[0].startswith(k))


def shape(x, key=None):
    if key in FREE_FORM:
        return "free-form"
    if isinstance(x, dict):
        return {k: shape(v, k) for k, v in x.items()}
    if isinstance(x, list):
        return [shape(x[0])] if x else []
    return "value"


def same_shape(a, b) -> bool:
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(same_shape(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return not a or not b or same_shape(a[0], b[0])
    return a == b


def send(client, ex, ids=None):
    """Send one example; `ids` maps ids from the example files to the ids this run produced."""
    method, target = ex["call"].split()
    path, _, query = target.partition("?")
    body = ex.get("request")
    if body is not None and ids:
        text = json.dumps(body)
        for old, new in ids.items():
            text = text.replace(old, new)
        body = json.loads(text)
    params = dict(kv.split("=", 1) for kv in query.split("&")) if query else None
    return client.request(method, path, headers=H, json=body, params=params)


def test_shape_check_catches_a_renamed_or_missing_field():
    good = {"results": [{"rank": 1, "handle": "a"}], "stats": {"x": 1}}
    assert same_shape(shape(good), shape({"results": [{"rank": 2, "handle": "b"}], "stats": {"y": 2}}))
    assert not same_shape(shape(good), shape({"results": [{"rank": 1, "creator": "a"}], "stats": {}}))
    assert not same_shape(shape(good), shape({"results": []}))


@pytest.mark.parametrize("ex", STORY, ids=[f.stem for f in EXAMPLES])
def test_example_fits_the_schemas(ex):
    assert {"id", "title", "scene", "call", "status", "response"} <= ex.keys()
    if "request" in ex:
        schema_for(REQUEST, ex["call"]).model_validate(ex["request"])
    if ex["status"] == 200:
        schema_for(RESPONSE, ex["call"]).model_validate(ex["response"])
    else:
        assert isinstance(ex["response"]["detail"], str)


def test_story_covers_every_feedback_type():
    from app.feedback.events import FEEDBACK_TYPES

    sent = {ex["request"]["event_type"] for ex in STORY if ex["call"] == "POST /ai/feedback" and ex["status"] == 200}
    # Not in the story yet; add an example when a screen sends one.
    later = {"ops_added_creator", "ops_removed_creator", "quality_override", "niche_override", "shortlist_maybe"}
    assert set(FEEDBACK_TYPES) - sent == later


# ------------------------------------------------------------------ the real service


@pytest.fixture
def real(session, monkeypatch):
    monkeypatch.setenv("AI_API_KEY", KEY)
    for k, v in IG_ENV.items():
        monkeypatch.setenv(k, v)
    get_settings.cache_clear()
    real_app.state.embedder = FakeEmbedder()
    real_app.dependency_overrides[get_session] = lambda: session
    with TestClient(real_app) as c:
        yield c
    real_app.dependency_overrides.clear()
    real_app.state.embedder = real_app.state.instagram = None
    get_settings.cache_clear()


def test_real_service_answers_like_the_examples(real):
    ids: dict[str, str] = {}
    for ex in STORY:
        if ex.get("replay") is False:
            continue
        r = send(real, ex, ids)
        assert r.status_code == ex["status"], (ex["title"], r.text)
        assert same_shape(shape(r.json()), shape(ex["response"])), (ex["title"], r.json())
        for k in ("event_id", "suggestion_id"):  # later examples point at these
            if ex["status"] == 200 and k in ex["response"] and ex["call"] != "POST /ai/feedback":
                ids[str(ex["response"][k])] = str(r.json()[k])


# ------------------------------------------------------------------ the mock


@pytest.fixture
def mock(monkeypatch):
    monkeypatch.setenv("AI_API_KEY", KEY)  # configured key: the mock checks it like production
    monkeypatch.setenv("APP_RETURN_URL", "")
    get_settings.cache_clear()
    mock_app.state.mock = State()
    with TestClient(mock_app) as c:
        yield c
    get_settings.cache_clear()


def test_mock_answers_like_the_examples(mock):
    for ex in STORY:
        if ex.get("replay") is False:
            continue
        r = send(mock, ex)
        assert r.status_code == ex["status"], (ex["title"], r.text)
        assert r.headers["X-Loofie-Mock"] == "true"
        assert same_shape(shape(r.json()), shape(ex["response"])), (ex["title"], r.json())


def test_mock_reads_any_brief_with_the_real_rules(mock):
    r = mock.post("/ai/brief/parse", headers=H, json={"text": "2 creators in chennai, tamil, 8k each, by 20th jan"})
    f = r.json()["fields"]
    assert (f["creators_needed"]["value"], f["city"]["value"], f["languages"]["value"]) == (2, "Chennai", ["Tamil"])
    assert f["disclosure"]["confidence"] == "fixed"


def test_mock_still_needs_a_key_header(mock):
    assert mock.post("/ai/brief/parse", json={"text": "x"}).status_code == 401


def test_mock_match_respects_exclude_and_top_k(mock):
    r = mock.post(
        "/ai/match", headers=H, json={"text": "sunscreen", "exclude_handles": ["@ananya.glows"], "top_k": 2}
    ).json()
    assert [x["rank"] for x in r["results"]] == [1, 2]
    assert "ananya.glows" not in [x["handle"] for x in r["results"]]


def test_mock_instagram_round_trip(mock):
    url = mock.get("/ai/instagram/login-url", headers=H, params={"account_ref": "cr-new"}).json()["url"]
    assert "Allow" in mock.get(url).text  # the fake consent page
    r = mock.get("/auth/instagram/callback", params={"code": "mock-code", "state": "cr-new"})
    assert r.json() == {"instagram": "connected", "account_ref": "cr-new", "handle": "ananya.glows"}
    acct = mock.get("/ai/instagram/accounts/cr-new", headers=H).json()
    assert acct["status"] in {"syncing", "active"}
    assert mock.get("/auth/instagram/callback", params={"error": "access_denied", "state": "x"}).status_code == 400
    assert mock.delete("/ai/instagram/accounts/cr-new", headers=H).json()["status"] == "revoked"
    assert mock.get("/ai/instagram/accounts/cr-new", headers=H).status_code == 404


# ------------------------------------------------------------------ same rules on both

JOB = {"event_type": "job_state_changed", "actor_type": "system", "subject_type": "job", "subject_id": "job-9"}
MOVE = {"creator_ref": "cr-ananya", "job_id": "job-9", "from": "PRODUCT_SHIPPED", "to": "REPLACEMENT_NEEDED"}
BAD = {
    "no creator": {**JOB, "payload": {k: v for k, v in MOVE.items() if k != "creator_ref"}},
    "code without fault party": {**JOB, "payload": {**MOVE, "reasonCode": "COURIER_LOST"}},
    "unknown reason code": {**JOB, "payload": {**MOVE, "reasonCode": "LATE", "faultParty": "BRAND"}},
    "unknown issue type": {**JOB, "payload": {**MOVE, "to": "DELIVERY_ISSUE", "issue_type": "LOST"}},
    "future time": {**JOB, "occurred_at": "2099-01-01T00:00:00+05:30", "payload": MOVE},
    "time without timezone": {**JOB, "occurred_at": "2026-11-13T10:00:00", "payload": MOVE},
    "locked disclosure": {
        "event_type": "brief_field_edited",
        "actor_type": "brand",
        "subject_type": "brief",
        "subject_id": "b-1",
        "payload": {"field": "disclosure", "old": "x", "new": ""},
    },
    "phone number": {**JOB, "payload": {**MOVE, "phone": "9876543210"}},
    "unknown suggestion": {**JOB, "payload": MOVE, "suggestion_id": "00000000-0000-0000-0000-000000000001"},
}


@pytest.mark.parametrize("case", BAD)
def test_mock_and_real_refuse_the_same_mistakes(case, real, mock):
    a = real.post("/ai/feedback", headers=H, json=BAD[case])
    b = mock.post("/ai/feedback", headers=H, json=BAD[case])
    assert a.status_code == b.status_code and a.status_code in {404, 422}, (a.text, b.text)


def test_good_reason_code_is_accepted_on_both(real, mock):
    ok = {**JOB, "payload": {**MOVE, "reasonCode": "COURIER_LOST", "faultParty": "COURIER"}}
    assert real.post("/ai/feedback", headers=H, json=ok).status_code == 200
    assert mock.post("/ai/feedback", headers=H, json=ok).status_code == 200
