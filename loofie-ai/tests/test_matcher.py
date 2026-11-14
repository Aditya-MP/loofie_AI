import pytest
import torch

from app.matcher.features import BriefQuery, budget_fit, city_fit, matcher_config, price_band
from app.matcher.ranker import rank
from tests.helpers import cand, vec

CFG = matcher_config()


SKINCARE = BriefQuery(text="gel sunscreen for oily skin", category="beauty_skincare", sub_niche="skincare")


def ids(results):
    return [r.creator_id for r in results]


# ------------------------------------------------------------------ filters


def test_suspicious_inactive_and_other_categories_are_left_out_with_reasons():
    pool = [
        cand(1),
        cand(2, quality_label="suspicious"),
        cand(3, activity="inactive"),
        cand(4, category="other", sub_niche=None),
    ]
    results, excluded = rank(SKINCARE, pool, vec(1, 0))
    assert ids(results) == [1]
    assert excluded == {"flagged suspicious": 1, "inactive": 1, "different category": 1}


def test_related_category_stays_but_ranks_lower():
    pool = [cand(1, category="personal_care", sub_niche="body_care"), cand(2)]
    results, _ = rank(SKINCARE, pool, vec(1, 0))
    assert ids(results) == [2, 1]


def test_unknown_category_is_kept_not_dropped():
    results, _ = rank(SKINCARE, [cand(1, category=None, sub_niche=None, embedding=None)], vec(1, 0))
    assert ids(results) == [1]


def test_follower_range():
    pool = [cand(1, followers=3000), cand(2, followers=20000), cand(3, followers=None)]
    brief = BriefQuery(text="x", category="beauty_skincare", min_followers=5000, max_followers=50000)
    results, excluded = rank(brief, pool, vec(1, 0))
    assert ids(results) == [2]
    assert excluded == {"too few followers": 1, "followers unknown": 1}


def test_budget_far_below_rate_excluded_slightly_below_kept():
    assert price_band(12000, CFG) == (3000, 7000)
    assert budget_fit(3500, 12000, CFG)[0] == 1.0
    score, reason, out = budget_fit(2000, 12000, CFG)
    assert not out and score == CFG["scores"]["budget_slightly_below"] and "above budget" in reason
    assert budget_fit(1000, 12000, CFG)[2] is True


def test_level_cap_only_when_enforced():
    brief = BriefQuery(text="x", category="beauty_skincare", budget_per_creator_inr=8000)
    assert ids(rank(brief, [cand(1, followers=30000)], vec(1, 0))[0]) == [1]
    strict = {**CFG, "filters": {**CFG["filters"], "enforce_level": True}}
    results, excluded = rank(
        brief, [cand(1, followers=30000), cand(2, followers=30000, level="VERIFIED")], vec(1, 0), strict
    )
    assert ids(results) == [2] and sum(excluded.values()) == 1


def test_already_picked_creators_are_skipped():
    brief = BriefQuery(text="x", category="beauty_skincare", exclude_creator_ids=[1])
    assert ids(rank(brief, [cand(1), cand(2)], vec(1, 0))[0]) == [2]


# ------------------------------------------------------------------ scoring


def test_text_similarity_orders_otherwise_equal_creators():
    pool = [cand(1, embedding=vec(0, 1)), cand(2, embedding=vec(1, 0.1)), cand(3, embedding=vec(1, 1))]
    results, _ = rank(SKINCARE, pool, vec(1, 0))
    assert ids(results) == [2, 3, 1]
    assert results[0].parts["similarity"] == 1.0 and results[-1].parts["similarity"] == 0.0


def test_spoken_language_beats_caption_language():
    brief = BriefQuery(text="x", category="beauty_skincare", languages=["Kannada"])
    pool = [
        cand(1, spoken_language="Hindi"),
        cand(2, caption_languages={"Kannada": 0.5, "English": 0.5}),
        cand(3, spoken_language="Kannada"),
    ]
    results, _ = rank(brief, pool, vec(1, 0))
    assert ids(results) == [3, 2, 1]
    assert "speaks Kannada" in results[0].why


def test_city_aliases():
    assert city_fit("Bangalore", "Bengaluru, India", CFG)[0] == 1.0
    assert city_fit("any", "Mumbai", CFG) == (None, None)
    assert city_fit("BLR", None, CFG)[0] == CFG["scores"]["city_unknown"]


def test_quality_real_beats_unverified():
    pool = [cand(1, quality_label="unsure", quality_score=100), cand(2)]
    assert ids(rank(SKINCARE, pool, vec(1, 0))[0]) == [2, 1]


def test_parts_the_brief_does_not_ask_about_do_not_count():
    results, _ = rank(SKINCARE, [cand(1)], vec(1, 0))
    assert set(results[0].parts) == {"similarity", "niche", "quality", "completeness"}


def test_why_line_is_readable():
    brief = BriefQuery(
        text="x", category="beauty_skincare", sub_niche="skincare", city="Bangalore", budget_per_creator_inr=5000
    )
    [r], _ = rank(brief, [cand(1, median_views=9200)], vec(1, 0))
    assert r.why.startswith("skincare creator")
    for part in ("based in Bengaluru", "median 9.2k views", "est. ₹3,000–₹7,000 fits budget", "quality check passed"):
        assert part in r.why
    assert r.estimated_rate_inr == (3000, 7000)


def test_top_k():
    pool = [cand(i, embedding=vec(1, i / 10)) for i in range(1, 30)]
    assert len(rank(SKINCARE, pool, vec(1, 0))[0]) == CFG["top_k"]
    assert len(rank(BriefQuery(text="x", category="beauty_skincare", top_k=3), pool, vec(1, 0))[0]) == 3


# ------------------------------------------------------------------ real model (GPU)


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the GPU and the e5 model")
def test_real_model_ranks_by_meaning():
    from app.ml.embed import get_embedder

    e = get_embedder()
    bios = {
        1: "Hair fall journey | onion oil, hair masks, curly hair routines",
        2: "Oily, acne-prone skin. Sunscreen and gel moisturiser reviews, no white cast tests",
        3: "Bridal and party makeup looks, lipstick swatches",
        4: "Dry skin barrier care, ceramides and heavy creams",
    }
    vecs = e.passages(list(bios.values()))
    pool = [cand(i, embedding=v, sub_niche=None) for i, v in zip(bios, vecs, strict=True)]
    brief = BriefQuery(text="Lightweight SPF 50 gel sunscreen for oily skin, no white cast", category="beauty_skincare")
    results, _ = rank(brief, pool, e.queries([brief.text])[0])
    assert results[0].creator_id == 2
    hair = BriefQuery(text="Ayurvedic anti hair fall oil with bhringraj and onion", category="beauty_skincare")
    assert rank(hair, pool, e.queries([hair.text])[0])[0][0].creator_id == 1
