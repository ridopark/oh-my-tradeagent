"""Sidecar entrypoint. Wires env-config into Watcher + TemporalEmitter."""

from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import sys

from dotenv import load_dotenv
from playwright.async_api import async_playwright

from .emitter import TemporalEmitter, TemporalWatchlistEmitter
from .watcher import Watcher
from .watchlist_watcher import WatchlistWatcher


def _setup_logging(level: str) -> logging.Logger:
    lvl = getattr(logging, level.upper(), logging.INFO)
    logging.basicConfig(
        level=lvl,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    return logging.getLogger("ohmytradeagent_sidecar")


def _required(name: str) -> str:
    val = os.getenv(name, "").strip()
    if not val:
        raise SystemExit(f"{name} is required")
    return val


def _parse_additional_targets(raw: str) -> list[tuple[str, str]]:
    """Parse ``SIGNAL_EMIT_ADDITIONAL_TARGETS`` (``tenant:strategy,tenant:strategy``) into a list of
    extra fan-out targets. Empty → no extras (single-tenant, unchanged). One browser/Discord session
    can thus feed several tenants on the same channel (e.g. a live tenant + a paper shadow)."""
    targets: list[tuple[str, str]] = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        tenant, _, strategy = item.partition(":")
        tenant, strategy = tenant.strip(), strategy.strip()
        if not tenant or not strategy:
            raise SystemExit(
                f"SIGNAL_EMIT_ADDITIONAL_TARGETS entry '{item}' must be 'tenant:strategy'"
            )
        targets.append((tenant, strategy))
    return targets


def _log_if_failed(log: logging.Logger, name: str):
    """Done-callback that logs a non-cancellation task failure. Used to isolate
    the best-effort watchlist watcher so its crash never propagates."""

    def _cb(task: "asyncio.Task[None]") -> None:
        if task.cancelled():
            return
        exc = task.exception()
        if exc is not None:
            log.error("%s ended with error: %r", name, exc)

    return _cb


async def _amain() -> None:
    load_dotenv()
    log = _setup_logging(os.getenv("LOG_LEVEL", "info"))

    channel_url = _required("DISCORD_CHANNEL_URL")
    tenant_id = _required("TENANT_ID")
    strategy_id = _required("STRATEGY_ID")
    additional_targets = _parse_additional_targets(
        os.getenv("SIGNAL_EMIT_ADDITIONAL_TARGETS", "")
    )
    temporal_target = os.getenv("TEMPORAL_TARGET", "localhost:7233")
    temporal_namespace = os.getenv("TEMPORAL_NAMESPACE", "default")
    task_queue = os.getenv("TEMPORAL_TASK_QUEUE", "orchestrator-core")
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "./state"))
    poll_interval = float(os.getenv("POLL_INTERVAL_SECS", "1.0"))

    watchlist_enabled = (
        os.getenv("WATCHLIST_MIRROR_ENABLED", "false").strip().lower() == "true"
    )
    watchlist_channel_url = os.getenv("DISCORD_WATCHLIST_CHANNEL_URL", "").strip()
    if watchlist_enabled and not watchlist_channel_url:
        # Enabled but no channel configured (e.g. the optional sidecar-config
        # secret key is absent → empty string). Degrade gracefully rather than
        # crash: a missing watchlist URL must never take down the trading-critical
        # signal sidecar. The mirror simply stays off until the URL is provided.
        log.warning(
            "WATCHLIST_MIRROR_ENABLED=true but DISCORD_WATCHLIST_CHANNEL_URL is "
            "unset — watchlist mirror disabled"
        )
        watchlist_enabled = False
    if watchlist_enabled:
        watchlist_poll_interval = float(os.getenv("WATCHLIST_POLL_INTERVAL_SECS", "45"))
        watchlist_author = os.getenv("WATCHLIST_AUTHOR", "TradingTheTrend")

    state_dir.mkdir(parents=True, exist_ok=True)

    # Fail fast on a missing Discord session BEFORE dialing Temporal, so we never
    # leave a connected emitter unclosed (its close() lives in the finally below).
    storage_state_path = state_dir / "storage_state.json"
    if not storage_state_path.exists():
        raise RuntimeError(
            f"storage_state.json missing at {storage_state_path} "
            "— run bootstrap first (see README)"
        )

    log.info(
        "starting sidecar (tenant=%s strategy=%s additional_targets=%s target=%s task_queue=%s)",
        tenant_id,
        strategy_id,
        additional_targets,
        temporal_target,
        task_queue,
    )
    emitter = await TemporalEmitter.connect(
        target=temporal_target, namespace=temporal_namespace, task_queue=task_queue
    )
    watcher = Watcher(
        channel_url=channel_url,
        state_dir=state_dir,
        emitter=emitter,
        tenant_id=tenant_id,
        strategy_id=strategy_id,
        additional_targets=additional_targets,
        log=log,
        poll_interval_secs=poll_interval,
    )

    watchlist_watcher: WatchlistWatcher | None = None
    if watchlist_enabled:
        # Reuse the SAME connected Temporal client + task queue — no second dial.
        watchlist_emitter = TemporalWatchlistEmitter(emitter.client, emitter.task_queue)
        watchlist_watcher = WatchlistWatcher(
            channel_url=watchlist_channel_url,
            state_dir=state_dir,
            emitter=watchlist_emitter,
            tenant_id=tenant_id,
            strategy_id=strategy_id,
            author=watchlist_author,
            log=log,
            poll_interval_secs=watchlist_poll_interval,
        )
        log.info("watchlist mirror enabled (channel=%s author=%s)",
                 watchlist_channel_url, watchlist_author)

    try:
        # ONE browser + context shared by both watchers — each gets its own
        # page (tab). A second Chromium would roughly double memory and OOM the
        # homelab sidecar's 1Gi limit (see PLAN-watchlist-mirror).
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=True)
            context = await browser.new_context(storage_state=str(storage_state_path))

            signal_page = await context.new_page()
            signal_task = asyncio.create_task(
                watcher.run_on_page(signal_page), name="signal-watcher"
            )

            watchlist_task: asyncio.Task[None] | None = None
            if watchlist_watcher is not None:
                watchlist_page = await context.new_page()
                watchlist_task = asyncio.create_task(
                    watchlist_watcher.run_on_page(watchlist_page), name="watchlist-watcher"
                )
                # ISOLATION: the watchlist watcher is best-effort. If it dies, log
                # it — never let it take down the process.
                watchlist_task.add_done_callback(_log_if_failed(log, "watchlist watcher"))

            try:
                # The signal watcher is trading-critical: await IT directly so a
                # crash propagates immediately and the process exits non-zero for
                # k8s to restart. We do NOT gather() over both — gather waits for
                # ALL tasks, and the forever-running watchlist task would
                # otherwise mask a signal-watcher crash indefinitely.
                await signal_task
            finally:
                if watchlist_task is not None:
                    watchlist_task.cancel()
                    try:
                        await watchlist_task
                    except asyncio.CancelledError:
                        pass
    finally:
        await emitter.close()


def main() -> None:
    asyncio.run(_amain())


if __name__ == "__main__":
    main()
