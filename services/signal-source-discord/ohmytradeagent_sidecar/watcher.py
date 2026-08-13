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
from datetime import datetime, timezone

from ohmytradeagent_contract.models.copytrade_derisk_payload import CopytradeDeriskPayload
from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CloseIntent,
    CopytradeSignalPayload,
    Right,
)
from playwright.async_api import Page

from .derisk_tracker import RecentBto, RecentBtoTracker
from .discord_dom import MESSAGES_LI_SELECTOR, extract_recent
from .emitter import DeriskEmitter, Emitter
from .stc_intent import StcIntentClassifier
from .seen_lru import BoundedSeenLRU
from .parser import DeriskCue, ParsedSignal, classify_derisk, parse_message


def _parse_posted_at(posted_at_iso: str | None) -> datetime:
    """Message timestamp as an aware datetime, falling back to now(UTC)."""
    return (
        datetime.fromisoformat(posted_at_iso)
        if posted_at_iso
        else datetime.now(timezone.utc)
    )


# The LRU itself lives in seen_lru.py so chat_watcher can reuse it without importing this module
# (which would pull in temporalio). Aliased to keep the original private name.
_BoundedSeenLRU = BoundedSeenLRU


class Watcher:
    """Polling loop. Construct with a configured Emitter and state dir; call
    run_on_page() with a page from the caller-owned browser."""

    DEFAULT_LRU_CAPACITY = 500
    INITIAL_SCRAPE_LIMIT = 50
    TICK_SCRAPE_LIMIT = 25
    DOM_READY_TIMEOUT_MS = 30_000
    # De-risk attribution look-back (PLAN-2026-08-04): a cue can only attach to a
    # BTO from the same author within this window, so it never grabs a stale entry.
    DERISK_WINDOW_SECS = 3600.0
    DERISK_PER_AUTHOR_CAP = 20

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
        derisk_emitter: DeriskEmitter | None = None,
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
        # De-risk-on-follow-up-cue (PLAN-2026-08-04, dark by default). None => disabled: non-grammar
        # messages are ignored exactly as before, and no per-author BTO history is kept. When
        # present, each parsed BTO is recorded per author and a later "0-or-hero"/"use-your-own-stop"
        # message trims + arms the attributed open position via a CopytradeDeriskWorkflow start.
        self._derisk_emitter = derisk_emitter
        self._tracker: RecentBtoTracker | None = (
            RecentBtoTracker(
                window_secs=self.DERISK_WINDOW_SECS,
                per_author_cap=self.DERISK_PER_AUTHOR_CAP,
            )
            if derisk_emitter is not None
            else None
        )
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
                # No BTO/STC/AVG grammar. When de-risk is enabled, a non-grammar message may be a
                # "0-or-hero"/"use-your-own-stop" escalation attributable to a preceding BTO.
                if self._derisk_emitter is not None:
                    await self._handle_derisk_cue(
                        message_id=m.message_id,
                        author=m.author,
                        posted_at_iso=m.timestamp_iso,
                        content=m.content,
                    )
                else:
                    self._log.debug("no signal in message %s", m.message_id)
                continue
            # Record BTOs per author BEFORE emitting, so a de-risk cue in a later poll can attribute
            # to them (no-op when de-risk is disabled — self._tracker is None).
            self._record_btos(
                author=m.author,
                message_id=m.message_id,
                posted_at_iso=m.timestamp_iso,
                parsed=parsed,
            )
            for i, sig in enumerate(parsed):
                await self._emit_signal(
                    message_id=m.message_id,
                    line_index=i,
                    author=m.author,
                    posted_at_iso=m.timestamp_iso,
                    sig=sig,
                )

    def _record_btos(
        self,
        *,
        author: str,
        message_id: str,
        posted_at_iso: str | None,
        parsed: list[ParsedSignal],
    ) -> None:
        """Record each BTO line under its author for later de-risk attribution."""
        if self._tracker is None:
            return
        posted_at = _parse_posted_at(posted_at_iso)
        for i, sig in enumerate(parsed):
            if sig.action != "BTO":
                continue
            self._tracker.record(
                author,
                RecentBto(
                    ticker=sig.ticker,
                    expiry=sig.expiry,
                    strike=sig.strike,
                    right=sig.right,
                    price=sig.price,
                    signal_id=f"{message_id}:{i}",
                    posted_at=posted_at,
                ),
            )

    async def _handle_derisk_cue(
        self,
        *,
        message_id: str,
        author: str,
        posted_at_iso: str | None,
        content: str,
    ) -> bool:
        """Classify a non-grammar message as a de-risk cue, attribute it to the same author's
        preceding BTO, and fan a CopytradeDeriskPayload out to every target. Returns True iff a cue
        was recognized AND attributed (a start was attempted for each target)."""
        if self._derisk_emitter is None or self._tracker is None:
            return False
        cue = classify_derisk(content)
        if cue is None:
            return False
        now = _parse_posted_at(posted_at_iso)
        target = self._tracker.resolve(author, cue.tickers, now)
        if target is None:
            self._log.info(
                "de-risk cue from %s unattributed (cue=%r tickers=%s) — no matching open BTO in "
                "window; ignored",
                author,
                cue.matched_cue,
                cue.tickers,
            )
            return False
        # Snapshot the target list reference once (a concurrent registry refresh REBINDS it).
        targets = self._targets
        for tenant_id, strategy_id in targets:
            payload = self._build_derisk_payload(
                message_id=message_id,
                author=author,
                posted_at_iso=posted_at_iso,
                content=content,
                cue=cue,
                target=target,
                tenant_id=tenant_id,
                strategy_id=strategy_id,
            )
            await self._emit_one_derisk(payload)
        return True

    def _build_derisk_payload(
        self,
        *,
        message_id: str,
        author: str,
        posted_at_iso: str | None,
        content: str,
        cue: DeriskCue,
        target: RecentBto,
        tenant_id: str,
        strategy_id: str,
    ) -> CopytradeDeriskPayload:
        return CopytradeDeriskPayload(
            schema_version=1,
            tenant_id=tenant_id,
            strategy_id=strategy_id,
            signal_id=f"{message_id}:derisk",
            message_id=message_id,
            author=author,
            posted_at=_parse_posted_at(posted_at_iso),
            ticker=target.ticker,
            expiry=target.expiry,
            strike=target.strike,
            right=target.right,
            target_bto_signal_id=target.signal_id,
            target_entry_premium=target.price,
            matched_cue=cue.matched_cue,
            raw_line=content,
        )

    async def _emit_one_derisk(self, payload: CopytradeDeriskPayload) -> None:
        assert self._derisk_emitter is not None  # guarded by caller
        result = await self._derisk_emitter.emit(payload)
        if result.deduped:
            self._log.info(
                "deduped de-risk %s (workflow_id=%s)", payload.signal_id, result.workflow_id
            )
            return
        self._log.info(
            "emitted de-risk %s -> trim+trail %s (target_bto=%s cue=%r workflow_id=%s)",
            payload.signal_id,
            payload.ticker,
            payload.target_bto_signal_id,
            payload.matched_cue,
            result.workflow_id,
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
