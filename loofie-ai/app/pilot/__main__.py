"""Usage (through the main command):
loofie pilot template [FILE]          blank pilot log with dropdowns (default: pilot_log.xlsx)
loofie pilot import FILE [--dry-run]  load the Log tab (.xlsx, or .csv from Google Sheets)
"""

import argparse
import sys
from pathlib import Path


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(prog="loofie pilot")
    sub = ap.add_subparsers(dest="action", required=True)
    sub.add_parser("template").add_argument("file", type=Path, nargs="?", default=Path("pilot_log.xlsx"))
    im = sub.add_parser("import")
    im.add_argument("file", type=Path)
    im.add_argument("--dry-run", action="store_true", help="check every row and show what would change")
    args = ap.parse_args()

    from app.pilot.sheet import import_sheet, write_template

    if args.action == "template":
        print(f"saved {write_template(args.file)}: fill the Log tab, then `loofie pilot import {args.file}`")
        return 0

    from app.db.session import SessionLocal

    if not args.file.exists():
        print(f"no such file: {args.file}")
        return 2
    with SessionLocal() as session:
        try:
            r = import_sheet(session, args.file, dry_run=args.dry_run)
        except ValueError as e:
            print(e)
            return 2
    print(f"{r.rows} rows -> {r.events} events")
    if r.errors:
        print(f"\nNothing loaded: fix these {len(r.errors)} rows and import again.")
        for e in r.errors:
            print(f"  {e}")
        return 1
    verb = "would be" if args.dry_run else ""
    print(f"  added {r.added} · changed {r.changed} · removed {r.removed} · unchanged {r.unchanged} {verb}".rstrip())
    print("  " + " · ".join(f"{a} {n}" for a, n in r.by_action.most_common()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
