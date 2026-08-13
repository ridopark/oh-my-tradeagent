"""Event-driven watcher for the read-only /options-chat Discord mirror (PLAN-2026-08-12 Phase 2).

WHY NOT A POLL LOOP. The signal watcher polls ``page.evaluate`` every 1.0s (``watcher.py``). Polling
harder to chase latency costs CPU linearly and still averages half the interval in lag. Instead a
``MutationObserver`` fires a Playwright binding when Discord's OWN gateway WebSocket inserts an
``<li>``, so latency is Discord's push plus one IPC hop, and idle CPU is ~zero. A slow reconcile
sweep remains as the safety net for anything the observer misses (a tab rebuild, a detached
observer, a mutation during the settle window).

THE BINDING ONLY SIGNALS; IT NEVER CARRIES A PAYLOAD. Playwright's ``_on_binding`` does
``asyncio.create_task`` per call with no queue and no concurrency cap, so a payload-pushing design
would fan out N concurrent HTTP posts on a busy minute. A one-line ``Event.set()`` collapses N
mutations into a single extraction, and keeps the page-side promise from staying pending.
"""

from __future__ import annotations

import asyncio
import logging
import pathlib
import time

from playwright.async_api import BrowserContext, Page

from .chat_dom import EXTRACT_JS, ExtractionStats, build_chat_messages
from .options_chat_ingest import IngestOutcome, OptionsChatIngestClient
from .page_rebuild import is_fatal_page_error, new_ready_page, rebuild_backoff_secs
from .seen_lru import BoundedSeenLRU

BINDING_NAME = "__omtaChatChanged"

# Installed via add_init_script so it survives a reload AND a tab rebuild with no re-install code.
OBSERVER_JS = """
() => {
  // Discord embeds iframes (players, previews); only the top document has the message list.
  if (window.top !== window) return;
  if (window.__omtaChatInstalled) return;
  window.__omtaChatInstalled = true;

  let observed = null;
  const ping = (kind) => {
    try {
      if (typeof window.__omtaChatChanged === 'function') window.__omtaChatChanged(kind);
    } catch (e) { /* MUST swallow: a throw here rejects a promise the page is awaiting */ }
  };
  const obs = new MutationObserver(() => ping('m'));

  // Discord REPLACES the scroller <ol> on channel switch and virtualization resets, silently
  // detaching a node-pinned observer. A 2s re-attach watchdog is immune to that, and costs one
  // querySelector — whereas observing document.body with subtree:true would fire on every hover,
  // typing indicator and relative-timestamp tick, i.e. thousands of create_task calls a minute.
  const attach = () => {
    const list = document.querySelector('ol[data-list-id="chat-messages"]');
    if (!list) { if (observed) { obs.disconnect(); observed = null; } return; }
    if (list === observed && document.contains(observed)) return;
    obs.disconnect();
    // subtree:true is REQUIRED — attachments and link previews are inserted INTO an existing <li>
    // seconds after it appears, so childList on the <ol> alone would miss every late accessory.
    // characterData catches edits.
    obs.observe(list, { childList: true, subtree: true, characterData: true });
    observed = list;
    ping('a');
  };
  setInterval(attach, 2000);
  attach();
}
"""


class ChatWatcher:
    """Scrapes one Discord channel and ships it to the BFF. Owns its own tab."""

    DOM_READY_TIMEOUT_MS = 45_000
    # Safety-net sweep. Also the cadence at which late-resolving embeds get a second look.
    RECONCILE_SECS = 10.0
    # Settle window after a mutation. Discord resolves link previews and image accessories AFTER
    # inserting the <li>; extracting at +250ms would store the message with zero children, and
    # because ingest only writes children on the winning parent insert, they would be lost
    # PERMANENTLY. 1.5s buys most accessories at a latency a human reads as instant.
    SETTLE_SECS = 1.5
    # Discord virtualizes, so DOM node count stays flat and cannot signal renderer bloat. Wall-clock
    # is the honest trigger; recycling is free because ingest is idempotent by snowflake.
    RECYCLE_SECS = 6 * 60 * 60
    MAX_CONSECUTIVE_CRASHES = 5
    # How many rendered messages to read per sweep.
    EXTRACT_LIMIT = 50

    def __init__(
        self,
        *,
        channel_url: str,
        channel_id: str,
        ingest: OptionsChatIngestClient,
        heartbeat_path: pathlib.Path,
        log: logging.Logger,
        seen_capacity: int = 1000,
    ) -> None:
        self._channel_url = channel_url
        self._channel_id = channel_id
        self._ingest = ingest
        self._heartbeat_path = heartbeat_path
        self._log = log
        self._seen = BoundedSeenLRU(seen_capacity)
        self._wake = asyncio.Event()
        self._last_stats: ExtractionStats | None = None

    async def _on_dom_changed(self, source, kind: str) -> None:
        """Binding callback. Must be trivial and must never raise.

        A raise here is INVISIBLE: Playwright catches it and sends a rejected promise back into the
        page, logging nothing on the Python side.
        """
        self._wake.set()

    async def install(self, context: BrowserContext) -> None:
        """Register the binding + observer ONCE, on the context.

        Context-level (not page-level) so a tab rebuild needs no re-installation, and so a second
        registration cannot raise 'already registered'.
        """
        await context.expose_binding(BINDING_NAME, self._on_dom_changed)
        await context.add_init_script(f"({OBSERVER_JS})()")

    async def run_on_context(self, context: BrowserContext) -> None:
        """Run until the tab cannot be rebuilt. The caller treats a return as fatal."""
        page: Page | None = None
        consecutive_crashes = 0
        opened_at = 0.0

        while True:
            try:
                if page is None or page.is_closed():
                    page = await new_ready_page(
                        context, self._channel_url, self._log, self.DOM_READY_TIMEOUT_MS
                    )
                    opened_at = time.monotonic()
                    consecutive_crashes = 0
                    # A fresh tab has a fresh DOM: sweep it immediately rather than waiting for the
                    # first mutation, so a restart repopulates the visible window.
                    self._wake.set()

                if time.monotonic() - opened_at > self.RECYCLE_SECS:
                    self._log.info("recycling the chat tab after %.0fs", self.RECYCLE_SECS)
                    await self._close_quietly(page)
                    page = None
                    continue

                try:
                    await asyncio.wait_for(self._wake.wait(), timeout=self.RECONCILE_SECS)
                except asyncio.TimeoutError:
                    pass  # safety-net reconcile

                self._wake.clear()
                await asyncio.sleep(self.SETTLE_SECS)
                # Swallow mutations that landed DURING the settle — they are already covered by the
                # extraction we are about to run, and re-waking would spin.
                self._wake.clear()

                await self._sweep(page)
                self._heartbeat_path.touch()

            except Exception as exc:  # noqa: BLE001 - the tab died; rebuild rather than exit
                # A None/closed page is unrecoverable by definition; otherwise ask the shared
                # crash-signature check whether this is a renderer death or a transient hiccup.
                fatal = page is None or page.is_closed() or is_fatal_page_error(exc, page)
                if not fatal:
                    self._log.exception("chat sweep failed — continuing")
                    self._heartbeat_path.touch()
                    await asyncio.sleep(1.0)
                    continue

                consecutive_crashes += 1
                self._log.warning(
                    "chat tab crashed (%d/%d): %r",
                    consecutive_crashes,
                    self.MAX_CONSECUTIVE_CRASHES,
                    exc,
                )
                await self._close_quietly(page)
                page = None
                if consecutive_crashes >= self.MAX_CONSECUTIVE_CRASHES:
                    self._log.error(
                        "chat tab exceeded %d consecutive crashes — giving up so the pod restarts",
                        self.MAX_CONSECUTIVE_CRASHES,
                    )
                    return
                # Keep the probe alive across the backoff; this is a rebuild, not a hang.
                self._heartbeat_path.touch()
                await asyncio.sleep(rebuild_backoff_secs(consecutive_crashes))

    async def _sweep(self, page: Page) -> None:
        raw = await page.evaluate(EXTRACT_JS, self.EXTRACT_LIMIT)
        messages, stats = build_chat_messages(raw, self._channel_id)
        self._last_stats = stats
        self._warn_on_regression(stats)

        fresh = [m for m in messages if m.message_id not in self._seen]
        if not fresh:
            return

        result = await self._ingest.ingest(self._channel_id, fresh)
        if result.outcome.terminal:
            # Mark seen ONLY on a terminal outcome. The inverse of watcher.py, which can mark first
            # because Temporal dedupes durably behind it; here a transient failure marked seen would
            # drop the message forever.
            for m in fresh:
                self._seen.add(m.message_id)
            if result.outcome is IngestOutcome.STORED and result.stored:
                self._log.info(
                    "options-chat stored %d/%d (attachments=%d embeds=%d)",
                    result.stored,
                    len(fresh),
                    stats.attachments,
                    stats.embeds,
                )

    def _warn_on_regression(self, stats: ExtractionStats) -> None:
        """A rotated selector is invisible in the output but obvious in the ratio.

        Fixture tests cannot catch a Discord release; this is the signal that can.
        """
        if stats.li_count and stats.content_missing / stats.li_count > 0.2:
            self._log.warning(
                "options-chat extraction looks degraded: %d/%d messages had no content div — "
                "the Discord DOM may have changed",
                stats.content_missing,
                stats.li_count,
            )

    async def _close_quietly(self, page: Page | None) -> None:
        if page is None:
            return
        try:
            await page.close()
        except Exception:  # noqa: BLE001 - best-effort; the page may already be dead
            pass
