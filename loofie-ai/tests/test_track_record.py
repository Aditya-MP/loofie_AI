"""Track record: past Loofie jobs feed the matcher fairly (docs/DECISIONS.md section 9)."""

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from app.matcher.features import BriefQuery, matcher_config
from app.matcher.ranker import rank
from app.matcher.track_record import build, describe
from tests.helpers import cand, vec

NOW = datetime(2026, 11, 15, 12, tzinfo=UTC)
CFG = matcher_config()


@dataclass
class Ev:
    event_type: str
    payload: dict = field(default_factory=dict)
    occurred_at: datetime = NOW - timedelta(days=1)


def outcome(job, on_time=True, **kw):
    return Ev("job_outcome", {"job_id": job, "on_time": on_time, **kw})


def code(job, reason, days_ago=1):
    return Ev("job_state_changed", {"job_id": job, "reasonCode": reason}, NOW - timedelta(days=days_ago))


def test_no_history_is_neutral():
    rec = build([], CFG, NOW)
    assert rec.score == 0.5 and rec.empty and describe(rec) is None


def test_good_jobs_raise_the_score_but_one_job_cannot_swing_it():
    one = build([outcome("j1")], CFG, NOW).score
    five = build([outcome(f"j{i}") for i in range(5)], CFG, NOW).score
    assert 0.5 < one < 0.65  # the prior counts as 3 neutral jobs
    assert one < five < 1.0


def test_late_and_extra_revisions_cost_less_than_a_drop_out():
    late = build([outcome("j1", on_time=False)], CFG, NOW).score
    revisions = build([outcome("j1", revisions=4)], CFG, NOW).score
    withdrew = build([code("j1", "CREATOR_WITHDREW")], CFG, NOW).score
    assert withdrew < late < 0.5 < revisions < build([outcome("j1")], CFG, NOW).score


def test_brand_or_courier_fault_never_counts_against_the_creator():
    # Replaced because the brand shipped late: the backend may still say on_time=false.
    events = [code("j1", "BRAND_LATE_SHIP"), outcome("j1", on_time=False, replaced=True), code("j2", "COURIER_LOST")]
    rec = build(events, CFG, NOW)
    assert rec.score == 0.5 and rec.jobs == 0 and rec.creator_faults == 0


def test_latest_reason_code_wins_so_ops_can_correct_a_mistake():
    wrong_then_fixed = [code("j1", "FALSE_CLAIM", days_ago=3), code("j1", "COURIER_LOST", days_ago=1)]
    assert build(wrong_then_fixed, CFG, NOW).creator_faults == 0
    fixed_then_wrong = [code("j1", "COURIER_LOST", days_ago=3), code("j1", "FALSE_CLAIM", days_ago=1)]
    assert build(fixed_then_wrong, CFG, NOW).creator_faults == 1


def test_creator_fault_job_counts_once_even_with_an_outcome():
    rec = build([code("j1", "CREATOR_UNRESPONSIVE"), outcome("j1", on_time=False, replaced=True)], CFG, NOW)
    assert rec.jobs == 1 and rec.creator_faults == 1 and rec.on_time == 0


def test_resends_count_once():
    events = [
        outcome("j1"),
        outcome("j1"),
        Ev("rating", {"job_id": "j1", "stars": 5}),
        Ev("rating", {"job_id": "j1", "stars": 5}),
    ]
    rec = build(events, CFG, NOW)
    assert rec.jobs == 1 and rec.stars == [5]


def test_ratings_and_rebooks_count():
    low = build([outcome("j1"), Ev("rating", {"job_id": "j1", "stars": 1})], CFG, NOW).score
    high = build(
        [outcome("j1"), Ev("rating", {"job_id": "j1", "stars": 5}), Ev("rebooked", {"job_id": "j2"})], CFG, NOW
    )
    assert low < high.score
    assert describe(high) == "1 Loofie job, 1 on time, ★5.0, rebooked 1×"


def test_old_mistakes_fade():
    recent = build([code("j1", "CREATOR_WITHDREW", days_ago=5)], CFG, NOW).score
    old = build([code("j1", "CREATOR_WITHDREW", days_ago=400)], CFG, NOW).score
    assert recent < old < 0.5


def test_proven_creator_wins_a_close_fit_but_not_a_clearly_better_one():
    brief = BriefQuery(text="sunscreen", category="beauty_skincare")
    q = vec(1, 0)
    proven = build([outcome(f"j{i}") for i in range(6)] + [Ev("rating", {"job_id": "j0", "stars": 5})], CFG, NOW)
    # Near-tie: content almost the same. The proven creator moves up.
    a, b, floor = (
        cand(1, embedding=vec(1, 0.05)),
        cand(2, embedding=vec(1, 0.06), track=proven),
        cand(3, embedding=vec(0, 1)),
    )
    ranked, _ = rank(brief, [a, b, floor], q, CFG)
    assert ranked[0].handle == "c2" and "6 Loofie jobs" in ranked[0].why
    # A clearly better fit (other niche entirely for the proven one) still wins.
    weak = cand(2, embedding=vec(0.2, 1), sub_niche="haircare", track=proven)
    ranked, _ = rank(brief, [cand(1, embedding=vec(1, 0)), weak, floor], q, CFG)
    assert ranked[0].handle == "c1"
