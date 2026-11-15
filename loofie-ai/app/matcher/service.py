"""Model 2 pipeline: retrieve → rank → rerank."""

from collections import Counter

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Brand
from app.matcher.features import BriefQuery, matcher_config
from app.matcher.ranker import MatchResult, rank
from app.matcher.rerank import apply_availability, rerank
from app.matcher.retrieve import retrieve


def competitors_of(session: Session, brand_handle: str | None) -> list[str]:
    """Brands in the same sub-niche (or category, if the sub-niche is unknown)."""
    if not brand_handle:
        return []
    brand = session.scalar(select(Brand).where(Brand.handle == brand_handle))
    if brand is None or (brand.sub_niche is None and brand.category is None):
        return []
    same = Brand.sub_niche == brand.sub_niche if brand.sub_niche else Brand.category == brand.category
    return list(session.scalars(select(Brand.handle).where(same, Brand.handle != brand_handle)))


def match(session: Session, brief: BriefQuery, embedder) -> tuple[list[MatchResult], Counter]:
    cfg = matcher_config()
    query_vec = embedder.queries([brief.text])[0] if brief.text else None
    if brief.brand_handle and not brief.competitor_handles:
        brief.competitor_handles = competitors_of(session, brief.brand_handle)

    candidates, left_out = retrieve(session, brief, query_vec, cfg=cfg)
    ranked, rank_out = rank(brief, candidates, query_vec, cfg, keep_all=True)
    ranked = apply_availability(brief, ranked, cfg)
    results = rerank(brief, ranked, {c.creator_id: c for c in candidates}, cfg)
    return results, left_out + rank_out
