"""Score Model 1 against the Briefs tab answer key, field by field.

Each field is one of: correct, wrong, missed (answer exists, model left it blank) or invented
(answer is blank, model filled something in). Invented is the one that must stay at zero.
Must-say points are scored by recall: how many of the answer's points the model found.
"""

import csv
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.brief.extract import BriefParse, parse
from app.db.models import Brief
from app.importer import parse as p
from app.importer.sheet import brief_row, is_example

FIELDS = ["category", "sub_niche", "creators_needed", "budget_total_inr", "city", "language", "format", "deadline"]
STOP = {"the", "a", "an", "our", "your", "of", "for", "and", "to", "is", "it", "in", "on", "with", "that", "how"}


@dataclass
class Report:
    outcomes: dict[str, Counter] = field(default_factory=lambda: {f: Counter() for f in FIELDS})
    must_recall: list[float] = field(default_factory=list)
    must_invented: int = 0
    mistakes: list[dict] = field(default_factory=list)
    briefs: int = 0


def predicted(bp: BriefParse) -> dict:
    v = bp.values()
    return {
        "category": v["category"],
        "sub_niche": v["sub_niche"],
        "creators_needed": v["creators_needed"],
        "budget_total_inr": v["budget_total_inr"],
        "city": v["city"],
        "language": v["languages"][0] if v["languages"] else None,
        "format": v["format"],
        "deadline": v["deadline"],
        "must_say": v["must_say"] or [],
    }


def _blank(x) -> bool:
    return x is None or x == "" or x == []


def outcome(name: str, pred, key) -> str:
    if name == "city":
        pred = None if pred in (None, "any") else pred
        key = None if _blank(key) or str(key).lower() == "any" else key
    if name == "deadline":
        # Day + month only: the year depends on when the brief was posted, which the sheet doesn't record.
        pred = (pred.month, pred.day) if pred else None
        key = (date.fromisoformat(key).month, date.fromisoformat(key).day) if key else None
    if _blank(key):
        return "correct" if _blank(pred) else "invented"
    if _blank(pred):
        return "missed"
    return "correct" if str(pred).lower() == str(key).lower() else "wrong"


def _tokens(s: str) -> set[str]:
    return {t for t in re.findall(r"[a-z0-9]+", s.lower()) if t not in STOP}


def point_matches(pred: str, key: str) -> bool:
    a, b = _tokens(pred), _tokens(key)
    if not a or not b:
        return False
    return b <= a or a <= b or len(a & b) / len(a | b) >= 0.5


def score(report: Report, brief_id: str, text: str, key: dict, as_of: date) -> None:
    pred = predicted(parse(text, as_of=as_of))
    report.briefs += 1
    for name in FIELDS:
        o = outcome(name, pred[name], key.get(name))
        report.outcomes[name][o] += 1
        if o != "correct":
            report.mistakes.append(
                {"brief": brief_id, "field": name, "outcome": o, "model": pred[name], "answer": key.get(name)}
            )
    want, got = key.get("must_say") or [], pred["must_say"]
    if want:
        found = sum(1 for k in want if any(point_matches(g, k) for g in got))
        report.must_recall.append(found / len(want))
        if found < len(want):
            report.mistakes.append(
                {
                    "brief": brief_id,
                    "field": "must_say",
                    "outcome": f"found {found}/{len(want)}",
                    "model": got,
                    "answer": want,
                }
            )
    elif got:
        report.must_invented += 1
        report.mistakes.append(
            {"brief": brief_id, "field": "must_say", "outcome": "invented", "model": got, "answer": []}
        )


def load_csv(path: Path, include_examples: bool = False) -> list[tuple[str, str, dict]]:
    out = []
    with path.open(encoding="utf-8-sig", newline="") as fh:
        for raw in csv.DictReader(fh):
            if not include_examples and is_example(raw):
                continue
            try:
                r = brief_row(raw)
            except p.ParseError:
                continue
            out.append((r["external_id"], r["raw_text"], r["answer_key"]))
    return out


def load_db(session: Session) -> list[tuple[str, str, dict]]:
    rows = session.scalars(select(Brief).where(Brief.origin == "dataset", Brief.answer_key.is_not(None)))
    return [(b.external_id, b.raw_text, b.answer_key) for b in rows]


def evaluate(briefs: list[tuple[str, str, dict]], as_of: date | None = None) -> Report:
    report = Report()
    for brief_id, text, key in briefs:
        score(report, brief_id, text, key, as_of or date.today())
    return report
