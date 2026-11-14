from datetime import date
from pathlib import Path

import pytest
import torch

from app.brief.evaluate import FIELDS, evaluate, load_csv, point_matches
from app.brief.extract import parse, to_brief_query

DATA = Path(__file__).parent / "data"
TODAY = date(2026, 11, 14)


def v(text, **kw):
    return parse(text, as_of=TODAY, **kw).values()


# ------------------------------------------------------------------ the two brief sets


@pytest.mark.parametrize("name, floor", [("dev", 0.95), ("holdout", 0.90)])
def test_brief_sets(name, floor):
    r = evaluate(load_csv(DATA / f"briefs_{name}.csv"), as_of=TODAY)
    for f in FIELDS:
        assert r.outcomes[f]["correct"] / r.briefs >= floor, (f, r.mistakes)
        assert r.outcomes[f]["invented"] == 0, (f, r.mistakes)
    assert r.must_invented == 0
    assert sum(r.must_recall) / len(r.must_recall) >= 0.9


# ------------------------------------------------------------------ money


def test_money_is_not_followers_percent_or_spf():
    x = v("3 creators with 20k+ followers, mention niacinamide 10% and SPF 50")
    assert x["budget_total_inr"] is None and x["min_followers"] == 20000


def test_per_creator_times_count():
    x = v("need 4 creators, 3k each")
    assert (x["budget_per_creator_inr"], x["budget_total_inr"]) == (3000, 12000)


def test_per_reel_times_reels_per_creator():
    x = v("4 creators. 2 reels per creator. 3k per reel")
    assert (x["budget_per_creator_inr"], x["budget_total_inr"]) == (6000, 24000)


def test_lakh_shorthand():
    assert v("12 creators, ₹1.5L total")["budget_total_inr"] == 150_000
    assert v("budget 2 lakh for 10 creators")["budget_total_inr"] == 200_000


def test_bare_number_is_not_guessed_as_money():
    assert v("need someone in chennai, tamil, 3500")["budget_total_inr"] is None


def test_price_range_of_product_is_not_budget():
    assert v("our serums cost 299-899, need 3 creators")["budget_total_inr"] is None


# ------------------------------------------------------------------ counts


@pytest.mark.parametrize(
    "text, n",
    [
        ("need 3 girls with oily skin", 3),
        ("hiring five UGC creators", 5),
        ("a couple of creators", 2),
        ("telugu girls, 5 of them", 5),
        ("3 kannada creators", 3),
        ("~7 creators needed", 7),
    ],
)
def test_counts(text, n):
    assert v(text)["creators_needed"] == n


def test_counts_ignore_other_numbers():
    assert v("4 reels from creators, D2C brand, 3 weeks")["creators_needed"] is None


# ------------------------------------------------------------------ dates


def test_year_rolls_over():
    assert v("go live on 3rd Jan")["deadline"] == date(2027, 1, 3)


def test_slashed_numbers_need_a_deadline_word():
    assert v("3-4 reels needed")["deadline"] is None
    assert v("reels by 30/11")["deadline"] == date(2026, 11, 30)


def test_deadline_word_beats_product_claim():
    assert v("mention brightening in 2 weeks. Deadline 20th Nov")["deadline"] == date(2026, 11, 20)


def test_relative_deadlines():
    assert v("within 10 days")["deadline"] == date(2026, 11, 24)
    assert v("reels by end of december")["deadline"] == date(2026, 12, 31)
    assert v("by month end")["deadline"] == date(2026, 11, 30)


def test_may_the_verb_is_not_a_date():
    assert v("you may 5 creators")["deadline"] is None


# ------------------------------------------------------------------ must-say


def test_negated_points_are_left_out():
    assert v("dont say chemical free. mention spf 50 and no white cast")["must_say"] == ["spf 50", "no white cast"]


def test_hinglish_trigger():
    assert v("bolna hai long lasting and gift box packaging, within 10 days")["must_say"] == [
        "long lasting",
        "gift box packaging",
    ]


def test_protected_phrase_and_trailing_product_words():
    assert v("showing before and after of our acne spot gel")["must_say"] == ["before and after"]


def test_usage_duration_point():
    assert "3-week use" in v("try our onion oil for 3 weeks and share your honest review")["must_say"]


def test_point_matching_is_forgiving_about_small_words():
    assert point_matches("the foam", "foam")
    assert point_matches("brightening in 2 weeks", "brightening in two weeks")
    assert not point_matches("matte finish", "no white cast")


# ------------------------------------------------------------------ niche, place, language


def test_longest_product_phrase_wins():
    x = v("our hair removal cream")
    assert (x["category"], x["sub_niche"]) == ("personal_care", "body_care")


def test_city_alias_and_word_boundaries():
    assert v("creators in blr")["city"] == "Bengaluru"
    assert v("hydrating serum")["city"] == "any"  # 'hyd' inside 'hydrating' is not Hyderabad


def test_languages_in_order():
    assert v("hindi or english both ok")["languages"] == ["Hindi", "English"]


def test_nothing_stated_means_blank_or_low_confidence_default():
    bp = parse("hello", as_of=TODAY)
    assert bp.format.value == "9:16 reel" and bp.format.confidence == "low"
    assert bp.city.value == "any" and bp.city.confidence == "low"
    for f in ("category", "creators_needed", "budget_total_inr", "deadline", "must_say", "languages"):
        assert getattr(bp, f).value is None


def test_every_value_has_evidence():
    bp = parse(
        "need 3 girls blr based for our gel sunscreen reel, 15k total, show no white cast, by 5th dec", as_of=TODAY
    )
    for name in ("creators_needed", "budget_total_inr", "city", "deadline", "must_say", "sub_niche"):
        f = getattr(bp, name)
        assert f.value is not None and f.evidence and f.confidence == "high", name


# ------------------------------------------------------------------ hand-off to the matcher


def test_to_brief_query():
    text = "need 3 girls blr for sunscreen reels, 15k total, kannada, 10k-50k followers"
    q = to_brief_query(parse(text, as_of=TODAY), text)
    assert (q.category, q.sub_niche, q.city, q.languages) == ("beauty_skincare", "skincare", "Bengaluru", ["Kannada"])
    assert (q.budget_per_creator_inr, q.min_followers, q.max_followers) == (5000, 10000, 50000)


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the GPU and the e5 model")
def test_niche_fallback_when_no_product_word():
    from app.ml.embed import get_embedder
    from app.profile.niche import ZeroShotNiche

    e = get_embedder()
    bp = parse(
        "we sell lip and cheek colour sticks in nude shades", as_of=TODAY, niche_model=ZeroShotNiche(e), embedder=e
    )
    assert bp.sub_niche.value == "makeup" and bp.sub_niche.confidence == "low"


# ------------------------------------------------------------------ ASCI disclosure (F2)


def test_disclosure_is_on_every_brief_and_nothing_removes_it():
    for text in ("hello", "need 3 girls, 15k total", "dont add #ad please, no paid partnership tag"):
        bp = parse(text, as_of=TODAY)
        assert bp.disclosure.confidence == "fixed"
        assert "Paid partnership" in bp.disclosure.value and "#ad" in bp.disclosure.value
        assert bp.disclosure.evidence.startswith("always added")
