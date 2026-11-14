"""End to end: data-team CSVs → importer → database → Profile Builder → Model 3."""

from sqlalchemy import select

from app.db.models import Creator, CreatorProfile, CreatorQuality
from app.importer.sheet import import_sheet
from app.profile.builder import build_and_save
from app.quality.service import assess_and_save
from tests.helpers import TODAY, FakeEmbedder, write_fixture


def test_sheet_to_quality_label(session, tmp_path):
    write_fixture(tmp_path)
    report = import_sheet(session, tmp_path)
    assert report.errors == []
    build_and_save(session, FakeEmbedder(), as_of=TODAY)
    assess_and_save(session, as_of=TODAY)
    session.commit()

    rows = session.execute(
        select(Creator.handle, CreatorProfile, CreatorQuality)
        .join(CreatorProfile, CreatorProfile.creator_id == Creator.id)
        .join(CreatorQuality, CreatorQuality.creator_id == Creator.id)
    ).all()
    by = {h: (p, q) for h, p, q in rows}
    assert len(by) == 3

    mira_p, mira_q = by["honest_mira"]
    assert mira_q.label == "real", mira_q.findings
    assert mira_p.stats["median_views"] == 3675 and mira_p.stats["paid_posts"] == 1
    assert mira_p.brands_mentioned == ["dewdrop.skin"]
    assert mira_p.missing == []

    _, glow_q = by["fast_glow"]
    codes = {f["code"] for f in glow_q.findings}
    assert glow_q.label == "suspicious"
    assert {"low_views", "likes_without_comments", "generic_comments"} <= codes
    assert "41,000 followers" in glow_q.summary

    assert mira_q.activity == "active"
    assert glow_q.activity == "active"

    # A genuine creator on a break: not suspicious, but not bookable right now
    _, sam_q = by["sleepy_sam"]
    assert sam_q.label == "real" and sam_q.activity == "inactive"
    assert sam_q.findings[0]["code"] == "inactive" and sam_q.findings[0]["kind"] == "activity"

    # The human label is untouched by the model
    assert session.scalar(select(Creator.label_quality).where(Creator.handle == "sleepy_sam")) == "unsure"
