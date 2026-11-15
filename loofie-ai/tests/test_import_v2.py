"""The v2 data sheet layout: title rows, headers in row 4, record_status, renamed columns."""

from openpyxl import Workbook
from sqlalchemy import select

from app.db.models import Brand, BrandCreatorLink, Comment, Creator, Post
from app.importer.sheet import import_sheet
from app.matcher.retrieve import to_candidate

TITLE = ["TITLE ROW"], ["One row = one thing."], ["REAL DATA: ..."]

TABS = {
    "Brands": [
        [
            "brand_id",
            "brand_name",
            "instagram_handle",
            "category",
            "sub_niche",
            "products",
            "price_min_inr",
            "price_max_inr",
            "ad_library_search_link",
            "uses_creator_ads",
            "collected_on",
            "record_status",
        ],
        ["DM-B001", "Demo", "demo_brand_01", "beauty_skincare", "skincare", "x", 1, 2, None, "yes", None, "DEMO"],
        [
            "BR-001",
            "Dot & Key",
            "dotandkey.skincare",
            "beauty_skincare",
            "skincare",
            "Serum, Mask",
            249,
            1884,
            "https://www.facebook.com/ads/library/?q=x",
            "unsure",
            "2026-11-15",
            "TO_REVIEW",
        ],
    ],
    "Creators": [
        [
            "creator_id",
            "instagram_handle",
            "city",
            "followers",
            "bio",
            "category",
            "sub_niche",
            "primary_language",
            "face_on_camera",
            "public_rate_card",
            "quality_label",
            "quality_reason",
            "collected_on",
            "record_status",
            "notes",
        ],
        [
            "CR-001",
            "skin.by.mira",
            "Bengaluru",
            12000,
            "Oily skin | honest reviews",
            "beauty_skincare",
            "skincare",
            "English",
            "unsure",
            "unsure",
            "unsure",
            "Not assessed",
            "2026-11-15",
            "TO_REVIEW",
            "Niche set on manual review of bio; language from caption text. Followers exact.",
        ],
        [
            "CR-002",
            "kannada.glow",
            "Bengaluru",
            9000,
            "Kannada skincare",
            "beauty_skincare",
            "skincare",
            "Kannada",
            "yes",
            "no",
            "real",
            "checked comments and growth",
            "2026-11-15",
            "VERIFIED",
            "",
        ],
        [
            "CR-003",
            "food.trails",
            "Delhi",
            15000,
            "food",
            "food_beverage",
            "food",
            "Hindi",
            "unsure",
            "unsure",
            "unsure",
            "Not assessed",
            "2026-11-15",
            "TO_REVIEW",
            "",
        ],
        [
            "CR-004",
            "bad.row",
            "Delhi",
            15000,
            "x",
            "beauty_skincare",
            "makeup",
            "English",
            None,
            None,
            None,
            None,
            "2026-11-15",
            "REJECTED",
            "copied account",
        ],
        ["CR-005", None, None, None, None, None, None, None, None, None, None, None, None, "TO_REVIEW", None],
    ],
    "Posts": [
        [
            "post_id",
            "creator_id",
            "creator_handle",
            "post_url",
            "post_date",
            "post_type",
            "views",
            "likes",
            "comments_count",
            "caption_text",
            "paid_partnership_label",
            "product_shown",
            "collected_on",
            "record_status",
            "notes",
        ],
        [
            "PO-0001",
            "CR-001",
            "skin.by.mira",
            "https://www.instagram.com/reel/AAA111/",
            "2026-11-01",
            "reel",
            None,
            300,
            12,
            "my sunscreen routine",
            "yes",
            "unsure",
            "2026-11-15",
            "TO_REVIEW",
            None,
        ],
        [
            "PO-0002",
            "CR-001",
            "skin.by.mira",
            "https://www.instagram.com/reel/BBB222/",
            "2026-10-20",
            "reel",
            None,
            3,
            4,
            "night routine",
            "no",
            "unsure",
            "2026-11-15",
            "TO_REVIEW",
            "Views not visible on Reels tab at collection; Like count hidden by creator",
        ],
    ],
    "Brand_Creator_Links": [
        [
            "link_id",
            "brand_id",
            "brand_handle",
            "creator_id",
            "creator_handle",
            "post_id",
            "post_url",
            "evidence_type",
            "post_date",
            "collected_on",
            "record_status",
        ],
        [
            "LK-001",
            "BR-001",
            "dotandkey.skincare",
            "CR-001",
            "skin.by.mira",
            "PO-0001",
            "https://www.instagram.com/reel/AAA111/",
            "paid_partnership_label",
            "2026-11-01",
            "2026-11-15",
            "TO_REVIEW",
        ],
    ],
    "Comments": [
        [
            "comment_id",
            "post_id",
            "creator_id",
            "creator_handle",
            "comment_number",
            "comment_text",
            "record_status",
            "review_label",
        ],
        ["CM-001", "PO-0001", "CR-001", "skin.by.mira", 1, "does it leave a white cast?", "TO_REVIEW", "unreviewed"],
        ["CM-002", None, None, None, None, None, "TO_REVIEW", None],
    ],
    "Briefs": [
        ["brief_id", "raw_brief_text", "answer_key_status", "record_status"],
        ["CB-001", None, "NOT_REVIEWED", "TO_REVIEW"],
    ],
}


def write_v2(path):
    wb = Workbook()
    wb.remove(wb.active)
    wb.create_sheet("00_README").append(["LOOFIE AI | DATA COLLECTION WORKBOOK"])
    for name, rows in TABS.items():
        ws = wb.create_sheet(name)
        for r in [*TITLE, *rows]:
            ws.append(list(r))
    wb.save(path)
    return path


def test_v2_layout_loads(session, tmp_path):
    report = import_sheet(session, write_v2(tmp_path / "v2.xlsx"))
    session.commit()
    assert report.errors == [], report.errors
    c = report.counts
    assert c["Creators"]["loaded"] == 3 and c["Creators"]["skipped_rejected"] == 1 and c["Creators"]["empty_slot"] == 1
    assert c["Brands"]["skipped_example"] == 1
    assert c["Briefs"].get("loaded", 0) == 0 and c["Briefs"]["empty_slot"] == 1
    assert c["Comments"]["loaded"] == 1  # found by post_id, no post_url in v2

    brand = session.scalar(select(Brand))
    assert (brand.price_min_inr, brand.price_max_inr, brand.what_they_sell) == (249, 1884, "Serum, Mask")
    assert brand.uses_creator_ads is None  # "unsure" = unknown, not no
    hidden = session.scalar(select(Post).where(Post.external_id == "PO-0002"))
    assert hidden.likes is None and hidden.comments_count == 4  # hidden likes: unknown, not the placeholder 3
    post = session.scalar(select(Post).where(Post.external_id == "PO-0001"))
    assert post.comments_count == 12 and post.caption == "my sunscreen routine" and post.product_shown is None
    assert session.scalar(select(BrandCreatorLink)).evidence == "paid_partnership_label"
    assert session.scalar(select(Comment)).text == "does it leave a white cast?"


def test_v2_honesty_rules(session, tmp_path):
    import_sheet(session, write_v2(tmp_path / "v2.xlsx"))
    session.commit()
    by = {c.handle: c for c in session.scalars(select(Creator))}
    mira, kan = by["skin.by.mira"], by["kannada.glow"]
    # Caption-guessed language is not the spoken language; "Not assessed" is not a quality label.
    assert mira.primary_language is None and mira.label_quality is None
    assert kan.primary_language == "Kannada" and kan.label_quality == "real"
    # TO_REVIEW numbers are unchecked; VERIFIED rows count as checked by the team.
    assert mira.metric_sources["followers"]["source"] == "LEGACY_IMPORT"
    assert kan.metric_sources["followers"]["source"] == "OPS_CHECKED"
    # An out-of-scope hand label keeps a food creator out of beauty briefs.
    food = to_candidate(by["food.trails"], None, None)
    assert food.category == "food_beverage"
