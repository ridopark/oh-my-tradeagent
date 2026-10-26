"""Entrypoint for the copytrade-lotto-v1 poller (PLAN-2026-10-26-copytrade-lotto-v1, phase 4).

Runs as its own Deployment (``lotto-signal``) from the SAME image as the trading sidecar via a
``command:`` override. No Discord session, no Playwright: it reads the rows the lotto mirror
(PR #986) already stored, through the BFF's ingest-token-gated read endpoint (phase 3), and starts
``CopytradeSignalWorkflow`` through ``TemporalEmitter`` exactly as ``main.py`` does.

Temporal is reached through ``TEMPORAL_TARGET`` / ``TEMPORAL_NAMESPACE``, which the
``65-lotto-signal.yaml`` manifest sets (as ``55-signal-source-discord.yaml`` does); the in-code
defaults below exist for local runs only. ``TEMPORAL_TASK_QUEUE`` is unset there and defaults to
``orchestrator-core`` exactly as ``main.py`` does.

Dry-run (the default, ``LOTTO_DRY_RUN`` anything but ``false``) swaps in ``InMemoryEmitter`` and
logs each would-emit payload; flipping that env is the go-live. ``--replay --since <iso>`` runs the
boot path over stored rows in dry-run and prints the would-emit list.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import pathlib
import sys
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta, timezone

import httpx
from dotenv import load_dotenv

from . import healthz
from .emitter import Emitter, InMemoryEmitter, TemporalEmitter
from .lotto_emit import ChatRow, LottoPoller, parse_cutoff_et
from .lotto_parser import AUTHOR, LottoBook
from .runtime import required, setup_logging

MESSAGES_PATH = "/internal/options-chat/messages"
# Mirrors the BFF's limit ceiling; the client pages while count == limit.
PAGE_LIMIT = 200
DEFAULT_CHANNEL_ID = "880503518878892143"
DEFAULT_STRATEGY_ID = "copytrade-lotto-v1"


class BffMessagesClient:
    """``GET /internal/options-chat/messages?channel=&after=&limit=`` with the ingest token.

    ``since`` never raises. ``None`` means the first page could not be read (transport error, 5xx,
    404 dark, 401/403 token, 400 channel) so the caller keeps its cursor and retries next poll; a
    failure on a LATER page returns the rows read so far, and the caller's per-row cursor picks the
    rest up next poll. Steady-state failures are throttled the way ``options_chat_ingest`` does, so
    a dark route or a wrong token does not flood the log every 5s.
    """

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        log: logging.Logger,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._token = token
        self._log = log
        self._owns_client = client is None
        self._consecutive_failures = 0
        self._last_failure: str | None = None
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(connect=3.0, read=10.0, write=10.0, pool=3.0),
        )

    async def since(
        self, channel_id: str, after_id: int, limit: int = PAGE_LIMIT
    ) -> list[ChatRow] | None:
        rows: list[ChatRow] = []
        cursor = after_id
        while True:
            page = await self._fetch(channel_id, cursor, limit)
            if page is None:
                return rows if rows else None
            items, count = page
            rows.extend(items)
            if count < limit or not items:
                return rows
            cursor = int(items[-1].message_id)

    async def _fetch(
        self, channel_id: str, after_id: int, limit: int
    ) -> tuple[list[ChatRow], int] | None:
        try:
            resp = await self._client.get(
                MESSAGES_PATH,
                params={"channel": channel_id, "after": str(after_id), "limit": str(limit)},
                headers={"Authorization": f"Bearer {self._token}"},
            )
        except Exception as exc:  # noqa: BLE001 - transport problems are transient
            self._failure(logging.WARNING, "lotto messages read transport error: %r", exc)
            return None
        code = resp.status_code
        if code == 200:
            self._consecutive_failures = 0
            self._last_failure = None
            body = resp.json()
            items: list[ChatRow] = []
            for item in body.get("items") or []:
                try:
                    items.append(ChatRow.from_json(item))
                except (KeyError, TypeError, ValueError) as exc:
                    # A malformed row is skipped rather than wedging the feed; the BFF is ours, so
                    # this is a contract bug to fix, not a condition to crash-loop on.
                    self._log.error(
                        "lotto messages row %s malformed, skipped: %r",
                        item.get("message_id") if isinstance(item, dict) else item,
                        exc,
                    )
            return items, int(body.get("count") or len(items))
        if code in (401, 403):
            self._failure(
                logging.ERROR,
                "lotto messages read rejected the token (%d) — is OPTIONS_CHAT_INGEST_TOKEN equal "
                "to the BFF's?",
                code,
            )
        elif code == 400:
            self._failure(
                logging.ERROR,
                "lotto messages read rejected the request (400): %s — is LOTTO_CHANNEL_ID in the "
                "BFF's OPTIONS_CHAT_CHANNEL_IDS?",
                self._truncate(resp.text),
            )
        elif code == 404:
            self._failure(
                logging.WARNING,
                "lotto messages route is 404 — is OPTIONS_CHAT_ENABLED set on the BFF?",
            )
        else:
            self._failure(
                logging.WARNING,
                "lotto messages read failed %d: %s",
                code,
                self._truncate(resp.text),
            )
        return None

    def _failure(self, level: int, msg: str, *args) -> None:
        # Throttle repeats of the SAME failure; a different one always logs on its first hit.
        if msg != self._last_failure:
            self._last_failure = msg
            self._consecutive_failures = 0
        self._consecutive_failures += 1
        n = self._consecutive_failures
        if n in (1, 3) or n % 60 == 0:
            self._log.log(level, msg + " (consecutive=%d)", *args, n)

    @staticmethod
    def _truncate(text: str, limit: int = 400) -> str:
        text = (text or "").replace("\n", " ").strip()
        return text if len(text) <= limit else text[:limit] + "…"

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def parse_dry_run(raw: str | None) -> bool:
    """Fail-safe: only the literal ``false`` goes live."""
    return (raw or "").strip().lower() != "false"


async def build_emitter(
    *, dry_run: bool, target: str, namespace: str, task_queue: str, log: logging.Logger
) -> Emitter:
    if dry_run:
        log.info("LOTTO_DRY_RUN is on: using InMemoryEmitter, no Temporal workflow is started")
        return InMemoryEmitter()
    log.info("dialing Temporal target=%s namespace=%s task_queue=%s", target, namespace, task_queue)
    return await TemporalEmitter.connect(target=target, namespace=namespace, task_queue=task_queue)


def _aware(raw: str) -> datetime:
    dt = datetime.fromisoformat(raw)
    if dt.tzinfo is None:
        raise argparse.ArgumentTypeError(f"--since must carry a timezone (got {raw!r})")
    return dt.astimezone(timezone.utc)


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="ohmytradeagent_sidecar.lotto_main")
    parser.add_argument(
        "--replay",
        action="store_true",
        help="rebuild the book from stored rows in dry-run, print the would-emit list, exit 0",
    )
    parser.add_argument(
        "--since",
        type=_aware,
        default=None,
        help="ISO-8601 start for --replay (default: now - LOTTO_LOOKBACK_DAYS)",
    )
    return parser.parse_args(argv)


def _build_poller(
    *,
    emitter: Emitter,
    dry_run: bool,
    log: logging.Logger,
    transport: httpx.BaseTransport | None = None,
) -> tuple[LottoPoller, BffMessagesClient]:
    bff_url = required("BFF_INTERNAL_URL")
    token = required("OPTIONS_CHAT_INGEST_TOKEN")
    tenant_id = required("TENANT_ID")
    author = os.getenv("LOTTO_AUTHOR", AUTHOR).strip() or AUTHOR
    client = BffMessagesClient(
        base_url=bff_url,
        token=token,
        log=log,
        client=httpx.AsyncClient(transport=transport, base_url=bff_url.rstrip("/"))
        if transport is not None
        else None,
    )
    poller = LottoPoller(
        source=client,
        emitter=emitter,
        book=LottoBook(author=author),
        channel_id=os.getenv("LOTTO_CHANNEL_ID", DEFAULT_CHANNEL_ID).strip() or DEFAULT_CHANNEL_ID,
        author=author,
        tenant_id=tenant_id,
        strategy_id=os.getenv("STRATEGY_ID", DEFAULT_STRATEGY_ID).strip() or DEFAULT_STRATEGY_ID,
        lookback_days=float(os.getenv("LOTTO_LOOKBACK_DAYS", "14")),
        emit_max_age_secs=float(os.getenv("LOTTO_EMIT_MAX_AGE_SECS", "120")),
        expiry_day_entry_cutoff_et=parse_cutoff_et(
            os.getenv("LOTTO_EXPIRY_DAY_ENTRY_CUTOFF_ET", "15:25")
        ),
        dry_run=dry_run,
        log=log,
    )
    return poller, client


async def replay_main(since: datetime | None, transport: httpx.BaseTransport | None = None) -> int:
    load_dotenv()
    log = setup_logging(os.getenv("LOG_LEVEL", "WARNING"), "lotto_replay")
    emitter = InMemoryEmitter()
    poller, client = _build_poller(emitter=emitter, dry_run=True, log=log, transport=transport)
    if since is None:
        since = datetime.now(timezone.utc) - timedelta(
            days=float(os.getenv("LOTTO_LOOKBACK_DAYS", "14"))
        )
    try:
        payloads = await poller.replay(since)
    finally:
        await client.aclose()
    if payloads is None:
        print(f"replay since {since.isoformat()}: the BFF read failed, nothing replayed")
        return 2
    for p in payloads:
        print(
            f"{p.action.value} {p.ticker} {p.expiry} {p.strike}{p.right.value} @ {p.price} "
            f"posted_at={p.posted_at.isoformat()} signal_id={p.signal_id} text={p.raw_line!r}"
        )
    print(f"replay since {since.isoformat()}: would-emit {len(payloads)} signal(s)")
    return 0


async def _run() -> int:
    load_dotenv()
    log = setup_logging(os.getenv("LOG_LEVEL", "INFO"), "lotto_signal")
    dry_run = parse_dry_run(os.getenv("LOTTO_DRY_RUN", "true"))
    poll_interval = float(os.getenv("POLL_INTERVAL_SECS", "5"))

    # Writable: owns the heartbeat the liveness probe reads (same shape as chat_main).
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "/app/state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    heartbeat_path = state_dir / "heartbeat"
    healthz.start_from_env(heartbeat_path, log)

    emitter = await build_emitter(
        dry_run=dry_run,
        target=os.getenv("TEMPORAL_TARGET", "localhost:7233"),
        namespace=os.getenv("TEMPORAL_NAMESPACE", "default"),
        task_queue=os.getenv("TEMPORAL_TASK_QUEUE", "orchestrator-core"),
        log=log,
    )
    try:
        poller, client = _build_poller(emitter=emitter, dry_run=dry_run, log=log)
    except SystemExit:
        await emitter.close()
        raise
    log.info(
        "lotto poller starting (dry_run=%s poll=%ss cursor_lookback=%s)",
        dry_run,
        poll_interval,
        os.getenv("LOTTO_LOOKBACK_DAYS", "14"),
    )
    try:
        await run_loop(poller, heartbeat_path, poll_interval)
    finally:
        await client.aclose()
        await emitter.close()
    return 1


async def run_loop(
    poller: LottoPoller,
    heartbeat_path: pathlib.Path,
    poll_interval: float,
    *,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> None:
    """Boot, then poll forever. The liveness probe reads ``heartbeat_path``'s mtime (stale after
    120s, ~150s from container start), so it is touched BEFORE the lookback rebuild — several BFF
    reads — and after every iteration whatever the BFF answered: a dark or erroring BFF must log
    its cause, not kill-loop the pod."""
    heartbeat_path.touch()
    # Boot must see the lookback window before polling starts; until the BFF answers, keep the
    # probe alive and retry rather than start from an empty book.
    while not await poller.boot():
        heartbeat_path.touch()
        await sleep(poll_interval)
    heartbeat_path.touch()
    while True:
        await poller.poll_once()
        heartbeat_path.touch()
        await sleep(poll_interval)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    if args.replay:
        sys.exit(asyncio.run(replay_main(args.since)))
    sys.exit(asyncio.run(_run()))


if __name__ == "__main__":
    main()
