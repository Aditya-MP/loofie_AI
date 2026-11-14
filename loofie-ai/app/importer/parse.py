"""Turn hand-typed spreadsheet cells into clean values. Every function returns None for a blank cell
and raises ParseError for a value it cannot trust, so bad cells are reported, never guessed."""

import re
from datetime import date, datetime


class ParseError(ValueError):
    pass


BLANKS = {"", "n/a", "na", "-", "none", "null", "not verified", "confirm with creator"}


def text(v) -> str | None:
    if v is None:
        return None
    s = str(v).strip()
    return None if s.lower() in BLANKS else s


_HANDLE_IN_URL = re.compile(r"instagram\.com/([A-Za-z0-9._]+)", re.I)
_HANDLE = re.compile(r"^[a-z0-9._]{1,30}$")


def handle(v) -> str | None:
    """Accepts 'name', '@name' or a profile link. A post link is rejected, not guessed from."""
    s = text(v)
    if s is None:
        return None
    m = _HANDLE_IN_URL.search(s)
    if m:
        if m.group(1).lower() in {"reel", "reels", "p", "tv", "stories"}:
            raise ParseError(f"this is a post link, not a profile: {v!r}")
        s = m.group(1)
    s = s.strip().lstrip("@").lower()
    if not _HANDLE.match(s):
        raise ParseError(f"not a valid Instagram handle: {v!r}")
    return s


def handles(v) -> list[str]:
    s = text(v)
    if s is None:
        return []
    return [h for part in re.split(r"[,\s]+", s) if (h := handle(part))]


_SUFFIX = {"": 1, "k": 1_000, "m": 1_000_000, "l": 100_000, "lakh": 100_000, "cr": 10_000_000}
_NUMBER = re.compile(r"(\d+(?:\.\d+)?)\s*(k|m|l|lakh|cr)?")


def integer(v) -> int | None:
    """Accepts 12400, '12,400', '12.4K', '1.2M', '1.5 lakh'."""
    if isinstance(v, bool):
        raise ParseError(f"expected a number, got {v!r}")
    if v is None:
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, float):
        return round(v)
    s = text(v)
    if s is None:
        return None
    s = s.lower().replace(",", "").replace("₹", "").replace("rs.", "").replace("rs", "").strip()
    m = _NUMBER.fullmatch(s)
    if not m:
        raise ParseError(f"not a number: {v!r}")
    return round(float(m.group(1)) * _SUFFIX[m.group(2) or ""])


def price_range(v) -> tuple[int | None, int | None]:
    s = text(v)
    if s is None:
        return None, None
    parts = [p for p in re.split(r"\s*(?:-|–|to)\s*", str(s)) if p]
    nums = [integer(p) for p in parts]
    if not 1 <= len(nums) <= 2:
        raise ParseError(f"price range should look like 299-899: {v!r}")
    return min(nums), max(nums)


_YES = {"yes", "y", "true", "1"}
_NO = {"no", "n", "false", "0"}


def yes_no(v) -> bool | None:
    if isinstance(v, bool):
        return v
    s = text(v)
    if s is None:
        return None
    s = s.lower()
    if s in _YES:
        return True
    if s in _NO:
        return False
    raise ParseError(f"expected yes or no: {v!r}")


_DATE_FORMATS = ("%Y-%m-%d", "%d-%m-%Y", "%d/%m/%Y", "%d.%m.%Y", "%d %b %Y", "%d %B %Y")


def a_date(v) -> date | None:
    """ISO first; slashed and dashed dates are read day-first, as written in India."""
    if isinstance(v, datetime):
        return v.date()
    if isinstance(v, date):
        return v
    s = text(v)
    if s is None:
        return None
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            pass
    raise ParseError(f"not a date (use 2026-11-14): {v!r}")


def choice(v, allowed: set[str]) -> str | None:
    s = text(v)
    if s is None:
        return None
    for a in allowed:
        if s.lower() == a.lower():
            return a
    raise ParseError(f"{v!r} is not one of {sorted(allowed)}")


def url(v) -> str | None:
    s = text(v)
    if s is None:
        return None
    if not re.match(r"https?://", s, re.I):
        raise ParseError(f"not a link: {v!r}")
    return s


def post_url(v) -> str | None:
    """Strips tracking parameters so the same post always has the same key."""
    s = url(v)
    if s is None:
        return None
    if "instagram.com" not in s.lower():
        raise ParseError(f"post link must be an instagram.com link: {v!r}")
    s = s.split("?")[0].split("#")[0]
    return s if s.endswith("/") else s + "/"
