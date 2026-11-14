"""Promote / roll back model settings: a new version goes live only if it scores at least as well
as the live one on the evaluation, and passes its guardrails. The old file is kept for rollback.

Today a "model" is its config file (weights, thresholds, word lists). When the learning job trains
real models, it hands its candidate to promote() the same way, so nothing reaches brands untested.
"""

import shutil
import tomllib
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app import config
from app.db.models import Creator, ModelVersion

BEAUTY = {"beauty_skincare", "personal_care"}


@dataclass
class Score:
    value: float | None  # None = no evaluation data for this model yet
    guardrails_ok: bool
    detail: str


# ------------------------------------------------------------------ one scorer per model


def score_brief(session: Session, embedder) -> Score:
    from app.brief.evaluate import FIELDS, evaluate, load_db

    briefs = load_db(session)
    if not briefs:
        return Score(None, True, "no briefs with an answer key")
    r = evaluate(briefs)
    value = sum(r.outcomes[f]["correct"] for f in FIELDS) / (len(FIELDS) * r.briefs)
    invented = sum(c["invented"] for c in r.outcomes.values()) + r.must_invented
    return Score(value, invented == 0, f"{value:.1%} fields correct on {r.briefs} briefs, {invented} invented")


def score_quality(session: Session, embedder) -> Score:
    from app.quality.rules import assess
    from app.quality.service import load_inputs

    human = dict(
        session.execute(select(Creator.id, Creator.label_quality).where(Creator.label_quality.is_not(None))).all()
    )
    pairs = [(human[cid], assess(q).label) for cid, q in load_inputs(session) if cid in human]
    if not pairs:
        return Score(None, True, "no quality_label from the team")
    value = sum(h == m for h, m in pairs) / len(pairs)
    # Guardrail: never flag a creator the team checked and found real as suspicious more often than 1 in 10.
    real = [m for h, m in pairs if h == "real"]
    false_flags = sum(m == "suspicious" for m in real) / len(real) if real else 0.0
    return Score(value, false_flags <= 0.10, f"{value:.1%} agree on {len(pairs)}, {false_flags:.0%} real flagged")


def score_matcher(session: Session, embedder) -> Score:
    from app.matcher.evaluate import evaluate

    results = evaluate(session, embedder)
    if not results:
        return Score(None, True, "no Brand_Creator_Links")
    value = sum(r.matcher for r in results) / len(results)
    baseline = sum(r.filters_only for r in results) / len(results)
    return Score(
        value, value >= baseline, f"recall@10 {value:.1%} vs filters-only {baseline:.1%}, {len(results)} brands"
    )


def score_profile(session: Session, embedder) -> Score:
    from app.profile.builder import build, load_inputs

    labels = dict(session.execute(select(Creator.id, Creator.category).where(Creator.category.is_not(None))).all())
    rows = [r for r in build(load_inputs(session), embedder) if r["creator_id"] in labels and r["category"]]
    if not rows:
        return Score(None, True, "no hand-entered categories")
    value = sum((labels[r["creator_id"]] in BEAUTY) == (r["category"] in BEAUTY) for r in rows) / len(rows)
    return Score(value, True, f"{value:.1%} beauty-vs-not agreement on {len(rows)}")


SCORERS: dict[str, Callable[[Session, object], Score]] = {
    "brief": score_brief,
    "quality": score_quality,
    "matcher": score_matcher,
    "profile": score_profile,
}


# ------------------------------------------------------------------ files and records


def live_path(name: str) -> Path:
    return config.CONFIG_DIR / f"{name}.toml"


def history_dir() -> Path:
    d = config.CONFIG_DIR / "history"
    d.mkdir(exist_ok=True)
    return d


def _version(path: Path) -> str:
    with path.open("rb") as f:
        return tomllib.load(f)["version"]


def _record(session: Session, name: str, version: str, status: str, metrics: dict | None = None) -> None:
    row = dict(
        model_name=name,
        version=version,
        status=status,
        params={},
        metrics=metrics or {},
        promoted_at=datetime.now(UTC) if status == "live" else None,
    )
    stmt = insert(ModelVersion).values(row)
    update = {
        "status": stmt.excluded.status,
        "promoted_at": func.coalesce(stmt.excluded.promoted_at, ModelVersion.promoted_at),
    }
    if metrics:
        update["metrics"] = stmt.excluded.metrics
    session.execute(stmt.on_conflict_do_update(index_elements=["model_name", "version"], set_=update))


@dataclass
class Decision:
    promoted: bool
    reason: str
    live: Score
    candidate: Score
    live_version: str
    candidate_version: str


def promote(session: Session, embedder, name: str, candidate: Path, force: bool = False) -> Decision:
    if name not in SCORERS:
        raise ValueError(f"unknown model {name!r}; one of {sorted(SCORERS)}")
    live_version, cand_version = _version(live_path(name)), _version(candidate)
    if cand_version == live_version:
        raise ValueError(f"candidate has the same version as live ({live_version}); bump `version` in it")
    with candidate.open("rb") as f:
        cand_cfg = tomllib.load(f)

    scorer = SCORERS[name]
    live = scorer(session, embedder)
    with config.config_override(name, cand_cfg):
        cand = scorer(session, embedder)

    if live.value is None or cand.value is None:
        ok, reason = (
            force,
            "no evaluation data" + ("; promoted anyway (--force)" if force else "; use --force to promote untested"),
        )
    elif not cand.guardrails_ok:
        ok, reason = False, f"candidate fails its guardrail: {cand.detail}"
    elif cand.value + 1e-9 < live.value:
        ok, reason = False, f"candidate scores lower: {cand.value:.1%} vs live {live.value:.1%}"
    else:
        ok, reason = True, f"candidate {cand.value:.1%} vs live {live.value:.1%}"

    metrics = {"candidate": asdict(cand), "live_at_decision": asdict(live), "reason": reason}
    if ok:
        kept = history_dir() / f"{name}-{live_version}.toml"
        if not kept.exists():
            shutil.copy2(live_path(name), kept)
        shutil.copyfile(candidate, live_path(name))
        config.reload_config()
        _record(session, name, live_version, "retired")
        _record(session, name, cand_version, "live", metrics)
    else:
        _record(session, name, cand_version, "rejected", metrics)
    session.commit()
    return Decision(ok, reason, live, cand, live_version, cand_version)


def rollback(session: Session, name: str) -> tuple[str, str]:
    """Put back the most recently retired version. Returns (from_version, to_version)."""
    current = _version(live_path(name))
    previous = sorted(
        (p for p in history_dir().glob(f"{name}-*.toml") if _version(p) != current), key=lambda p: p.stat().st_mtime
    )
    if not previous:
        raise ValueError(f"no earlier {name} version in {history_dir()}")
    target = previous[-1]
    kept = history_dir() / f"{name}-{current}.toml"
    if not kept.exists():
        shutil.copy2(live_path(name), kept)
    shutil.copyfile(target, live_path(name))
    config.reload_config()
    to_version = _version(target)
    _record(session, name, current, "retired")
    _record(session, name, to_version, "live")
    session.commit()
    return current, to_version


def listing(session: Session) -> list[dict]:
    rows = session.scalars(select(ModelVersion).order_by(ModelVersion.model_name, ModelVersion.created_at)).all()
    recorded = [
        dict(model=r.model_name, version=r.version, status=r.status, reason=(r.metrics or {}).get("reason", ""))
        for r in rows
    ]
    live_files = [dict(model=n, version=_version(live_path(n)), status="live (file)", reason="") for n in SCORERS]
    return live_files + recorded
