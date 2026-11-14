"""Shared test helpers: a fake embedder, matcher candidates, and a realistic CSV fixture."""

import hashlib
from datetime import date, timedelta
from pathlib import Path

import numpy as np

from app.matcher.features import Candidate

TODAY = date(2026, 11, 14)


class FakeEmbedder:
    """Deterministic vectors from a text hash: tests the plumbing, not meaning."""

    name = "fake"
    dim = 384

    def _vec(self, t: str) -> np.ndarray:
        seed = int(hashlib.md5(t.encode()).hexdigest()[:8], 16)
        v = np.random.default_rng(seed).standard_normal(self.dim).astype(np.float32)
        return v / np.linalg.norm(v)

    def passages(self, texts):
        return np.stack([self._vec(t) for t in texts]) if texts else np.zeros((0, self.dim), np.float32)

    queries = passages


def vec(*xs):
    v = np.zeros(384, dtype=np.float32)
    v[: len(xs)] = xs
    return v / np.linalg.norm(v)


def cand(cid, **kw):
    base = dict(
        creator_id=cid,
        handle=f"c{cid}",
        followers=12000,
        city="Bengaluru",
        category="beauty_skincare",
        sub_niche="skincare",
        spoken_language=None,
        caption_languages={"English": 1.0},
        embedding=vec(1, 0),
        quality_label="real",
        quality_score=100,
        activity="active",
        level="NEW",
        median_views=4000,
        completeness=1.0,
    )
    return Candidate(**(base | kw))


CREATORS = """creator_id,instagram_handle,followers,following,bio,quality_label
C-1,honest_mira,12000,600,Oily skin girl | honest skincare reviews | Bengaluru,real
C-2,fast_glow,41000,7400,Beauty | DM for collab,suspicious
C-3,sleepy_sam,8000,500,Haircare and hair fall tips,unsure
"""

GENERIC = ["nice 🔥", "🔥🔥", "wow", "Nice pic", "awesome", "😍😍", "super", "nice"]
QUESTIONS = [
    "does this work on dry skin?",
    "which shade did you use",
    "price please",
    "bought it after your reel!",
    "is it fragrance free?",
    "how long did it last",
    "my sister uses this too",
    "link?",
    "works under makeup?",
    "can men use it",
    "where did you buy",
    "too sticky for me honestly",
    "does it leave white cast",
    "how many days to see results",
    "is it safe in pregnancy",
    "great review",
]


def write_fixture(folder: Path) -> None:
    (folder / "Creators.csv").write_text(CREATORS, encoding="utf-8")
    posts = [
        "post_id,creator_handle,post_url,post_date,post_type,views,likes,comments,caption,paid_partnership_label,brands_tagged"
    ]
    comments = ["post_url,creator_handle,comment_no,comment_text"]
    plans = {
        # honest: views ~30% of followers, real comment ratio, one paid post
        "honest_mira": (2, [(3000 + 150 * i, 300 + 15 * i, 20 + 2 * i) for i in range(10)], QUESTIONS),
        # bought likes: views ~1% of followers, thousands of likes, almost no comments
        "fast_glow": (1, [(300 + 20 * i, 2200 + 10 * i, 3 + i % 3) for i in range(10)], GENERIC * 2),
        # inactive: last post 150 days ago
        "sleepy_sam": (150, [(2500, 250, 25)] * 10, []),
    }
    for handle, (start, rows, texts) in plans.items():
        for i, (views, likes, comments_n) in enumerate(rows):
            url = f"https://www.instagram.com/reel/{handle}{i}/"
            day = TODAY - timedelta(days=start + 3 * i)
            paid = "yes" if (handle == "honest_mira" and i == 0) else "no"
            tag = "dewdrop.skin" if paid == "yes" else ""
            posts.append(
                f"P,{handle},{url},{day},reel,{views},{likes},{comments_n},sunscreen routine day {i} #skincare,{paid},{tag}"
            )
            if i == 0:
                comments += [f"{url},{handle},{n},{t}" for n, t in enumerate(texts, 1)]
    (folder / "Posts.csv").write_text("\n".join(posts) + "\n", encoding="utf-8")
    (folder / "Comments.csv").write_text("\n".join(comments) + "\n", encoding="utf-8")
