"""Discord channel watcher.

Single-responsibility: poll the channel DOM, parse new messages, hand each
parsed signal to an ``Emitter``. The Emitter abstraction is the seam that
makes the watcher unit-testable without Temporal — see ``tests/test_watcher.py``.

The reference (oh-my-opentrade/services/discord-copytrade/watcher.py) persisted
a ``seen_ids.json`` file as an idempotency layer. We drop that here: Temporal's
``WorkflowIDReusePolicy=REJECT_DUPLICATE`` keyed on the signal_id is the durable
dedupe. The in-memory ``OrderedDict`` LRU below is a cost optimization — it
suppresses redundant ``start_workflow`` RPCs across DOM polls. Two replicas
running concurrently are safe: at most one of them wins the Temporal start;
the other gets a ``deduped=True`` ``EmitResult`` back from the Emitter.
"""

from __future__ import annotations

import asyncio
import logging
import pathlib
from collections import OrderedDict
from datetime import datetime, timezone

from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CloseIntent,
    CopytradeSignalPayload,
    Right,
)
from playwright.async_api import Page

from .discord_dom import MESSAGES_LI_SELECTOR, extract_recent
from .emitter import Emitter
from .stc_intent import StcIntentClassifier
from .parser import ParsedSignal, parse_message


class _BoundedSeenLRU:
    """Single-purpose data class: a bounded ordered set used as an LRU cache.

    Extracted as a class so the eviction policy lives in one place rather than
    being inlined in the watcher loop (SRP). The semantics intentionally match
    only the watcher's needs — adding, membership testing, eviction on cap —
    no general-purpose collection API.
    """

    def __init__(self, capacity: int) -> None:
        if capacity <= 0:
            raise ValueError("capacity must be positive")
        self._capacity = capacity
        self._items: OrderedDict[str, None] = OrderedDict()

    def add(self, key: str) -> None:
        if key in self._items:
            self._items.move_to_end(key)
            return
        self._items[key] = None
        if len(self._items) > self._capacity:
            self._items.popitem(last=False)

    def __contains__(self, key: object) -> bool:
        return key in self._items

    def __len__(self) -> int:
        return len(self._items)


class Watcher:
    """Polling loop. Construct with a configured Emitter and state dir; call
    run_on_page() with a page from the caller-owned browser."""

    DEFAULT_LRU_CAPACITY = 500
    INITIAL_SCRAPE_LIMIT = 50
    TICK_SCRAPE_LIMIT = 25
    DOM_READY_TIMEOUT_MS = 30_000

    def __init__(
        self,
        *,
        channel_url: str,
        state_dir: pathlib.Path,
        emitter: Emitter,
        tenant_id: str,
        strategy_id: str,
        log: logging.Logger,
        poll_interval_secs: float,
        additional_targets: list[tuple[str, str]] | None = None,
        lru_capacity: int = DEFAULT_LRU_CAPACITY,
        intent_classifier: StcIntentClassifier | None = None,
    ) -> None:
        self._channel_url = channel_url
        self._state_dir = state_dir
        self._emitter = emitter
        self._tenant_id = tenant_id
        self._strategy_id = strategy_id
        # STC close-intent enrichment (Phase 2, dark by default). None => disabled: signals are
        # emitted exactly as before. When present, an STC tail is classified ONCE per signal (below,
        # before the fan-out loop) and the result rides on every target's payload. The classifier is
        # fail-safe: any failure yields None, i.e. an absent close_intent = today's behavior.
        self._intent_classifier = intent_classifier
        # Fan-out targets: the primary (tenant_id, strategy_id) plus any additional ones, so ONE
        # browser/Discord session feeds several tenants watching the same channel (e.g. a live tenant
        # and a paper shadow). Each parsed signal is emitted once per target; the per-target workflow
        # id (t-<tenant>/s-<strategy>/sig/<id>) keeps them distinct and independently deduped.
        self._targets: list[tuple[str, str]] = [
            (tenant_id, strategy_id),
            *(additional_targets or []),
        ]
        self._log = log
        self._poll_interval = poll_interval_secs
        self._heartbeat_path = state_dir / "heartbeat"
        self._seen = _BoundedSeenLRU(lru_capacity)

    @property
    def targets(self) -> list[tuple[str, str]]:
        """Current fan-out targets (the live list reference — do not mutate)."""
        return self._targets

    def update_targets(self, additional_targets: list[tuple[str, str]]) -> None:
        """Atomically swap the fan-out target set to union(primary, given),
        deduped and order-stable (primary first, then given order).

        Used by the registry refresher (Phase B2) to pick up newly-enabled
        tenants without a restart. The swap is a single reference REBIND — an
        in-flight ``_emit_signal`` snapshots ``self._targets`` into a local at
        entry, so a mid-emit refresh can never tear its iteration. The primary
        is always included, so a poll can never drop the fan-out to empty.
        """
        primary = (self._tenant_id, self._strategy_id)
        # dict.fromkeys is order-stable dedup: primary first, then given order.
        self._targets = list(dict.fromkeys((primary, *additional_targets)))

    async def run_on_page(self, page: Page) -> None:
        """Run the poll loop on a caller-owned page. ``main`` owns the browser
        and context so the signal and watchlist watchers share ONE Chromium
        (two tabs) — a second browser would roughly double memory.
        """
        self._log.info("navigating to %s", self._channel_url)
        await page.goto(self._channel_url, wait_until="domcontentloaded")
        await page.wait_for_selector(
            MESSAGES_LI_SELECTOR, timeout=self.DOM_READY_TIMEOUT_MS
        )

        # Seed the LRU with currently-visible message IDs so a fresh
        # process doesn't replay the channel backlog. Identical to the
        # reference's "seed seen_ids on startup" behaviour.
        initial = await extract_recent(page, limit=self.INITIAL_SCRAPE_LIMIT)
        for m in initial:
            self._seen.add(m.message_id)
        self._log.info("seeded %d existing messages", len(self._seen))

        while True:
            try:
                await self._tick(page)
            except Exception:  # noqa: BLE001
                self._log.exception("tick error")
            self._heartbeat_path.touch()
            await asyncio.sleep(self._poll_interval)

    async def _tick(self, page: Page) -> None:
        msgs = await extract_recent(page, limit=self.TICK_SCRAPE_LIMIT)
        for m in msgs:
            if m.message_id in self._seen:
                continue
            self._seen.add(m.message_id)
            if not m.content.strip():
                continue
            parsed = parse_message(m.content)
            if not parsed:
                self._log.debug("no signal in message %s", m.message_id)
                continue
            for i, sig in enumerate(parsed):
                await self._emit_signal(
                    message_id=m.message_id,
                    line_index=i,
                    author=m.author,
                    posted_at_iso=m.timestamp_iso,
                    sig=sig,
                )

    async def _emit_signal(
        self,
        *,
        message_id: str,
        line_index: int,
        author: str,
        posted_at_iso: str | None,
        sig: ParsedSignal,
    ) -> None:
        """Fan one parsed signal out to every configured (tenant, strategy) target."""
        # Classify the STC close-intent ONCE per signal, before the fan-out loop — the same tail
        # goes to every target, and _build_payload runs once per target, so classifying here (not in
        # _build_payload) fires a single /classify call instead of one per target. Only STC lines are
        # classified; the classifier is fail-safe (any failure -> None -> unchanged behavior).
        close_intent: CloseIntent | None = None
        close_confidence: float | None = None
        if self._intent_classifier is not None and sig.action == "STC":
            result = await self._intent_classifier.classify(sig.tail)
            if result is not None:
                close_intent, close_confidence = result
        # Snapshot the target list reference once, so a concurrent registry
        # refresh (which atomically REBINDS self._targets) can't tear this
        # fan-out's iteration mid-signal.
        targets = self._targets
        for tenant_id, strategy_id in targets:
            payload = self._build_payload(
                message_id=message_id,
                line_index=line_index,
                author=author,
                posted_at_iso=posted_at_iso,
                sig=sig,
                tenant_id=tenant_id,
                strategy_id=strategy_id,
                close_intent=close_intent,
                close_confidence=close_confidence,
            )
            await self._emit_one(payload)

    def _build_payload(
        self,
        *,
        message_id: str,
        line_index: int,
        author: str,
        posted_at_iso: str | None,
        sig: ParsedSignal,
        tenant_id: str | None = None,
        strategy_id: str | None = None,
        close_intent: CloseIntent | None = None,
        close_confidence: float | None = None,
    ) -> CopytradeSignalPayload:
        posted_at = (
            datetime.fromisoformat(posted_at_iso)
            if posted_at_iso
            else datetime.now(timezone.utc)
        )
        return CopytradeSignalPayload(
            schema_version=1,
            tenant_id=tenant_id or self._tenant_id,
            strategy_id=strategy_id or self._strategy_id,
            signal_id=f"{message_id}:{line_index}",
            message_id=message_id,
            author=author,
            posted_at=posted_at,
            action=Action(sig.action),
            ticker=sig.ticker,
            expiry=sig.expiry,
            strike=sig.strike,
            right=Right(sig.right),
            price=sig.price,
            tail=sig.tail,
            raw_line=sig.raw_line,
            close_intent=close_intent,
            close_confidence=close_confidence,
        )

    async def _emit_one(self, payload: CopytradeSignalPayload) -> None:
        result = await self._emitter.emit(payload)
        if result.deduped:
            self._log.info(
                "deduped %s (workflow_id=%s)", payload.signal_id, result.workflow_id
            )
            return
        self._log.info(
            "emitted %s %s %s %s%s @ %s (author=%s, workflow_id=%s)",
            payload.action.value,
            payload.ticker,
            payload.expiry,
            payload.strike,
            payload.right.value,
            payload.price,
            payload.author,
            result.workflow_id,
        )
