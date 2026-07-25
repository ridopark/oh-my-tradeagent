"""Torch-free semantic STC close-intent classifier.

Approach (see PLAN-2026-07-25-stc-intent-classifier):
- Embed a curated set of labeled example tails per class with fastembed
  (Qdrant's onnxruntime backend for ``all-MiniLM-L6-v2`` — NO torch), once, at
  startup, and average each class's L2-normalized vectors into a **centroid**.
- Per request: embed the text, take cosine similarity (dot of normalized vectors)
  to each centroid, softmax the two similarities with a temperature, and pick the
  higher as ``intent`` with the softmax probability as ``confidence``.

~10ms CPU, ~300-500MB RAM, torch-free. Deterministic: the model is a fixed set of
ONNX weights and the seed centroids are frozen at construction time.
"""

from __future__ import annotations

import numpy as np
from fastembed import TextEmbedding

# The embedding model. all-MiniLM-L6-v2 is small (384-dim), fast on CPU, and
# available in fastembed's onnxruntime catalogue with no torch dependency.
MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"

# Softmax temperature over the two cosine similarities. Cosine values for this
# model sit in a narrow band (~0.2-0.9), so a temperature well above 1 is needed
# to turn small margins into a usable confidence spread. 10 gives a decisive-but-
# not-saturated probability.
SOFTMAX_TEMPERATURE = 10.0

# Curated, labeled STC tails from the audit corpus. Kept as a module constant so
# the seed set is easy to extend later without touching the classifier logic.
SEED_TAILS: dict[str, list[str]] = {
    "full": [
        "out",
        "all out",
        "all out good enough for me",
        "closing to keep my winrate for the week",
        "cutting the rest running out of time",
        "I'm dumping it, taking the safe exit",
        "not letting this one go red",
        "stop hit on the remainder, not letting it go red",
        "bears can't finish taking the W. Don't want to see it go red again",
        "done with this one, taking the L",
        "stopped out",
    ],
    "partial": [
        "partial",
        "partial taking a few more",
        "partial, half out keeping half",
        "trim half here",
        "sold a third, holding the rest",
        "holding most",
        "PARTIAL easy money",
        "taking some here",
        "scaling out 25%",
        "partial 1/2 position now",
    ],
}

# Fixed intent order (softmax indices stable/deterministic) DERIVED from the seed
# dict's keys — one source of truth, so adding a class to SEED_TAILS can't drift
# from a separately-maintained tuple.
_INTENTS: tuple[str, ...] = tuple(SEED_TAILS)


def _l2_normalize(mat: np.ndarray) -> np.ndarray:
    """Row-wise L2 normalize; guard the zero-vector edge case."""
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms = np.where(norms == 0.0, 1.0, norms)
    return mat / norms


class Classifier:
    """Loads the fastembed model once and builds per-class centroids from the
    seed tails. ``classify`` is pure/deterministic given a fixed model."""

    def __init__(self) -> None:
        self._model = TextEmbedding(model_name=MODEL_NAME)
        # Build one centroid per intent, in the fixed _INTENTS order.
        centroids = []
        for intent in _INTENTS:
            vecs = self._embed(SEED_TAILS[intent])
            centroid = vecs.mean(axis=0, keepdims=True)
            centroids.append(centroid)
        # Stack to (n_intents, dim) and normalize so cosine == dot product.
        self._centroids = _l2_normalize(np.vstack(centroids))

    def _embed(self, texts: list[str]) -> np.ndarray:
        """Embed a list of texts to an L2-normalized (n, dim) float array."""
        # fastembed yields generators of np.ndarray rows; materialize + stack.
        raw = np.vstack([np.asarray(v, dtype=np.float32) for v in self._model.embed(texts)])
        return _l2_normalize(raw)

    def classify(self, text: str) -> tuple[str, float]:
        """Return ``(intent, confidence)`` for ``text``.

        Empty/whitespace text still gets a best-effort verdict (the embedding of
        an empty string is a valid vector); confidence is naturally low because
        it sits near-equidistant from both centroids.
        """
        vec = self._embed([text or ""])  # (1, dim), normalized
        # Cosine similarity to each centroid == dot of normalized vectors.
        sims = (vec @ self._centroids.T).ravel()  # (n_intents,)
        probs = self._softmax(sims * SOFTMAX_TEMPERATURE)
        idx = int(np.argmax(probs))
        return _INTENTS[idx], float(probs[idx])

    @staticmethod
    def _softmax(x: np.ndarray) -> np.ndarray:
        z = x - np.max(x)  # numerical stability
        e = np.exp(z)
        return e / e.sum()
