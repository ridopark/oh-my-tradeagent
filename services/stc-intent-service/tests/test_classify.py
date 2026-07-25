"""Contract + behavior tests for the STC close-intent /classify service.

Uses FastAPI's TestClient, which drives the lifespan (so the real Classifier —
fastembed model + centroids — is built once). The model downloads on first use;
if the sandbox blocks the HF/fastembed fetch these tests cannot run here and must
be validated where network is available.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from ohmytradeagent_stc_intent.main import app


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def _classify(client, text: str) -> dict:
    resp = client.post("/classify", json={"text": text})
    assert resp.status_code == 200
    body = resp.json()
    # Response schema matches the contract exactly.
    assert set(body.keys()) == {"intent", "confidence"}
    assert body["intent"] in {"full", "partial"}
    assert isinstance(body["confidence"], float)
    assert 0.0 <= body["confidence"] <= 1.0
    return body


def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


@pytest.mark.parametrize(
    "text",
    [
        "bears can't finish taking the W. Don't want to see it go red again",  # the incident tail
        "all out good enough for me",
        "out",
    ],
)
def test_full_intent(client, text):
    body = _classify(client, text)
    assert body["intent"] == "full", f"expected full for {text!r}, got {body}"


@pytest.mark.parametrize(
    "text",
    [
        "partial taking a few more",
        "trim half",
        "holding most",
    ],
)
def test_partial_intent(client, text):
    body = _classify(client, text)
    assert body["intent"] == "partial", f"expected partial for {text!r}, got {body}"


def test_empty_text_is_valid(client):
    # A bare STC (empty tail) must not error; returns a valid, low-ish confidence verdict.
    body = _classify(client, "")
    assert body["intent"] in {"full", "partial"}
    # Equidistant-ish from both centroids => confidence near the 0.5 floor, not saturated.
    assert body["confidence"] < 0.9
