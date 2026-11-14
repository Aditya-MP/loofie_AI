"""Retrieval runs the hard filters in SQL. These tests check SQL and Python agree exactly."""

import numpy as np
import pytest
from sqlalchemy import select

from app.db.models import Brand, Creator
from app.importer.sheet import import_sheet
from app.matcher.features import BriefQuery, matcher_config
from app.matcher.ranker import rank
from app.matcher.retrieve import load_all, retrieve
from app.matcher.service import competitors_of, match
from app.profile.builder import build_and_save
from app.quality.service import assess_and_save
from tests.helpers import TODAY, FakeEmbedder, write_fixture

CFG = matcher_config()

EXTRA = """instagram_handle,followers,following,bio,category,sub_niche
food_fara,9000,400,Street food and biryani crawls,,
no_numbers,,,,beauty_skincare,makeup
big_one,400000,900,Skincare reviews for oily skin,beauty_skincare,skincare
honest_mira,,,,beauty_skincare,skincare
"""


@pytest.fixture
def seeded(session, tmp_path):
    write_fixture(tmp_path)  # honest_mira (real), fast_glow (suspicious), sleepy_sam (inactive)
    import_sheet(session, tmp_path)
    extra = tmp_path / "extra"
    extra.mkdir()
    (extra / "Creators.csv").write_text(EXTRA, encoding="utf-8")
    assert import_sheet(session, extra).errors == []
    e = FakeEmbedder()
    build_and_save(session, e, as_of=TODAY)
    assess_and_save(session, as_of=TODAY)
    session.commit()
    return session, e


BRIEFS = [
    BriefQuery(text="sunscreen for oily skin", category="beauty_skincare"),
    BriefQuery(text="sunscreen", category="beauty_skincare", min_followers=5000, max_followers=50000),
    BriefQuery(text="anything"),
    BriefQuery(text="body wash", category="personal_care", max_followers=20000),
]


@pytest.mark.parametrize("brief", BRIEFS, ids=range(len(BRIEFS)))
def test_sql_filters_match_python_filters(seeded, brief):
    session, e = seeded
    q = e.queries([brief.text])[0]
    sql_pool, sql_out = retrieve(session, brief, q)
    py_ranked, py_out = rank(brief, load_all(session), q, CFG, keep_all=True)
    assert {c.creator_id for c in sql_pool} == {r.creator_id for r in py_ranked}
    assert sql_out == py_out


def test_nearest_first_and_limit(seeded):
    session, e = seeded
    brief = BriefQuery(text="Street food and biryani crawls")
    q = e.passages(["Street food and biryani crawls"])[0]  # same text as food_fara's bio → closest
    pool, _ = retrieve(session, brief, q, limit=2)
    assert len(pool) == 2 and pool[0].handle == "food_fara"


def test_creators_without_text_come_last_not_never(seeded):
    session, e = seeded
    pool, _ = retrieve(session, BriefQuery(text="makeup"), e.queries(["makeup"])[0])
    assert pool[-1].handle == "no_numbers" and pool[-1].embedding is None


def test_excluded_ids_are_not_counted_as_left_out(seeded):
    session, e = seeded
    mira = session.scalar(select(Creator.id).where(Creator.handle == "honest_mira"))
    brief = BriefQuery(text="x", category="beauty_skincare", exclude_creator_ids=[mira])
    pool, out = retrieve(session, brief, e.queries(["x"])[0])
    assert mira not in {c.creator_id for c in pool} and "already on this brief" not in out


def test_competitors_and_full_pipeline(seeded):
    session, e = seeded
    session.add_all(
        [
            Brand(handle="dewdrop.skin", sub_niche="skincare", data_source="test"),
            Brand(handle="rival.skin", sub_niche="skincare", data_source="test"),
            Brand(handle="kesh_roots", sub_niche="haircare", data_source="test"),
        ]
    )
    session.commit()
    assert competitors_of(session, "dewdrop.skin") == ["rival.skin"]
    assert competitors_of(session, "unknown_brand") == []

    brief = BriefQuery(text="sunscreen for oily skin", category="beauty_skincare", brand_handle="rival.skin")
    results, left_out = match(session, brief, e)
    by = {r.handle: r for r in results}
    # honest_mira did a paid post for dewdrop.skin, a competitor of rival.skin → listed last
    assert results[-1].handle == "honest_mira" and by["honest_mira"].rerank_note.startswith("conflict")
    assert left_out["flagged suspicious"] == 1 and left_out["inactive"] == 1
    assert all(isinstance(r.rank_before_rerank, int) for r in results)


def test_query_vector_type(seeded):
    session, _ = seeded
    pool, _ = retrieve(session, BriefQuery(text="x"), np.ones(384, dtype=np.float32) / np.sqrt(384))
    assert pool
