"""Model 2, stage 3 of 3: business rules on top of the ranker's order.

The ranker answers "who fits this brief best?". This stage answers "is this a good shortlist?":
  1. competitor conflict: a recent paid post for a same-niche brand moves a creator down
  2. diversity: near-duplicate content doesn't fill the list
  3. exploration: one slot for a NEW creator, so new creators can ever build history
Every move is written on the result (rerank_note), and rank_before_rerank keeps the ranker's own
position, so the learning step can tell the ranker's choices from the rules' choices.
"""

from app.matcher.features import BriefQuery, Candidate, matcher_config
from app.matcher.ranker import MatchResult


def _similar(a: Candidate, b: Candidate) -> float | None:
    if a.embedding is None or b.embedding is None:
        return None
    return float(a.embedding @ b.embedding)


def _diverse(items: list[MatchResult], pool: dict[int, Candidate], k: int, rc: dict) -> list[MatchResult]:
    """Greedy pick: highest score first, minus a penalty for a near-twin already picked."""
    picked: list[MatchResult] = []
    rest = list(items)
    while rest and len(picked) < k:
        best, best_val, best_twin = None, None, None
        for r in rest:  # rest is in ranker order, so ties keep that order
            twin = None
            for p in picked:
                s = _similar(pool[r.creator_id], pool[p.creator_id])
                if s is not None and s >= rc["duplicate_similarity"]:
                    twin = p
                    break
            val = r.score - (rc["duplicate_penalty"] if twin else 0.0)
            if best_val is None or val > best_val:
                best, best_val, best_twin = r, val, twin
        if best_twin is not None:
            best.rerank_note = f"moved down: very similar content to @{best_twin.handle}"
        picked.append(best)
        rest.remove(best)
    return picked


def apply_availability(brief: BriefQuery, ranked: list[MatchResult], cfg: dict) -> list[MatchResult]:
    """Ready / Interested creators book on pick, so they get a small boost: enough to beat an
    equally good fit, never enough to lift a weak fit over a strong one. rank_before_rerank keeps
    the fit-only position, so the learning step can tell fit and availability apart."""
    ac = cfg["availability"]
    for r in ranked:
        if r.creator_id in brief.ready_creator_ids:
            r.availability, boost, note = "ready", ac["ready_boost"], "ready this week"
        elif r.creator_id in brief.interested_creator_ids:
            r.availability, boost, note = "interested", ac["interested_boost"], "said I'm in"
        else:
            continue
        r.score = round(r.score + boost, 4)
        r.why = f"{r.why} · {note}" if r.why else note
    ranked.sort(key=lambda r: (-r.score, r.rank_before_rerank or 0))
    return ranked


def rerank(
    brief: BriefQuery, ranked: list[MatchResult], pool: dict[int, Candidate], cfg: dict | None = None
) -> list[MatchResult]:
    """ranked: the ranker's full ordered list (keep_all=True). Returns the final top k."""
    cfg = cfg or matcher_config()
    rc = cfg["rerank"]
    k = brief.top_k or cfg["top_k"]

    # 1. competitor conflict
    competitors = set(brief.competitor_handles) - {brief.brand_handle}
    clean, conflicted = [], []
    for r in ranked:
        hit = sorted(competitors & set(pool[r.creator_id].paid_brands))
        if hit and rc["demote_competitor_conflict"]:
            r.rerank_note = f"conflict: recent paid post for @{hit[0]}, a same-niche brand; listed after the others"
            conflicted.append(r)
        else:
            clean.append(r)

    # 2. diversity, within each group so it can't lift a conflicted creator above a clean one
    top = _diverse(clean, pool, k, rc)
    if len(top) < k:
        top += _diverse(conflicted, pool, k - len(top), rc)

    # 3. exploration: only meaningful when the list holds creators with history (VERIFIED)
    slots = rc["explore_slots"]
    if slots and top and any(pool[r.creator_id].level != "NEW" for r in top):
        chosen = {r.creator_id for r in top}
        cutoff = top[-1].score * rc["explore_min_share"]
        newcomers = [
            r for r in clean if r.creator_id not in chosen and pool[r.creator_id].level == "NEW" and r.score >= cutoff
        ]
        regular = [i for i, r in enumerate(top) if pool[r.creator_id].level != "NEW"]
        for newcomer, slot in zip(newcomers[:slots], reversed(regular), strict=False):
            newcomer.rerank_note = "new creator: exploration slot, no booking history yet"
            top[slot] = newcomer

    for i, r in enumerate(top, 1):
        if r.rerank_note and r.rerank_note.startswith("moved down") and i <= r.rank_before_rerank:
            r.rerank_note = None  # the duplicate rule applied but didn't actually move it
    return top
