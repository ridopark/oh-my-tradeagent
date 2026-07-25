"""Phase 2 tests: STC close-intent enrichment in the sidecar.

Two layers:
- StcIntentClassifier unit tests — the /classify HTTP client is fail-safe:
  every failure mode (timeout, non-2xx, garbage body, bad intent, out-of-range
  or low confidence) returns None, never raises.
- Watcher integration tests — enrichment is dark unless a classifier is
  injected, only fires for STC, and any classifier miss leaves close_intent
  absent (== today's behavior). Reproduces the 2026-07-24 META incident tail.

asyncio auto-mode (pyproject) → async tests need no decorator.
"""

from __future__ import annotations

import logging
import pathlib
from datetime import date

import httpx
from ohmytradeagent_contract.models.copytrade_signal_payload import CloseIntent

from ohmytradeagent_sidecar.emitter import InMemoryEmitter
from ohmytradeagent_sidecar.parser import ParsedSignal
from ohmytradeagent_sidecar.stc_intent import StcIntentClassifier
from ohmytradeagent_sidecar.watcher import Watcher

LOG = logging.getLogger("test")
INCIDENT_TAIL = "bears can't finish taking the W. Don't want to see it go red again"


# ---- factories -------------------------------------------------------------
def _classifier(handler, *, min_conf: float = 0.0) -> StcIntentClassifier:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://stc")
    return StcIntentClassifier(
        url="http://stc/classify", timeout_ms=300, min_confidence=min_conf, log=LOG, client=client
    )


def _ok(intent: str = "full", confidence: float = 0.95):
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"intent": intent, "confidence": confidence})

    return handler


def _timeout(_request: httpx.Request) -> httpx.Response:
    raise httpx.TimeoutException("slow")


def _garbage(_request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, text="not json at all")


def _server_error(_request: httpx.Request) -> httpx.Response:
    return httpx.Response(500, json={"intent": "full", "confidence": 0.9})


def _watcher(tmp_path: pathlib.Path, classifier=None) -> Watcher:
    return Watcher(
        channel_url="https://discord/channel/x",
        state_dir=tmp_path,
        emitter=InMemoryEmitter(),
        tenant_id="dev",
        strategy_id="copytrade-v1",
        log=LOG,
        poll_interval_secs=1.0,
        intent_classifier=classifier,
    )


def _stc(tail: str) -> ParsedSignal:
    return ParsedSignal(
        action="STC", ticker="META", expiry=date(2026, 7, 27), strike=590.0,
        right="P", price=2.37, tail=tail, raw_line=f"STC META 7/27 590P @ 2.37 {tail}",
    )


def _bto() -> ParsedSignal:
    return ParsedSignal(
        action="BTO", ticker="NVDA", expiry=date(2026, 7, 27), strike=140.0,
        right="C", price=2.30, tail="starter", raw_line="BTO NVDA 7/27 140C @ 2.30 starter",
    )


async def _emit(w: Watcher, sig: ParsedSignal) -> None:
    await w._emit_signal(  # type: ignore[attr-defined]
        message_id="m", line_index=0, author="a", posted_at_iso="2026-07-24T13:00:00Z", sig=sig
    )


class _SpyClassifier:
    """Duck-typed stand-in that records whether classify() was awaited."""

    def __init__(self) -> None:
        self.calls = 0

    async def classify(self, tail):  # noqa: ANN001
        self.calls += 1
        return CloseIntent.full, 0.99


# ---- classifier unit tests -------------------------------------------------
async def test_classifier_returns_full() -> None:
    assert await _classifier(_ok("full", 0.95)).classify("all out") == (CloseIntent.full, 0.95)


async def test_classifier_returns_partial() -> None:
    assert await _classifier(_ok("partial", 0.8)).classify("trim half") == (CloseIntent.partial, 0.8)


async def test_classifier_timeout_none() -> None:
    assert await _classifier(_timeout).classify("out") is None


async def test_classifier_bad_body_none() -> None:
    assert await _classifier(_garbage).classify("out") is None


async def test_classifier_non_2xx_none() -> None:
    assert await _classifier(_server_error).classify("out") is None


async def test_classifier_bad_intent_none() -> None:
    # An intent outside {full, partial} is malformed → None (never a ValidationError downstream).
    assert await _classifier(_ok("scale", 0.9)).classify("scaling out") is None


async def test_classifier_out_of_range_confidence_none() -> None:
    assert await _classifier(_ok("full", 1.5)).classify("out") is None


async def test_classifier_low_confidence_none() -> None:
    assert await _classifier(_ok("full", 0.4), min_conf=0.7).classify("maybe out") is None


# ---- watcher integration tests ---------------------------------------------
async def test_enrich_disabled_leaves_intent_none(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path, classifier=None)
    await _emit(w, _stc(INCIDENT_TAIL))
    emitted = w._emitter.emitted  # type: ignore[attr-defined]
    assert len(emitted) == 1
    assert emitted[0].close_intent is None
    assert emitted[0].close_confidence is None


async def test_enrich_stc_sets_intent(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path, classifier=_classifier(_ok("full", 0.9)))
    await _emit(w, _stc("all out"))
    emitted = w._emitter.emitted  # type: ignore[attr-defined]
    assert emitted[0].close_intent == CloseIntent.full
    assert emitted[0].close_confidence == 0.9


async def test_enrich_incident_tail(tmp_path: pathlib.Path) -> None:
    # The 2026-07-24 tail that matched no keyword → with enrichment on, close_intent=full rides the
    # signal, giving the orchestrator (a later phase) what the keyword matcher missed.
    w = _watcher(tmp_path, classifier=_classifier(_ok("full", 0.88)))
    await _emit(w, _stc(INCIDENT_TAIL))
    emitted = w._emitter.emitted  # type: ignore[attr-defined]
    assert emitted[0].close_intent == CloseIntent.full


async def test_enrich_non_stc_skips(tmp_path: pathlib.Path) -> None:
    spy = _SpyClassifier()
    w = _watcher(tmp_path, classifier=spy)
    await _emit(w, _bto())
    emitted = w._emitter.emitted  # type: ignore[attr-defined]
    assert spy.calls == 0  # the sig.action == "STC" gate short-circuits before any classify()
    assert emitted[0].close_intent is None


async def test_enrich_timeout_falls_back_none(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path, classifier=_classifier(_timeout))
    await _emit(w, _stc("out"))  # must not raise
    emitted = w._emitter.emitted  # type: ignore[attr-defined]
    assert emitted[0].close_intent is None
    assert emitted[0].close_confidence is None


async def test_enrich_low_confidence_none(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path, classifier=_classifier(_ok("full", 0.3), min_conf=0.7))
    await _emit(w, _stc("might be out"))
    emitted = w._emitter.emitted  # type: ignore[attr-defined]
    assert emitted[0].close_intent is None
