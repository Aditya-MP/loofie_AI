import numpy as np

from app.matcher.features import BriefQuery, matcher_config
from app.matcher.ranker import rank
from app.matcher.rerank import rerank
from tests.helpers import cand, vec

CFG = matcher_config()


def run(pool, brief=None, q=vec(1, 0), cfg=CFG):
    brief = brief or BriefQuery(text="x", category="beauty_skincare", top_k=3)
    ranked, _ = rank(brief, pool, q, cfg, keep_all=True)
    return rerank(brief, ranked, {c.creator_id: c for c in pool}, cfg)


def handles(results):
    return [r.creator_id for r in results]


def test_no_rule_applies_keeps_ranker_order():
    pool = [cand(i, embedding=vec(1, i)) for i in range(1, 6)]
    out = run(pool)
    assert handles(out) == [1, 2, 3]
    assert all(r.rerank_note is None and r.rank_before_rerank == i for i, r in enumerate(out, 1))


FAR = cand(99, embedding=vec(0, 1))  # an off-topic creator, so similarity is scaled like a real pool


def test_near_duplicate_moves_down():
    # 2 is a near-twin of 1 (cosine ≈ 1.0). 3 and 4 are close behind and not twins.
    # (3 and 4 point in different directions, so they are not twins of each other)
    pool = [
        cand(1, embedding=vec(1, 0, 0)),
        cand(2, embedding=vec(1, 0, 0.01)),
        cand(3, embedding=vec(1, 0.3, 0)),
        cand(4, embedding=vec(1, 0, 0, 0.4)),
        FAR,
    ]
    assert handles(run(pool)) == [1, 3, 4]  # the twin drops out of the top 3

    pool[3] = cand(4, embedding=vec(1, 1, 0))  # 4 now much weaker: the twin stays on, one place lower
    out = run(pool)
    assert handles(out) == [1, 3, 2]
    twin = out[2]
    assert twin.rank_before_rerank == 2 and twin.rerank_note == "moved down: very similar content to @c1"


def test_duplicate_penalty_doesnt_override_a_big_lead():
    pool = [cand(1, embedding=vec(1, 0, 0)), cand(2, embedding=vec(1, 0, 0.01)), cand(3, embedding=vec(1, 1.5, 0)), FAR]
    out = run(pool)
    assert handles(out) == [1, 2, 3] and out[1].rerank_note is None


def test_competitor_conflict_listed_after_everyone_else():
    pool = [cand(1, paid_brands=["rival.skin"]), cand(2, embedding=vec(1, 1)), cand(3, embedding=vec(1, 2))]
    brief = BriefQuery(
        text="x",
        category="beauty_skincare",
        top_k=3,
        brand_handle="dewdrop.skin",
        competitor_handles=["rival.skin", "other.skin"],
    )
    out = run(pool, brief)
    assert handles(out) == [2, 3, 1]
    assert out[-1].rerank_note.startswith("conflict: recent paid post for @rival.skin")


def test_paid_post_for_the_same_brand_is_not_a_conflict():
    pool = [cand(1, paid_brands=["dewdrop.skin"]), cand(2, embedding=vec(1, 1))]
    brief = BriefQuery(
        text="x", category="beauty_skincare", top_k=2, brand_handle="dewdrop.skin", competitor_handles=["dewdrop.skin"]
    )
    assert handles(run(pool, brief)) == [1, 2]


def test_exploration_slot_for_a_new_creator():
    # Each creator in its own direction, so no one is a near-twin and diversity stays out of it.
    pool = [
        cand(1, level="VERIFIED", embedding=vec(1, 0, 0, 0)),
        cand(2, level="VERIFIED", embedding=vec(1, 0.5, 0, 0)),
        cand(3, level="VERIFIED", embedding=vec(1, 0, 0.7, 0)),
        cand(9, level="NEW", embedding=vec(1, 0, 0, 0.8)),
        cand(99, level="VERIFIED", embedding=vec(0, 1)),
    ]
    out = run(pool)
    assert handles(out) == [1, 2, 9]  # the NEW creator takes the last regular slot
    assert out[-1].rerank_note.startswith("new creator: exploration slot")
    assert out[-1].rank_before_rerank == 4


def test_exploration_needs_a_close_enough_score():
    pool = [cand(i, level="VERIFIED", embedding=vec(1, i * 0.1)) for i in range(1, 4)]
    pool.append(cand(9, level="NEW", embedding=vec(0, 1), quality_label="unsure", quality_score=40, completeness=0.0))
    assert 9 not in handles(run(pool))


def test_exploration_does_nothing_when_everyone_is_new():
    pool = [cand(i, embedding=vec(1, i * 0.1)) for i in range(1, 6)]
    assert all(r.rerank_note is None for r in run(pool))


def test_exploration_can_be_switched_off():
    cfg = {**CFG, "rerank": {**CFG["rerank"], "explore_slots": 0}}
    pool = [cand(i, level="VERIFIED", embedding=vec(1, i * 0.1)) for i in range(1, 4)]
    pool.append(cand(9, level="NEW", embedding=vec(1, 0.35)))
    assert 9 not in handles(run(pool, cfg=cfg))


def test_short_pool_returns_what_there_is():
    assert handles(run([cand(1)])) == [1]
    assert run([]) == []


def test_unit_vectors_are_what_the_duplicate_rule_expects():
    assert abs(float(vec(1, 0.01) @ vec(1, 0)) - 1) < 1e-3 and np.isclose(np.linalg.norm(vec(3, 4)), 1)
