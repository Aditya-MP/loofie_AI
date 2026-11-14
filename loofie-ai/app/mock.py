"""Mock AI service for the backend and frontend: same endpoints, same request checks, same answers'
shape as the real one, with no GPU, no models and no database.

    uv run loofie mock            # http://127.0.0.1:8100, same port as the real service

- Requests are validated with the real schemas, and /ai/feedback runs the real rules
  (app/api/rules.py): unknown types, missing keys, wrong reason codes, personal data, retries
  (duplicate) and reused event ids (409) behave exactly as in production.
- /ai/brief/parse runs the real rule-based brief reader (only the GPU fallback is off).
- /ai/match and /ai/creators/* answer from contracts/examples (Ananya's story, docs/FLOW.md).
- Instagram Login has a fake consent page, so the full browser round trip can be tested.
- State lives in memory: restarting the mock forgets everything.
Every response carries `X-Loofie-Mock: true`, so a mock answer is never mistaken for a real one.
"""

import copy
import hmac
import json
import uuid
from datetime import UTC, date, datetime, timedelta
from typing import Annotated
from urllib.parse import urlencode

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

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
    MatchOut,
    ProfileOut,
)
from app.brief.extract import Field, parse
from app.config import ROOT, get_settings
from app.importer import parse as p

EXAMPLES = ROOT / "contracts" / "examples"


def load_examples() -> dict[str, dict]:
    """call ("POST /ai/match") -> first 200 example for it."""
    out: dict[str, dict] = {}
    for f in sorted(EXAMPLES.glob("*.json")):
        ex = json.loads(f.read_text(encoding="utf-8"))
        method, path = ex["call"].split()
        if ex["status"] == 200:
            out.setdefault(f"{method} {path.split('?')[0]}", ex)
    return out


class State:
    def __init__(self):
        self.examples = load_examples()
        self.known_ids: set[str] = set()  # brief event_ids and suggestion_ids feedback may point at
        self.events: dict[str, dict] = {}
        self.creators: dict[str, dict] = {}
        self.accounts: dict[str, dict] = {}
        for ex in self.examples.values():  # the ids inside the example files work too
            r = ex["response"]
            self.known_ids.update(str(r[k]) for k in ("event_id", "suggestion_id") if k in r)
        profile = self.example("GET /ai/creators/ananya.glows")
        self.creators[profile["handle"]] = profile

    def example(self, call: str) -> dict:
        return copy.deepcopy(self.examples[call]["response"])


app = FastAPI(title="Loofie AI (MOCK)", version="1")
app.state.mock = State()


@app.middleware("http")
async def mark_mock(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Loofie-Mock"] = "true"
    return response


def require_key(x_api_key: str) -> None:
    keys = get_settings().api_keys()
    if keys:  # configured: same check as production
        if not any(hmac.compare_digest(x_api_key.encode(), k.encode()) for _, k in keys):
            raise HTTPException(401, "missing or wrong X-API-Key")
    elif not x_api_key:  # not configured: any key works, but the header must be there
        raise HTTPException(401, "missing X-API-Key (the mock accepts any value)")


Key = Annotated[str, Header(alias="X-API-Key")]


def state(request: Request) -> State:
    return request.app.state.mock


def clean_handle(raw: str) -> str:
    try:
        h = p.handle(raw)
    except p.ParseError as e:
        raise HTTPException(422, str(e)) from e
    if h is None:
        raise HTTPException(422, "handle is required")
    return h


@app.get("/health")
def health():
    return {"status": "ok", "mode": "mock", "device": "none", "versions": {"brief": "mock", "matcher": "mock"}}


@app.post("/ai/brief/parse", response_model=BriefParseOut)
def brief_parse(body: BriefParseIn, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    bp = parse(body.text, as_of=body.as_of or date.today())  # rules only: the GPU fallback is off
    event_id = uuid.uuid4()
    state(request).known_ids.add(str(event_id))
    fields = {k: FieldOut(**v.__dict__) for k, v in bp.__dict__.items() if isinstance(v, Field)}
    return BriefParseOut(version=bp.version, event_id=event_id, fields=fields)


@app.post("/ai/creators/profile", response_model=ProfileOut)
def creator_profile(body: CreatorIn, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    h = clean_handle(body.handle)
    for post in body.posts:
        try:
            p.post_url(post.post_url)
        except p.ParseError as e:
            raise HTTPException(422, f"post {post.post_url!r}: {e}") from e
    out = state(request).example("POST /ai/creators/profile")
    out.update(handle=h, creator_ref=body.creator_ref)
    state(request).creators[h] = out
    return out


@app.get("/ai/creators/{handle}", response_model=ProfileOut)
def get_creator(handle: str, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    h = clean_handle(handle)
    if h not in state(request).creators:
        raise HTTPException(404, f"no profile for @{h}")
    return state(request).creators[h]


@app.post("/ai/match", response_model=MatchOut)
def match(body: MatchIn, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    out = state(request).example("POST /ai/match")
    excluded = {clean_handle(h) for h in body.exclude_handles}
    results = [r for r in out["results"] if r["handle"] not in excluded][: body.top_k]
    for i, r in enumerate(results, 1):
        r["rank"] = i
    out.update(results=results, suggestion_id=str(uuid.uuid4()))
    state(request).known_ids.add(out["suggestion_id"])
    return out


@app.post("/ai/feedback", response_model=FeedbackOut)
def feedback(body: FeedbackIn, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    check_feedback(body)
    st = state(request)
    if body.suggestion_id and str(body.suggestion_id) not in st.known_ids:
        raise HTTPException(404, f"suggestion_id {body.suggestion_id} not found")
    fields = jsonable_encoder(body.model_dump(exclude={"event_id", "occurred_at"}))
    if body.event_id is not None:
        saved = st.events.get(str(body.event_id))
        if saved is not None:
            if saved != fields:
                raise HTTPException(409, f"event_id {body.event_id} was already used for a different event")
            return FeedbackOut(event_id=body.event_id, duplicate=True)
    event_id = body.event_id or uuid.uuid4()
    st.events[str(event_id)] = fields
    return FeedbackOut(event_id=event_id)


# ------------------------------------------------------------------ Instagram Login, fake round trip


def account(st: State, ref: str) -> dict:
    if ref not in st.accounts:
        raise HTTPException(404, f"no Instagram connection for account {ref!r}")
    acct = st.accounts[ref]
    if acct["status"] == "syncing" and datetime.now(UTC) >= acct["_active_at"]:
        acct["status"], acct["last_synced_at"] = "active", datetime.now(UTC)
    return InstagramAccountOut(**{k: v for k, v in acct.items() if not k.startswith("_")}).model_dump()


@app.get("/ai/instagram/login-url", response_model=InstagramLoginOut)
def instagram_login_url(
    request: Request, account_ref: Annotated[str, Query(min_length=1, max_length=64)], x_api_key: Key = ""
):
    require_key(x_api_key)
    base = str(request.base_url).rstrip("/")
    return InstagramLoginOut(url=f"{base}/mock/instagram/consent?{urlencode({'state': account_ref})}", expires_in=600)


@app.get("/mock/instagram/consent", response_class=HTMLResponse)
def consent(state: str):
    """Stands in for Instagram's 'Allow Loofie?' screen."""
    q = urlencode({"state": state})
    return (
        "<!doctype html><meta name=viewport content='width=device-width'>"
        "<body style='font-family:sans-serif;max-width:420px;margin:40px auto;padding:0 16px'>"
        "<h2>MOCK Instagram</h2><p>Allow Loofie to see your profile and post stats?</p>"
        f"<p><a href='/auth/instagram/callback?code=mock-code&{q}'>Allow</a> &nbsp; "
        f"<a href='/auth/instagram/callback?error=access_denied&{q}'>Cancel</a></p>"
        "<p><small>Handle used: <code>ananya.glows</code>. Use state <code>fail-*</code> to test a failure.</small></p>"
    )


def back_to_app(outcome: dict):
    base = get_settings().app_return_url
    if not base:
        return JSONResponse(outcome, status_code=200 if outcome["instagram"] == "connected" else 400)
    return RedirectResponse(base + ("&" if "?" in base else "?") + urlencode(outcome), status_code=303)


@app.get("/auth/instagram/callback")
def callback(request: Request, state: str | None = None, code: str | None = None, error: str | None = None):
    if error or not code or not state:
        return back_to_app({"instagram": "failed", "reason": "denied" if error else "bad_request"})
    if state.startswith("fail-"):
        return back_to_app({"instagram": "failed", "reason": "not_professional"})
    now = datetime.now(UTC)
    request.app.state.mock.accounts[state] = {
        "account_ref": state,
        "handle": "ananya.glows",
        "status": "syncing",  # becomes active after 3 seconds, like a real fetch
        "insights_allowed": True,
        "token_expires_at": now + timedelta(days=60),
        "last_synced_at": None,
        "last_error": None,
        "_active_at": now + timedelta(seconds=3),
    }
    return back_to_app({"instagram": "connected", "account_ref": state, "handle": "ananya.glows"})


@app.get("/ai/instagram/accounts/{account_ref}", response_model=InstagramAccountOut)
def instagram_account(account_ref: str, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    return account(state(request), account_ref)


@app.post("/ai/instagram/accounts/{account_ref}/sync", response_model=InstagramAccountOut)
def instagram_sync(account_ref: str, request: Request, x_api_key: Key = ""):
    require_key(x_api_key)
    out = account(state(request), account_ref)
    state(request).accounts[account_ref]["last_synced_at"] = datetime.now(UTC)
    return out


@app.delete("/ai/instagram/accounts/{account_ref}")
def instagram_disconnect(account_ref: str, request: Request, delete_data: bool = False, x_api_key: Key = ""):
    require_key(x_api_key)
    account(state(request), account_ref)
    del state(request).accounts[account_ref]
    return {"status": "deleted" if delete_data else "revoked", "confirmation_code": uuid.uuid4().hex[:12]}
