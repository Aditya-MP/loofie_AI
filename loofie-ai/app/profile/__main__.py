"""Usage:
    python -m app.profile build [--handle NAME ...]

Builds creator profiles on the GPU, saves them, and prints how the predicted niche compares
with the niche the data team entered by hand.
"""

import argparse
import sys
from collections import Counter

from sqlalchemy import select

from app.db.models import Creator
from app.db.session import SessionLocal
from app.ml.embed import get_embedder
from app.profile.builder import build_and_save

BEAUTY = {"beauty_skincare", "personal_care"}
SHOW = 15


def compare_with_labels(session, rows: list[dict]) -> None:
    """Hand-entered category vs prediction, on 'is this a beauty/personal-care creator?'."""
    labels = {c.id: c for c in session.scalars(select(Creator).where(Creator.id.in_([r["creator_id"] for r in rows])))}
    pairs = [(labels[r["creator_id"]], r) for r in rows if r["category"] and labels[r["creator_id"]].category]
    if not pairs:
        print("\nNo hand-entered categories to compare with yet.")
        return
    right = [(c, r) for c, r in pairs if (c.category in BEAUTY) == (r["category"] in BEAUTY)]
    print(f"\nBeauty vs not, against hand labels: {len(right)}/{len(pairs)} agree ({len(right) / len(pairs):.0%})")
    wrong = [(c, r) for c, r in pairs if (c, r) not in right]
    for c, r in wrong[:SHOW]:
        guess = r["sub_niche"] or r["other_niche"]
        print(
            f"  @{c.handle:28} hand: {c.category:16} model: {guess} ({r['category_confidence']:.2f})"
            f"  text: {c.category_raw or (c.bio or '')[:40]!r}"
        )

    subs = [(c, r) for c, r in pairs if c.sub_niche and r["sub_niche"]]
    if subs:
        ok = sum(1 for c, r in subs if c.sub_niche == r["sub_niche"])
        print(f"Sub-niche, against hand labels: {ok}/{len(subs)} agree ({ok / len(subs):.0%})")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252: no ₹ or emoji
    ap = argparse.ArgumentParser(prog="python -m app.profile")
    ap.add_argument("command", choices=["build"])
    ap.add_argument("--handle", action="append", help="only these creators (repeatable)")
    args = ap.parse_args()

    embedder = get_embedder()
    with SessionLocal() as session:
        rows = build_and_save(session, embedder, handles=args.handle)
        session.commit()

        print(f"Built {len(rows)} profiles on {embedder.device}  ({rows[0]['version'] if rows else ''})")
        with_vec = sum(1 for r in rows if r["embedding"] is not None)
        print(f"  with enough text for a niche: {with_vec}   too little text: {len(rows) - with_vec}")
        print(f"  marked unsure: {sum(1 for r in rows if r['niche_unsure'])}")
        print(f"  text used: {dict(Counter(s.split(':')[0] for r in rows for s in r['text_sources']))}")
        print(f"  most common missing data: {Counter(m for r in rows for m in r['missing']).most_common()}")
        print("\nPredicted niche:")
        for niche, n in Counter(r["sub_niche"] or r["other_niche"] or "(no text)" for r in rows).most_common():
            print(f"  {niche:16} {n}")
        compare_with_labels(session, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
