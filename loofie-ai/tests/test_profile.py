from datetime import date, timedelta

import numpy as np
import pytest
import torch

from app.profile import language, signals
from app.profile.builder import CreatorInput, build
from app.profile.signals import PostView
from tests.helpers import FakeEmbedder

TODAY = date(2026, 11, 14)


def post(
    days_ago=1, views=None, likes=None, comments=None, caption=None, post_type="reel", paid=None, ad=None, tagged=()
):
    return PostView(
        TODAY - timedelta(days=days_ago), post_type, views, likes, comments, caption, paid, ad, list(tagged)
    )


# ------------------------------------------------------------------ language


def test_language_scripts_and_romanized():
    assert language.detect(["ನಮ್ಮ ಬೆಂಗಳೂರು ಸ್ಕಿನ್ ಕೇರ್ ರೊಟೀನ್"]).primary == "Kannada"
    assert language.detect(["yeh serum bahut accha hai yaar, kya glow aaya"]).primary == "Hindi"
    assert language.detect(["this is my favourite sunscreen for oily skin and it works"]).primary == "English"


def test_language_needs_enough_signal():
    r = language.detect(["✨🔥"])
    assert r.primary is None and r.shares == {}


def test_language_mixed_reports_shares():
    r = language.detect(["thumba chennagide guru, this sunscreen is the best for my skin"])
    assert set(r.shares) == {"Kannada", "English"}


# ------------------------------------------------------------------ signals


def test_median_not_average_and_spike():
    posts = [post(i, views=v) for i, v in enumerate([1000, 1200, 900, 1100, 50000], 1)]
    st = signals.stats(posts, followers=10000, as_of=TODAY)
    assert st["median_views"] == 1100
    assert st["views_to_followers"] == 0.11
    assert st["view_spike"] == pytest.approx(45.45, abs=0.01)


def test_recent_window_and_dates():
    posts = [post(d, views=100) for d in (2, 10, 25, 40, 90)]
    st = signals.stats(posts, followers=None, as_of=TODAY)
    assert st["posts_last_30d"] == 3
    assert st["days_since_last_post"] == 2
    assert st["views_to_followers"] is None


def test_engagement_uses_likes_and_comments():
    st = signals.stats([post(1, likes=400, comments=40, post_type="photo")], followers=10000, as_of=TODAY)
    assert st["engagement_rate"] == 0.044
    assert st["reels_seen"] == 0


def test_paid_detection():
    assert signals.is_paid(post(caption="loving this #ad"))
    assert signals.is_paid(post(caption="Paid partnership with @x"))
    assert signals.is_paid(post(paid=True))
    assert not signals.is_paid(post(caption="my adorable cat #adorable"))


def test_brands_only_from_paid_posts():
    posts = [
        post(1, caption="New sunscreen from @dewdrop.skin! #ad", tagged=["dewdrop.skin"]),
        post(2, caption="shot by @myphotographer_friend"),
        post(3, caption="#collab with @kesh_roots and @dewdrop.skin."),
    ]
    assert signals.brands_mentioned(posts, own_handle="me") == ["dewdrop.skin", "kesh_roots"]


def test_mentions_ignore_emails():
    assert signals.mentions("mail me at hi@studio.com or @brand_x") == ["brand_x"]


def test_clean_caption_trims_hashtag_walls():
    cap = "Night routine 🌙 " + " ".join(f"#tag{i}" for i in range(30))
    out = signals.clean_caption(cap)
    assert out.startswith("Night routine 🌙") and out.count("#") == 5


# ------------------------------------------------------------------ builder


def test_builder_full_creator():
    c = CreatorInput(
        1,
        "mira",
        "Oily skin girl | honest skincare reviews | Bengaluru",
        None,
        12000,
        [post(d, views=3000 + d, likes=300, comments=20, caption=f"sunscreen review day {d}") for d in range(1, 8)],
    )
    [row] = build([c], FakeEmbedder(), as_of=TODAY)
    assert row["text_sources"] == ["bio", "captions:7"]
    assert len(row["embedding"]) == 384
    assert abs(np.linalg.norm(row["embedding"]) - 1) < 1e-5
    assert row["category"] is not None
    assert row["missing"] == [] and row["completeness"] == 1.0


def test_builder_falls_back_to_category_text_and_flags_missing():
    c = CreatorInput(2, "old_row", None, "Beauty & Makeup", 5000, [])
    [row] = build([c], FakeEmbedder(), as_of=TODAY)
    assert row["text_sources"] == ["category_raw"]
    assert set(row["missing"]) == {"bio", "captions", "views", "post_dates"}


def test_builder_no_text_means_no_guess():
    c = CreatorInput(3, "empty", None, None, None, [])
    [row] = build([c], FakeEmbedder(), as_of=TODAY)
    assert row["embedding"] is None and row["category"] is None


# ------------------------------------------------------------------ real model (GPU)


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the GPU and the e5 model")
def test_real_model_separates_niches():
    from app.ml.embed import get_embedder

    creators = [
        CreatorInput(
            1, "a", "Honest skincare reviews for oily, acne-prone skin. Sunscreen and serum addict", None, 1, []
        ),
        CreatorInput(2, "b", "Hair fall journey | onion oil, hair masks and curly hair routines", None, 1, []),
        CreatorInput(3, "c", "Street food and restaurant reviews across Bengaluru", None, 1, []),
        CreatorInput(4, "d", "Beard care and grooming tips for men", None, 1, []),
    ]
    rows = build(creators, get_embedder(), as_of=TODAY)
    assert [r["sub_niche"] or r["other_niche"] for r in rows] == ["skincare", "haircare", "food", "mens_grooming"]
    assert [r["category"] for r in rows] == ["beauty_skincare", "beauty_skincare", "other", "personal_care"]
