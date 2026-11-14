"""Where each creator number came from, and when (Foundation v2, F1).

Every metric carries {"source": ..., "captured_on": ...}. For NUMBERS (which can be inflated) a new
value replaces a stored one only if it is at least as trustworthy, or the stored one is stale: a
verified 12,400 followers is not overwritten by a self-reported "50k" typed the next day, but a
6-month-old verified number can be. DESCRIPTIONS (city, bio, language...) always take the latest
value, since the creator knows those best; their source is still recorded.
"""

from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Creator

# Foundation v2 names, highest trust first. LEGACY_IMPORT: the first field export, partly from
# third-party sites, so it ranks below anything a creator or the team states now.
TRUST = {"API_VERIFIED": 3, "OPS_CHECKED": 2, "SELF_REPORTED": 1, "LEGACY_IMPORT": 0}
SOURCE_OF = {
    "instagram_api": "API_VERIFIED",
    "sheet": "OPS_CHECKED",  # the data team read it off the public profile
    "manual_profile": "SELF_REPORTED",
    "legacy_field_csv": "LEGACY_IMPORT",
}
METRICS = (
    "followers",
    "following",
    "total_posts",
    "bio",
    "bio_link",
    "city",
    "display_name",
    "primary_language",
    "avg_views_reported",
    "last_post_date",
    "posts_last_30d",
    "face_on_camera",
    "speaks_to_camera",
    "public_rate_card",
)
# Numbers a creator could inflate: only an equal or more trusted source may replace them.
PROTECTED = {"followers", "following", "total_posts", "avg_views_reported", "last_post_date", "posts_last_30d"}
STALE_AFTER = timedelta(days=30)


def _accept(new_source: str, new_date: date | None, old: dict | None) -> bool:
    if old is None:
        return True
    if TRUST[new_source] >= TRUST.get(old.get("source"), -1):
        return True
    old_date = date.fromisoformat(old["captured_on"]) if old.get("captured_on") else None
    if old_date is None:
        return True  # undated: can't defend it
    return new_date is not None and new_date - old_date > STALE_AFTER


def apply(session: Session, rows: list[dict], today: date | None = None) -> list[str]:
    """Decide, metric by metric, whether each new value may replace the stored one.

    Edits rows in place: a rejected value becomes None (so the upsert keeps what is stored) and
    every row gets the merged `metric_sources`. Returns a note for each rejected value."""
    today = today or date.today()
    handles = [r["handle"] for r in rows]
    stored = (
        {c.handle: c for c in session.scalars(select(Creator).where(Creator.handle.in_(handles)))} if handles else {}
    )
    notes = []
    for r in rows:
        source = SOURCE_OF[r["data_source"]]
        captured = r.get("collected_on") or (None if source == "LEGACY_IMPORT" else today)
        old = stored.get(r["handle"])
        sources = dict(old.metric_sources or {}) if old else {}
        for m in METRICS:
            if r.get(m) is None:
                continue
            stored_value = getattr(old, m) if old else None
            previous = sources.get(m) if stored_value is not None else None
            if m not in PROTECTED or _accept(source, captured, previous):
                sources[m] = {"source": source, "captured_on": captured.isoformat() if captured else None}
            else:
                if r[m] != stored_value:
                    notes.append(
                        f"@{r['handle']} {m}: kept {stored_value!r} ({previous['source']}) over {r[m]!r} ({source})"
                    )
                r[m] = None
        r["metric_sources"] = sources
    return notes


def is_verified(metric_sources: dict | None, metric: str = "followers") -> bool:
    entry = (metric_sources or {}).get(metric)
    return bool(entry) and entry.get("source") in {"API_VERIFIED", "OPS_CHECKED"}
