"""Profile Builder: raw creator data in, a clean searchable profile out.

For each creator it works out the niche, caption language, brands they've done paid posts for,
honest numbers (medians), an embedding for matching, and what data is still missing.
"""

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date

import numpy as np
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Creator, CreatorProfile, Post
from app.profile import language, signals
from app.profile.niche import ZeroShotNiche
from app.profile.settings import profile_config

MIN_POSTS = 5


@dataclass
class CreatorInput:
    id: int
    handle: str
    bio: str | None
    category_raw: str | None
    followers: int | None
    posts: list[signals.PostView] = field(default_factory=list)


@dataclass
class TextPlan:
    """The texts to embed for one creator and how much each counts."""

    texts: list[str]
    weights: list[float]
    sources: list[str]

    @property
    def chars(self) -> int:
        return sum(len(t) for t in self.texts)


def text_plan(c: CreatorInput, cfg: dict) -> TextPlan:
    texts, weights, sources = [], [], []
    if c.bio:
        texts.append(c.bio)
        weights.append(cfg["bio_weight"])
        sources.append("bio")
    dated = sorted(c.posts, key=lambda p: p.post_date or date.min, reverse=True)
    captions = [signals.clean_caption(p.caption) for p in dated if p.caption and p.caption.strip()]
    captions = [t for t in captions[: cfg["max_captions"]] if t]
    if captions:
        texts += captions
        weights += [1.0] * len(captions)
        sources.append(f"captions:{len(captions)}")
    if not texts and c.category_raw:
        # Old field data has no bio or captions, only a typed category like "Beauty & Makeup".
        texts.append(c.category_raw)
        weights.append(1.0)
        sources.append("category_raw")
    return TextPlan(texts, weights, sources)


def missing_fields(c: CreatorInput, st: dict) -> list[str]:
    checks = {
        "followers": c.followers is not None,
        "bio": bool(c.bio),
        "captions": sum(1 for p in c.posts if p.caption) >= MIN_POSTS,
        "views": st["median_views"] is not None,
        "post_dates": st["days_since_last_post"] is not None,
    }
    return [name for name, ok in checks.items() if not ok]


def build(
    creators: list[CreatorInput],
    embedder,
    niche: ZeroShotNiche | None = None,
    as_of: date | None = None,
) -> list[dict]:
    """Pure function: no database. Returns one creator_profiles row per creator."""
    cfg = profile_config()
    as_of = as_of or date.today()
    niche = niche or ZeroShotNiche(embedder)
    version = "|".join([cfg["version"], embedder.name, niche.version, cfg["language"]["version"]])

    plans = [text_plan(c, cfg["text"]) for c in creators]
    # One GPU call for every text of every creator, then split back per creator.
    flat = [t for plan in plans for t in plan.texts]
    vecs = embedder.passages(flat)

    rows, start = [], 0
    for c, plan in zip(creators, plans, strict=True):
        own = vecs[start : start + len(plan.texts)]
        start += len(plan.texts)

        vec = None
        fallback_only = plan.sources == ["category_raw"]
        min_chars = cfg["text"]["min_chars_category_raw" if fallback_only else "min_chars"]
        if plan.chars >= min_chars:
            v = (own * np.array(plan.weights, dtype=np.float32)[:, None]).sum(axis=0)
            vec = v / np.linalg.norm(v)
        pred = niche.predict(vec) if vec is not None else None
        lang = language.detect(plan.texts)
        st = signals.stats(c.posts, c.followers, as_of)
        missing = missing_fields(c, st)

        rows.append(
            dict(
                creator_id=c.id,
                version=version,
                text_sources=plan.sources,
                embedding=vec.tolist() if vec is not None else None,
                category=pred.category if pred else None,
                category_confidence=pred.category_confidence if pred else None,
                sub_niche=pred.sub_niche if pred else None,
                sub_niche_confidence=pred.sub_niche_confidence if pred else None,
                other_niche=pred.other_niche if pred else None,
                niche_scores=pred.top_labels if pred else {},
                niche_unsure=pred.unsure if pred else None,
                caption_language=lang.primary,
                language_shares=lang.shares,
                brands_mentioned=signals.brands_mentioned(c.posts, c.handle),
                stats=st,
                completeness=round(1 - len(missing) / 5, 2),
                missing=missing,
            )
        )
    return rows


def load_inputs(session: Session, handles: list[str] | None = None) -> list[CreatorInput]:
    q = select(Creator)
    if handles:
        q = q.where(Creator.handle.in_(handles))
    creators = session.scalars(q.order_by(Creator.id)).all()
    ids = [c.id for c in creators]
    posts = defaultdict(list)
    if ids:
        for p in session.scalars(select(Post).where(Post.creator_id.in_(ids))):
            posts[p.creator_id].append(
                signals.PostView(
                    post_date=p.post_date,
                    post_type=p.post_type,
                    views=p.views,
                    likes=p.likes,
                    comments=p.comments_count,
                    caption=p.caption,
                    paid_label=p.paid_partnership_label,
                    ad_hashtag=p.ad_hashtag,
                    brands_tagged=list(p.brands_tagged or []),
                )
            )
    return [CreatorInput(c.id, c.handle, c.bio, c.category_raw, c.followers, posts[c.id]) for c in creators]


def build_and_save(
    session: Session, embedder, handles: list[str] | None = None, as_of: date | None = None
) -> list[dict]:
    rows = build(load_inputs(session, handles), embedder, as_of=as_of)
    if rows:
        # A profile is always rebuilt whole from the raw data, so replace the old row outright.
        table = CreatorProfile.__table__
        session.execute(table.delete().where(table.c.creator_id.in_([r["creator_id"] for r in rows])))
        session.execute(table.insert(), rows)
    return rows
