"""Write contracts/examples/*.json: the story (docs/FLOW.md) run through the REAL service.

    uv run python scripts/make_examples.py

Every example is a real call and its real answer, so the backend builds against what the service
actually does. Uses the TEST database (wiped first) and the real embedder (GPU if there is one).
Re-run after any change to app/api/schemas.py; tests/test_contract.py fails until you do.
"""

import json
import sys
import uuid
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import text  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

from app.api.main import app, get_session  # noqa: E402
from app.api.schemas import InstagramAccountOut  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.db.models import Base  # noqa: E402
from app.db.session import make_engine  # noqa: E402

OUT = ROOT / "contracts" / "examples"
AS_OF = date(2026, 11, 14)
KEY = "example-key"
H = {"X-API-Key": KEY}


def eid(n: int) -> str:
    """Stable event ids, so re-running doesn't rewrite every file."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"loofie-example-{n}"))


def creator(handle, ref, followers, language, captions, days=2, city="Bengaluru", tagged=()):
    return {
        "handle": handle,
        "creator_ref": ref,
        "display_name": handle.split(".")[0].title(),
        "city": city,
        "followers": followers,
        "following": 480,
        "total_posts": 210,
        "bio": "honest skincare reviews for Indian skin",
        "primary_language": language,
        "posts": [
            {
                "post_url": f"https://www.instagram.com/reel/{handle.replace('.', '')}{i}/",
                "post_date": str(AS_OF - timedelta(days=days + 4 * i)),
                "post_type": "reel",
                "views": int(followers * 0.3) + 37 * i,
                "likes": int(followers * 0.03) + 5 * i,
                "comments": 40 + i,
                "caption": captions[i % len(captions)],
                "brands_tagged": list(tagged) if i == 0 else [],
            }
            for i in range(8)
        ],
    }


SUNSCREEN = [
    "Tried this gel sunscreen on my oily skin for 7 days: no white cast, no grease #sunscreen #oilyskin",
    "My acne-prone skin morning routine: cleanser, niacinamide, SPF 50 #skincare",
    "Sunscreen myths we need to stop believing #spf #skincaretips",
]
MAKEUP = ["Everyday makeup look under 10 minutes #makeup", "Kajal that lasts all day? Testing 5 #makeuptest"]


def main() -> int:
    s = get_settings()
    cfg = Config(str(ROOT / "alembic.ini"))
    cfg.attributes["url"] = s.test_database_url
    command.upgrade(cfg, "head")
    engine = make_engine(s.test_database_url)
    with engine.begin() as conn:
        conn.execute(
            text(f"TRUNCATE {', '.join(t.name for t in Base.metadata.sorted_tables)} RESTART IDENTITY CASCADE")
        )
    session = sessionmaker(engine)()

    import os

    os.environ.update(
        AI_API_KEY=KEY,
        IG_APP_ID="1234567890",
        IG_APP_SECRET="example-secret",
        IG_REDIRECT_URI="https://ai.loofie.example/auth/instagram/callback",
        IG_TOKEN_KEY="Kx6l9v2wq0mH1p3s5u7y9A0cE2gI4kM6oQ8sU0wY2a4=",
        APP_RETURN_URL="",
    )
    get_settings.cache_clear()
    app.dependency_overrides[get_session] = lambda: session
    examples: list[dict] = []

    with TestClient(app) as c:

        def call(title, scene, method, path, body=None, params=None, expect=200, note=None):
            r = c.request(method, path, headers=H, json=body, params=params)
            assert r.status_code == expect, (title, r.status_code, r.text)
            shown = path + ("?" + "&".join(f"{k}={v}" for k, v in params.items()) if params else "")
            ex = {
                "id": len(examples) + 1,
                "title": title,
                "scene": scene,
                "call": f"{method} {shown}",
                "status": expect,
            }
            if note:
                ex["note"] = note
            if body is not None:
                ex["request"] = body
            ex["response"] = r.json()
            examples.append(ex)
            return r.json()

        # Scene 2: creators join (manual path; Instagram Login numbers arrive through F1 instead).
        ananya = creator("ananya.glows", "cr-ananya", 12400, "Kannada", SUNSCREEN)
        call(
            "Creator profile: save rate-card details and build the passport", 2, "POST", "/ai/creators/profile", ananya
        )
        for about, extra in [
            ("another sunscreen creator", creator("kavya.skincare", "cr-kavya", 18200, "Kannada", SUNSCREEN, days=4)),
            (
                "an English-speaking creator",
                creator("riya.routines", "cr-riya", 9100, "English", SUNSCREEN[:2] + MAKEUP, days=1),
            ),
            ("a makeup creator", creator("meera.makeup", "cr-meera", 21000, "Kannada", MAKEUP, days=3)),
            (
                "a creator who stopped posting 5 months ago",
                creator("old.glow", "cr-old", 15000, "Kannada", SUNSCREEN, days=150),
            ),
        ]:
            call(f"Creator profile: {about}", 2, "POST", "/ai/creators/profile", extra)
        call("Creator profile: read it back", 2, "GET", "/ai/creators/ananya.glows")

        login = call(
            "Instagram Login: get the link for 'Continue with Instagram'",
            2,
            "GET",
            "/ai/instagram/login-url",
            params={"account_ref": "cr-ananya"},
            note="Send the creator's browser to `url`. The state inside expires; ask again for every tap.",
        )
        assert login["url"].startswith("https://www.instagram.com/oauth/authorize")
        account = InstagramAccountOut(
            account_ref="cr-ananya",
            handle="ananya.glows",
            status="active",
            insights_allowed=True,
            token_expires_at="2027-01-13T10:00:00Z",
            last_synced_at="2026-11-14T10:00:05Z",
            last_error=None,
        )
        examples.append(
            {
                "id": len(examples) + 1,
                "replay": False,
                "title": "Instagram Login: poll the connection until status is active",
                "scene": 2,
                "call": "GET /ai/instagram/accounts/cr-ananya",
                "status": 200,
                "note": "Shape from the schema (a real connection needs Meta). syncing → active, or error / expired.",
                "response": json.loads(account.model_dump_json()),
            }
        )

        # Scene 3: the brief.
        brief = call(
            "Brief reader: brand's rough notes into form fields",
            3,
            "POST",
            "/ai/brief/parse",
            {
                "text": "need 3 girls blr, kannada ok, oily skin sunscreen, 15k total, reels, by 5th dec",
                "as_of": str(AS_OF),
                "brief_ref": "brief-1042",
            },
        )
        fb = dict(actor_type="brand", actor_id="brand-dewdrop", occurred_at="2026-11-13T11:00:00+05:30")
        call(
            "Brand corrected a field the brief reader filled",
            3,
            "POST",
            "/ai/feedback",
            {
                "event_type": "brief_field_edited",
                **fb,
                "subject_type": "brief",
                "subject_id": "brief-1042",
                "suggestion_id": brief["event_id"],
                "event_id": eid(6),
                "payload": {"field": "budget_total_inr", "old": 15000, "new": 18000},
            },
        )
        call(
            "Brand confirmed the brief",
            3,
            "POST",
            "/ai/feedback",
            {
                "event_type": "brief_confirmed",
                **fb,
                "subject_type": "brief",
                "subject_id": "brief-1042",
                "suggestion_id": brief["event_id"],
                "event_id": eid(7),
                "payload": {},
            },
        )

        # Scene 4: the shortlist.
        m = call(
            "Matcher: ranked shortlist for the confirmed brief",
            4,
            "POST",
            "/ai/match",
            {
                "text": "Lightweight gel sunscreen for oily skin, no white cast",
                "category": "beauty_skincare",
                "city": "Bengaluru",
                "languages": ["Kannada"],
                "budget_per_creator_inr": 6000,
                "top_k": 5,
                "brief_ref": "brief-1042",
                "brand_handle": "dewdrop.skin",
            },
        )
        sid = m["suggestion_id"]
        shortlist = dict(subject_type="shortlist", subject_id="brief-1042", suggestion_id=sid)
        call(
            "What the brand actually saw (after ops edits)",
            4,
            "POST",
            "/ai/feedback",
            {
                "event_type": "shortlist_shown",
                "actor_type": "ops",
                "actor_id": "ops-ravi",
                "occurred_at": "2026-11-13T12:00:00+05:30",
                **shortlist,
                "event_id": eid(9),
                "payload": {"creator_handles": [r["handle"] for r in m["results"]]},
            },
        )
        call(
            "Brand picked a creator",
            4,
            "POST",
            "/ai/feedback",
            {
                "event_type": "shortlist_pick",
                **fb,
                **shortlist,
                "event_id": eid(10),
                "payload": {"creator_ref": "cr-ananya"},
            },
        )
        call(
            "Brand rejected a creator, with the reason",
            4,
            "POST",
            "/ai/feedback",
            {
                "event_type": "shortlist_reject",
                **fb,
                **shortlist,
                "event_id": eid(11),
                "payload": {"creator_ref": "cr-riya", "reason": "too expensive"},
            },
            note="Reasons teach the matcher: 'too expensive', 'wrong vibe', 'too small'...",
        )

        # Scenes 6-8: the job.
        job = dict(actor_type="system", subject_type="job", subject_id="job-42")
        shipped = {
            "event_type": "job_state_changed",
            **job,
            "event_id": eid(12),
            "occurred_at": "2026-11-13T15:30:00+05:30",
            "payload": {
                "creator_ref": "cr-ananya",
                "job_id": "job-42",
                "brief_ref": "brief-1042",
                "from": "CREATOR_ACCEPTED",
                "to": "PRODUCT_SHIPPED",
                "deadline_at": "2026-11-17T18:00:00+05:30",
            },
        }
        call("Job moved: brand shipped the product", 6, "POST", "/ai/feedback", shipped)
        call(
            "Retry of the same event (same event_id, same content): nothing stored twice",
            6,
            "POST",
            "/ai/feedback",
            shipped,
            note="Safe to retry forever. `duplicate: true` means it was already saved.",
        )
        call(
            "Job moved: creator reported a delivery problem",
            6,
            "POST",
            "/ai/feedback",
            {
                "event_type": "job_state_changed",
                **job,
                "actor_type": "creator",
                "actor_id": "cr-ananya",
                "event_id": eid(14),
                "occurred_at": "2026-11-13T16:00:00+05:30",
                "payload": {
                    "creator_ref": "cr-ananya",
                    "job_id": "job-42",
                    "from": "PRODUCT_SHIPPED",
                    "to": "DELIVERY_ISSUE",
                    "issue_type": "DAMAGED",
                },
            },
        )
        call(
            "Job moved: creator went silent and is being replaced (reason code + fault party)",
            8,
            "POST",
            "/ai/feedback",
            {
                "event_type": "job_state_changed",
                "actor_type": "system",
                "subject_type": "job",
                "subject_id": "job-43",
                "event_id": eid(15),
                "occurred_at": "2026-11-13T17:00:00+05:30",
                "payload": {
                    "creator_ref": "cr-kavya",
                    "job_id": "job-43",
                    "from": "IN_PRODUCTION",
                    "to": "REPLACEMENT_NEEDED",
                    "reasonCode": "CREATOR_UNRESPONSIVE",
                    "faultParty": "CREATOR",
                },
            },
            note="Only CREATOR-fault codes count against a creator's reliability.",
        )
        call(
            "Job closed: how it went",
            9,
            "POST",
            "/ai/feedback",
            {
                "event_type": "job_outcome",
                **job,
                "event_id": eid(16),
                "occurred_at": "2026-11-13T18:00:00+05:30",
                "payload": {
                    "creator_ref": "cr-ananya",
                    "job_id": "job-42",
                    "on_time": True,
                    "revisions": 1,
                    "disputed": False,
                    "replaced": False,
                },
            },
        )
        call(
            "Brand rated the creator",
            11,
            "POST",
            "/ai/feedback",
            {
                "event_type": "rating",
                **fb,
                "subject_type": "job",
                "subject_id": "job-42",
                "event_id": eid(17),
                "payload": {"creator_ref": "cr-ananya", "job_id": "job-42", "stars": 5},
            },
        )
        call(
            "Video result about 30 days after approval",
            10,
            "POST",
            "/ai/feedback",
            {
                "event_type": "video_performance",
                **fb,
                "subject_type": "job",
                "subject_id": "job-42",
                "event_id": eid(18),
                "payload": {"creator_ref": "cr-ananya", "job_id": "job-42", "result": "winner"},
            },
        )
        call(
            "Brand rebooked the creator",
            11,
            "POST",
            "/ai/feedback",
            {
                "event_type": "rebooked",
                **fb,
                "subject_type": "creator",
                "subject_id": "cr-ananya",
                "event_id": eid(19),
                "payload": {"creator_ref": "cr-ananya", "job_id": "job-58"},
            },
        )

        # Mistakes the backend will make, and what it gets back.
        call(
            "Error: same event_id reused for a different event",
            12,
            "POST",
            "/ai/feedback",
            {**shipped, "payload": {**shipped["payload"], "to": "DELIVERY_ISSUE"}},
            expect=409,
            note="A bug on the caller's side, not a retry: give every event its own id.",
        )
        call(
            "Error: reason code with the wrong fault party",
            12,
            "POST",
            "/ai/feedback",
            {
                "event_type": "job_state_changed",
                **job,
                "event_id": eid(21),
                "payload": {
                    "creator_ref": "cr-ananya",
                    "job_id": "job-42",
                    "from": "PRODUCT_SHIPPED",
                    "to": "REPLACEMENT_NEEDED",
                    "reasonCode": "BRAND_LATE_SHIP",
                    "faultParty": "CREATOR",
                },
            },
            expect=422,
        )
        call(
            "Error: personal or payment data in the payload",
            12,
            "POST",
            "/ai/feedback",
            {
                "event_type": "job_outcome",
                **job,
                "event_id": eid(22),
                "payload": {"creator_ref": "cr-ananya", "job_id": "job-42", "on_time": True, "upi_id": "x@okaxis"},
            },
            expect=422,
        )
        call(
            "Error: unknown event type (it's `rating`, not `job_rating`)",
            12,
            "POST",
            "/ai/feedback",
            {
                "event_type": "job_rating",
                **fb,
                "subject_type": "job",
                "subject_id": "job-42",
                "event_id": eid(23),
                "payload": {"creator_ref": "cr-ananya", "stars": 5},
            },
            expect=422,
        )

    app.dependency_overrides.clear()
    session.close()
    engine.dispose()

    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    for ex in examples:
        slug = ex["call"].split()[1].split("?")[0].strip("/").replace("/", "_").replace("ai_", "", 1)
        if slug == "feedback":
            slug = f"feedback_{ex['request']['event_type']}"
        if ex["status"] != 200:
            slug += f"_error{ex['status']}"
        elif ex.get("response", {}).get("duplicate"):
            slug += "_retry"
        name = f"{ex['id']:02d}_{slug}.json"
        (OUT / name).write_text(json.dumps(ex, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(examples)} examples to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
