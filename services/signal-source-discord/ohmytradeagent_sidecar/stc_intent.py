"""Out-of-band STC close-intent classifier client (Phase 2 of
PLAN-2026-07-25-stc-intent-classifier).

Given the free-text tail of an STC line, ask a small `/classify` HTTP service
whether the author intends a FULL exit or a PARTIAL, so the orchestrator can (in
a later, per-tenant-gated phase) size the exit off intent instead of only the
keyword matcher — closing the ~50-67% full-close recall gap that left 70% of a
real-money META put open on 2026-07-24.

Design contract (this is the CLIENT half; the P0 `/classify` service implements
the server half):
- Request : ``POST <url>``  body ``{"text": "<tail>"}``
- Response: ``{"intent": "full"|"partial", "confidence": <0..1>}``

FAIL-SAFE IS THE WHOLE POINT. ``classify`` NEVER raises and returns ``None`` on
*any* problem — timeout, transport error, non-2xx, malformed body, an intent
outside {full, partial}, a confidence outside [0, 1], or a confidence below the
configured floor. ``None`` means "unclassified", which leaves the signal exactly
as it is today (keyword-matcher path downstream). A classifier outage can
therefore only ever be a no-op, never a blocked or mis-sized exit.

Async by construction: the caller (`Watcher._emit_signal`) runs on the asyncio
event loop, so this uses ``httpx.AsyncClient`` (already a sidecar dependency) —
never a blocking client that would stall the Discord poll loop.
"""

from __future__ import annotations

import logging

import httpx

from ohmytradeagent_contract.models.copytrade_signal_payload import CloseIntent

_VALID_INTENTS = {"full", "partial"}


class StcIntentClassifier:
    """Owns (or borrows) an ``httpx.AsyncClient`` and POSTs an STC tail to the
    ``/classify`` service. Mirrors ``FanoutRegistryClient``'s owns-or-injected
    client shape so it is unit-testable via ``httpx.MockTransport``."""

    def __init__(
        self,
        *,
        url: str,
        timeout_ms: float,
        min_confidence: float,
        log: logging.Logger,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._url = url
        self._min_confidence = min_confidence
        self._log = log
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(timeout=timeout_ms / 1000.0)

    async def classify(self, tail: str | None) -> tuple[CloseIntent, float] | None:
        """Return ``(intent, confidence)`` for the tail, or ``None`` on any
        failure or when confidence is below the floor. Never raises."""
        try:
            resp = await self._client.post(self._url, json={"text": tail or ""})
            resp.raise_for_status()
            body = resp.json()
            intent = body.get("intent")
            confidence = body.get("confidence")
            if intent not in _VALID_INTENTS:
                return None
            # bool is an int subclass — exclude it so `{"confidence": true}` is malformed.
            if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
                return None
            if not 0.0 <= confidence <= 1.0:
                return None
            if confidence < self._min_confidence:
                return None
            return CloseIntent(intent), float(confidence)
        except Exception as exc:  # noqa: BLE001 — fail-safe: an outage must be a no-op, never propagate.
            self._log.debug("stc-intent classify failed (returning None): %r", exc)
            return None

    async def aclose(self) -> None:
        """Close the underlying client iff this instance created it."""
        if self._owns_client:
            await self._client.aclose()
