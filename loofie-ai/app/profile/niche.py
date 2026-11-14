"""Day-1 niche prediction with no training data: compare the creator's text with short written
descriptions of each niche. Replaced by a classifier trained on labelled creators once it scores
better (the learning step)."""

from dataclasses import dataclass

import numpy as np

from app.profile.settings import profile_config

OTHER = "other"


@dataclass
class NichePrediction:
    category: str
    category_confidence: float
    sub_niche: str | None
    sub_niche_confidence: float | None
    other_niche: str | None
    top_labels: dict[str, float]
    unsure: bool


class ZeroShotNiche:
    def __init__(self, embedder, cfg: dict | None = None):
        cfg = cfg or profile_config()["niche"]
        self.version = cfg["version"]
        self.temperature = cfg["temperature"]
        self.unsure_below = cfg["unsure_below"]
        self.labels = list(cfg["labels"])
        self.category_of = {name: spec["category"] for name, spec in cfg["labels"].items()}

        phrases, owners = [], []
        for i, name in enumerate(self.labels):
            for phrase in cfg["labels"][name]["phrases"]:
                phrases.append(phrase)
                owners.append(i)
        self.phrase_vecs = embedder.queries(phrases)
        self.owners = np.array(owners)

    def label_probs(self, vec: np.ndarray) -> np.ndarray:
        sims = self.phrase_vecs @ vec
        best = np.array([sims[self.owners == i].max() for i in range(len(self.labels))])
        z = np.exp((best - best.max()) / self.temperature)
        return z / z.sum()

    def predict(self, vec: np.ndarray) -> NichePrediction:
        """The category is chosen by total probability, so a creator split across several beauty
        sub-niches is not lost to one stronger non-beauty label."""
        probs = self.label_probs(vec)
        order = np.argsort(-probs)
        by_category: dict[str, float] = {}
        for name, p in zip(self.labels, probs, strict=True):
            by_category[self.category_of[name]] = by_category.get(self.category_of[name], 0.0) + float(p)
        category = max(by_category, key=by_category.get)
        category_conf = by_category[category]
        best_in = next(i for i in order if self.category_of[self.labels[i]] == category)
        is_sub = category != OTHER
        return NichePrediction(
            category=category,
            category_confidence=round(category_conf, 3),
            sub_niche=self.labels[best_in] if is_sub else None,
            sub_niche_confidence=round(float(probs[best_in]), 3) if is_sub else None,
            other_niche=None if is_sub else self.labels[best_in],
            top_labels={self.labels[i]: round(float(probs[i]), 3) for i in order[:5]},
            unsure=category_conf < self.unsure_below,
        )
