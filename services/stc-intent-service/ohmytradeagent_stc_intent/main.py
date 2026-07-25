"""FastAPI app exposing the STC close-intent ``/classify`` backend.

HTTP contract (fixed — the sidecar StcIntentClassifier client already posts/expects this):
- ``POST /classify``  ``{"text": "<stc tail>"}``  ->  ``{"intent": "full"|"partial", "confidence": <0..1>}``
- ``GET /health``  ->  200 ``{"status": "ok"}``  (k8s probe).

The model + centroids are built once at startup (lifespan) and reused per request.
"""

from __future__ import annotations

from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel, Field

from ohmytradeagent_stc_intent.classifier import Classifier


class ClassifyRequest(BaseModel):
    text: str = Field(default="", description="Free-text tail of an STC line; may be empty.")


class ClassifyResponse(BaseModel):
    intent: str
    confidence: float


# Populated once at startup; a single Classifier is shared across requests.
_state: dict[str, Classifier] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    _state["classifier"] = Classifier()
    yield
    _state.clear()


app = FastAPI(title="stc-intent-service", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/classify", response_model=ClassifyResponse)
async def classify(req: ClassifyRequest) -> ClassifyResponse:
    intent, confidence = _state["classifier"].classify(req.text)
    return ClassifyResponse(intent=intent, confidence=confidence)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
