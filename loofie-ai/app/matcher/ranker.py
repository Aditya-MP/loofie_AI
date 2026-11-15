"""Model 2, stage 2 of 3 (retrieve → rank → rerank): filter, score, explain.

Pure functions, no database, so it is easy to test and to swap the hand-set weights for a
learned model later without touching retrieval or re-ranking."""

from collections import Counter
from dataclasses import dataclass

import numpy as np

from app.matcher.features import (
    BriefQuery,
    Candidate,
    budget_fit,
    city_fit,
    language_fit,
    matcher_config,
    niche_fit,
    price_band,
    quality_fit,
    track_fit,
)


@dataclass
class MatchResult:
    creator_id: int
    handle: str
    score: float
    why: str
    parts: dict[str, float]
    estimated_rate_inr: tuple[int, int] | None
    quality_label: str | None = None
    activity: str | None = None
    rank_before_rerank: int | None = None  # the ranker's own position, so feedback can tell the two apart
    rerank_note: str | None = None  # why a business rule moved this creator
    availability: str = "suggested"  # ready | interested | suggested (picking a suggested one sends an invite)


def _views(v: float | None) -> str | None:
    if v is None:
        return None
    return f"median {v / 1000:.1f}k views".replace(".0k", "k") if v >= 1000 else f"median {v:.0f} views"


def exclusion(brief: BriefQuery, c: Candidate, cfg: dict) -> str | None:
    f = cfg["filters"]
    if c.quality_label in f["exclude_quality"]:
        return "flagged suspicious"
    if c.activity in f["exclude_activity"]:
        return "inactive"
    if brief.min_followers is not None or brief.max_followers is not None:
        if c.followers is None:
            return "followers unknown"
        if brief.min_followers is not None and c.followers < brief.min_followers:
            return "too few followers"
        if brief.max_followers is not None and c.followers > brief.max_followers:
            return "too many followers"
    if (
        f["enforce_level"]
        and c.level == "NEW"
        and brief.budget_per_creator_inr
        and brief.budget_per_creator_inr > f["new_level_max_inr"]
    ):
        return f"NEW level: max ₹{f['new_level_max_inr']:,} per job"
    return None


def rank(
    brief: BriefQuery,
    candidates: list[Candidate],
    query_vec: np.ndarray | None,
    cfg: dict | None = None,
    keep_all: bool = False,
) -> tuple[list[MatchResult], Counter]:
    """keep_all=True returns every scored candidate (the re-ranker needs more than the top k)."""
    cfg = cfg or matcher_config()
    w = cfg["weights"]
    excluded: Counter = Counter()
    kept = []
    skip = set(brief.exclude_creator_ids)

    for c in candidates:
        if c.creator_id in skip:
            continue
        reason = exclusion(brief, c, cfg)
        niche, niche_reason, niche_out = niche_fit(brief, c, cfg)
        budget, budget_reason, budget_out = budget_fit(brief.budget_per_creator_inr, c.followers, cfg)
        if reason is None and niche_out:
            reason = "different category"
        if reason is None and budget_out:
            reason = "estimated rate far above budget"
        if reason:
            excluded[reason] += 1
            continue
        kept.append((c, niche, niche_reason, budget, budget_reason))

    # Raw cosine similarities are bunched together (0.75–0.90 for e5), so rescale within the
    # candidates: best match = 1, worst = 0. Creators with no text get 0.
    sims = np.zeros(len(kept))
    has_vec = [i for i, (c, *_) in enumerate(kept) if c.embedding is not None]
    if query_vec is not None and has_vec:
        raw = np.stack([kept[i][0].embedding for i in has_vec]) @ query_vec
        spread = raw.max() - raw.min()
        sims[has_vec] = (raw - raw.min()) / spread if spread > 1e-6 else 0.5

    results = []
    for (c, niche, niche_reason, budget, budget_reason), sim in zip(kept, sims, strict=True):
        quality, quality_reason = quality_fit(c, cfg)
        lang, lang_reason = language_fit(brief.languages, c, cfg)
        city, city_reason = city_fit(brief.city, c.city, cfg)
        track, track_reason = track_fit(c.track, cfg)
        parts = {
            "similarity": float(sim),
            "niche": niche,
            "quality": quality,
            "language": lang,
            "budget": budget,
            "city": city,
            "completeness": c.completeness,
            "track_record": track,
        }
        used = {k: v for k, v in parts.items() if v is not None}
        # Parts the brief doesn't ask about (no city, no budget...) drop out instead of counting as 0.
        score = sum(w[k] * v for k, v in used.items()) / sum(w[k] for k in used)

        reasons = [
            niche_reason,
            "content close to the brief" if c.embedding is not None and sim >= 0.7 else None,
            lang_reason,
            city_reason,
            _views(c.median_views),
            budget_reason,
            quality_reason,
            track_reason,
        ]
        results.append(
            MatchResult(
                creator_id=c.creator_id,
                handle=c.handle,
                score=round(score, 4),
                why=" · ".join(r for r in reasons if r),
                parts={k: round(v, 3) for k, v in used.items()},
                estimated_rate_inr=price_band(c.followers, cfg),
                quality_label=c.quality_label,
                activity=c.activity,
            )
        )

    results.sort(key=lambda r: (-r.score, r.creator_id))
    for i, r in enumerate(results, 1):
        r.rank_before_rerank = i
    return (results if keep_all else results[: brief.top_k or cfg["top_k"]]), excluded
