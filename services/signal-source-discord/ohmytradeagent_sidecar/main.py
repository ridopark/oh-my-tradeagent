"""Sidecar entrypoint. Wires env-config into Watcher + TemporalEmitter."""

from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import sys

from dotenv import load_dotenv

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
    temporal_target = os.getenv("TEMPORAL_TARGET", "localhost:7233")
    temporal_namespace = os.getenv("TEMPORAL_NAMESPACE", "default")
    task_queue = os.getenv("TEMPORAL_TASK_QUEUE", "orchestrator-core")
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "./state"))
    poll_interval = float(os.getenv("POLL_INTERVAL_SECS", "1.0"))

    watchlist_enabled = (
        os.getenv("WATCHLIST_MIRROR_ENABLED", "false").strip().lower() == "true"
    )
    if watchlist_enabled:
        watchlist_channel_url = _required("DISCORD_WATCHLIST_CHANNEL_URL")
        watchlist_poll_interval = float(os.getenv("WATCHLIST_POLL_INTERVAL_SECS", "45"))
        watchlist_author = os.getenv("WATCHLIST_AUTHOR", "TradingTheTrend")

    state_dir.mkdir(parents=True, exist_ok=True)

    log.info(
        "starting sidecar (tenant=%s strategy=%s target=%s task_queue=%s)",
        tenant_id,
        strategy_id,
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
        log=log,
        poll_interval_secs=poll_interval,
    )

    signal_task = asyncio.create_task(watcher.run(), name="signal-watcher")
    watchlist_task: asyncio.Task[None] | None = None
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
        watchlist_task = asyncio.create_task(watchlist_watcher.run(), name="watchlist-watcher")
        # ISOLATION: the watchlist watcher is best-effort. If it dies, log it —
        # never let it take down the process. (.run() loops forever, so this only
        # fires on an unexpected escape; the watchlist stays down until restart.)
        watchlist_task.add_done_callback(_log_if_failed(log, "watchlist watcher"))

    try:
        # The signal watcher is trading-critical: await IT directly so a crash
        # propagates immediately and the process exits non-zero for k8s to
        # restart (exactly the old `await watcher.run()` semantics). We do NOT
        # gather() over both — gather waits for ALL tasks, and the forever-running
        # watchlist task would otherwise mask a signal-watcher crash indefinitely.
        await signal_task
    finally:
        if watchlist_task is not None:
            watchlist_task.cancel()
        await emitter.close()


def main() -> None:
    asyncio.run(_amain())


if __name__ == "__main__":
    main()
