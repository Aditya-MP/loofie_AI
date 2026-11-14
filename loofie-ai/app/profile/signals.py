"""Numbers and caption signals for a creator, calculated without any model."""

import re
import statistics
from dataclasses import dataclass
from datetime import date

PAID_HASHTAG = re.compile(
    r"#(ad|ads|advert|advertisement|sponsored|sponsor|collab|collaboration|partner|partnership|"
    r"paidpartnership|gifted|pr)\b",
    re.I,
)
PAID_PHRASE = re.compile(r"paid partnership|in (?:paid )?collaboration with|sponsored by|gifted by", re.I)
MENTION = re.compile(r"(?<![\w.@])@([A-Za-z0-9._]{1,30})")
HASHTAG = re.compile(r"#\w+")

RECENT = 10


@dataclass
class PostView:
    """The parts of a post the profile needs. Keeps this module free of database code."""

    post_date: date | None
    post_type: str | None
    views: int | None
    likes: int | None
    comments: int | None
    caption: str | None
    paid_label: bool | None
    ad_hashtag: bool | None
    brands_tagged: list[str]


def is_paid(p: PostView) -> bool:
    if p.paid_label or p.ad_hashtag:
        return True
    return bool(p.caption and (PAID_HASHTAG.search(p.caption) or PAID_PHRASE.search(p.caption)))


def mentions(text: str | None) -> list[str]:
    if not text:
        return []
    return [m.rstrip(".").lower() for m in MENTION.findall(text)]


def brands_mentioned(posts: list[PostView], own_handle: str) -> list[str]:
    """Handles tagged or @mentioned in paid posts, most frequent first. Only paid posts, so friends
    and photographers tagged in ordinary posts are not mistaken for brands."""
    seen: dict[str, int] = {}
    for p in posts:
        if not is_paid(p):
            continue
        for h in set(p.brands_tagged) | set(mentions(p.caption)):
            if h != own_handle:
                seen[h] = seen.get(h, 0) + 1
    return sorted(seen, key=lambda h: (-seen[h], h))


def _median(values: list[int | None]) -> float | None:
    vals = [v for v in values if v is not None]
    return float(statistics.median(vals)) if vals else None


def _ratio(a: float | None, b: int | None) -> float | None:
    return round(a / b, 4) if a is not None and b else None


def stats(posts: list[PostView], followers: int | None, as_of: date) -> dict:
    """Uses medians, not averages: one viral reel must not make a creator look 10x bigger."""
    dated = sorted((p for p in posts if p.post_date), key=lambda p: p.post_date, reverse=True)
    recent = (dated or posts)[:RECENT]
    reels = [p for p in recent if p.post_type == "reel" or p.views is not None]

    median_views = _median([p.views for p in reels])
    median_likes = _median([p.likes for p in recent])
    median_comments = _median([p.comments for p in recent])
    views = [p.views for p in reels if p.views is not None]
    engagement = None
    if followers and (median_likes is not None or median_comments is not None):
        engagement = round(((median_likes or 0) + (median_comments or 0)) / followers, 4)

    return {
        "posts_seen": len(posts),
        "reels_seen": len(reels),
        "median_views": median_views,
        "median_likes": median_likes,
        "median_comments": median_comments,
        "views_to_followers": _ratio(median_views, followers),
        "engagement_rate": engagement,
        # max / median: a value of 20 means one reel did 20x the usual, so averages would mislead
        "view_spike": round(max(views) / median_views, 2) if views and median_views else None,
        "posts_last_30d": sum(1 for p in dated if (as_of - p.post_date).days <= 30) if dated else None,
        "days_since_last_post": (as_of - dated[0].post_date).days if dated else None,
        "paid_posts": sum(1 for p in recent if is_paid(p)),
        "paid_share": round(sum(1 for p in recent if is_paid(p)) / len(recent), 3) if recent else None,
    }


def clean_caption(caption: str) -> str:
    """Hashtag walls add noise to embeddings; keep at most the first 5 hashtags."""
    tags = HASHTAG.findall(caption)
    body = HASHTAG.sub("", caption)
    return " ".join([" ".join(body.split()), *tags[:5]]).strip()
