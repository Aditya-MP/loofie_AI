"""Usage:
    python -m app.matcher match "brief text" [--category C] [--sub-niche S] [--city X]
                                [--language L ...] [--budget INR] [--min-followers N] [--max-followers N]
    python -m app.matcher evaluate [--k 10]

Run `python -m app.profile build` and `python -m app.quality assess` first.
"""

import argparse
import sys

from app.db.session import SessionLocal
from app.matcher.evaluate import evaluate
from app.matcher.features import BriefQuery, matcher_config
from app.matcher.service import match
from app.ml.embed import get_embedder

MIN_BRANDS = 10


def cmd_match(args) -> int:
    brief = BriefQuery(
        text=args.text,
        category=args.category,
        sub_niche=args.sub_niche,
        city=args.city,
        languages=args.language or [],
        budget_per_creator_inr=args.budget,
        min_followers=args.min_followers,
        max_followers=args.max_followers,
    )
    with SessionLocal() as session:
        results, excluded = match(session, brief, get_embedder())
    print(f"Top {len(results)} ({matcher_config()['version']})")
    for i, r in enumerate(results, 1):
        print(f"{i:>3}. @{r.handle:28} {r.score:.3f}  {r.why}")
    if excluded:
        print("\nLeft out: " + ", ".join(f"{n} {why}" for why, n in excluded.most_common()))
    return 0


def cmd_evaluate(args) -> int:
    with SessionLocal() as session:
        results = evaluate(session, get_embedder(), k=args.k)
    if not results:
        print("No Brand_Creator_Links yet: the brand-hire test needs brands with proven paid collaborations.")
        return 0
    print(f"Brand-hire test: share of each brand's real hires found in the top {args.k}\n")
    print(
        f"{'brand':24} {'hires':>5} {'pool':>5} {'matcher':>8} {'by size':>8} {'filters':>8} {'random':>7}  first hit"
    )
    for r in results:
        print(
            f"{r.brand:24} {r.hires:>5} {r.eligible:>5} {r.matcher:>8.0%} {r.popularity:>8.0%} "
            f"{r.filters_only:>8.0%} {r.random:>7.0%}  {r.first_hit_rank or '-'}"
        )
    n = len(results)

    def mean(f):
        return sum(getattr(r, f) for r in results) / n

    print(
        f"{'AVERAGE':24} {'':>5} {'':>5} {mean('matcher'):>8.0%} {mean('popularity'):>8.0%} "
        f"{mean('filters_only'):>8.0%} {mean('random'):>7.0%}"
    )
    if n < MIN_BRANDS:
        print(f"\nOnly {n} brand(s): too few to conclude anything. Aim for 30 brands with 3+ hires each.")
    return 0


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252: no ₹ or emoji
    ap = argparse.ArgumentParser(prog="python -m app.matcher")
    sub = ap.add_subparsers(dest="command", required=True)
    m = sub.add_parser("match")
    m.add_argument("text")
    m.add_argument("--category")
    m.add_argument("--sub-niche")
    m.add_argument("--city")
    m.add_argument("--language", action="append")
    m.add_argument("--budget", type=int)
    m.add_argument("--min-followers", type=int)
    m.add_argument("--max-followers", type=int)
    e = sub.add_parser("evaluate")
    e.add_argument("--k", type=int, default=10)
    args = ap.parse_args()
    return cmd_match(args) if args.command == "match" else cmd_evaluate(args)


if __name__ == "__main__":
    sys.exit(main())
