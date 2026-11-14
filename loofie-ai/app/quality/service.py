from collections import defaultdict
from dataclasses import asdict
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Comment, Creator, CreatorProfile, CreatorQuality, Post
from app.importer import provenance
from app.quality.rules import QualityInput, assess, quality_config


def load_inputs(session: Session, handles: list[str] | None = None) -> list[tuple[int, QualityInput]]:
    q = select(Creator, CreatorProfile).outerjoin(CreatorProfile, CreatorProfile.creator_id == Creator.id)
    if handles:
        q = q.where(Creator.handle.in_(handles))
    pairs = session.execute(q.order_by(Creator.id)).all()
    ids = [c.id for c, _ in pairs]
    comments = defaultdict(list)
    if ids:
        rows = session.execute(
            select(Post.creator_id, Comment.text).join(Post, Post.id == Comment.post_id).where(Post.creator_id.in_(ids))
        )
        for creator_id, text in rows:
            comments[creator_id].append(text)
    return [
        (
            c.id,
            QualityInput(
                handle=c.handle,
                followers=c.followers,
                following=c.following,
                stats=(p.stats if p else {}) or {},
                avg_views_reported=c.avg_views_reported,
                posts_last_30d_reported=c.posts_last_30d,
                last_post_date_reported=c.last_post_date,
                comments=comments[c.id],
                followers_verified=provenance.is_verified(c.metric_sources),
            ),
        )
        for c, p in pairs
    ]


def assess_and_save(session: Session, handles: list[str] | None = None, as_of: date | None = None) -> list[dict]:
    version = quality_config()["version"]
    rows = []
    for creator_id, qi in load_inputs(session, handles):
        r = assess(qi, as_of=as_of)
        rows.append(
            dict(
                creator_id=creator_id,
                version=version,
                label=r.label,
                activity=r.activity,
                score=r.score,
                summary=r.summary,
                findings=[asdict(f) for f in r.findings],
                signals_used=r.signals_used,
            )
        )
    if rows:
        table = CreatorQuality.__table__
        session.execute(table.delete().where(table.c.creator_id.in_([r["creator_id"] for r in rows])))
        session.execute(table.insert(), rows)
    return rows
