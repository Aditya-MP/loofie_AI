"""Instagram API response → the same creator and post rows the sheet importer produces.

Pure functions (no database, no network), so every mapping rule is tested directly.
"""

from datetime import UTC, date, datetime, timedelta

from app.importer import parse as p

SOURCE = "instagram_api"  # provenance maps this to API_VERIFIED


def post_type(m: dict) -> str | None:
    product, kind = m.get("media_product_type"), m.get("media_type")
    if product in {"STORY", "AD"}:
        return None  # stories vanish in 24h and ads aren't the creator's own posts
    if product == "REELS" or kind == "VIDEO":
        return "reel"
    if kind == "CAROUSEL_ALBUM":
        return "carousel"
    if kind == "IMAGE":
        return "photo"
    return None


def posted_on(m: dict) -> date | None:
    ts = m.get("timestamp")
    if not ts:
        return None
    # Meta sends "2026-11-10T14:03:22+0000"
    return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S%z").astimezone(UTC).date()


def to_rows(me: dict, media: list[dict], account_ref: str, today: date, cfg: dict) -> tuple[dict, list[dict]]:
    """Returns (creator_row, post_rows). Post rows have no creator_id yet: the caller adds it."""
    handle = p.handle(me["username"])
    posts = []
    for m in media:
        kind = post_type(m)
        url = m.get("permalink")
        if kind is None or not url:
            continue
        insights = m.get("insights") or {}
        posts.append(
            dict(
                post_url=p.post_url(url),
                external_id=str(m["id"]),
                post_date=posted_on(m),
                post_type=kind,
                views=insights.get("views"),
                likes=m.get("like_count"),
                comments_count=m.get("comments_count"),
                caption=(m.get("caption") or "").strip() or None,
                data_source=SOURCE,
                collected_on=today,
            )
        )

    dated = [x["post_date"] for x in posts if x["post_date"]]
    reels = sorted(
        (x for x in posts if x["post_type"] == "reel" and x["views"] is not None and x["post_date"]),
        key=lambda x: x["post_date"],
        reverse=True,
    )[: cfg["avg_views_last_n_reels"]]
    creator = dict(
        handle=handle,
        external_id=account_ref,
        display_name=(me.get("name") or "").strip() or None,
        profile_link=f"https://www.instagram.com/{handle}/",
        followers=me.get("followers_count"),
        following=me.get("follows_count"),
        total_posts=me.get("media_count"),
        # Only claim these when we actually saw posts: no posts fetched ≠ "never posts".
        last_post_date=max(dated) if dated else None,
        posts_last_30d=sum(d >= today - timedelta(days=30) for d in dated) if dated else None,
        avg_views_reported=round(sum(x["views"] for x in reels) / len(reels)) if reels else None,
        data_source=SOURCE,
        collected_on=today,
    )
    return creator, posts


def snapshot(me: dict) -> dict:
    return dict(
        followers=me.get("followers_count"), following=me.get("follows_count"), media_count=me.get("media_count")
    )
