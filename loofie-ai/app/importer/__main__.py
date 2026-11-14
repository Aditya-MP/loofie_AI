"""Usage:
    python -m app.importer sheet PATH [--include-examples] [--strict]
    python -m app.importer legacy PATH [--strict]

PATH for `sheet` is the .xlsx file or a folder holding one CSV per tab (Brands.csv, Creators.csv, ...).
--strict rolls everything back if any row has an error.
"""

import argparse
import sys
from pathlib import Path

from app.db.session import SessionLocal
from app.importer.legacy import import_legacy
from app.importer.sheet import Report, import_sheet

SHOW = 25


def print_report(report: Report) -> None:
    print(f"\n{'tab':22} {'loaded':>7} {'examples skipped':>17}")
    for tab, c in report.counts.items():
        print(f"{tab:22} {c.get('loaded', 0):>7} {c.get('skipped_example', 0):>17}")
    for title, items, key in (
        ("ERRORS (row skipped)", report.errors, "error"),
        ("WARNINGS", report.warnings, "warning"),
    ):
        if items:
            print(f"\n{title}: {len(items)}")
            for it in items[:SHOW]:
                where = f"{it['tab']} row {it['row']}" if it["row"] else it["tab"]
                print(f"  {where}: {it[key]}")
            if len(items) > SHOW:
                print(f"  … {len(items) - SHOW} more, see the import_runs table")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252: no ₹ or emoji
    ap = argparse.ArgumentParser(prog="python -m app.importer")
    ap.add_argument("kind", choices=["sheet", "legacy"])
    ap.add_argument("path", type=Path)
    ap.add_argument("--include-examples", action="store_true", help="also load the grey EXAMPLE rows")
    ap.add_argument("--strict", action="store_true", help="load nothing if any row has an error")
    args = ap.parse_args()

    if not args.path.exists():
        print(f"not found: {args.path}", file=sys.stderr)
        return 2

    with SessionLocal() as session:
        if args.kind == "sheet":
            report = import_sheet(session, args.path, include_examples=args.include_examples)
        else:
            report = import_legacy(session, args.path)
        print_report(report)
        if args.strict and report.errors:
            session.rollback()
            print("\n--strict: nothing was saved. Fix the rows above and run again.")
            return 1
        session.commit()
    print("\nsaved.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
