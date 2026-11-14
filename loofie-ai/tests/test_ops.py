"""Promote / rollback, the weekly report, and the scorers they rely on."""

import shutil
import tomllib
import uuid
from datetime import UTC, datetime
from pathlib import Path

import pytest
from sqlalchemy import select

from app import config
from app.db.models import Event, ModelVersion
from app.feedback.events import log_event
from app.importer.sheet import import_sheet
from app.ops import registry
from app.ops.report import build_report
from app.profile.builder import build_and_save
from tests.helpers import TODAY, FakeEmbedder, write_fixture

DATA = Path(__file__).parent / "data"


@pytest.fixture
def config_copy(tmp_path, monkeypatch):
    """Promote and rollback rewrite config files: work on a copy, never the real config/."""
    live = tmp_path / "config"
    shutil.copytree(config.ROOT / "config", live, ignore=shutil.ignore_patterns("history"))
    monkeypatch.setattr(config, "CONFIG_DIR", live)
    config.reload_config()
    yield live
    config.reload_config()


@pytest.fixture
def briefs(session, tmp_path):
    folder = tmp_path / "briefs"
    folder.mkdir()
    shutil.copy(DATA / "briefs_dev.csv", folder / "Briefs.csv")
    import_sheet(session, folder)
    session.commit()
    return session


def candidate(live: Path, tmp_path: Path, version: str, edit=None) -> Path:
    text = (live / "brief.toml").read_text(encoding="utf-8")
    text = text.replace('version = "brief-rules-v2"', f'version = "{version}"')
    if edit:
        text = edit(text)
    out = tmp_path / f"{version}.toml"
    out.write_text(text, encoding="utf-8")
    tomllib.loads(text)  # the candidate itself must be valid
    return out


def status(session, version):
    return session.scalar(select(ModelVersion.status).where(ModelVersion.version == version))


def test_equal_score_is_promoted_and_rollback_restores(briefs, config_copy, tmp_path):
    session = briefs
    cand = candidate(config_copy, tmp_path, "brief-rules-v3")
    d = registry.promote(session, None, "brief", cand)
    assert d.promoted and d.candidate.value == pytest.approx(d.live.value)
    assert config.load_toml("brief")["version"] == "brief-rules-v3"
    assert (config_copy / "history" / "brief-brief-rules-v2.toml").exists()
    assert (status(session, "brief-rules-v3"), status(session, "brief-rules-v2")) == ("live", "retired")

    assert registry.rollback(session, "brief") == ("brief-rules-v3", "brief-rules-v2")
    assert config.load_toml("brief")["version"] == "brief-rules-v2"
    assert (status(session, "brief-rules-v2"), status(session, "brief-rules-v3")) == ("live", "retired")


def test_worse_candidate_is_rejected_and_live_untouched(briefs, config_copy, tmp_path):
    before = (config_copy / "brief.toml").read_bytes()
    # Forget every skincare word: category and sub-niche get worse.
    cand = candidate(
        config_copy,
        tmp_path,
        "brief-rules-worse",
        lambda t: t.replace('skincare = ["sunscreen"', 'skincare = ["zzz_never"'),
    )
    d = registry.promote(briefs, None, "brief", cand)
    assert not d.promoted and "scores lower" in d.reason
    assert (config_copy / "brief.toml").read_bytes() == before
    assert status(briefs, "brief-rules-worse") == "rejected"


def test_guardrail_blocks_a_candidate_that_invents(briefs, config_copy, tmp_path):
    # Counting "reels" as people makes it invent creators_needed for "4 reels" (answer: blank).
    cand = candidate(
        config_copy,
        tmp_path,
        "brief-rules-invents",
        lambda t: t.replace('nouns = ["creator"', 'nouns = ["reels", "creator"').replace(
            'blockers = ["reel", "reels", ', 'blockers = ["reel", '
        ),
    )
    d = registry.promote(briefs, None, "brief", cand)
    assert not d.promoted and "guardrail" in d.reason


def test_no_evaluation_data_needs_force(session, config_copy, tmp_path):
    cand = candidate(config_copy, tmp_path, "brief-rules-untested")
    assert not registry.promote(session, None, "brief", cand).promoted
    assert registry.promote(session, None, "brief", cand, force=True).promoted


def test_same_version_is_refused(briefs, config_copy, tmp_path):
    with pytest.raises(ValueError, match="same version"):
        registry.promote(briefs, None, "brief", candidate(config_copy, tmp_path, "brief-rules-v2"))


def test_rollback_with_nothing_to_go_back_to(session, config_copy):
    with pytest.raises(ValueError, match="no earlier"):
        registry.rollback(session, "matcher")


def test_quality_scorer_against_team_labels(session, tmp_path):
    write_fixture(tmp_path)  # team labels: real, suspicious, unsure
    import_sheet(session, tmp_path)
    build_and_save(session, FakeEmbedder(), as_of=TODAY)
    session.commit()
    s = registry.score_quality(session, None)
    # sleepy_sam: team said unsure; the model says real (genuine, just inactive) → 2 of 3 agree
    assert s.value == pytest.approx(2 / 3) and s.guardrails_ok


# ------------------------------------------------------------------ report


def test_report_shadow_agreement_and_outcomes(session):
    def ev(event_type, **payload):
        return log_event(
            session, event_type=event_type, actor_type="ops", subject_type="brief", subject_id="b1", payload=payload
        )

    sid = ev("shortlist_suggested", results=[{"handle": h} for h in ("a", "b", "c")])
    ev("shortlist_shown", suggestion_id=str(sid), creator_handles=["a", "b", "x", "y"])
    ev("shortlist_shown", suggestion_id=str(uuid.uuid4()), creator_handles=["z"])  # unknown suggestion: ignored
    ev("ops_added_creator", creator_handle="x")
    ev("shortlist_reject", creator_handle="b", reason="too expensive")
    ev("brief_parsed")
    ev("brief_parsed")
    ev("brief_field_edited", field="budget_total_inr", old=1, new=2)
    ev("job_outcome", creator_handle="a", on_time=True)
    ev("job_outcome", creator_handle="b", on_time=False)
    session.commit()

    r = build_report(session, days=7, now=datetime.now(UTC))
    assert r["shadow_agreement"] == 0.5  # 2 of the 4 creators ops showed were in the matcher's list
    assert r["ops_added_outside_matcher"] == 1
    assert r["reject_reasons"] == {"too expensive": 1}
    assert (r["briefs_parsed"], r["brief_fix_rate"], r["brief_fields_fixed"]) == (2, 0.5, {"budget_total_inr": 1})
    assert (r["jobs_finished"], r["on_time_rate"]) == (2, 0.5)
    assert session.scalar(select(Event).limit(1)) is not None


def test_empty_report(session):
    r = build_report(session, days=7)
    assert r["events"] == 0 and r["shadow_agreement"] is None and r["on_time_rate"] is None
