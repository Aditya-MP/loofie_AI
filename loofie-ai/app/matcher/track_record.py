"""Track record: how a creator's past Loofie jobs went, as one 0..1 score for the matcher.

Built from the events log only (job_outcome, job_state_changed reason codes, rating, rebooked), so
nothing new has to be sent. Rules (docs/DECISIONS.md section 9):

- Only the creator's own fault counts. A job replaced because the brand shipped late or the courier
  lost the parcel is ignored, even if it arrives as on_time=false.
- Per job, the latest reason code wins, so an ops correction (FALSE_CLAIM later reversed) undoes
  itself. One outcome and one rating per job: retries and resends count once.
- New creators start in the middle (prior), not at zero, and one job can't swing the score far:
  the prior counts as a few neutral jobs. Old jobs fade (half-life), so people can recover.
"""

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import UTC, datetime

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.db.models import Event
from app.feedback.events import REASON_FAULT

TRACK_EVENTS = ("job_outcome", "job_state_changed", "rating", "rebooked")


@dataclass
class TrackRecord:
    jobs: int = 0  # jobs that count (finished, or ended by the creator's fault)
    on_time: int = 0
    creator_faults: int = 0  # jobs ended or disputed by the creator's own fault
    stars: list[int] = field(default_factory=list)
    rebooks: int = 0
    score: float = 0.5

    @property
    def empty(self) -> bool:
        return not (self.jobs or self.stars or self.rebooks)


@dataclass
class _Obs:
    value: float
    at: datetime


def _when(e) -> datetime:
    at = e.occurred_at
    return at if at.tzinfo else at.replace(tzinfo=UTC)


def build(events: Iterable, cfg: dict, now: datetime | None = None) -> TrackRecord:
    """events: Event rows (or anything with event_type, payload, occurred_at) for ONE creator."""
    t = cfg["track_record"]
    now = now or datetime.now(UTC)
    events = sorted(events, key=_when)

    outcomes: dict[str, tuple[dict, datetime]] = {}  # job_id -> latest job_outcome
    codes: dict[str, tuple[str, datetime]] = {}  # job_id -> latest reason code
    ratings: dict[str, tuple[int, datetime]] = {}  # job_id (or event id) -> latest stars
    rebooks: dict[str, datetime] = {}
    for e in events:
        p, at = e.payload or {}, _when(e)
        job = str(p.get("job_id") or "")
        if e.event_type == "job_outcome" and job:
            outcomes[job] = (p, at)
        elif e.event_type == "job_state_changed" and job and p.get("reasonCode") in REASON_FAULT:
            codes[job] = (p["reasonCode"], at)
        elif e.event_type == "rating" and isinstance(p.get("stars"), int) and 1 <= p["stars"] <= 5:
            ratings[job or str(getattr(e, "id", len(ratings)))] = (p["stars"], at)
        elif e.event_type == "rebooked":
            rebooks[job or str(getattr(e, "id", len(rebooks)))] = at

    rec = TrackRecord()
    obs: list[_Obs] = []
    for job in outcomes.keys() | codes.keys():
        code, code_at = codes.get(job, (None, None))
        if code and REASON_FAULT[code] == "CREATOR":
            rec.jobs += 1
            rec.creator_faults += 1
            obs.append(_Obs(t["creator_fault_job"], code_at))
            continue
        if job not in outcomes:
            continue  # a brand / courier / OTHER code with no finished job: nothing about the creator
        p, at = outcomes[job]
        if p.get("replaced") is True:
            continue  # replaced, and not by her fault (that case is handled above)
        if not isinstance(p.get("on_time"), bool):
            continue
        value = 1.0 if p["on_time"] else t["late_job"]
        revisions = p.get("revisions")
        if isinstance(revisions, int) and not isinstance(revisions, bool):
            extra = max(0, revisions - t["free_revisions"])
            value -= min(extra * t["revision_penalty"], t["revision_penalty_cap"])
        rec.jobs += 1
        rec.on_time += p["on_time"]
        obs.append(_Obs(max(value, 0.0), at))

    for stars, at in ratings.values():
        rec.stars.append(stars)
        obs.append(_Obs((stars - 1) / 4, at))
    for at in rebooks.values():
        rec.rebooks += 1
        obs.append(_Obs(1.0, at))

    half_life = t["half_life_days"]
    weights = [0.5 ** (max((now - o.at).total_seconds(), 0) / 86400 / half_life) for o in obs]
    prior_n, prior = t["prior_jobs"], t["prior"]
    total = prior_n * prior + sum(w * o.value for w, o in zip(weights, obs, strict=True))
    rec.score = round(total / (prior_n + sum(weights)), 4)
    return rec


def load(
    session: Session, creators: Iterable[tuple[int, str | None, str]], cfg: dict, now: datetime | None = None
) -> dict[int, TrackRecord]:
    """creators: (creator_id, creator_ref, handle). One query for all of them."""
    creators = list(creators)
    if not creators:
        return {}
    by_ref = {ref: cid for cid, ref, _ in creators if ref}
    by_handle = {h: cid for cid, _, h in creators}
    ref_col, handle_col = Event.payload["creator_ref"].astext, Event.payload["creator_handle"].astext
    rows = session.scalars(
        select(Event).where(
            Event.event_type.in_(TRACK_EVENTS),
            or_(ref_col.in_(list(by_ref) or [""]), handle_col.in_(list(by_handle))),
        )
    ).all()
    grouped: dict[int, list] = {}
    for e in rows:
        cid = by_ref.get(e.payload.get("creator_ref")) or by_handle.get(e.payload.get("creator_handle"))
        if cid is not None:
            grouped.setdefault(cid, []).append(e)
    return {cid: build(evs, cfg, now) for cid, evs in grouped.items()}


def describe(rec: TrackRecord | None) -> str | None:
    """Short, factual line for the shortlist. None for a creator with no Loofie history."""
    if rec is None or rec.empty:
        return None
    bits = []
    if rec.jobs:
        bits.append(f"{rec.jobs} Loofie job{'s' if rec.jobs != 1 else ''}, {rec.on_time} on time")
    if rec.stars:
        bits.append(f"★{sum(rec.stars) / len(rec.stars):.1f}")
    if rec.rebooks:
        bits.append(f"rebooked {rec.rebooks}×")
    if rec.creator_faults:
        bits.append(f"{rec.creator_faults} creator-side issue{'s' if rec.creator_faults != 1 else ''}")
    return ", ".join(bits)


def summary(rec: TrackRecord | None) -> dict | None:
    """The `track_record` object on GET /ai/creators/{handle} (real service and mock)."""
    if rec is None or rec.empty:
        return None
    return {
        "jobs": rec.jobs,
        "on_time": rec.on_time,
        "creator_faults": rec.creator_faults,
        "avg_stars": round(sum(rec.stars) / len(rec.stars), 2) if rec.stars else None,
        "rebooks": rec.rebooks,
        "score": rec.score,
        "summary": describe(rec),
    }
