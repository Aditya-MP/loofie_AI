"""F1: every creator metric records its source and date, and a weaker source can't overwrite a stronger one."""

from datetime import date, timedelta

from sqlalchemy import select

from app.db.models import Creator
from app.importer import provenance
from app.importer.upsert import upsert
from app.quality.rules import QualityInput, assess

TODAY = date(2026, 11, 14)


def save(session, source, when=TODAY, **values):
    row = {"handle": "mira", "data_source": source, "collected_on": when, **values}
    notes = provenance.apply(session, [row], today=when)
    upsert(session, Creator, [row], ["handle"])
    session.commit()
    return notes, session.scalar(select(Creator).where(Creator.handle == "mira"))


def test_each_metric_records_source_and_date(session):
    _, c = save(session, "instagram_api", followers=12400, bio="skincare")
    assert c.metric_sources["followers"] == {"source": "API_VERIFIED", "captured_on": "2026-11-14"}
    assert c.metric_sources["bio"]["source"] == "API_VERIFIED"
    assert "city" not in c.metric_sources  # never sent, never claimed


def test_self_reported_cannot_overwrite_verified(session):
    save(session, "instagram_api", followers=12400)
    notes, c = save(session, "manual_profile", when=TODAY + timedelta(days=1), followers=50000, city="Bengaluru")
    assert c.followers == 12400 and c.metric_sources["followers"]["source"] == "API_VERIFIED"
    assert c.city == "Bengaluru" and c.metric_sources["city"]["source"] == "SELF_REPORTED"  # nothing better stored
    assert notes == ["@mira followers: kept 12400 (API_VERIFIED) over 50000 (SELF_REPORTED)"]


def test_stale_verified_value_can_be_replaced(session):
    save(session, "instagram_api", followers=12400)
    _, c = save(session, "manual_profile", when=TODAY + timedelta(days=45), followers=15000)
    assert c.followers == 15000 and c.metric_sources["followers"]["source"] == "SELF_REPORTED"


def test_equal_or_stronger_source_always_updates(session):
    save(session, "manual_profile", followers=9000)
    _, c = save(session, "sheet", followers=9100)
    assert (c.followers, c.metric_sources["followers"]["source"]) == (9100, "OPS_CHECKED")
    _, c = save(session, "instagram_api", followers=9200)
    assert (c.followers, c.metric_sources["followers"]["source"]) == (9200, "API_VERIFIED")


def test_legacy_import_only_fills_gaps(session, tmp_path):
    save(session, "sheet", followers=9100)
    row = {"handle": "mira", "data_source": "legacy_field_csv", "followers": 1, "avg_views_reported": 4000}
    notes = provenance.apply(session, [row], today=TODAY)
    upsert(session, Creator, [row], ["handle"], prefer="existing", overwrite=("metric_sources",))
    session.commit()
    c = session.scalar(select(Creator).where(Creator.handle == "mira"))
    assert c.followers == 9100 and c.avg_views_reported == 4000
    assert c.metric_sources["avg_views_reported"] == {"source": "LEGACY_IMPORT", "captured_on": None}
    assert len(notes) == 1


def test_is_verified():
    assert provenance.is_verified({"followers": {"source": "API_VERIFIED"}})
    assert provenance.is_verified({"followers": {"source": "OPS_CHECKED"}})
    assert not provenance.is_verified({"followers": {"source": "SELF_REPORTED"}})
    assert not provenance.is_verified({})


def test_self_reported_numbers_are_never_called_real():
    stats = dict(
        median_views=4000,
        median_likes=400,
        median_comments=30,
        engagement_rate=0.036,
        posts_last_30d=8,
        days_since_last_post=3,
    )
    q = QualityInput(handle="x", followers=12000, following=600, stats=stats, followers_verified=False)
    r = assess(q, as_of=TODAY)
    assert r.label == "unsure" and "self-reported" in r.summary
    q.followers_verified = True
    assert assess(q, as_of=TODAY).label == "real"
