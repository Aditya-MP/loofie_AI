"""Load the first field database (Creator_Profiles_v2.csv, 101 creators).

It is a weaker source than the AI data sheet, so it only fills columns that are still empty and
never overwrites what the sheet or the Instagram API stored.
"""

import csv
from pathlib import Path

from sqlalchemy.orm import Session

from app.db.models import Creator, ImportRun
from app.importer import parse as p
from app.importer import provenance
from app.importer.sheet import Report
from app.importer.upsert import upsert

TAB = "legacy_creators"


def legacy_row(r: dict) -> dict:
    h = p.handle(r.get("instagram_handle"))
    if h is None:
        raise p.ParseError("instagram_handle is required")
    notes = "; ".join(n for n in (p.text(r.get("views_flag")), p.text(r.get("notes"))) if n)
    return dict(
        handle=h,
        external_id=p.text(r.get("creator_id")),
        display_name=p.text(r.get("display_name")),
        profile_link=f"https://www.instagram.com/{h}/",
        city=p.text(r.get("city")),
        followers=p.integer(r.get("followers")),
        category=p.text(r.get("category")),
        category_raw=p.text(r.get("category_raw")),
        primary_language=p.text(r.get("primary_language")),
        avg_views_reported=p.integer(r.get("avg_views_reported")),
        notes=notes or None,
        data_source="legacy_field_csv",
    )


def import_legacy(session: Session, path: Path) -> Report:
    report = Report()
    clean: dict[str, dict] = {}
    with path.open(encoding="utf-8-sig", newline="") as fh:
        for rowno, raw in enumerate(csv.DictReader(fh), 2):
            try:
                rec = legacy_row(raw)
            except p.ParseError as e:
                report.error(TAB, rowno, str(e))
                continue
            if rec["handle"] in clean:
                report.warn(TAB, rowno, f"duplicate handle {rec['handle']!r}; this row replaces the earlier one")
            clean[rec["handle"]] = rec
    if clean:
        rows = list(clean.values())
        for note in provenance.apply(session, rows):
            report.warn(TAB, None, note)
        upsert(session, Creator, rows, ["handle"], prefer="existing", overwrite=("metric_sources",))
    report.bump(TAB, "loaded", len(clean))
    session.add(ImportRun(source=str(path), counts=report.counts, errors=report.errors, warnings=report.warnings))
    return report
