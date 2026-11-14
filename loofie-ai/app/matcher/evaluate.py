"""Brand-hire test for Model 2 (the ranker alone: re-ranking rules like exploration would blur it).

For every brand with proven paid collaborations (Brand_Creator_Links), give the matcher only the
brand's own product description and check whether the creators the brand really hired land in
the top k. Compared against three baselines that need no AI.

No cheating: creator profiles are rebuilt from organic posts only, with every @mention removed,
so a hire can't be found just because its sponsored caption names the brand.
"""

import re
from collections import defaultdict
from dataclasses import dataclass, replace

import numpy as np
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Brand, BrandCreatorLink
from app.matcher.features import BriefQuery
from app.matcher.ranker import rank
from app.matcher.retrieve import load_all
from app.profile import builder, signals
from app.profile.settings import profile_config

MENTION = re.compile(r"@[A-Za-z0-9._]+")


@dataclass
class BrandResult:
    brand: str
    hires: int
    pool: int
    eligible: int
    matcher: float
    popularity: float
    filters_only: float
    random: float
    first_hit_rank: int | None


def organic_vectors(session: Session, embedder) -> dict[int, np.ndarray]:
    cfg = profile_config()["text"]
    inputs = builder.load_inputs(session)
    plans = {}
    for c in inputs:
        organic = [
            replace(p, caption=MENTION.sub("", p.caption) if p.caption else p.caption)
            for p in c.posts
            if not signals.is_paid(p)
        ]
        clean = replace(c, bio=MENTION.sub("", c.bio) if c.bio else c.bio, posts=organic)
        plan = builder.text_plan(clean, cfg)
        if plan.texts:
            plans[c.id] = plan
    flat = [t for p in plans.values() for t in p.texts]
    vecs = embedder.passages(flat)
    out, start = {}, 0
    for cid, plan in plans.items():
        own = vecs[start : start + len(plan.texts)]
        start += len(plan.texts)
        v = (own * np.array(plan.weights, dtype=np.float32)[:, None]).sum(axis=0)
        out[cid] = v / np.linalg.norm(v)
    return out


def recall(ranked_ids: list[int], hired: set[int], k: int) -> float:
    return len(hired & set(ranked_ids[:k])) / len(hired)


def evaluate(session: Session, embedder, k: int = 10) -> list[BrandResult]:
    hires = defaultdict(set)
    for brand_id, creator_id in session.execute(select(BrandCreatorLink.brand_id, BrandCreatorLink.creator_id)):
        hires[brand_id].add(creator_id)
    if not hires:
        return []
    brands = {b.id: b for b in session.scalars(select(Brand).where(Brand.id.in_(hires)))}

    organic = organic_vectors(session, embedder)
    pool = [replace(c, embedding=organic.get(c.creator_id)) for c in load_all(session)]
    texts = {
        bid: " ".join(t for t in (b.what_they_sell, b.product_description) if t) or b.name or b.handle
        for bid, b in brands.items()
    }
    query_vecs = dict(zip(texts, embedder.queries(list(texts.values())), strict=True))

    results = []
    for bid, hired in sorted(hires.items()):
        b = brands[bid]
        brief = BriefQuery(text=texts[bid], category=b.category, sub_niche=b.sub_niche, top_k=len(pool))
        ranked, _ = rank(brief, pool, query_vecs[bid])
        ranked_ids = [r.creator_id for r in ranked]
        eligible = set(ranked_ids)
        followers = {c.creator_id: c.followers or 0 for c in pool}
        by_size = sorted(eligible, key=lambda cid: (-followers[cid], cid))
        hit_ranks = [i for i, cid in enumerate(ranked_ids, 1) if cid in hired]
        results.append(
            BrandResult(
                brand=b.handle,
                hires=len(hired),
                pool=len(pool),
                eligible=len(eligible),
                matcher=recall(ranked_ids, hired, k),
                popularity=recall(by_size, hired, k),
                # Expected recall when picking k at random: from the filtered set, or from everyone.
                filters_only=sum(min(1.0, k / len(eligible)) for h in hired if h in eligible) / len(hired)
                if eligible
                else 0.0,
                random=min(1.0, k / len(pool)),
                first_hit_rank=hit_ranks[0] if hit_ranks else None,
            )
        )
    return results
