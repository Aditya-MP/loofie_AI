from functools import lru_cache

import numpy as np
from sentence_transformers import SentenceTransformer

from app.config import get_settings
from app.ml.device import get_device


class Embedder:
    """Turns text into unit-length vectors on the GPU.

    e5 models expect "query: " on the searching side (briefs, niche descriptions) and
    "passage: " on the searched side (creator bios and captions).
    """

    def __init__(self, model_name: str | None = None):
        self.name = model_name or get_settings().embed_model
        self.device = get_device()
        self.model = SentenceTransformer(self.name, device=self.device)
        if self.device.startswith("cuda"):
            self.model.half()
        self.dim = self.model.get_embedding_dimension()

    def _encode(self, texts: list[str], prefix: str) -> np.ndarray:
        if not texts:
            return np.zeros((0, self.dim), dtype=np.float32)
        vecs = self.model.encode(
            [prefix + t for t in texts],
            batch_size=64,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        return vecs.astype(np.float32)

    def passages(self, texts: list[str]) -> np.ndarray:
        return self._encode(texts, "passage: ")

    def queries(self, texts: list[str]) -> np.ndarray:
        return self._encode(texts, "query: ")


@lru_cache
def get_embedder() -> Embedder:
    return Embedder()
