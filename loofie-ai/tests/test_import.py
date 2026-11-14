from pathlib import Path

import pytest
from sqlalchemy import func, select

from app.config import ROOT
from app.db.models import Brand, BrandCreatorLink, Brief, Comment, Creator, Post
from app.importer.legacy import import_legacy
from app.importer.sheet import import_sheet

DATA = ROOT.parent / "data"
SHEET = DATA / "Loofie_AI_Data_Collection.xlsx"
LEGACY = DATA / "Creator_Profiles_v2.csv"


def count(session, model) -> int:
    return session.scalar(select(func.count()).select_from(model))


@pytest.mark.skipif(not SHEET.exists(), reason="data sheet not in project folder")
def test_examples_are_skipped_by_default(session):
    report = import_sheet(session, SHEET)
    session.commit()
    assert count(session, Creator) == 0
    assert report.counts["Creators"]["skipped_example"] == 2


@pytest.mark.skipif(not SHEET.exists(), reason="data sheet not in project folder")
def test_example_rows_load_and_link(session):
    report = import_sheet(session, SHEET, include_examples=True)
    session.commit()
    assert count(session, Brand) == 2
    assert count(session, Creator) == 2
    assert count(session, Post) == 2
    assert count(session, Comment) == 2
    assert count(session, Brief) == 2
    # EX-L02 points at a creator who is not on the Creators tab: reported, not loaded.
    assert count(session, BrandCreatorLink) == 1
    assert any("example_curlsbykavya" in e["error"] for e in report.errors)

    brief = session.scalar(select(Brief).where(Brief.external_id == "EX-BR01"))
    assert brief.answer_key["budget_total_inr"] == 15000
    assert brief.answer_key["must_say"] == ["no white cast", "made for oily skin"]

    post = session.scalar(select(Post).where(Post.external_id == "EX-P01"))
    assert post.brands_tagged == ["example_dewdropskin"]


@pytest.mark.skipif(not SHEET.exists(), reason="data sheet not in project folder")
def test_import_twice_does_not_duplicate(session):
    import_sheet(session, SHEET, include_examples=True)
    session.commit()
    import_sheet(session, SHEET, include_examples=True)
    session.commit()
    assert count(session, Creator) == 2
    assert count(session, Post) == 2


def test_csv_folder_and_bad_rows(session, tmp_path: Path):
    (tmp_path / "Creators.csv").write_text(
        "creator_id,instagram_handle,followers,quality_label\n"
        "C-1,@Real_One,12.4K,real\n"
        "C-2,https://www.instagram.com/reel/XYZ/,500,real\n"
        "C-3,third_one,lots,real\n",
        encoding="utf-8",
    )
    report = import_sheet(session, tmp_path)
    session.commit()
    creator = session.scalar(select(Creator))
    assert count(session, Creator) == 1
    assert (creator.handle, creator.followers, creator.label_quality) == ("real_one", 12400, "real")
    assert sorted(e["row"] for e in report.errors) == [3, 4]


def test_blank_cell_does_not_erase_stored_value(session, tmp_path: Path):
    f = tmp_path / "Creators.csv"
    f.write_text("instagram_handle,city\nsomeone,Bengaluru\n", encoding="utf-8")
    import_sheet(session, tmp_path)
    session.commit()
    f.write_text("instagram_handle,city,followers\nsomeone,,9000\n", encoding="utf-8")
    import_sheet(session, tmp_path)
    session.commit()
    c = session.scalar(select(Creator))
    assert (c.city, c.followers) == ("Bengaluru", 9000)


@pytest.mark.skipif(not LEGACY.exists(), reason="legacy CSV not in project folder")
def test_legacy_never_overwrites_sheet(session, tmp_path: Path):
    report = import_legacy(session, LEGACY)
    session.commit()
    loaded = report.counts["legacy_creators"]["loaded"]
    assert loaded >= 90
    first = session.scalar(select(Creator).order_by(Creator.id))

    (tmp_path / "Creators.csv").write_text(f"instagram_handle,followers\n{first.handle},777\n", encoding="utf-8")
    import_sheet(session, tmp_path)
    session.commit()
    import_legacy(session, LEGACY)
    session.commit()
    session.refresh(first)
    assert first.followers == 777
    assert count(session, Creator) == loaded
