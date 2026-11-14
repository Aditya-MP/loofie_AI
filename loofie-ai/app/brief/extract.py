"""Model 1 (Brief Reader), rules version.

Turns a brand's free text into the Brief Builder fields. Every value comes with the words it was
read from and a confidence ("high" = an explicit statement, "low" = a default or a weak hint).
A field with no evidence stays empty: the brand fills it in the form, nothing is invented.
"""

import calendar
import re
from dataclasses import dataclass, field
from datetime import date, timedelta

from app.config import load_toml

HIGH, LOW, FIXED = "high", "low", "fixed"  # fixed: added by rule, not read from the text
MONTHS = {m.lower(): i for i, m in enumerate(calendar.month_abbr) if m}
MONTH = r"(jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec)[a-z]*"


def _month(word: str) -> int:
    return MONTHS[word.lower()[:3]]


def brief_config() -> dict:
    return load_toml("brief")


@dataclass
class Field:
    value: object = None
    confidence: str | None = None
    evidence: str | None = None


@dataclass
class BriefParse:
    category: Field = field(default_factory=Field)
    sub_niche: Field = field(default_factory=Field)
    creators_needed: Field = field(default_factory=Field)
    budget_total_inr: Field = field(default_factory=Field)
    budget_per_creator_inr: Field = field(default_factory=Field)
    city: Field = field(default_factory=Field)
    languages: Field = field(default_factory=Field)
    format: Field = field(default_factory=Field)
    deadline: Field = field(default_factory=Field)
    must_say: Field = field(default_factory=Field)
    min_followers: Field = field(default_factory=Field)
    max_followers: Field = field(default_factory=Field)
    disclosure: Field = field(default_factory=Field)
    version: str = ""

    def values(self) -> dict:
        return {k: v.value for k, v in self.__dict__.items() if isinstance(v, Field)}


def _phrase(words: list[str]) -> str:
    """Regex alternation, longest first, on word boundaries."""
    return r"(?<![\w])(?:" + "|".join(re.escape(w) for w in sorted(words, key=len, reverse=True)) + r")(?![\w])"


def _norm(text: str) -> str:
    return text.replace("’", "'").replace("–", "-").replace("—", "-").lower()


# ------------------------------------------------------------------ amounts and counts

AMOUNT = re.compile(
    r"(?P<cur>₹|rs\.?|inr|rupees)?\s*(?P<num>\d{1,3}(?:,\d{2,3})+|\d+(?:\.\d+)?)\s*"
    r"(?P<suf>k|thousand|lakhs?|lacs?|lakh|l|cr|crores?)?(?![\w%])(?P<cur2>\s*(?:/-|rs\b|inr\b|rupees\b))?",
    re.I,
)
SUFFIX = {
    "k": 1_000,
    "thousand": 1_000,
    "l": 100_000,
    "lakh": 100_000,
    "lakhs": 100_000,
    "lac": 100_000,
    "lacs": 100_000,
    "cr": 10_000_000,
    "crore": 10_000_000,
    "crores": 10_000_000,
}
NOT_MONEY_AFTER = re.compile(
    r"^\s*\+?\s*(?:-\s*\d+\s*k?\s*)?(?:followers|follower|views|subs|subscribers|likes|"
    r"st\b|nd\b|rd\b|th\b|days?|weeks?|months?|hours?|hrs?|mins?|reels?|videos?|posts?|"
    r"creators?|influencers?|shades|%|ml\b|gm?\b|:)",
    re.I,
)
PER_AFTER = re.compile(
    r"^\s*(?:/-)?\s*(?:per|each|/|a)\s*(?:creator|influencer|person|head|reel|video|post|"
    r"girl|guy|deliverable|piece)|^\s*(?:/-)?\s*each\b",
    re.I,
)
PER_BEFORE = re.compile(
    r"(?:per|each)\s+(?:creator|influencer|person|head|reel|video|post)\s*"
    r"(?:budget|fee|rate|pay|payout)?\s*(?:is|of|:|-|=)?\s*$",
    re.I,
)
TOTAL_NEAR = re.compile(r"\b(?:total|overall|in all|for all|altogether)\b", re.I)
MONEY_BEFORE = re.compile(r"\b(?:budget|pay|paying|fee|fees|rate|payout|compensation|price)\b[^.\n]{0,12}$", re.I)


def _amount_value(m: re.Match) -> int | None:
    try:
        n = float(m.group("num").replace(",", ""))
    except ValueError:
        return None
    suf = (m.group("suf") or "").lower()
    return round(n * SUFFIX.get(suf, 1))


def amounts(text: str) -> list[tuple[int, str, str]]:
    """[(value, 'per' | 'total' | 'unknown', evidence)] for every money mention."""
    out = []
    for m in AMOUNT.finditer(text):
        before, after = text[max(0, m.start() - 30) : m.start()], text[m.end() : m.end() + 30]
        if NOT_MONEY_AFTER.match(after):
            continue
        has_unit = m.group("cur") or m.group("cur2") or m.group("suf")
        per = bool(PER_AFTER.match(after) or PER_BEFORE.search(before))
        total = bool(TOTAL_NEAR.search(after[:15]) or TOTAL_NEAR.search(before[-15:]))
        # "6000 each", "3000 per post", "10000 total" are money even without ₹ or k
        if not (has_unit or per or total or MONEY_BEFORE.search(before)):
            continue
        value = _amount_value(m)
        if value is None or value < 100:
            continue
        if per:
            kind = (
                "per_reel"
                if re.match(r"^\s*(?:/-)?\s*(?:per|each|/|a)\s*(?:reel|video|post|piece|deliverable)", after, re.I)
                else "per"
            )
        elif total:
            kind = "total"
        else:
            kind = "unknown"
        out.append((value, kind, m.group(0).strip()))
    return out


def creators_needed(text: str, cfg: dict) -> Field:
    words = cfg["numbers"]
    num = r"(\d{1,3}|" + "|".join(re.escape(w) for w in sorted(words, key=len, reverse=True)) + ")"
    nouns = "|".join(sorted(cfg["count"]["nouns"], key=len, reverse=True))
    pat = re.compile(rf"(?<![\w.,/-]){num}\s+((?:[a-z'\-]+\s+){{0,3}}?)(?:ugc\s+|content\s+)?(?:{nouns})\b", re.I)
    blockers = set(cfg["count"]["blockers"])
    for m in pat.finditer(text):
        between = m.group(2).split()
        if any(w in blockers or any(ch.isdigit() for ch in w) for w in between):
            continue
        raw = m.group(1).lower()
        n = int(raw) if raw.isdigit() else words.get(raw)
        if n and 0 < n <= 500:
            return Field(n, HIGH, m.group(0))
    # "telugu girls, 5 of them"
    m = re.search(rf"(?<![\w.,/-]){num}\s+of\s+them\b", text, re.I)
    if m:
        raw = m.group(1).lower()
        return Field(int(raw) if raw.isdigit() else words[raw], HIGH, m.group(0))
    # "need someone" / "need a creator": one person, but said loosely
    m = re.search(rf"\b(?:need|want|looking for|hiring)\s+(?:someone|somebody|an?\s+(?:{nouns}))\b", text, re.I)
    if m:
        return Field(1, LOW, m.group(0))
    return Field()


DELIVERABLES = re.compile(
    r"\b(\d{1,2})\s+(?:reels?|videos?|posts?)\s+(?:per|each|/|a)\s*(?:creator|influencer|person|head)?", re.I
)


def budget(text: str, count: int | None) -> tuple[Field, Field]:
    found = amounts(text)
    if not found:
        return Field(), Field()
    # "3k per reel" with "2 reels per creator" means 6k per creator
    reels = DELIVERABLES.search(text)
    n_reels = int(reels.group(1)) if reels else 1
    found = [
        (v * n_reels, "per", f"{e} × {n_reels} reels")
        if k == "per_reel" and n_reels > 1
        else (v, "per" if k == "per_reel" else k, e)
        for v, k, e in found
    ]
    per = next(((v, e) for v, k, e in found if k == "per"), None)
    total = next(((v, e) for v, k, e in found if k == "total"), None)
    unknown = next(((v, e) for v, k, e in found if k == "unknown"), None)
    if total is None and per is None and unknown:
        total = unknown  # one plain amount, e.g. "budget 25k": most briefs state the total
        total_conf = LOW if count else HIGH
    else:
        total_conf = HIGH
    total_f, per_f = Field(), Field()
    if per:
        per_f = Field(per[0], HIGH, per[1])
    if total:
        total_f = Field(total[0], total_conf, total[1])
    elif per and count:
        total_f = Field(per[0] * count, HIGH, f"{per[1]} × {count}")
    if total and not per and count:
        per_f = Field(round(total[0] / count), LOW, f"{total[1]} ÷ {count}")
    return total_f, per_f


FOLLOWERS = re.compile(
    r"(?:(?P<op>above|over|min(?:imum)?|at least|atleast|under|below|less than|upto|up to|max(?:imum)?)\s*)?"
    r"(?P<a>\d+(?:\.\d+)?)\s*(?P<as>k|l|lakh|m)?\s*(?P<plus>\+|plus)?\s*"
    r"(?:(?:-|to)\s*(?P<b>\d+(?:\.\d+)?)\s*(?P<bs>k|l|lakh|m)?\s*)?followers",
    re.I,
)
FSUF = {"k": 1_000, "l": 100_000, "lakh": 100_000, "m": 1_000_000}


def follower_range(text: str) -> tuple[Field, Field]:
    m = FOLLOWERS.search(text)
    if not m:
        return Field(), Field()
    a = round(float(m.group("a")) * FSUF.get((m.group("as") or "").lower(), 1))
    ev = m.group(0)
    if m.group("b"):
        bs = m.group("bs") or m.group("as") or ""
        b = round(float(m.group("b")) * FSUF.get(bs.lower(), 1))
        return Field(min(a, b), HIGH, ev), Field(max(a, b), HIGH, ev)
    op = (m.group("op") or "").lower()
    if op in {"under", "below", "less than", "upto", "up to", "max", "maximum"}:
        return Field(), Field(a, HIGH, ev)
    return Field(a, HIGH, ev), Field()


# ------------------------------------------------------------------ place, language, format


def city(text: str, cfg: dict) -> Field:
    best = None
    for name, spellings in cfg["cities"].items():
        m = re.search(_phrase(spellings), text, re.I)
        if m and (best is None or m.start() < best[1].start()):
            best = (name, m)
    if best is None:
        return Field("any", LOW, None)  # brief doesn't limit the city
    return Field(best[0], HIGH, best[1].group(0))


def languages(text: str, cfg: dict) -> Field:
    hits = []
    for name, spellings in cfg["languages"].items():
        m = re.search(_phrase(spellings), text, re.I)
        if m:
            hits.append((m.start(), name, m.group(0)))
    if not hits:
        return Field()
    hits.sort()
    return Field([h[1] for h in hits], HIGH, ", ".join(h[2] for h in hits))


def content_format(text: str, cfg: dict) -> Field:
    best = None
    for name, words in cfg["formats"].items():
        m = re.search(_phrase(words), text, re.I)
        if m and (best is None or m.start() < best[1].start()):
            best = (name, m)
    if best is None:
        return Field(cfg["default_format"], LOW, None)
    return Field(best[0], HIGH, best[1].group(0))


def niche(text: str, cfg: dict) -> tuple[Field, Field]:
    """Product words, longest phrase first; each match is blanked so 'hair removal' can't also count as hair."""
    work = text.lower()
    hits: dict[str, list[tuple[int, str]]] = {}
    phrases = sorted(((w, sub) for sub, ws in cfg["products"].items() for w in ws), key=lambda x: -len(x[0]))
    for word, sub in phrases:
        for m in re.finditer(_phrase([word]), work):
            hits.setdefault(sub, []).append((m.start(), m.group(0)))
            work = work[: m.start()] + " " * (m.end() - m.start()) + work[m.end() :]
    if not hits:
        return Field(), Field()
    best = max(hits, key=lambda s: (len(hits[s]), -min(p for p, _ in hits[s])))
    words = sorted(hits[best])
    conf = HIGH if len(hits) == 1 or len(hits[best]) > max(len(v) for k, v in hits.items() if k != best) else LOW
    ev = ", ".join(w for _, w in words)
    return Field(cfg["sub_niche_category"][best], conf, ev), Field(best, conf, ev)


# ------------------------------------------------------------------ deadline

DEADLINE_WORDS = re.compile(
    r"(?:\bby|before|deadline|due|till|until|latest|live on|go live|post on|"
    r"deliver(?:ed|y)?|on or before|submit)\b[\s:,-]*(?:the\s+)?(?:on\s+)?$",
    re.I,
)


def _resolve(day: int, month: int, as_of: date, year: int | None = None) -> date | None:
    try:
        d = date(year or as_of.year, month, day)
    except ValueError:
        return None
    if year is None and d < as_of - timedelta(days=7):
        d = d.replace(year=d.year + 1)  # "by 3rd Jan" written in November means next year
    return d


def deadline(text: str, as_of: date, numbers: dict) -> Field:
    found: list[tuple[int, date, str, bool]] = []  # (position, date, evidence, has deadline word)

    def add(m: re.Match, d: date | None, needs_keyword: bool = False):
        if d is None:
            return
        keyword = bool(DEADLINE_WORDS.search(text[max(0, m.start() - 25) : m.start()]))
        if needs_keyword and not keyword:
            return
        found.append((m.start(), d, m.group(0), keyword))

    for m in re.finditer(rf"\b(\d{{1,2}})(?:st|nd|rd|th)?\s*(?:of\s+)?{MONTH}\b", text, re.I):
        add(m, _resolve(int(m.group(1)), _month(m.group(2)), as_of))
    for m in re.finditer(rf"\b{MONTH}\s+(\d{{1,2}})(?:st|nd|rd|th)?\b(?!\s*(?:k|followers|creators))", text, re.I):
        add(m, _resolve(int(m.group(2)), _month(m.group(1)), as_of))
    # 30/11 or 30-11-2026: day first, and only after a deadline word ("3-4 reels" is not a date)
    for m in re.finditer(r"\b(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?\b", text):
        y = m.group(3)
        year = (int(y) + 2000 if len(y) == 2 else int(y)) if y else None
        add(m, _resolve(int(m.group(1)), int(m.group(2)), as_of, year), needs_keyword=True)

    num = r"(\d+|" + "|".join(re.escape(w) for w in numbers) + ")"
    for m in re.finditer(rf"\b(in|within)\s+(?:the\s+next\s+)?{num}\s+(days?|weeks?)\b", text, re.I):
        raw = m.group(2).lower()
        n = int(raw) if raw.isdigit() else numbers[raw]
        # "within 10 days" is a deadline; "brightening in 2 weeks" is a product claim unless a deadline word precedes it
        keyword = m.group(1).lower() == "within" or bool(
            DEADLINE_WORDS.search(text[max(0, m.start() - 25) : m.start()])
        )
        found.append(
            (
                m.start(),
                as_of + timedelta(days=n * (7 if m.group(3).lower().startswith("week") else 1)),
                m.group(0),
                keyword,
            )
        )
    for m in re.finditer(r"\bnext week\b", text, re.I):
        found.append((m.start(), as_of + timedelta(days=7), m.group(0), False))
    for m in re.finditer(r"\b(?:month[\s-]?end|end of (?:the |this )?month|eom)\b", text, re.I):
        last = calendar.monthrange(as_of.year, as_of.month)[1]
        found.append((m.start(), as_of.replace(day=last), m.group(0), True))
    for m in re.finditer(rf"\bend of {MONTH}\b", text, re.I):
        month = _month(m.group(1))
        year = as_of.year + (1 if month < as_of.month else 0)
        found.append((m.start(), date(year, month, calendar.monthrange(year, month)[1]), m.group(0), True))

    if not found:
        return Field()
    # Prefer a date introduced by "by / deadline / before", then the earliest in the text.
    found.sort(key=lambda f: (not f[3], f[0]))
    pos, d, ev, keyword = found[0]
    return Field(d, HIGH if keyword else LOW, ev)


# ------------------------------------------------------------------ must-say points

USAGE = re.compile(
    r"\b(?:use|try|using|apply|test)\b[^.!?\n]{0,40}?\bfor\s+(\d+|a|one|two|three|four)\s+(day|week|month)s?\b", re.I
)
WORDS_TO_N = {"a": 1, "one": 1, "two": 2, "three": 3, "four": 4}
LEAD = re.compile(r"^(?:that|how|about|the fact that|your|our|the|its|it's|it is|on|is)\s+", re.I)
TRAIL = re.compile(r"\s+(?:of|in|on|for|with|from)\s+(?:our|the|this|my|your)\b.*$", re.I)


def _is_stop(part: str, cfg: dict) -> bool:
    p = part.strip().lower()
    if not p:
        return True
    if re.match(_phrase(cfg["must_say"]["stop_words"]), p):
        return True
    if any(kind != "unknown" or "₹" in ev or "k" in ev.lower() for _, kind, ev in amounts(p)):
        return True
    return bool(re.search(rf"\b\d{{1,2}}(?:st|nd|rd|th)?\s+{MONTH}\b|\b\d{{1,2}}/\d{{1,2}}\b", p))


def must_say(text: str, cfg: dict) -> Field:
    ms = cfg["must_say"]
    trig = re.compile(_phrase(ms["triggers"]), re.I)
    neg = re.compile(_phrase(ms["negations"]) + r"\s*$", re.I)
    points: list[str] = []
    evidence: list[str] = []

    for m in USAGE.finditer(text):
        raw = m.group(1).lower()
        n = int(raw) if raw.isdigit() else WORDS_TO_N[raw]
        points.append(f"{n}-{m.group(2).lower()} use")
        evidence.append(m.group(0))

    triggers = list(trig.finditer(text))
    for i, m in enumerate(triggers):
        if neg.search(text[max(0, m.start() - 12) : m.start()]):
            continue  # "don't say chemical free"
        end = triggers[i + 1].start() if i + 1 < len(triggers) else len(text)
        clause = re.split(r"[.!?\n;]", text[m.end() : end])[0]
        for j, phrase in enumerate(ms["protected"]):
            clause = re.sub(re.escape(phrase), f"\x00{j}\x00", clause, flags=re.I)
        for part in re.split(r",|\band\b|&|\+|\|", clause, flags=re.I):
            # Check for a stop before restoring protected phrases: "before and after" is not "before <date>".
            if _is_stop(part.strip(" :-–\"'"), cfg):
                break
            part = re.sub(r"\x00(\d+)\x00", lambda g: ms["protected"][int(g.group(1))], part).strip(" :-–\"'")
            part = TRAIL.sub("", LEAD.sub("", LEAD.sub("", part))).strip()
            if part and len(part) <= 80 and part.lower() not in (p.lower() for p in points):
                points.append(part)
        evidence.append(text[m.start() : m.end() + len(clause)].strip())

    if not points:
        return Field()
    return Field(points, HIGH, " | ".join(evidence))


# ------------------------------------------------------------------ all fields


def parse(text: str, as_of: date | None = None, niche_model=None, embedder=None) -> BriefParse:
    """niche_model + embedder are optional: used only when no product word is found."""
    cfg = brief_config()
    as_of = as_of or date.today()
    t = _norm(text)
    out = BriefParse(version=cfg["version"])

    out.creators_needed = creators_needed(t, cfg)
    out.budget_total_inr, out.budget_per_creator_inr = budget(t, out.creators_needed.value)
    out.min_followers, out.max_followers = follower_range(t)
    out.city = city(t, cfg)
    out.languages = languages(t, cfg)
    out.format = content_format(t, cfg)
    out.deadline = deadline(t, as_of, cfg["numbers"])
    out.must_say = must_say(t, cfg)
    out.category, out.sub_niche = niche(t, cfg)
    # Always added, never read from the brand's text, so nothing they write can remove it.
    out.disclosure = Field(cfg["disclosure"]["text"], FIXED, f"always added: {cfg['disclosure']['source']}")

    if out.sub_niche.value is None and niche_model is not None and embedder is not None:
        pred = niche_model.predict(embedder.passages([text])[0])
        if pred.category != "other":
            out.category = Field(pred.category, LOW, "text similarity")
            out.sub_niche = Field(pred.sub_niche, LOW, "text similarity")
    return out


def to_brief_query(bp: BriefParse, text: str):
    """Hand a confirmed brief to Model 2. In the app the brand confirms or edits each field first."""
    from app.matcher.features import BriefQuery

    v = bp.values()
    per = v["budget_per_creator_inr"]
    if per is None and v["budget_total_inr"] and v["creators_needed"]:
        per = round(v["budget_total_inr"] / v["creators_needed"])
    return BriefQuery(
        text=text,
        category=v["category"],
        sub_niche=v["sub_niche"],
        city=v["city"],
        languages=v["languages"] or [],
        budget_per_creator_inr=per,
        min_followers=v["min_followers"],
        max_followers=v["max_followers"],
    )
