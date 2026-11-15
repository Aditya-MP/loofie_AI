"""Model 2, stage 1 of 3: candidate retrieval.

One database query: hard filters in SQL, then the nearest creators to the brief by embedding
(pgvector, HNSW index), limited to a few hundred. The ranker only ever sees these, so matching
stays fast at 100,000 creators. Creators with no text yet come last, not never.

The same query counts what each filter removed, so "left out" still explains itself.
"""

from collections import Counter

import numpy as np
from sqlalchemy import and_, case, false, func, not_, or_, select
from sqlalchemy.orm import Session

from app.db.models import Creator, CreatorProfile, CreatorQuality
from app.importer import vocab
from app.matcher.features import BriefQuery, Candidate, matcher_config

# The sheet's own category names count as hand labels. Out-of-scope ones (fashion, food...) only
# from the data sheet, so a hand-labelled fashion creator is left out of a skincare brief; old
# field data used free-text names, which don't count.
HAND_CATEGORIES = vocab.IN_SCOPE
LEGACY = "legacy_field_csv"


def is_hand_label(category: str | None, data_source: str | None) -> bool:
    return category in HAND_CATEGORIES or (category in vocab.OUT_OF_SCOPE and data_source != LEGACY)


def to_candidate(c: Creator, p: CreatorProfile | None, q: CreatorQuality | None) -> Candidate:
    # The team's hand-entered niche wins over the model's guess. Old field data used other
    # category names, so only the sheet's own categories count as hand labels.
    hand = is_hand_label(c.category, c.data_source)
    return Candidate(
        creator_id=c.id,
        handle=c.handle,
        followers=c.followers,
        city=c.city,
        category=c.category if hand else (p.category if p else None),
        sub_niche=(c.sub_niche or (p.sub_niche if p else None)) if hand else (p.sub_niche if p else None),
        # Old field data mixed spoken and caption language, so only newer sources count as spoken.
        spoken_language=c.primary_language if c.data_source != LEGACY else None,
        caption_languages=(p.language_shares if p else {}) or {},
        embedding=np.asarray(p.embedding, dtype=np.float32) if p is not None and p.embedding is not None else None,
        quality_label=q.label if q else None,
        quality_score=q.score if q else None,
        activity=q.activity if q else None,
        level=c.level,
        median_views=(p.stats or {}).get("median_views") if p else None,
        completeness=p.completeness if p else 0.0,
        paid_brands=list(p.brands_mentioned or []) if p else [],
        creator_ref=c.external_id,
    )


def _effective_category():
    """The team's hand label wins; otherwise the Profile Builder's guess (same rule as to_candidate)."""
    hand = or_(
        Creator.category.in_(HAND_CATEGORIES),
        and_(Creator.category.in_(vocab.OUT_OF_SCOPE), Creator.data_source != LEGACY),
    )
    return case((hand, Creator.category), else_=CreatorProfile.category)


def exclusion_rules(brief: BriefQuery, cfg: dict) -> list[tuple[str, object]]:
    """(reason, SQL condition that excludes). Checked in order; the first that matches is the reason.
    Must agree with ranker.exclusion() + niche_fit(); test_retrieve checks they do."""
    f = cfg["filters"]
    rules = [
        ("flagged suspicious", CreatorQuality.label.in_(f["exclude_quality"])),
        ("inactive", CreatorQuality.activity.in_(f["exclude_activity"])),
    ]
    if brief.min_followers is not None or brief.max_followers is not None:
        rules.append(("followers unknown", Creator.followers.is_(None)))
        if brief.min_followers is not None:
            rules.append(("too few followers", Creator.followers < brief.min_followers))
        if brief.max_followers is not None:
            rules.append(("too many followers", Creator.followers > brief.max_followers))
    if brief.category:
        allowed = [brief.category, *cfg["related_categories"].get(brief.category, [])]
        cat = _effective_category()
        rules.append(("different category", and_(cat.is_not(None), cat.not_in(allowed))))
    if brief.exclude_creator_ids:
        rules.append(("already on this brief", Creator.id.in_(brief.exclude_creator_ids)))
    return rules


def _base():
    return (
        select(Creator, CreatorProfile, CreatorQuality)
        .outerjoin(CreatorProfile, CreatorProfile.creator_id == Creator.id)
        .outerjoin(CreatorQuality, CreatorQuality.creator_id == Creator.id)
    )


def retrieve(
    session: Session, brief: BriefQuery, query_vec: np.ndarray | None, limit: int | None = None, cfg: dict | None = None
) -> tuple[list[Candidate], Counter]:
    cfg = cfg or matcher_config()
    rules = exclusion_rules(brief, cfg)
    # NULL-safe: a creator with no quality row yet must not be dropped by "label IN (...)".
    excluded_by = [func.coalesce(cond, false()) for _, cond in rules]

    # Count each reason once, in order: a suspicious AND inactive creator counts as suspicious.
    counts = []
    for i in range(len(rules)):
        earlier = [not_(c) for c in excluded_by[:i]]
        counts.append(func.count().filter(and_(excluded_by[i], *earlier)).label(f"r{i}"))
    left_out = Counter()
    if counts:
        row = session.execute(_base().with_only_columns(*counts)).one()
        left_out = Counter(
            {reason: n for (reason, _), n in zip(rules, row, strict=True) if n and reason != "already on this brief"}
        )

    q = _base().where(not_(or_(*excluded_by))) if excluded_by else _base()
    if query_vec is not None:
        q = q.order_by(CreatorProfile.embedding.cosine_distance(query_vec.tolist()).asc().nulls_last(), Creator.id)
    else:
        q = q.order_by(Creator.id)
    rows = session.execute(q.limit(limit or cfg["retrieval"]["limit"])).all()
    return [to_candidate(c, p, qr) for c, p, qr in rows], left_out


def load_all(session: Session) -> list[Candidate]:
    """Every creator, unfiltered. For evaluation only; matching goes through retrieve()."""
    return [to_candidate(c, p, q) for c, p, q in session.execute(_base().order_by(Creator.id)).all()]
