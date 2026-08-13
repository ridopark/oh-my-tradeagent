"""Watchlist-channel watcher.

Single-responsibility: poll a SECOND Discord channel (the watchlist channel),
detect the daily watchlist message from the configured author, and emit a
``WatchlistMirrorWorkflow`` start carrying the verbatim text — exactly once per
ET calendar day, durable across pod restarts. This sidecar does NOT post to
Discord; the Java orchestrator does.

Mirrors ``watcher.py``'s seams: the per-tick logic lives in ``process`` so it
is unit-testable without a browser; ``run_on_context`` runs the poll loop,
owning its own watchlist tab on the caller-owned context (one browser shared
with the signal watcher) so it can rebuild the tab after a renderer crash. The
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
from datetime import date, datetime

from playwright.async_api import BrowserContext, Page

from .discord_dom import RawMessage, extract_recent
from .emitter import WatchlistEmitter
from .page_rebuild import (
    FATAL_ERROR_SUBSTRINGS,
    is_fatal_page_error,
    new_ready_page,
    rebuild_backoff_secs,
)
from .watcher import _BoundedSeenLRU
from .watchlist_detector import is_watchlist
from .watchlist_state import _ET, DailyMirrorState, et_today

from ohmytradeagent_contract.models.watchlist_mirror_payload import WatchlistMirrorPayload


def _posted_et_date(timestamp_iso: str) -> str | None:
    """ET calendar date (ISO ``YYYY-MM-DD``) of a Discord posted timestamp, or
    ``None`` if the timestamp is missing or unparseable.

    Uses the same ``America/New_York`` zone as ``et_today`` so the posted date
    and "today" are compared in one consistent calendar.
    """
    if not timestamp_iso:
        return None
    try:
        # Discord emits e.g. "2026-06-05T13:00:00.000Z"; normalize the trailing
        # 'Z' that older Pythons' fromisoformat rejects.
        dt = datetime.fromisoformat(timestamp_iso.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        return None
    return dt.astimezone(_ET).date().isoformat()


# Crash-signature detection lives in page_rebuild so the /options-chat mirror shares ONE definition
# (and one place to widen the TODO). Re-exported under the original private names because the
# existing tests import them.
_FATAL_ERROR_SUBSTRINGS = FATAL_ERROR_SUBSTRINGS
_is_fatal_page_error = is_fatal_page_error


class WatchlistWatcher:
    """Polling loop for the watchlist channel. Construct, then call
    run_on_context() with the caller-owned browser context; the watcher owns
    its watchlist tab and rebuilds it on a renderer crash."""

    TICK_SCRAPE_LIMIT = 25
    DOM_READY_TIMEOUT_MS = 30_000
    # Bounded self-healing: after this many CONSECUTIVE fatal page rebuilds
    # that keep crashing, give up and let the task die loudly (a successful
    # tick resets the counter). Backoff between rebuilds is capped.
    MAX_CONSECUTIVE_CRASHES = 5
    _REBUILD_BACKOFF_BASE_SECS = 2.0
    _REBUILD_BACKOFF_CAP_SECS = 60.0

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
        additional_targets: list[tuple[str, str]] | None = None,
    ) -> None:
        self._channel_url = channel_url
        self._state_dir = state_dir
        self._emitter = emitter
        self._tenant_id = tenant_id
        self._strategy_id = strategy_id
        # Fan-out targets BEYOND the primary (tenant_id, strategy_id), mirroring the signal Watcher
        # so the daily watchlist lands in the SAME channels signals do (e.g. a live tenant + a paper
        # shadow). The primary stays the once-per-day / stale-dedup leader; extras are best-effort.
        # Each target gets its own tenant-scoped WatchlistMirrorWorkflow id, so Temporal
        # REJECT_DUPLICATE dedupes per tenant and a re-find never double-posts.
        self._additional_targets: list[tuple[str, str]] = list(additional_targets or [])
        self._primary = (tenant_id, strategy_id)
        self._author = author
        self._log = log
        self._poll_interval = poll_interval_secs
        # Separate from the signal watcher's "heartbeat" (the liveness probe).
        self._heartbeat_path = state_dir / "watchlist_heartbeat"
        self._state = DailyMirrorState(state_dir / "watchlist_state.json")
        # In-process memo of the ET date we've confirmed mirrored, so we don't
        # re-read the state file every tick. Reset on ET day-rollover.
        self._mirrored_date: str | None = None
        # Per-process seen-set so a stale watchlist that keeps re-appearing
        # (e.g. after the midnight gate opens but before today's post lands) is
        # emitted at most once, not every poll tick.
        self._seen = _BoundedSeenLRU(200)

    def update_targets(self, additional_targets: list[tuple[str, str]]) -> None:
        """Atomically rebind the watchlist fan-out targets (Phase 2, DB-driven).

        Used by the registry refresher to pick up newly-enabled watchlist tenants without a
        sidecar restart — the DB-driven replacement for WATCHLIST_MIRROR_ADDITIONAL_TARGETS.
        The swap is a single reference REBIND, not an in-place mutation: an in-flight emit loop
        (``for ... in self._additional_targets``) holds the OLD list reference for the rest of its
        iteration, so a mid-emit refresh can never tear it.

        Unlike the signal watcher, the primary (tenant_id, strategy_id) is NOT unioned in — the
        watchlist watcher emits its primary separately, and the sidecar's primary strategy is
        copytrade (not watchlist-trigger-v1), so it is never a valid watchlist target. The registry
        already returns the full enabled watchlist-trigger set, which is dropped in verbatim
        (order-stable dedupe; the primary is defensively excluded in case a future sidecar runs a
        watchlist primary).
        """
        deduped = list(dict.fromkeys(additional_targets))
        self._additional_targets = [t for t in deduped if t != self._primary]

    def _already_mirrored_today(self, et_date: str) -> bool:
        if self._mirrored_date == et_date:
            return True
        if self._state.already_mirrored_today(et_date):
            self._mirrored_date = et_date
            return True
        return False

    def _payload_for(
        self, tenant_id: str, strategy_id: str, m: RawMessage, et_date: str
    ) -> WatchlistMirrorPayload:
        """Build the per-target mirror payload (same message, per-tenant scope)."""
        return WatchlistMirrorPayload(
            schema_version=1,
            tenant_id=tenant_id,
            strategy_id=strategy_id,
            et_date=date.fromisoformat(et_date),
            author=m.author,
            raw_text=m.content,
            source_message_id=m.message_id,
        )

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
            if m.message_id in self._seen:
                continue
            self._seen.add(m.message_id)
            # Posted-date gate: a watchlist-shaped message whose POSTED ET date
            # is not today is a prior-day watchlist still in scrollback (e.g. at
            # midnight ET, before today's post lands). Mirroring it would
            # mislabel it with today's date and consume the day, blocking the
            # real post. Missing/unparseable timestamp → fail closed (skip), as
            # re-posting a stale watchlist is strictly worse than skipping a rare
            # timestamp-less message.
            posted_et = _posted_et_date(m.timestamp_iso)
            if posted_et is None:
                # A watchlist-shaped message with no parseable timestamp is
                # unexpected (the signal path relies on it too) — fail closed.
                self._log.warning(
                    "skipping watchlist (msg=%s) — missing/unparseable timestamp",
                    m.message_id,
                )
                continue
            if posted_et != et_date:
                # Expected/common: a prior-day watchlist still in scrollback (e.g.
                # the newest one at midnight ET, before today's post lands). Skip
                # quietly so we don't mislabel it with today's date + burn the day.
                self._log.debug(
                    "skipping stale watchlist (msg=%s) — posted ET %s != today %s",
                    m.message_id,
                    posted_et,
                    et_date,
                )
                continue
            result = await self._emitter.emit(
                self._payload_for(self._tenant_id, self._strategy_id, m, et_date)
            )
            if result.deduped:
                # Temporal already has this message's workflow — it's a stale
                # re-find (e.g. yesterday's watchlist still newest right after
                # the midnight gate opens). Do NOT consume today's slot; keep
                # scanning for a genuinely new post.
                self._log.info(
                    "watchlist already mirrored (msg=%s) — stale, not consuming today's slot",
                    m.message_id,
                )
                continue
            self._log.info(
                "mirrored watchlist for %s from %s (msg=%s tenant=%s workflow_id=%s)",
                et_date,
                m.author,
                m.message_id,
                self._tenant_id,
                result.workflow_id,
            )
            # Genuinely-new watchlist: fan out to the additional shadow tenants so the digest
            # lands in their channels too. Best-effort — a per-target emit failure must never
            # block recording the day or the other targets (the primary already posted).
            for tenant_id, strategy_id in self._additional_targets:
                try:
                    extra = await self._emitter.emit(
                        self._payload_for(tenant_id, strategy_id, m, et_date)
                    )
                    self._log.info(
                        "mirrored watchlist for %s tenant=%s (msg=%s deduped=%s workflow_id=%s)",
                        et_date,
                        tenant_id,
                        m.message_id,
                        extra.deduped,
                        extra.workflow_id,
                    )
                except Exception:  # noqa: BLE001 - best-effort fan-out, never block the day
                    self._log.exception(
                        "additional watchlist target emit failed tenant=%s (msg=%s) — continuing",
                        tenant_id,
                        m.message_id,
                    )
            self._state.record(et_date=et_date, source_message_id=m.message_id)
            self._mirrored_date = et_date
            return

    async def _new_ready_page(self, context: BrowserContext) -> Page:
        """Open a fresh watchlist tab, navigate, and wait for the DOM to be ready.

        Delegates to the shared helper so the watchlist tab and the /options-chat mirror cannot
        drift on what "ready" means or on the tab-leak cleanup.
        """
        return await new_ready_page(
            context, self._channel_url, self._log, self.DOM_READY_TIMEOUT_MS
        )

    def _rebuild_backoff_secs(self, attempt: int) -> float:
        """Capped exponential backoff (secs) for the Nth consecutive crash."""
        return rebuild_backoff_secs(
            attempt, self._REBUILD_BACKOFF_BASE_SECS, self._REBUILD_BACKOFF_CAP_SECS
        )

    async def run_on_context(self, context: BrowserContext) -> None:
        """Run the watchlist poll loop on the caller-owned browser context.

        Owns its own watchlist tab: builds it via ``_new_ready_page`` and, if
        the renderer crashes / the page closes, rebuilds it (bounded retries +
        backoff) instead of re-ticking a dead page forever. A rebuild that
        itself fails counts against the SAME budget, so a hiccup during recovery
        can't bypass the bound. On a transient tick error, keeps the tolerant
        swallow-and-continue on the SAME page. On bounded-crash exhaustion,
        ``raise`` so the task dies loudly (its ``_log_if_failed`` done-callback
        logs it). Shares one Chromium with the signal watcher (two tabs) —
        ``main`` owns the context.
        """
        page: Page | None = None
        consecutive_crashes = 0

        while True:
            # (Re)build the tab whenever we don't hold a live one. The rebuild
            # can ITSELF fail (goto/wait_for_selector timing out or a transient
            # network error while re-navigating right after a renderer crash —
            # the most likely moment), so it counts against the SAME bounded /
            # backoff budget as a tick-time crash instead of escaping unguarded
            # on the first attempt.
            if page is None:
                try:
                    page = await self._new_ready_page(context)
                except Exception:  # noqa: BLE001 - rebuild right after a crash can fail
                    consecutive_crashes += 1
                    self._log.exception(
                        "watchlist page rebuild failed (attempt %d)",
                        consecutive_crashes,
                    )
                    if consecutive_crashes >= self.MAX_CONSECUTIVE_CRASHES:
                        # Bounded: give up so the task dies loudly (feeds Phase 2).
                        raise
                    await asyncio.sleep(
                        self._rebuild_backoff_secs(consecutive_crashes)
                    )
                    continue

            try:
                await self._tick(page)
                consecutive_crashes = 0
            except Exception as exc:  # noqa: BLE001
                if not _is_fatal_page_error(exc, page):
                    # Transient DOM hiccup — swallow and re-tick the same page.
                    # Do NOT refresh the heartbeat here: the tick raised, so the
                    # tab is not proven healthy this iteration. Only a clean
                    # _tick return advances watchlist_heartbeat (F2) — otherwise
                    # a dead tab keeps the mtime fresh and defeats staleness.
                    self._log.exception("watchlist tick error")
                    await asyncio.sleep(self._poll_interval)
                    continue

                # Fatal: tear down the dead tab and rebuild on the next
                # iteration (guarded above), counting this crash toward the
                # bounded budget with backoff.
                consecutive_crashes += 1
                self._log.exception(
                    "rebuilding watchlist page after renderer crash (attempt %d)",
                    consecutive_crashes,
                )
                # Tear down the dead tab BEFORE deciding whether to give up, so
                # exhaustion doesn't leak the crashed page when the task dies.
                try:
                    await page.close()
                except Exception:  # noqa: BLE001 - best-effort; page may be gone
                    self._log.debug("watchlist dead-page close failed (ignored)")
                page = None
                if consecutive_crashes >= self.MAX_CONSECUTIVE_CRASHES:
                    # Bounded: give up so the task dies loudly (feeds Phase 2).
                    raise
                await asyncio.sleep(self._rebuild_backoff_secs(consecutive_crashes))
                continue

            # Reached only when _tick returned WITHOUT raising (transient/fatal
            # paths above `continue`/`raise` before here). That includes the
            # cheap already-mirrored-today early return — a healthy-but-idle
            # tick — so the heartbeat stays fresh after the morning post without
            # going falsely stale all afternoon.
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
