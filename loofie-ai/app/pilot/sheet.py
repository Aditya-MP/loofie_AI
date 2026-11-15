"""Pilot log: the sheet ops fills in by hand while jobs run on WhatsApp, loaded into the events log
as if the app had sent each event (docs/OPERATIONS.md "Pilot log").

- One row per thing that happened. "What happened" and "Reason code" are dropdowns.
- Every row goes through the same checks as POST /ai/feedback (app/api/rules.py), so pilot data and
  app data look the same to the models.
- All or nothing: one bad row and nothing is loaded; every bad row is listed.
- Re-importing is a sync: rows ops fixed are replaced, rows ops deleted are removed, unchanged rows
  stay. Only events that came from the sheet (model_name = "pilot_sheet") are ever touched; this is
  the one exception to "never delete events", because the sheet, not the log, is the pilot record.
"""

import csv
import re
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

from fastapi import HTTPException
from fastapi.encoders import jsonable_encoder
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.api.rules import check_feedback
from app.api.schemas import FeedbackIn
from app.db.models import Creator, Event
from app.feedback.events import REASON_FAULT
from app.importer import parse

SOURCE = "pilot_sheet"
IST = timezone(timedelta(hours=5, minutes=30))
NAMESPACE = uuid.UUID("6f1d3b6e-5c1a-4a43-9a4f-0c7f3e1b9a10")
COLUMNS = ["Date", "Brief", "Brand", "Creator", "What happened", "Detail", "Reason code"]

# What happened -> (help text for the How-to tab, what goes in Detail)
ACTIONS: dict[str, tuple[str, str]] = {
    "Shortlisted": ("Creator was on the shortlist sent to the brand", "rank on the list (optional)"),
    "Ops added": ("Ops put a creator on the list the matcher did not suggest", ""),
    "Ops removed": ("Ops took a matcher suggestion off the list", "why (required, no personal details)"),
    "Picked": ("Brand chose this creator", ""),
    "Maybe": ("Brand said maybe", ""),
    "Rejected": ("Brand said no to this creator", "why, in a few words (required)"),
    "Product received": ("Creator got the product", ""),
    "Delivery problem": ("Parcel missing, damaged or wrong", "NOT_RECEIVED / DAMAGED / WRONG_ITEM"),
    "Revision asked": ("Brand asked for changes to the video", ""),
    "Delivered": ("Final video approved", "on time / late (required)"),
    "Dropped out": ("Creator was replaced or the job ended early", "Reason code column is required"),
    "Rated": ("Brand's star rating", "1-5 (required)"),
    "Price paid": ("The fee agreed for this creator's video", "rupees, e.g. 5000 (required)"),
    "Rebooked": ("Brand booked the same creator again (put the NEW brief in Brief)", ""),
    "Video result": ("Brand's verdict about 30 days later", "winner / average / loser"),
}
# Free text (Detail on Rejected / Ops removed) must not carry personal or payment details.
PERSONAL = re.compile(r"\d{10}|\S+@\S+|\b(pan|aadhaar|upi|ifsc|account no)\b", re.IGNORECASE)


@dataclass
class Row:
    n: int  # row number in the sheet, for error messages
    day: date | None
    brief: str
    brand: str
    creator: str
    action: str
    detail: str
    code: str


@dataclass
class Result:
    rows: int = 0
    events: int = 0
    added: int = 0
    changed: int = 0
    removed: int = 0
    unchanged: int = 0
    errors: list[str] = field(default_factory=list)
    by_action: Counter = field(default_factory=Counter)


# ------------------------------------------------------------------ reading


def _text(v) -> str:
    if v is None:
        return ""
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return str(v).strip()


def _handle(v) -> str:
    """The cleaned Instagram handle; something invalid is kept as typed, so the row error shows it."""
    s = _text(v)
    try:
        return parse.handle(s) or ""
    except parse.ParseError:
        return s


def _day(v) -> date | None:
    if isinstance(v, datetime):
        return v.date()
    if isinstance(v, date):
        return v
    s = _text(v)
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d %b %Y", "%d %B %Y"):
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            pass
    return None


def read_rows(path: Path) -> list[Row]:
    """The "Log" tab of an .xlsx, or a .csv (Google Sheets: File > Download > CSV)."""
    if path.suffix.lower() == ".csv":
        with path.open(encoding="utf-8-sig", newline="") as f:
            raw = list(csv.reader(f))
    else:
        wb = load_workbook(path, data_only=True, read_only=True)
        ws = wb["Log"] if "Log" in wb.sheetnames else wb.worksheets[0]
        raw = [list(r) for r in ws.iter_rows(values_only=True)]
    if not raw:
        return []
    header = [_text(h).lower() for h in raw[0]]
    want = [c.lower() for c in COLUMNS]
    missing = [c for c, w in zip(COLUMNS, want, strict=True) if w not in header]
    if missing:
        raise ValueError(f"the Log tab needs these columns in row 1: {missing}")
    at = {w: header.index(w) for w in want}
    rows = []
    for i, r in enumerate(raw[1:], start=2):
        cells = {w: (r[at[w]] if at[w] < len(r) else None) for w in want}
        if not any(_text(v) for v in cells.values()):
            continue
        rows.append(
            Row(
                n=i,
                day=_day(cells["date"]),
                brief=_text(cells["brief"]),
                brand=_handle(cells["brand"]),
                creator=_handle(cells["creator"]),
                action=_text(cells["what happened"]),
                detail=_text(cells["detail"]),
                code=_text(cells["reason code"]).upper(),
            )
        )
    return rows


# ------------------------------------------------------------------ rows -> events


def _job(r: Row) -> str:
    return f"pilot-{r.brief}-{r.creator}"


def _int(s: str) -> int | None:
    s = s.replace(",", "").replace("₹", "").strip()
    return int(s) if s.isdigit() else None


def _event(r: Row, event_type: str, payload: dict, subject: str = "job") -> dict:
    return {
        "event_type": event_type,
        "subject_type": subject,
        "subject_id": r.brief if subject == "brief" else _job(r),
        "payload": payload,
    }


def to_events(r: Row, refs: dict[str, str | None], revisions: int) -> tuple[list[dict], str | None]:
    """One row -> event dicts (without ids or times), or an error message."""
    if r.day is None:
        return [], "Date is missing or not a date (use 2026-11-18 or 18/11/2026)"
    if not r.brief:
        return [], "Brief is missing"
    if r.action not in ACTIONS:
        return [], f"What happened must be one of: {', '.join(ACTIONS)}"
    if not r.creator:
        return [], "Creator (Instagram handle) is missing"
    if r.creator not in refs:
        return [], f"creator @{r.creator} is not in Loofie yet: add their profile first"
    if r.code and r.code not in REASON_FAULT:
        return [], f"Reason code must be one of: {', '.join(REASON_FAULT)}"
    if PERSONAL.search(r.detail):
        return [], "Detail looks like it holds a phone, email, UPI or ID number: remove it"

    who = {"creator_handle": r.creator, **({"creator_ref": refs[r.creator]} if refs[r.creator] else {})}
    on_brief = {**who, "brief_ref": r.brief, **({"brand_handle": r.brand} if r.brand else {})}
    job = {**who, "job_id": _job(r), "brief_ref": r.brief}
    a, d = r.action, r.detail
    code = {"reasonCode": r.code, "faultParty": REASON_FAULT[r.code]} if r.code else {}

    if a == "Shortlisted":
        return [], None  # grouped per brief into one shortlist_shown (see build)
    if a in ("Picked", "Maybe", "Ops added"):
        kind = {"Picked": "shortlist_pick", "Maybe": "shortlist_maybe", "Ops added": "ops_added_creator"}[a]
        return [_event(r, kind, on_brief, "brief")], None
    if a in ("Rejected", "Ops removed"):
        if not d:
            return [], f"{a} needs a reason in Detail"
        kind = "shortlist_reject" if a == "Rejected" else "ops_removed_creator"
        return [_event(r, kind, {**on_brief, "reason": d}, "brief")], None
    if a == "Product received":
        return [_event(r, "job_state_changed", {**job, "from": "PRODUCT_SHIPPED", "to": "PRODUCT_RECEIVED"})], None
    if a == "Delivery problem":
        issue = d.upper().replace(" ", "_")
        if issue not in ("NOT_RECEIVED", "DAMAGED", "WRONG_ITEM"):
            return [], "Delivery problem needs Detail: NOT_RECEIVED, DAMAGED or WRONG_ITEM"
        payload = {**job, "from": "PRODUCT_SHIPPED", "to": "DELIVERY_ISSUE", "issue_type": issue, **code}
        return [_event(r, "job_state_changed", payload)], None
    if a == "Revision asked":
        return [_event(r, "job_state_changed", {**job, "from": "IN_REVIEW", "to": "REVISION_REQUESTED"})], None
    if a == "Delivered":
        when = d.lower().replace("-", " ").strip()
        if when not in ("on time", "late"):
            return [], "Delivered needs Detail: on time or late"
        outcome = {**job, "on_time": when == "on time", "revisions": revisions, "replaced": False, "disputed": False}
        return [_event(r, "job_outcome", outcome)], None
    if a == "Dropped out":
        if not r.code:
            return [], "Dropped out needs a Reason code (it decides whose fault it was)"
        payload = {**job, "from": "CREATOR_ACCEPTED", "to": "REPLACEMENT_NEEDED", **code}
        return [_event(r, "job_state_changed", payload)], None
    if a == "Rated":
        stars = _int(d)
        if stars is None or not 1 <= stars <= 5:
            return [], "Rated needs Detail: a whole number 1-5"
        return [_event(r, "rating", {**job, "stars": stars})], None
    if a == "Price paid":
        price = _int(d)
        if not price:
            return [], "Price paid needs Detail: rupees as a number, e.g. 5000"
        return [_event(r, "deal_price", {**job, "price_inr": price})], None
    if a == "Rebooked":
        return [_event(r, "rebooked", job, "creator")], None
    if a == "Video result":
        if d.lower() not in ("winner", "average", "loser"):
            return [], "Video result needs Detail: winner, average or loser"
        return [_event(r, "video_performance", {**job, "result": d.lower()})], None
    return [], f"unhandled action {a}"  # pragma: no cover


def _when(r: Row) -> datetime:
    # Start of the row's day (IST) plus the row number in seconds: same-day rows keep the sheet's
    # order, and a row for today filled in this morning is not "in the future".
    return datetime.combine(r.day, time(0), IST) + timedelta(seconds=r.n)


def build(session: Session, rows: list[Row]) -> tuple[dict[uuid.UUID, dict], list[str]]:
    handles = {r.creator for r in rows if r.creator}
    refs = dict(session.execute(select(Creator.handle, Creator.external_id).where(Creator.handle.in_(handles))).all())
    revisions = Counter(_job(r) for r in rows if r.action == "Revision asked")
    events: dict[uuid.UUID, dict] = {}
    errors: list[str] = []
    seen: Counter = Counter()
    shortlists: dict[str, list[tuple[int, int, Row]]] = defaultdict(list)

    for r in rows:
        made, err = to_events(r, refs, revisions[_job(r)])
        if err:
            errors.append(f"row {r.n}: {err}")
            continue
        if r.action == "Shortlisted":
            shortlists[r.brief].append((_int(r.detail) or 999, r.n, r))
            continue
        for ev in made:
            # The id is stable while the row means the same thing, so editing its Detail is a change,
            # not a new event. The count tells apart two identical rows (e.g. two revisions).
            key = (r.brief, r.creator, r.action)
            seen[key] += 1
            eid = uuid.uuid5(NAMESPACE, "|".join([*key, str(seen[key])]))
            events[eid] = {**ev, "occurred_at": _when(r)}

    for brief, items in shortlists.items():
        items.sort(key=lambda x: (x[0], x[1]))
        first = min(items, key=lambda x: x[1])[2]
        payload = {"creator_handles": [r.creator for _, _, r in items], "brief_ref": brief}
        eid = uuid.uuid5(NAMESPACE, f"{brief}|shortlist_shown")
        events[eid] = {**_event(first, "shortlist_shown", payload, "brief"), "occurred_at": _when(first)}

    for eid, ev in events.items():
        try:
            check_feedback(FeedbackIn(actor_type="ops", **ev, event_id=eid))
        except HTTPException as e:
            errors.append(f"{ev['event_type']} for {ev['subject_id']}: {e.detail}")
    return events, errors


# ------------------------------------------------------------------ sync


def _same(saved: Event, ev: dict) -> bool:
    return (
        saved.event_type == ev["event_type"]
        and saved.subject_type == ev["subject_type"]
        and saved.subject_id == ev["subject_id"]
        and saved.payload == jsonable_encoder(ev["payload"])
        and saved.occurred_at == ev["occurred_at"]
    )


def import_sheet(session: Session, path: Path, dry_run: bool = False) -> Result:
    rows = read_rows(path)
    res = Result(rows=len(rows), by_action=Counter(r.action for r in rows))
    events, res.errors = build(session, rows)
    res.events = len(events)
    if res.errors:
        return res  # all or nothing

    saved = {e.id: e for e in session.scalars(select(Event).where(Event.model_name == SOURCE))}
    stale = [eid for eid, e in saved.items() if eid not in events or not _same(e, events[eid])]
    res.removed = sum(eid not in events for eid in stale)
    res.changed = len(stale) - res.removed
    res.unchanged = len(saved) - len(stale)
    new = {eid: ev for eid, ev in events.items() if eid not in saved or eid in stale}
    res.added = len(new) - res.changed
    if dry_run:
        return res
    if stale:
        session.execute(delete(Event).where(Event.id.in_(stale)))
    for eid, ev in new.items():
        session.add(
            Event(
                id=eid,
                actor_type="ops",
                actor_id="pilot-sheet",
                model_name=SOURCE,
                **{**ev, "payload": jsonable_encoder(ev["payload"])},
            )
        )
    session.commit()
    return res


# ------------------------------------------------------------------ the blank template


def write_template(path: Path) -> Path:
    wb = Workbook()
    font, bold = Font(name="Arial", size=10), Font(name="Arial", size=10, bold=True, color="FFFFFF")
    head_fill = PatternFill("solid", fgColor="1F4E78")

    log = wb.active
    log.title = "Log"
    widths = [12, 12, 18, 20, 18, 34, 22]
    for i, (name, w) in enumerate(zip(COLUMNS, widths, strict=True), start=1):
        c = log.cell(row=1, column=i, value=name)
        c.font, c.fill = bold, head_fill
        log.column_dimensions[c.column_letter].width = w
    log.freeze_panes = "A2"
    for row in log.iter_rows(min_row=2, max_row=2000, max_col=len(COLUMNS)):
        for c in row:
            c.font = font
        row[0].number_format = "dd mmm yyyy"

    lists = wb.create_sheet("Lists")
    lists["A1"], lists["B1"] = "What happened", "Reason code"
    for i, a in enumerate(ACTIONS, start=2):
        lists.cell(row=i, column=1, value=a)
    for i, code in enumerate(REASON_FAULT, start=2):
        lists.cell(row=i, column=2, value=code)
        lists.cell(row=i, column=3, value=f"fault: {REASON_FAULT[code]}")
    lists.sheet_state = "hidden"

    what = DataValidation(type="list", formula1=f"=Lists!$A$2:$A${len(ACTIONS) + 1}", allow_blank=True)
    why = DataValidation(type="list", formula1=f"=Lists!$B$2:$B${len(REASON_FAULT) + 1}", allow_blank=True)
    when = DataValidation(type="date", operator="greaterThan", formula1="DATE(2026,1,1)", allow_blank=True)
    for dv, col in ((what, "E"), (why, "G"), (when, "A")):
        dv.error, dv.showErrorMessage = "Pick a value from the list", True
        log.add_data_validation(dv)
        dv.add(f"{col}2:{col}2000")

    how = wb.create_sheet("How to fill", 0)
    lines = [
        ("Loofie pilot log", True),
        ("One row for each thing that happens on a pilot job. Fill the Log tab only.", False),
        ("Takes about 10 seconds a row. Fill it the same day.", False),
        ("", False),
        ("Columns", True),
        ("Date: the day it happened.", False),
        ("Brief: one short id per brief, the same on every row of that brief, e.g. br-001.", False),
        ("Brand: the brand's Instagram handle.  Creator: the creator's Instagram handle.", False),
        ("What happened + Detail: pick from the list; Detail as below.", False),
        ("Reason code: only for Dropped out and Delivery problem. It decides whose fault it was,", False),
        ("so brand or courier problems never count against the creator.", False),
        ("Never type phone numbers, emails, UPI ids, bank or ID numbers anywhere.", False),
        ("To fix a mistake: just edit the row. Re-importing replaces it.", False),
        ("Revisions are counted for you: add one 'Revision asked' row each time.", False),
        ("", False),
        ("What happened / Detail", True),
        *((f"{a}: {h}." + (f"  Detail: {d}." if d else ""), False) for a, (h, d) in ACTIONS.items()),
        ("", False),
        ("Example (one job, from shortlist to rebook)", True),
    ]
    for i, (text, is_head) in enumerate(lines, start=1):
        c = how.cell(row=i, column=1, value=text)
        c.font = Font(name="Arial", size=12 if i == 1 else 10, bold=is_head)
    example = [
        ("18 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Shortlisted", "1", ""),
        ("18 Nov 2026", "br-001", "dewdrop.skin", "kavya.skincare", "Shortlisted", "2", ""),
        ("19 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Picked", "", ""),
        ("19 Nov 2026", "br-001", "dewdrop.skin", "kavya.skincare", "Rejected", "too much makeup content", ""),
        ("22 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Product received", "", ""),
        ("26 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Revision asked", "", ""),
        ("27 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Delivered", "on time", ""),
        ("28 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Rated", "5", ""),
        ("28 Nov 2026", "br-001", "dewdrop.skin", "ananya.glows", "Price paid", "5000", ""),
        ("30 Nov 2026", "br-002", "glowco", "riya.routines", "Dropped out", "", "CREATOR_WITHDREW"),
    ]
    top = len(lines) + 1
    for j, name in enumerate(COLUMNS, start=1):
        c = how.cell(row=top, column=j, value=name)
        c.font, c.fill = bold, head_fill
    for i, values in enumerate(example, start=top + 1):
        for j, v in enumerate(values, start=1):
            how.cell(row=i, column=j, value=v).font = font
    how.column_dimensions["A"].width = 14
    for col, w in zip("BCDEFG", widths[1:], strict=True):
        how.column_dimensions[col].width = w
    for row in how.iter_rows(max_row=top - 1, max_col=1):
        row[0].alignment = Alignment(wrap_text=False)

    wb.active = wb.sheetnames.index("Log")
    path.parent.mkdir(parents=True, exist_ok=True)
    wb.save(path)
    return path
