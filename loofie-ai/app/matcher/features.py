"""Model 2 building blocks. Each takes plain values and returns (score 0..1, short reason)."""

from dataclasses import dataclass, field

import numpy as np

from app.config import load_toml
from app.matcher.track_record import TrackRecord, describe


def matcher_config() -> dict:
    return load_toml("matcher")


@dataclass
class BriefQuery:
    text: str
    category: str | None = None
    sub_niche: str | None = None
    city: str | None = None  # None or "any" = anywhere
    languages: list[str] = field(default_factory=list)
    budget_per_creator_inr: int | None = None
    min_followers: int | None = None
    max_followers: int | None = None
    exclude_creator_ids: list[int] = field(default_factory=list)
    top_k: int | None = None
    brand_handle: str | None = None
    # Same-niche brands: a creator who just did a paid post for one of them is a risk for this brand.
    competitor_handles: list[str] = field(default_factory=list)
    # Ready: said yes this week, has capacity, brief is within their rules (the backend checks that).
    # Interested: tapped "I'm in" on this brief. Both book on pick; both get a small boost.
    ready_creator_ids: set[int] = field(default_factory=set)
    interested_creator_ids: set[int] = field(default_factory=set)


@dataclass
class Candidate:
    creator_id: int
    handle: str
    followers: int | None
    city: str | None
    category: str | None
    sub_niche: str | None
    spoken_language: str | None  # hand-entered
    caption_languages: dict[str, float]
    embedding: np.ndarray | None
    quality_label: str | None
    quality_score: int | None
    activity: str | None
    level: str
    median_views: float | None
    completeness: float
    paid_brands: list[str] = field(default_factory=list)  # brands in recent paid posts
    creator_ref: str | None = None  # the backend's id; events are keyed by it
    track: "TrackRecord | None" = None  # past Loofie jobs (filled by the service, None = no history)


def city_key(city: str | None, aliases: dict[str, list[str]]) -> str | None:
    if not city:
        return None
    c = city.strip().lower().split(",")[0].strip()
    for key, names in aliases.items():
        if c in names:
            return key
    return c


def city_fit(brief_city: str | None, creator_city: str | None, cfg: dict) -> tuple[float | None, str | None]:
    """None score = brief doesn't care about city."""
    s, aliases = cfg["scores"], cfg["city_aliases"]
    want = city_key(brief_city, aliases)
    if want in (None, "any"):
        return None, None
    have = city_key(creator_city, aliases)
    if have is None:
        return s["city_unknown"], "city not stated"
    if have == want:
        return s["city_match"], f"based in {creator_city}"
    return s["city_mismatch"], f"based in {creator_city}"


def language_fit(wanted: list[str], c: Candidate, cfg: dict) -> tuple[float | None, str | None]:
    s = cfg["scores"]
    if not wanted:
        return None, None
    want = {w.lower() for w in wanted}
    if c.spoken_language:
        if c.spoken_language.lower() in want:
            return s["language_spoken_match"], f"speaks {c.spoken_language}"
        return s["language_spoken_mismatch"], f"speaks {c.spoken_language}"
    captions = {lang.lower() for lang, share in c.caption_languages.items() if share >= 0.2}
    if not captions:
        return s["language_unknown"], "language unknown"
    hit = captions & want
    if hit:
        return s["language_caption_match"], f"captions in {next(iter(hit)).title()}"
    return s["language_caption_mismatch"], "captions in another language"


def price_band(followers: int | None, cfg: dict) -> tuple[int, int] | None:
    if followers is None:
        return None
    for band in cfg["price_bands"]:
        if followers <= band["max_followers"]:
            return band["low"], band["high"]
    return None


def budget_fit(budget: int | None, followers: int | None, cfg: dict) -> tuple[float | None, str | None, bool]:
    """Returns (score, reason, exclude)."""
    if budget is None:
        return None, None, False
    band = price_band(followers, cfg)
    if band is None:
        return cfg["scores"]["budget_unknown"], "rate unknown", False
    low, high = band
    est = f"est. ₹{low:,}–₹{high:,}"
    if budget >= low:
        return 1.0, f"{est} fits budget", False
    if budget >= low * cfg["filters"]["exclude_budget_below_share"]:
        return cfg["scores"]["budget_slightly_below"], f"{est}, above budget", False
    return 0.0, f"{est}, far above budget", True


def niche_fit(brief: BriefQuery, c: Candidate, cfg: dict) -> tuple[float, str | None, bool]:
    """Returns (score, reason, exclude). A creator in an unrelated category is excluded."""
    s = cfg["scores"]
    if not brief.category:
        if brief.sub_niche and c.sub_niche == brief.sub_niche:
            return s["niche_same_sub"], f"{c.sub_niche} creator", False
        return s["niche_unknown"], None, False
    if c.category is None:
        return s["niche_unknown"], "niche unknown", False
    related = cfg["related_categories"].get(brief.category, [])
    if c.category == brief.category:
        if brief.sub_niche and c.sub_niche == brief.sub_niche:
            return s["niche_same_sub"], f"{c.sub_niche} creator", False
        label = c.sub_niche or c.category
        return s["niche_same_category"], f"{label} creator", False
    if c.category in related:
        return s["niche_related_category"], f"{c.sub_niche or c.category} creator", False
    return 0.0, None, True


def quality_fit(c: Candidate, cfg: dict) -> tuple[float, str | None]:
    s = cfg["scores"]
    scale = (c.quality_score or 0) / 100
    if c.quality_label == "real":
        return s["quality_real"] * scale, "quality check passed"
    if c.quality_label == "unsure" or c.quality_label is None:
        return s["quality_unsure"] * max(scale, 0.5), "quality not yet verified"
    return 0.0, "flagged suspicious"


def track_fit(track: TrackRecord | None, cfg: dict) -> tuple[float, str | None]:
    """Always scored: a creator with no history gets the neutral prior, so she is neither helped nor
    punished, and the explore slot (rerank) still gives new creators their first job."""
    if track is None:
        return cfg["track_record"]["prior"], None
    return track.score, describe(track)
