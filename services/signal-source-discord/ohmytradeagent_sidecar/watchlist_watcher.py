"""Watchlist-channel watcher.

Single-responsibility: poll a SECOND Discord channel (the watchlist channel),
detect the daily watchlist message from the configured author, and emit a
``WatchlistMirrorWorkflow`` start carrying the verbatim text — exactly once per
ET calendar day, durable across pod restarts. This sidecar does NOT post to
Discord; the Java orchestrator does.

Mirrors ``watcher.py``'s seams: the per-tick logic lives in ``process`` so it
is unit-testable without a browser; ``run`` owns the Playwright loop. The
``DailyMirrorState`` file is the durable once-per-day gate; Temporal's
REJECT_DUPLICATE keyed on source_message_id is the hard dedupe backstop.

Heartbeat ownership: the liveness probe reads ``state_dir/heartbeat``, which is
owned by the signal ``Watcher``. This watcher must NEVER touch that file; it
uses a separate ``state_dir/watchlist_heartbeat`` so a watchlist stall cannot
falsely keep (or kill) the trading-critical signal liveness signal.
"""

from __future__ import annotations

import asyncio
import logging
import pathlib
from datetime import date

from playwright.async_api import Page, async_playwright

from .discord_dom import MESSAGES_LI_SELECTOR, RawMessage, extract_recent
from .emitter import WatchlistEmitter
from .watchlist_detector import is_watchlist
from .watchlist_state import DailyMirrorState, et_today

from ohmytradeagent_contract.models.watchlist_mirror_payload import WatchlistMirrorPayload


class WatchlistWatcher:
    """Polling loop for the watchlist channel. Construct, then call run()."""

    TICK_SCRAPE_LIMIT = 25
    DOM_READY_TIMEOUT_MS = 30_000

    def __init__(
        self,
        *,
        channel_url: str,
        state_dir: pathlib.Path,
        emitter: WatchlistEmitter,
        tenant_id: str,
        strategy_id: str,
        author: str,
        log: logging.Logger,
        poll_interval_secs: float,
    ) -> None:
        self._channel_url = channel_url
        self._state_dir = state_dir
        self._emitter = emitter
        self._tenant_id = tenant_id
        self._strategy_id = strategy_id
        self._author = author
        self._log = log
        self._poll_interval = poll_interval_secs
        # Separate from the signal watcher's "heartbeat" (the liveness probe).
        self._heartbeat_path = state_dir / "watchlist_heartbeat"
        self._storage_state_path = state_dir / "storage_state.json"
        self._state = DailyMirrorState(state_dir / "watchlist_state.json")
        # In-process memo of the ET date we've confirmed mirrored, so we don't
        # re-read the state file every tick. Reset on ET day-rollover.
        self._mirrored_date: str | None = None

    def _already_mirrored_today(self, et_date: str) -> bool:
        if self._mirrored_date == et_date:
            return True
        if self._state.already_mirrored_today(et_date):
            self._mirrored_date = et_date
            return True
        return False

    async def process(self, msgs: list[RawMessage], et_date: str) -> None:
        """Emit at most one watchlist for ``et_date`` from the given messages.

        Once-per-day (not once-per-message): after a successful record, stop
        emitting further watchlist messages this tick.
        """
        if self._already_mirrored_today(et_date):
            return
        for m in msgs:
            if m.author != self._author:
                continue
            if not is_watchlist(m.content):
                continue
            payload = WatchlistMirrorPayload(
                schema_version=1,
                tenant_id=self._tenant_id,
                strategy_id=self._strategy_id,
                et_date=date.fromisoformat(et_date),
                author=m.author,
                raw_text=m.content,
                source_message_id=m.message_id,
            )
            result = await self._emitter.emit(payload)
            self._state.record(et_date=et_date, source_message_id=m.message_id)
            self._mirrored_date = et_date
            self._log.info(
                "mirrored watchlist for %s from %s (msg=%s workflow_id=%s deduped=%s)",
                et_date,
                m.author,
                m.message_id,
                result.workflow_id,
                result.deduped,
            )
            return

    async def run(self) -> None:
        if not self._storage_state_path.exists():
            raise RuntimeError(
                f"storage_state.json missing at {self._storage_state_path} "
                "— run bootstrap first (see README)"
            )

        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=True)
            context = await browser.new_context(storage_state=str(self._storage_state_path))
            page = await context.new_page()
            self._log.info("navigating to watchlist channel %s", self._channel_url)
            await page.goto(self._channel_url, wait_until="domcontentloaded")
            await page.wait_for_selector(
                MESSAGES_LI_SELECTOR, timeout=self.DOM_READY_TIMEOUT_MS
            )

            while True:
                try:
                    await self._tick(page)
                except Exception:  # noqa: BLE001
                    self._log.exception("watchlist tick error")
                self._heartbeat_path.touch()
                await asyncio.sleep(self._poll_interval)

    async def _tick(self, page: Page) -> None:
        # Cheap gate first: if we've already mirrored today, skip the expensive
        # Playwright DOM scrape entirely (the watchlist posts once each morning,
        # so this avoids ~8h/day of pointless scrapes). et_today() is re-read
        # each tick so ET day-rollover resets the gate.
        et_date = et_today()
        if self._already_mirrored_today(et_date):
            return
        msgs = await extract_recent(page, limit=self.TICK_SCRAPE_LIMIT)
        await self.process(msgs, et_date)
