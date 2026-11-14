"""Usage:
    python -m app.quality assess [--handle NAME ...]

Run after `python -m app.profile build` (the quality check reads the profile numbers).
Prints the labels, the most common findings, and agreement with the team's quality_label.
"""

import argparse
import sys
from collections import Counter

from sqlalchemy import select

from app.db.models import Creator
from app.db.session import SessionLocal
from app.quality.service import assess_and_save

LABELS = ["real", "unsure", "suspicious"]
SHOW = 15


def compare_with_labels(session, rows: list[dict]) -> None:
    human = dict(
        session.execute(select(Creator.id, Creator.label_quality).where(Creator.label_quality.is_not(None))).all()
    )
    pairs = [(human[r["creator_id"]], r) for r in rows if r["creator_id"] in human]
    if not pairs:
        print("\nNo hand-entered quality_label yet, so agreement can't be measured.")
        return
    print(f"\nAgainst the team's quality_label ({len(pairs)} creators). Rows = team, columns = model:")
    print(f"  {'':12}" + "".join(f"{m:>12}" for m in LABELS))
    for h in LABELS:
        print(f"  {h:12}" + "".join(f"{sum(1 for x, r in pairs if x == h and r['label'] == m):>12}" for m in LABELS))
    flagged = [r for x, r in pairs if r["label"] == "suspicious"]
    truly = [r for x, r in pairs if x == "suspicious"]
    caught = [r for x, r in pairs if x == "suspicious" and r["label"] == "suspicious"]
    if truly:
        print(f"  Caught {len(caught)}/{len(truly)} of the creators the team marked suspicious")
    if flagged:
        print(f"  {len(caught)}/{len(flagged)} of the model's suspicious flags were also suspicious to the team")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252: no ₹ or emoji
    ap = argparse.ArgumentParser(prog="python -m app.quality")
    ap.add_argument("command", choices=["assess"])
    ap.add_argument("--handle", action="append")
    args = ap.parse_args()

    with SessionLocal() as session:
        rows = assess_and_save(session, handles=args.handle)
        session.commit()
        print(f"Assessed {len(rows)} creators ({rows[0]['version'] if rows else ''})")
        for label, n in Counter(r["label"] for r in rows).most_common():
            print(f"  {label:12} {n}")
        print("Activity: " + ", ".join(f"{a} {n}" for a, n in Counter(r["activity"] for r in rows).most_common()))
        print("\nMost common findings:")
        for code, n in Counter(f["code"] for r in rows for f in r["findings"]).most_common():
            print(f"  {code:28} {n}")
        handles = dict(session.execute(select(Creator.id, Creator.handle)).all())
        flagged = sorted((r for r in rows if r["label"] == "suspicious"), key=lambda r: r["score"])
        if flagged:
            print("\nSuspicious (sent to ops to check, never auto-rejected):")
            for r in flagged[:SHOW]:
                print(f"  @{handles[r['creator_id']]:28} score {r['score']:>3}  {r['summary']}")
        compare_with_labels(session, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
