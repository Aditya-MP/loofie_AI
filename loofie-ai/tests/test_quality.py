from datetime import date, timedelta

from app.quality.rules import QualityInput, assess

TODAY = date(2026, 11, 14)


def stats(**kw):
    base = dict(
        median_views=None,
        median_likes=None,
        median_comments=None,
        engagement_rate=None,
        view_spike=None,
        posts_last_30d=None,
        days_since_last_post=None,
    )
    return base | kw


def creator(followers=12000, following=600, **kw):
    s = stats(**{k: v for k, v in kw.items() if k in stats()})
    rest = {k: v for k, v in kw.items() if k not in s}
    return QualityInput(handle="x", followers=followers, following=following, stats=s, **rest)


def codes(r):
    return [f.code for f in r.findings]


def healthy(**kw):
    base = dict(
        median_views=4000,
        median_likes=400,
        median_comments=30,
        engagement_rate=0.036,
        posts_last_30d=8,
        days_since_last_post=3,
    )
    return creator(**(base | kw))


def test_healthy_creator_is_real():
    r = assess(healthy(), as_of=TODAY)
    assert r.label == "real" and r.score == 100 and r.findings == []


def test_views_far_below_followers_is_suspicious():
    r = assess(healthy(followers=41000, median_views=400, engagement_rate=0.011), as_of=TODAY)
    assert r.label == "suspicious"
    assert codes(r)[0] == "low_views"
    assert "41,000 followers" in r.summary


def test_reported_average_can_only_raise_amber():
    r = assess(creator(followers=50000, avg_views_reported=500), as_of=TODAY)
    [f] = r.findings
    assert f.code == "low_views" and f.severity == "amber"


def test_small_accounts_skip_ratio_rules():
    r = assess(healthy(followers=3000, median_views=50, engagement_rate=0.001), as_of=TODAY)
    assert "low_views" not in codes(r) and "low_engagement" not in codes(r)


def test_viral_reach_and_spike_are_notes_not_penalties():
    r = assess(healthy(followers=1400, median_views=36000, view_spike=25), as_of=TODAY)
    assert set(codes(r)) == {"views_far_above_followers", "view_spike"}
    assert r.score == 100 and r.label == "real"


def test_follow_for_follow_and_likes_without_comments_add_up():
    r = assess(healthy(following=11000, median_likes=900, median_comments=2), as_of=TODAY)
    assert set(codes(r)) == {"follow_back", "likes_without_comments"}
    assert r.label == "unsure" and r.score == 60


def test_high_engagement_on_big_account():
    r = assess(healthy(followers=60000, median_views=30000, engagement_rate=0.4, following=500), as_of=TODAY)
    assert codes(r) == ["high_engagement"]


def test_inactive_is_activity_not_fraud_and_not_double_counted():
    r = assess(healthy(days_since_last_post=150, posts_last_30d=0), as_of=TODAY)
    assert codes(r) == ["inactive"]
    assert r.label == "real" and r.score == 100 and r.activity == "inactive"


def test_slow_poster():
    r = assess(healthy(posts_last_30d=1, days_since_last_post=12), as_of=TODAY)
    assert codes(r) == ["rarely_posts"] and r.activity == "slow" and r.label == "real"


def test_activity_unknown_without_dates():
    r = assess(creator(followers=9000, avg_views_reported=4000), as_of=TODAY)
    assert r.activity == "unknown"


def test_inactive_from_hand_entered_date():
    q = creator(median_views=4000, last_post_date_reported=TODAY - timedelta(days=60))
    r = assess(q, as_of=TODAY)
    assert codes(r) == ["inactive"] and r.activity == "slow"


def test_fraud_and_inactive_together():
    r = assess(healthy(followers=41000, median_views=400, engagement_rate=0.011, days_since_last_post=200), as_of=TODAY)
    assert r.label == "suspicious" and r.activity == "inactive"
    assert r.summary.startswith("41,000 followers")


def test_generic_comments():
    bots = ["nice 🔥", "🔥🔥", "wow", "Nice pic", "awesome", "😍😍", "super", "nice 🔥"] * 2
    r = assess(healthy(comments=bots), as_of=TODAY)
    assert "generic_comments" in codes(r)

    real = [
        "does this work on dry skin?",
        "which shade did you use",
        "price please",
        "bought it after your reel!",
        "is it fragrance free?",
        "how long did it last",
        "my sister uses this too",
        "link?",
        "works under makeup?",
        "can men use it",
        "where did you buy",
        "too sticky for me honestly",
        "does it leave white cast",
        "how many days to see results",
        "is it safe in pregnancy",
        "love the review, very honest",
    ]
    assert "generic_comments" not in codes(assess(healthy(comments=real), as_of=TODAY))


def test_clean_but_thin_data_is_not_called_real():
    r = assess(creator(followers=9000, avg_views_reported=4000), as_of=TODAY)
    assert r.findings == [] and r.label == "unsure" and "too little data" in r.summary


def test_too_few_comments_are_not_judged():
    assert "generic_comments" not in codes(assess(healthy(comments=["nice"] * 5), as_of=TODAY))


def test_not_enough_data_is_unsure_never_suspicious():
    r = assess(creator(followers=None, following=None), as_of=TODAY)
    assert r.label == "unsure" and "not enough data" in r.summary
