"""Model 3 (Quality Check), rules version.

Each rule looks at one signal and returns a Finding with a reason a brand or ops person can read,
or None. The label comes from the total score. Nothing here rejects a creator: "suspicious" sends
them to the ops queue for a human to check.
"""

import re
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date

from app.config import load_toml


def quality_config() -> dict:
    return load_toml("quality")


@dataclass
class QualityInput:
    handle: str
    followers: int | None
    following: int | None
    stats: dict  # from the Profile Builder
    avg_views_reported: int | None = None  # older field data: an average, weaker than a median
    posts_last_30d_reported: int | None = None
    last_post_date_reported: date | None = None
    comments: list[str] = field(default_factory=list)
    # False = followers are only self-reported (or from old imports); None = not known
    followers_verified: bool | None = None


@dataclass
class Finding:
    code: str
    severity: str  # red | amber | info
    reason: str
    value: float | None = None
    kind: str = "fraud"  # fraud: is the audience real? activity: are they posting enough to book?


@dataclass
class QualityResult:
    label: str  # real | suspicious | unsure — from fraud findings only
    score: int
    findings: list[Finding]
    signals_used: int
    summary: str
    activity: str  # active | slow | inactive | unknown — a genuine creator on a break is not "suspicious"


def _pct(x: float) -> str:
    return f"{x:.1%}" if x < 0.1 else f"{x:.0%}"


def _views(q: QualityInput) -> tuple[float | None, str]:
    if q.stats.get("median_views") is not None:
        return q.stats["median_views"], "median"
    if q.avg_views_reported is not None:
        return float(q.avg_views_reported), "reported average"
    return None, ""


def _weaker(sev: str, source: str) -> str:
    """An unverified average can raise a question, not a verdict."""
    return "amber" if sev == "red" and source == "reported average" else sev


def low_views(q, c):
    views, source = _views(q)
    if views is None or not q.followers or q.followers < c["min_followers"]:
        return None
    r = views / q.followers
    sev = "red" if r < c["red_below"] else "amber" if r < c["amber_below"] else None
    if not sev:
        return None
    return Finding(
        "low_views",
        _weaker(sev, source),
        f"{q.followers:,} followers but {source} reel views are {views:,.0f} ({_pct(r)} of followers)",
        r,
    )


def views_far_above_followers(q, c):
    views, source = _views(q)
    if views is None or not q.followers:
        return None
    r = views / q.followers
    if r <= c["info_above"]:
        return None
    return Finding(
        "views_far_above_followers",
        "info",
        f"{source} views are {r:.0f}x followers: reach comes mostly from non-followers, "
        f"so expect results to vary reel to reel",
        r,
    )


def view_spike(q, c):
    s = q.stats.get("view_spike")
    if s is None or s <= c["info_above"]:
        return None
    return Finding(
        "view_spike", "info", f"best reel did {s:.0f}x the usual views; judge by the median, not the best reel", s
    )


def low_engagement(q, c):
    e = q.stats.get("engagement_rate")
    if e is None or not q.followers or q.followers < c["min_followers"]:
        return None
    sev = "red" if e < c["red_below"] else "amber" if e < c["amber_below"] else None
    if not sev:
        return None
    return Finding(
        "low_engagement", sev, f"likes + comments are {_pct(e)} of followers; most followers don't interact", e
    )


def high_engagement(q, c):
    e = q.stats.get("engagement_rate")
    if e is None or not q.followers or q.followers < c["min_followers"] or e <= c["amber_above"]:
        return None
    return Finding(
        "high_engagement",
        "amber",
        f"likes + comments are {_pct(e)} of {q.followers:,} followers, unusually high for this size "
        f"(possible engagement groups)",
        e,
    )


def follow_back(q, c):
    if not q.followers or q.following is None or q.followers > c["max_followers"]:
        return None
    r = q.following / q.followers
    if r <= c["amber_above"]:
        return None
    return Finding(
        "follow_back",
        "amber",
        f"follows {q.following:,} accounts with {q.followers:,} followers; may have grown by follow-for-follow",
        r,
    )


def likes_without_comments(q, c):
    likes, comments = q.stats.get("median_likes"), q.stats.get("median_comments")
    if likes is None or comments is None or likes < c["min_median_likes"]:
        return None
    r = comments / likes
    if r >= c["amber_below"]:
        return None
    return Finding(
        "likes_without_comments",
        "amber",
        f"about {likes:,.0f} likes but only {comments:,.0f} comments per post; bought likes rarely comment",
        r,
    )


def inactive(q, c, as_of: date):
    days = q.stats.get("days_since_last_post")
    if days is None and q.last_post_date_reported:
        days = (as_of - q.last_post_date_reported).days
    if days is None:
        return None
    sev = "red" if days >= c["red_days"] else "amber" if days >= c["amber_days"] else None
    if not sev:
        return None
    return Finding("inactive", sev, f"last post was {days} days ago", days, kind="activity")


def rarely_posts(q, c):
    days = q.stats.get("days_since_last_post")
    if days is not None and days > 30:
        return None  # `inactive` already covers it; don't count the same problem twice
    n = q.stats.get("posts_last_30d")
    if n is None:
        n = q.posts_last_30d_reported
    if n is None or n >= c["amber_below"]:
        return None
    return Finding("rarely_posts", "amber", f"only {n} post(s) in the last 30 days", n, kind="activity")


_LETTERS = re.compile(r"[^\w\s]|_|\d", re.UNICODE)


def generic_comments(q, c):
    if len(q.comments) < c["min_comments"]:
        return None
    generic_words = set(c["generic_words"])
    normalized, generic = [], 0
    for text in q.comments:
        words = _LETTERS.sub(" ", text.lower()).split()
        normalized.append(" ".join(words))
        if not words or (len(words) <= 4 and all(w in generic_words for w in words)):
            generic += 1
    share = generic / len(q.comments)
    unique = len(set(normalized)) / len(q.comments)
    if share < c["amber_generic_share"] and unique >= c["amber_unique_below"]:
        return None
    return Finding(
        "generic_comments",
        "amber",
        f"{_pct(share)} of {len(q.comments)} comments are emoji-only or generic ('nice 🔥'), "
        f"{_pct(unique)} are unique; real audiences ask questions",
        share,
    )


RULES: dict[str, Callable] = {
    "low_views": low_views,
    "views_far_above_followers": views_far_above_followers,
    "view_spike": view_spike,
    "low_engagement": low_engagement,
    "high_engagement": high_engagement,
    "follow_back": follow_back,
    "likes_without_comments": likes_without_comments,
    "inactive": inactive,
    "rarely_posts": rarely_posts,
    "generic_comments": generic_comments,
}


def signals_available(q: QualityInput) -> int:
    views, _ = _views(q)
    return sum(v is not None for v in (q.followers, views, q.stats.get("median_likes"), q.stats.get("median_comments")))


def assess(q: QualityInput, as_of: date | None = None, cfg: dict | None = None) -> QualityResult:
    cfg = cfg or quality_config()
    as_of = as_of or date.today()
    findings = []
    for name, rule in RULES.items():
        rc = cfg["rules"][name]
        f = rule(q, rc, as_of) if name == "inactive" else rule(q, rc)
        if f:
            findings.append(f)
    order = {"red": 0, "amber": 1, "info": 2}
    findings.sort(key=lambda f: order[f.severity])
    fraud = [f for f in findings if f.kind == "fraud"]
    activity_found = [f for f in findings if f.kind == "activity"]

    score = max(0, 100 - sum(cfg["penalty"][f.severity] for f in fraud))
    used = signals_available(q)
    lc = cfg["label"]
    if used < lc["min_signals"]:
        label, summary = "unsure", "not enough data to judge (need followers plus views, likes or comments)"
    elif score < lc["suspicious_below"] or any(f.severity == "red" for f in fraud):
        label, summary = "suspicious", fraud[0].reason
    elif score < lc["real_at_least"]:
        label, summary = "unsure", fraud[0].reason
    elif lc["real_needs_verified"] and q.followers_verified is False:
        label, summary = (
            "unsure",
            "no red flags, but the numbers are self-reported; verify with an insights screenshot or Instagram connect",
        )
    elif used < lc["min_signals_for_real"]:
        label, summary = (
            "unsure",
            "no red flags, but too little data to call it real (needs likes and comments from recent posts)",
        )
    else:
        warn = [f for f in fraud if f.severity != "info"]
        label, summary = "real", warn[0].reason if warn else "numbers look normal for this account size"

    if any(f.severity == "red" for f in activity_found):
        activity = "inactive"
    elif activity_found:
        activity = "slow"
    elif (
        q.stats.get("days_since_last_post") is not None
        or q.last_post_date_reported
        or q.posts_last_30d_reported is not None
    ):
        activity = "active"
    else:
        activity = "unknown"
    return QualityResult(label, score, findings, used, summary, activity)
