"""Usage:
    python -m app.brief parse "need 3 girls in blr for sunscreen reels, 15k total, by 5th dec"
    python -m app.brief evaluate [--csv Briefs.csv] [--as-of 2026-11-14]

evaluate reads the Briefs loaded by the importer, or a Briefs CSV directly.
"""

import argparse
import sys
from datetime import date
from pathlib import Path

from app.brief.evaluate import FIELDS, evaluate, load_csv, load_db
from app.brief.extract import Field, parse

SHOW = 30


def cmd_parse(args) -> int:
    bp = parse(args.text, as_of=args.as_of)
    print(f"({bp.version})")
    for name, f in bp.__dict__.items():
        if isinstance(f, Field):
            value = "-" if f.value in (None, []) else f.value
            conf = f"[{f.confidence}]" if f.confidence else ""
            ev = f'  ← "{f.evidence}"' if f.evidence else ""
            print(f"  {name:24} {str(value):30} {conf:7}{ev}")
    return 0


def cmd_evaluate(args) -> int:
    if args.csv:
        briefs = load_csv(args.csv)
    else:
        from app.db.session import SessionLocal

        with SessionLocal() as session:
            briefs = load_db(session)
    if not briefs:
        print("No briefs with an answer key yet. Fill the Briefs tab and import it, or pass --csv.")
        return 0
    r = evaluate(briefs, as_of=args.as_of)
    print(f"Brief Reader on {r.briefs} briefs\n")
    print(f"{'field':18} {'correct':>8} {'wrong':>6} {'missed':>7} {'invented':>9}")
    for name in FIELDS:
        c = r.outcomes[name]
        print(f"{name:18} {c['correct'] / r.briefs:>8.0%} {c['wrong']:>6} {c['missed']:>7} {c['invented']:>9}")
    if r.must_recall:
        print(
            f"{'must_say':18} {sum(r.must_recall) / len(r.must_recall):>8.0%}  of answer points found"
            f" ({len(r.must_recall)} briefs), invented on {r.must_invented}"
        )
    invented = sum(c["invented"] for c in r.outcomes.values()) + r.must_invented
    print(f"\nInvented fields in total: {invented}  (should be 0)")
    if r.mistakes:
        print(f"\nMistakes ({len(r.mistakes)}):")
        for m in r.mistakes[:SHOW]:
            print(f"  {m['brief']:10} {m['field']:16} {m['outcome']:12} model={m['model']!r}  answer={m['answer']!r}")
    return 0


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252: no ₹ or emoji
    ap = argparse.ArgumentParser(prog="python -m app.brief")
    sub = ap.add_subparsers(dest="command", required=True)
    pp = sub.add_parser("parse")
    pp.add_argument("text")
    pp.add_argument("--as-of", type=date.fromisoformat, default=date.today())
    ev = sub.add_parser("evaluate")
    ev.add_argument("--csv", type=Path)
    ev.add_argument("--as-of", type=date.fromisoformat, default=date.today())
    args = ap.parse_args()
    return cmd_parse(args) if args.command == "parse" else cmd_evaluate(args)


if __name__ == "__main__":
    sys.exit(main())
