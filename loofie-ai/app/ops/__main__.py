"""Usage (through the main command):
loofie models list
loofie models promote NAME CANDIDATE.toml [--force]    NAME: brief | quality | matcher | profile
loofie models rollback NAME
loofie report [--days 7]
loofie backup [--keep 14]
loofie restore FILE --yes
"""

import argparse
import sys
from pathlib import Path

from app.db.session import SessionLocal


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(prog="loofie")
    sub = ap.add_subparsers(dest="command", required=True)
    models = sub.add_parser("models").add_subparsers(dest="action", required=True)
    models.add_parser("list")
    pr = models.add_parser("promote")
    pr.add_argument("name")
    pr.add_argument("candidate", type=Path)
    pr.add_argument("--force", action="store_true", help="promote even with no evaluation data")
    models.add_parser("rollback").add_argument("name")
    sub.add_parser("report").add_argument("--days", type=int, default=7)
    sub.add_parser("backup").add_argument("--keep", type=int, default=14)
    rs = sub.add_parser("restore")
    rs.add_argument("file", type=Path)
    rs.add_argument("--yes", action="store_true", help="confirm: this replaces the current database")
    args = ap.parse_args()

    if args.command == "backup":
        from app.ops.backup import backup

        print(f"saved {backup(args.keep)}")
        return 0
    if args.command == "restore":
        from app.ops.backup import restore

        if not args.yes:
            print("This replaces everything in the database with the backup. Run again with --yes to confirm.")
            return 2
        restore(args.file)
        print(f"restored {args.file}")
        return 0

    with SessionLocal() as session:
        if args.command == "report":
            from app.ops.report import build_report, print_report

            print_report(build_report(session, args.days))
            return 0

        from app.ops import registry

        if args.action == "list":
            for r in registry.listing(session):
                print(f"  {r['model']:8} {r['version']:24} {r['status']:12} {r['reason']}")
            return 0
        if args.action == "rollback":
            old, new = registry.rollback(session, args.name)
            print(f"{args.name}: rolled back {old} → {new}")
            return 0

        from app.ml.embed import get_embedder

        d = registry.promote(session, get_embedder(), args.name, args.candidate, force=args.force)
        print(f"live      {d.live_version:24} {d.live.detail}")
        print(f"candidate {d.candidate_version:24} {d.candidate.detail}")
        print(f"\n{'PROMOTED' if d.promoted else 'NOT PROMOTED'}: {d.reason}")
        return 0 if d.promoted else 1


if __name__ == "__main__":
    sys.exit(main())
