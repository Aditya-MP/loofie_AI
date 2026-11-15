"""Pilot log: ops' hand-filled sheet becomes the same events the app will send later."""

import csv

import pytest
from openpyxl import load_workbook
from sqlalchemy import select

from app.db.models import Creator, Event
from app.matcher.features import matcher_config
from app.matcher.track_record import load
from app.pilot.sheet import COLUMNS, SOURCE, import_sheet, write_template

JOB = [
    ("18 Oct 2026", "br-001", "dewdrop.skin", "@ananya.glows", "Shortlisted", "1", ""),
    ("18 Oct 2026", "br-001", "dewdrop.skin", "kavya.skincare", "Shortlisted", "2", ""),
    ("19 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Picked", "", ""),
    ("19 Oct 2026", "br-001", "dewdrop.skin", "kavya.skincare", "Rejected", "too much makeup content", ""),
    ("22 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Product received", "", ""),
    ("26 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Revision asked", "", ""),
    ("26 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Revision asked", "", ""),
    ("27 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Delivered", "on time", ""),
    ("28 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Rated", "5", ""),
    ("28 Oct 2026", "br-001", "dewdrop.skin", "ananya.glows", "Price paid", "5,000", ""),
    ("30 Oct 2026", "br-002", "glowco", "kavya.skincare", "Dropped out", "", "CREATOR_WITHDREW"),
]


@pytest.fixture
def creators(session):
    for h, ref in (("ananya.glows", "cr-ananya"), ("kavya.skincare", "cr-kavya")):
        session.add(Creator(handle=h, external_id=ref, data_source="test"))
    session.commit()
    return session


def write_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(COLUMNS)
        w.writerows(rows)
    return path


def pilot_events(session):
    return session.scalars(select(Event).where(Event.model_name == SOURCE)).all()


def test_a_whole_job_loads_as_app_events(creators, tmp_path):
    r = import_sheet(creators, write_csv(tmp_path / "log.csv", JOB))
    assert r.errors == [] and r.added == r.events
    by_type = {}
    for e in pilot_events(creators):
        by_type.setdefault(e.event_type, []).append(e.payload)
    assert by_type["shortlist_shown"] == [
        {"creator_handles": ["ananya.glows", "kavya.skincare"], "brief_ref": "br-001"}
    ]
    outcome = by_type["job_outcome"][0]
    assert outcome["on_time"] is True and outcome["revisions"] == 2  # counted from the rows
    assert outcome["creator_ref"] == "cr-ananya" and outcome["job_id"] == "pilot-br-001-ananya.glows"
    assert by_type["deal_price"][0]["price_inr"] == 5000
    assert by_type["job_state_changed"][-1]["faultParty"] == "CREATOR"  # derived from the code

    # The track record reads pilot events exactly like app events.
    recs = load(creators, [(1, "cr-ananya", "ananya.glows"), (2, "cr-kavya", "kavya.skincare")], matcher_config())
    assert recs[1].jobs == 1 and recs[1].stars == [5] and recs[1].score > 0.5
    assert recs[2].creator_faults == 1 and recs[2].score < 0.5


def test_reimport_is_a_sync(creators, tmp_path):
    path = write_csv(tmp_path / "log.csv", JOB)
    first = import_sheet(creators, path)
    again = import_sheet(creators, path)
    assert again.added == again.changed == again.removed == 0 and again.unchanged == first.events

    fixed = [row if row[4] != "Rated" else (*row[:5], "4", "") for row in JOB[:-1]]  # edit a rating, drop a row
    r = import_sheet(creators, write_csv(path, fixed))
    assert (r.changed, r.removed) == (1, 1)
    stars = [e.payload["stars"] for e in pilot_events(creators) if e.event_type == "rating"]
    assert stars == [4]
    assert not [e for e in pilot_events(creators) if e.payload.get("reasonCode")]


def test_one_bad_row_loads_nothing_and_every_problem_is_listed(creators, tmp_path):
    bad = [
        *JOB,
        ("1 Nov 2026", "br-003", "glowco", "nobody.here", "Picked", "", ""),
        ("1 Nov 2026", "br-003", "glowco", "ananya.glows", "Rated", "6", ""),
        ("1 Nov 2026", "br-003", "glowco", "ananya.glows", "Dropped out", "", ""),
        ("1 Nov 2026", "br-003", "glowco", "ananya.glows", "Rejected", "call her on 9876543210", ""),
        ("someday", "br-003", "glowco", "ananya.glows", "Picked", "", ""),
        ("1 Nov 2026", "br-003", "glowco", "ananya.glows", "Hired", "", ""),
    ]
    r = import_sheet(creators, write_csv(tmp_path / "log.csv", bad))
    assert len(r.errors) == 6 and pilot_events(creators) == []
    text = "\n".join(r.errors)
    for hint in ("not in Loofie yet", "1-5", "Reason code", "phone", "not a date", "must be one of"):
        assert hint in text


def test_events_the_app_sends_are_never_touched(creators, tmp_path):
    from app.feedback.events import log_event

    log_event(
        creators,
        event_type="rebooked",
        actor_type="brand",
        subject_type="creator",
        subject_id="x",
        payload={"creator_ref": "cr-ananya"},
    )
    creators.commit()
    import_sheet(creators, write_csv(tmp_path / "log.csv", JOB))
    import_sheet(creators, write_csv(tmp_path / "log.csv", []))  # everything removed from the sheet
    assert pilot_events(creators) == []
    assert creators.scalar(select(Event).where(Event.event_type == "rebooked")) is not None


def test_dry_run_changes_nothing(creators, tmp_path):
    r = import_sheet(creators, write_csv(tmp_path / "log.csv", JOB), dry_run=True)
    assert r.added == r.events > 0 and pilot_events(creators) == []


def test_template_round_trip(creators, tmp_path):
    path = write_template(tmp_path / "pilot_log.xlsx")
    wb = load_workbook(path)
    assert wb.sheetnames == ["How to fill", "Log", "Lists"] and wb.active.title == "Log"
    assert [c.value for c in wb["Log"][1]] == COLUMNS
    assert {str(dv.sqref) for dv in wb["Log"].data_validations.dataValidation} == {
        "E2:E2000",
        "G2:G2000",
        "A2:A2000",
    }
    log = wb["Log"]
    for i, row in enumerate(JOB, start=2):
        for j, v in enumerate(row, start=1):
            log.cell(row=i, column=j, value=v or None)
    wb.save(path)
    r = import_sheet(creators, path)
    assert r.errors == [] and r.rows == len(JOB)
    assert import_sheet(creators, write_template(tmp_path / "blank.xlsx")).rows == 0


def test_a_row_for_today_is_accepted(creators, tmp_path):
    from datetime import datetime, timedelta, timezone

    today = datetime.now(timezone(timedelta(hours=5, minutes=30))).strftime("%d %b %Y")
    row = (today, "br-009", "glowco", "ananya.glows", "Picked", "", "")
    assert import_sheet(creators, write_csv(tmp_path / "log.csv", [row])).errors == []
