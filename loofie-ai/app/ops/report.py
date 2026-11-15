"""Weekly health report from the events log: is each model helping, and where is it wrong?

Shadow mode: ops builds every shortlist by hand while the matcher runs alongside. Agreement =
share of the creators ops actually showed the brand that were already in the matcher's top 10.
"""

from collections import Counter
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Event


def build_report(session: Session, days: int = 7, now: datetime | None = None) -> dict:
    since = (now or datetime.now(UTC)) - timedelta(days=days)
    events = session.scalars(select(Event).where(Event.occurred_at >= since).order_by(Event.occurred_at)).all()
    by_type = Counter(e.event_type for e in events)

    # Model 1: which fields brands had to fix
    edited = Counter(e.payload.get("field") for e in events if e.event_type == "brief_field_edited")
    parsed = by_type["brief_parsed"]

    # Model 2 shadow mode: what ops showed vs what the matcher suggested
    suggested = {
        str(e.id): [r["handle"] for r in e.payload.get("results", [])]
        for e in events
        if e.event_type == "shortlist_suggested"
    }
    shown_total = shown_in_top = 0
    for e in events:
        if e.event_type == "shortlist_shown" and e.payload.get("suggestion_id") in suggested:
            top = set(suggested[e.payload["suggestion_id"]])
            shown = e.payload.get("creator_handles", [])
            shown_total += len(shown)
            shown_in_top += sum(h in top for h in shown)

    picks, rejects = by_type["shortlist_pick"], by_type["shortlist_reject"]
    reasons = Counter(e.payload.get("reason") for e in events if e.event_type == "shortlist_reject")
    outcomes = [e.payload.get("on_time") for e in events if e.event_type == "job_outcome"]
    on_time = [o for o in outcomes if isinstance(o, bool)]

    return {
        "days": days,
        "events": len(events),
        "briefs_parsed": parsed,
        "brief_fields_fixed": dict(edited.most_common()),
        "brief_fix_rate": round(sum(edited.values()) / parsed, 2) if parsed else None,
        "shortlists_suggested": len(suggested),
        "shadow_agreement": round(shown_in_top / shown_total, 3) if shown_total else None,
        "ops_added_outside_matcher": by_type["ops_added_creator"],
        "ops_removed_from_matcher": by_type["ops_removed_creator"],
        "picks": picks,
        "rejects": rejects,
        "reject_reasons": dict(reasons.most_common()),
        "quality_overrides": by_type["quality_override"],
        "niche_overrides": by_type["niche_override"],
        "jobs_finished": len(outcomes),
        "on_time_rate": round(sum(on_time) / len(on_time), 3) if on_time else None,
        "rebooked": by_type["rebooked"],
        "deal_prices": sorted(
            e.payload["price_inr"] for e in events if e.event_type == "deal_price" and "price_inr" in e.payload
        ),
        "from_pilot_sheet": sum(e.model_name == "pilot_sheet" for e in events),
    }


def print_report(r: dict) -> None:
    def pct(x):
        return "-" if x is None else f"{x:.0%}"

    print(f"Last {r['days']} days: {r['events']} events\n")
    print("Model 1 · Brief Reader")
    print(f"  briefs parsed            {r['briefs_parsed']}")
    print(f"  field fixes per brief    {r['brief_fix_rate'] if r['brief_fix_rate'] is not None else '-'}")
    for f, n in r["brief_fields_fixed"].items():
        print(f"    {f:22} fixed {n}x")
    print("\nModel 2 · Matcher (shadow mode)")
    print(f"  shortlists suggested     {r['shortlists_suggested']}")
    print(f"  ops picks in matcher top {pct(r['shadow_agreement'])}   ← the number to watch")
    print(f"  ops added from outside   {r['ops_added_outside_matcher']}   (matcher missed them)")
    print(f"  ops removed              {r['ops_removed_from_matcher']}   (matcher was wrong)")
    print(f"  brand picks / rejects    {r['picks']} / {r['rejects']}")
    for reason, n in r["reject_reasons"].items():
        print(f"    {reason!s:22} {n}")
    print("\nModel 3 · Quality")
    print(f"  ops overrides            {r['quality_overrides']} quality, {r['niche_overrides']} niche")
    print("\nOutcomes (what the learning job will train on)")
    print(f"  jobs finished            {r['jobs_finished']}")
    print(f"  on time                  {pct(r['on_time_rate'])}")
    print(f"  rebooked                 {r['rebooked']}")
    prices = r["deal_prices"]
    median = f"median ₹{prices[len(prices) // 2]:,}" if prices else "-"
    print(f"  real prices recorded     {len(prices)}   {median}   (rate estimate is replaced after 20-30)")
    print()
    print(f"{r['from_pilot_sheet']} of these events came from the pilot log (loofie pilot import)")
