"""Which languages a creator writes in, from bio and captions.

This is the language of the *text*. Many creators speak Kannada in reels but caption in English,
so a human-entered primary_language always overrides this.
"""

import re
from collections import Counter
from dataclasses import dataclass

from app.profile.settings import profile_config

_WORD = re.compile(r"[a-z]+")


@dataclass
class LanguageResult:
    shares: dict[str, float]
    primary: str | None
    signals: int


def detect(texts: list[str], cfg: dict | None = None) -> LanguageResult:
    cfg = cfg or profile_config()["language"]
    counts: Counter[str] = Counter()
    joined = "\n".join(t for t in texts if t)

    for lang, (lo, hi) in cfg["scripts"].items():
        chars = sum(1 for ch in joined if lo <= ord(ch) <= hi)
        # ~5 characters of script is about one word of evidence
        counts[lang] += chars // 5

    word_to_lang = {w: lang for lang, words in cfg["words"].items() for w in words}
    for w in _WORD.findall(joined.lower()):
        if w in word_to_lang:
            counts[word_to_lang[w]] += 1

    total = sum(counts.values())
    if total < cfg["min_signals"]:
        return LanguageResult({}, None, total)
    shares = {lang: round(n / total, 3) for lang, n in counts.most_common() if n}
    return LanguageResult(shares, next(iter(shares)), total)
