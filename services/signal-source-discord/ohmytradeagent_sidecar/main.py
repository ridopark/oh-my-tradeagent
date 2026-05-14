"""Sidecar entrypoint. Wires env-config into Watcher + TemporalEmitter."""

from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import sys

from dotenv import load_dotenv

from .emitter import TemporalEmitter
from .watcher import Watcher


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
    try:
        await watcher.run()
    finally:
        await emitter.close()


def main() -> None:
    asyncio.run(_amain())


if __name__ == "__main__":
    main()
