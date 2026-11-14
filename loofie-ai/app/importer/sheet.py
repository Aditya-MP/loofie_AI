"""Load Loofie_AI_Data_Collection.xlsx, or a folder with one CSV per tab, into the database.

Bad rows are skipped and reported with their tab and row number; good rows are still loaded.
Running the same file twice is safe: rows are matched on their key and updated, not duplicated.
"""

import csv
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from openpyxl import load_workbook
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Brand, BrandCreatorLink, Brief, Comment, Creator, ImportRun, Post
from app.importer import parse as p
from app.importer import provenance, vocab
from app.importer.upsert import upsert

TABS = ["Brands", "Creators", "Posts", "Brand_Creator_Links", "Comments", "Briefs"]
ID_COLUMNS = ("brand_id", "creator_id", "post_id", "link_id", "brief_id")
HANDLE_COLUMNS = ("instagram_handle", "creator_handle", "brand_handle")


@dataclass
class Report:
    counts: dict = field(default_factory=dict)
    errors: list = field(default_factory=list)
    warnings: list = field(default_factory=list)

    def bump(self, tab: str, what: str, n: int = 1) -> None:
        self.counts.setdefault(tab, {}).setdefault(what, 0)
        self.counts[tab][what] += n

    def error(self, tab: str, row: int | None, msg: str) -> None:
        self.errors.append({"tab": tab, "row": row, "error": msg})

    def warn(self, tab: str, row: int | None, msg: str) -> None:
        self.warnings.append({"tab": tab, "row": row, "warning": msg})


def read_tabs(path: Path) -> dict[str, list[tuple[int, dict]]]:
    """Returns {tab: [(spreadsheet row number, {header: value})]}, skipping empty rows."""
    tabs: dict[str, list[tuple[int, dict]]] = {}
    if path.is_dir():
        for tab in TABS:
            f = path / f"{tab}.csv"
            if f.exists():
                with f.open(encoding="utf-8-sig", newline="") as fh:
                    tabs[tab] = [(i, r) for i, r in enumerate(csv.DictReader(fh), 2) if any(r.values())]
        return tabs
    wb = load_workbook(path, read_only=True, data_only=True)
    try:
        for tab in TABS:
            if tab not in wb.sheetnames:
                continue
            rows = wb[tab].iter_rows(values_only=True)
            header = [str(h).strip() if h is not None else "" for h in next(rows, ())]
            tabs[tab] = [
                (i, dict(zip(header, r, strict=False)))
                for i, r in enumerate(rows, 2)
                if any(c not in (None, "") for c in r)
            ]
    finally:
        wb.close()
    return tabs


def is_example(row: dict) -> bool:
    if any(str(row.get(c) or "").upper().startswith("EX-") for c in ID_COLUMNS):
        return True
    if any(str(row.get(c) or "").lower().startswith("example_") for c in HANDLE_COLUMNS):
        return True
    return str(row.get("collected_by") or row.get("written_by") or "").strip().upper() == "EXAMPLE"


def required(value, name: str):
    if value is None:
        raise p.ParseError(f"{name} is required")
    return value


def lookup(mapping: dict, key, what: str, tab: str):
    if key not in mapping:
        raise p.ParseError(f"{what} {key!r} is not on the {tab} tab")
    return mapping[key]


# ------------------------------------------------------------------ one function per tab


def brand_row(r: dict) -> dict:
    lo, hi = p.price_range(r.get("price_range_inr"))
    return dict(
        handle=required(p.handle(r.get("instagram_handle")), "instagram_handle"),
        external_id=p.text(r.get("brand_id")),
        name=p.text(r.get("brand_name")),
        website=p.url(r.get("website")),
        category=p.choice(r.get("category"), vocab.CATEGORY),
        sub_niche=p.choice(r.get("sub_niche"), vocab.SUB_NICHE),
        what_they_sell=p.text(r.get("what_they_sell")),
        product_description=p.text(r.get("product_description")),
        price_min_inr=lo,
        price_max_inr=hi,
        ad_library_link=p.url(r.get("ad_library_link")),
        running_ads_now=p.yes_no(r.get("running_ads_now")),
        uses_creator_ads=p.yes_no(r.get("uses_creator_ads")),
        data_source="sheet",
        collected_on=p.a_date(r.get("collected_on")),
        collected_by=p.text(r.get("collected_by")),
    )


def creator_row(r: dict) -> dict:
    h = p.handle(r.get("instagram_handle")) or p.handle(r.get("profile_link"))
    return dict(
        handle=required(h, "instagram_handle"),
        external_id=p.text(r.get("creator_id")),
        display_name=p.text(r.get("display_name")),
        profile_link=p.url(r.get("profile_link")),
        city=p.text(r.get("city")),
        followers=p.integer(r.get("followers")),
        following=p.integer(r.get("following")),
        total_posts=p.integer(r.get("total_posts")),
        bio=p.text(r.get("bio")),
        bio_link=p.url(r.get("bio_link")),
        category=p.choice(r.get("category"), vocab.CATEGORY),
        sub_niche=p.choice(r.get("sub_niche"), vocab.SUB_NICHE),
        primary_language=p.choice(r.get("primary_language"), vocab.LANGUAGE),
        face_on_camera=p.yes_no(r.get("face_on_camera")),
        speaks_to_camera=p.yes_no(r.get("speaks_to_camera")),
        last_post_date=p.a_date(r.get("last_post_date")),
        posts_last_30d=p.integer(r.get("posts_last_30d")),
        public_rate_card=p.yes_no(r.get("public_rate_card")),
        rate_card_link=p.url(r.get("rate_card_link")),
        label_quality=p.choice(r.get("quality_label"), vocab.QUALITY),
        label_quality_reason=p.text(r.get("quality_reason")),
        data_source="sheet",
        collected_on=p.a_date(r.get("collected_on")),
        collected_by=p.text(r.get("collected_by")),
    )


def post_row(r: dict, creators: dict[str, int]) -> dict:
    h = required(p.handle(r.get("creator_handle")), "creator_handle")
    return dict(
        post_url=required(p.post_url(r.get("post_url")), "post_url"),
        external_id=p.text(r.get("post_id")),
        creator_id=lookup(creators, h, "creator", "Creators"),
        post_date=p.a_date(r.get("post_date")),
        post_type=p.choice(r.get("post_type"), vocab.POST_TYPE),
        views=p.integer(r.get("views")),
        likes=p.integer(r.get("likes")),
        comments_count=p.integer(r.get("comments")),
        caption=p.text(r.get("caption")),
        paid_partnership_label=p.yes_no(r.get("paid_partnership_label")),
        ad_hashtag=p.yes_no(r.get("ad_hashtag")),
        brands_tagged=p.handles(r.get("brands_tagged")),
        product_shown=p.yes_no(r.get("product_shown")),
        data_source="sheet",
        collected_on=p.a_date(r.get("collected_on")),
    )


def link_row(r: dict, brands: dict[str, int], creators: dict[str, int]) -> dict:
    b = required(p.handle(r.get("brand_handle")), "brand_handle")
    c = required(p.handle(r.get("creator_handle")), "creator_handle")
    return dict(
        external_id=p.text(r.get("link_id")),
        brand_id=lookup(brands, b, "brand", "Brands"),
        creator_id=lookup(creators, c, "creator", "Creators"),
        post_url=required(p.post_url(r.get("post_url")), "post_url"),
        evidence=p.choice(r.get("evidence"), vocab.EVIDENCE),
        post_date=p.a_date(r.get("post_date")),
        collected_on=p.a_date(r.get("collected_on")),
    )


def comment_row(r: dict, posts: dict[str, int]) -> dict:
    url = required(p.post_url(r.get("post_url")), "post_url")
    no = required(p.integer(r.get("comment_no")), "comment_no")
    if no < 1:
        raise p.ParseError(f"comment_no must be 1 or more: {no}")
    return dict(
        post_id=lookup(posts, url, "post", "Posts"),
        comment_no=no,
        text=required(p.text(r.get("comment_text")), "comment_text"),
        collected_on=p.a_date(r.get("collected_on")),
    )


def brief_row(r: dict) -> dict:
    deadline = p.a_date(r.get("ans_deadline"))
    must_say = p.text(r.get("ans_must_say"))
    return dict(
        external_id=required(p.text(r.get("brief_id")), "brief_id"),
        origin="dataset",
        brand_handle=p.handle(r.get("brand_handle")),
        source_type=p.choice(r.get("source_type"), vocab.BRIEF_SOURCE),
        source_link=p.url(r.get("source_link")),
        raw_text=required(p.text(r.get("raw_brief_text")), "raw_brief_text"),
        answer_key=dict(
            category=p.choice(r.get("ans_category"), vocab.CATEGORY),
            sub_niche=p.choice(r.get("ans_sub_niche"), vocab.SUB_NICHE),
            creators_needed=p.integer(r.get("ans_creators_needed")),
            budget_total_inr=p.integer(r.get("ans_budget_total_inr")),
            city=p.text(r.get("ans_city")),
            language=p.choice(r.get("ans_language"), vocab.LANGUAGE),
            format=p.choice(r.get("ans_format"), vocab.FORMAT),
            deadline=deadline.isoformat() if deadline else None,
            must_say=[s.strip() for s in must_say.split("|") if s.strip()] if must_say else [],
        ),
        written_by=p.text(r.get("written_by")),
    )


# ------------------------------------------------------------------ runner


def load_tab(
    session: Session,
    report: Report,
    tab: str,
    rows: list[tuple[int, dict]],
    convert: Callable[[dict], dict],
    model,
    key: list[str],
    include_examples: bool,
    prepare: Callable[[list[dict]], list[str]] | None = None,
) -> None:
    clean: dict[tuple, dict] = {}
    for rowno, raw in rows:
        if not include_examples and is_example(raw):
            report.bump(tab, "skipped_example")
            continue
        try:
            rec = convert(raw)
        except p.ParseError as e:
            report.error(tab, rowno, str(e))
            continue
        k = tuple(rec[c] for c in key)
        if k in clean:
            report.warn(tab, rowno, f"same {', '.join(key)} as an earlier row; this row replaces it")
        clean[k] = rec
    if clean:
        rows = list(clean.values())
        for note in prepare(rows) if prepare else []:
            report.warn(tab, None, note)
        upsert(session, model, rows, key)
    report.bump(tab, "loaded", len(clean))


def import_sheet(session: Session, path: Path, include_examples: bool = False) -> Report:
    report = Report()
    tabs = read_tabs(path)
    for tab in TABS:
        if tab not in tabs:
            report.warn(tab, None, "tab not found in the file")

    def load(tab, convert, model, key, prepare=None):
        load_tab(session, report, tab, tabs.get(tab, []), convert, model, key, include_examples, prepare)

    load("Brands", brand_row, Brand, ["handle"])
    load("Creators", creator_row, Creator, ["handle"], prepare=lambda rows: provenance.apply(session, rows))
    brands = dict(session.execute(select(Brand.handle, Brand.id)).all())
    creators = dict(session.execute(select(Creator.handle, Creator.id)).all())
    load("Posts", lambda r: post_row(r, creators), Post, ["post_url"])
    posts = dict(session.execute(select(Post.post_url, Post.id)).all())
    load(
        "Brand_Creator_Links",
        lambda r: link_row(r, brands, creators),
        BrandCreatorLink,
        ["brand_id", "creator_id", "post_url"],
    )
    load("Comments", lambda r: comment_row(r, posts), Comment, ["post_id", "comment_no"])
    load("Briefs", brief_row, Brief, ["external_id"])

    session.add(ImportRun(source=str(path), counts=report.counts, errors=report.errors, warnings=report.warnings))
    return report
