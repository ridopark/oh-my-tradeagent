"""Entrypoint for the dark-pool print collector (PLAN-2026-10-29-darkpool-prints, phase 2).

Runs as its own Deployment (``darkpool-collector``, ``infra/k8s/66-darkpool-collector.yaml``) from
the SAME image as the trading sidecar via a ``command:`` override. No Discord, no Chromium, no
Temporal: every ``DARKPOOL_POLL_SECS`` (default 60) between 04:00 and 20:00 ET it polls Alpaca's
REST data API for each symbol of ``DARKPOOL_UNIVERSE_FILE``, keeps the off-exchange prints, stamps
each with the NBBO at the print, folds ALL trades into 5-minute bars, and POSTs both to the BFF's
``/internal/darkpool/ingest`` with the options-chat ingest token. Read-only on markets; nothing
here is on a trading path.

NEVER opens a WebSocket (see ``darkpool_collector``): REST only, with a hard per-cycle request cap
(``DARKPOOL_MAX_REQUESTS``, default 1000), a stop when ``X-Ratelimit-Remaining`` runs low, and a
429 ending the cycle after a short sleep.

Dry-run (the default, ``DARKPOOL_DRY_RUN`` anything but ``false``) logs the batch summary and posts
nothing; flipping that env is the go-live. There is no cursor persistence: a restart starts every
symbol at ``bucket5m(now - DARKPOOL_LOOKBACK_MINUTES)`` (default 10 min), i.e. on a 5-minute
boundary, and no trade before that instant is ever aggregated or posted. That alignment is what
makes a re-read SAFE: the BFF upserts a bar WHOLE on (symbol, bucket), so a bucket rebuilt from a
partial window (a restart at 10:07 rebuilding 09:55 from 09:57 on) would OVERWRITE the complete
row the previous process had stored — one corrupted bucket per symbol on every image roll.
Prints dedupe on (symbol, ts, trade_id), so re-reading them is harmless.

Trades are fetched up to ``now - 5 s``: TRF prints are indexed with a lag, and a print whose
``t`` is already behind the cursor when it lands would otherwise be lost for good.

Per-symbol isolation: a symbol whose fetch fails (any HTTP or transport error, a malformed row)
is logged and skipped for this cycle with its cursor untouched; the others proceed. A symbol is
committed (aggregated, cursor advanced, prints queued) only once ALL of its requests — trades
pages and NBBO lookups — succeeded, so a budget stop mid-symbol loses nothing. When the cap
stops a cycle, the NEXT cycle starts at the first symbol that was skipped (round-robin), so a
fixed universe order cannot starve its tail.
"""

from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import sys
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime, time, timedelta, timezone

import httpx
from dotenv import load_dotenv

from .darkpool_collector import (
    ET,
    AlpacaDataClient,
    BarAccumulator,
    BarKey,
    BudgetExhausted,
    Cursor,
    aggregate,
    bucket5m,
    keep,
    print_to_wire,
    prune_accumulators,
    read_universe,
    side,
)
from .runtime import required, setup_logging

INGEST_PATH = "/internal/darkpool/ingest"
# Mirrors DarkpoolIngestBatch.MAX_ROWS on the BFF; a larger batch is rejected wholesale.
MAX_ROWS = 5000
WINDOW_OPEN_ET = time(4, 0)
WINDOW_CLOSE_ET = time(20, 0)
# Late-indexed TRF prints: fetch up to now - this, so a print with t <= cursor is not missed.
SETTLE_LAG = timedelta(seconds=5)


def parse_dry_run(raw: str | None) -> bool:
    """Fail-safe: only the literal ``false`` posts."""
    return (raw or "").strip().lower() != "false"


def in_poll_window(now: datetime) -> bool:
    """Weekdays, 04:00 <= ET clock < 20:00."""
    et = now.astimezone(ET)
    return et.weekday() < 5 and WINDOW_OPEN_ET <= et.time() < WINDOW_CLOSE_ET


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


class DarkpoolIngestClient:
    """``POST /internal/darkpool/ingest`` in chunks of <= ``MAX_ROWS`` prints and bars, with the
    ingest token as a Bearer (the header ``ServiceTokenFilter`` reads, as ``options_chat_ingest``
    sends it). Never raises. Transport errors and 5xx retry with back-off up to ``max_attempts``;
    a 404 (route dark: ``DARKPOOL_ENABLED`` unset on the BFF) or 401/403 (token) is a steady
    state, logged throttled and not retried; a 4xx is a contract bug, logged with the body."""

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        log: logging.Logger,
        client: httpx.AsyncClient | None = None,
        max_attempts: int = 3,
        backoff_base_secs: float = 1.0,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._token = token
        self._log = log
        self._max_attempts = max_attempts
        self._backoff_base_secs = backoff_base_secs
        self._sleep = sleep
        self._owns_client = client is None
        self._steady_failures = 0
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(connect=3.0, read=20.0, write=20.0, pool=3.0),
        )

    async def post(self, prints: list[dict], bars: list[dict]) -> bool:
        """All chunks stored -> True. A failed chunk is logged and dropped (prints are lost; a
        bar is re-sent whole the next time its bucket is touched)."""
        if not prints and not bars:
            return True
        ok = True
        chunks = max(-(-len(prints) // MAX_ROWS), -(-len(bars) // MAX_ROWS))
        for k in range(chunks):
            body = {
                "prints": prints[k * MAX_ROWS : (k + 1) * MAX_ROWS],
                "bars": bars[k * MAX_ROWS : (k + 1) * MAX_ROWS],
            }
            ok = await self._post_one(body) and ok
        return ok

    async def _post_one(self, body: dict) -> bool:
        for attempt in range(1, self._max_attempts + 1):
            try:
                resp = await self._client.post(
                    INGEST_PATH, json=body, headers={"Authorization": f"Bearer {self._token}"}
                )
            except Exception as exc:  # noqa: BLE001 - transport problems are transient
                self._log.warning("darkpool ingest transport error: %r", exc)
                if attempt < self._max_attempts:
                    await self._sleep(self._backoff_base_secs * 2 ** (attempt - 1))
                    continue
                return False
            code = resp.status_code
            if 200 <= code < 300:
                self._steady_failures = 0
                return True
            if code == 404:
                self._steady("darkpool ingest route is 404 — is DARKPOOL_ENABLED set on the BFF?")
                return False
            if code in (401, 403):
                self._steady(
                    f"darkpool ingest rejected the token ({code}) — is OPTIONS_CHAT_INGEST_TOKEN "
                    "equal to the BFF's?"
                )
                return False
            if code < 500:
                self._log.error(
                    "darkpool ingest rejected the batch (%d): %s", code, self._trunc(resp.text)
                )
                return False
            self._log.warning("darkpool ingest server error %d: %s", code, self._trunc(resp.text))
            if attempt < self._max_attempts:
                await self._sleep(self._backoff_base_secs * 2 ** (attempt - 1))
        return False

    def _steady(self, msg: str) -> None:
        self._steady_failures += 1
        n = self._steady_failures
        if n in (1, 3) or n % 60 == 0:
            self._log.warning("%s (consecutive=%d)", msg, n)

    @staticmethod
    def _trunc(text: str, limit: int = 400) -> str:
        text = (text or "").replace("\n", " ").strip()
        return text if len(text) <= limit else text[:limit] + "…"

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


@dataclass
class CycleStats:
    symbols: int = 0
    requests: int = 0
    kept_prints: int = 0
    bars: int = 0
    errors: int = 0
    skipped: int = 0
    posted: bool = False


class DarkpoolCollector:
    def __init__(
        self,
        *,
        data: AlpacaDataClient,
        ingest: DarkpoolIngestClient,
        universe: list[str],
        min_notional: float,
        min_size: int,
        lookback_minutes: float,
        dry_run: bool,
        log: logging.Logger,
        clock: Callable[[], datetime] = now_utc,
        heartbeat: Callable[[], None] | None = None,
    ) -> None:
        self._data = data
        self._ingest = ingest
        self._universe = universe
        self._min_notional = min_notional
        self._min_size = min_size
        self._lookback = timedelta(minutes=lookback_minutes)
        self._dry_run = dry_run
        self._log = log
        self._clock = clock
        self._heartbeat = heartbeat or (lambda: None)
        self.cursors: dict[str, Cursor] = {}
        # Per symbol: the bucket-aligned instant its cursor started at; nothing earlier is kept.
        self.floors: dict[str, datetime] = {}
        self.accumulators: dict[BarKey, BarAccumulator] = {}
        self._next_start = 0

    async def cycle(self) -> CycleStats:
        stats = CycleStats(symbols=len(self._universe))
        now = self._clock()
        prints: list[dict] = []
        touched: set[BarKey] = set()
        self._data.reset_budget()
        order = self._universe[self._next_start :] + self._universe[: self._next_start]
        self._next_start = 0
        for n, symbol in enumerate(order):
            if symbol not in self.cursors:
                self.floors[symbol] = bucket5m(now - self._lookback)
                self.cursors[symbol] = Cursor.start(self.floors[symbol])
            cursor = self.cursors[symbol]
            try:
                fresh, sides, quotes = await self._fetch(symbol, cursor, now - SETTLE_LAG)
            except BudgetExhausted as exc:
                stats.skipped = len(order) - n
                self._next_start = self._universe.index(symbol)
                self._log.warning(
                    "darkpool request budget hit (%s); skipping %d symbols", exc, stats.skipped
                )
                break
            except Exception as exc:  # noqa: BLE001 - one bad symbol never stops the others
                stats.errors += 1
                self._log.warning("darkpool %s fetch failed: %r", symbol, exc)
                continue
            finally:
                self._heartbeat()
            # Commit only after every request for the symbol succeeded.
            touched |= aggregate(
                fresh,
                sides,
                self.accumulators,
                min_notional=self._min_notional,
                min_size=self._min_size,
            )
            self.cursors[symbol] = cursor.advance(fresh)
            for t in fresh:
                if t.trade_id in sides:
                    prints.append(print_to_wire(t, quotes[t.trade_id], sides[t.trade_id]))
        bars = [self.accumulators[k].to_wire() for k in sorted(touched)]
        prune_accumulators(self.accumulators)
        stats.requests = self._data.requests_this_cycle
        stats.kept_prints = len(prints)
        stats.bars = len(bars)
        if self._dry_run:
            stats.posted = False
        elif prints or bars:
            stats.posted = await self._ingest.post(prints, bars)
        self._log.info(
            "darkpool cycle: symbols=%d requests=%d kept_prints=%d bars=%d dry_run=%s "
            "errors=%d skipped=%d posted=%s",
            stats.symbols,
            stats.requests,
            stats.kept_prints,
            stats.bars,
            self._dry_run,
            stats.errors,
            stats.skipped,
            stats.posted,
        )
        return stats

    async def _fetch(self, symbol: str, cursor: Cursor, end: datetime):
        """Trades in (cursor, end] plus the NBBO side of every kept print. Raises on any failure.
        Anything before the symbol's floor is dropped even if the API returns it."""
        floor = self.floors[symbol]
        fresh = [
            t
            for t in cursor.filter_new(await self._data.trades(symbol, cursor.last_t, end))
            if t.ts >= floor
        ]
        sides: dict[int, str] = {}
        quotes: dict = {}
        for t in fresh:
            if keep(t, min_notional=self._min_notional, min_size=self._min_size):
                quote = await self._data.quote_before(symbol, t.ts)
                quotes[t.trade_id] = quote
                sides[t.trade_id] = side(t.price, quote, t.ts)
        return fresh, sides, quotes


async def run_loop(
    collector: DarkpoolCollector,
    heartbeat_path: pathlib.Path,
    poll_secs: float,
    *,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    clock: Callable[[], datetime] = now_utc,
    log: logging.Logger = logging.getLogger("darkpool_collector"),
) -> None:
    """Poll forever inside the 04:00–20:00 ET window, sleep outside it. The liveness probe reads
    ``heartbeat_path``'s mtime, so it is touched before and after every cycle and on every idle
    sleep: a failing Alpaca or BFF must log its cause, not kill-loop the pod."""
    while True:
        heartbeat_path.touch()
        if in_poll_window(clock()):
            try:
                await collector.cycle()
            except Exception:  # noqa: BLE001 - same discipline as lotto_main's loop
                log.exception("darkpool cycle error")
            heartbeat_path.touch()
        await sleep(poll_secs)


def universe_from_env() -> list[str]:
    return read_universe(pathlib.Path(required("DARKPOOL_UNIVERSE_FILE")))


async def _run() -> int:
    load_dotenv()
    log = setup_logging(os.getenv("LOG_LEVEL", "INFO"), "darkpool_collector")
    dry_run = parse_dry_run(os.getenv("DARKPOOL_DRY_RUN", "true"))
    poll_secs = float(os.getenv("DARKPOOL_POLL_SECS", "60"))

    # Writable: owns the heartbeat the liveness probe reads (same shape as lotto_main).
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "/app/state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    heartbeat_path = state_dir / "heartbeat"

    universe = universe_from_env()
    data = AlpacaDataClient(
        key_id=required("APCA_API_KEY_ID_DATA"),
        secret=required("APCA_API_SECRET_KEY_DATA"),
        log=log,
        max_requests=int(os.getenv("DARKPOOL_MAX_REQUESTS", "1000")),
    )
    ingest = DarkpoolIngestClient(
        base_url=required("BFF_INTERNAL_URL"), token=required("OPTIONS_CHAT_INGEST_TOKEN"), log=log
    )
    collector = DarkpoolCollector(
        data=data,
        ingest=ingest,
        universe=universe,
        min_notional=float(os.getenv("DARKPOOL_MIN_NOTIONAL", "200000")),
        min_size=int(os.getenv("DARKPOOL_MIN_SIZE", "10000")),
        lookback_minutes=float(os.getenv("DARKPOOL_LOOKBACK_MINUTES", "10")),
        dry_run=dry_run,
        log=log,
        heartbeat=heartbeat_path.touch,
    )
    log.info(
        "darkpool collector starting (dry_run=%s poll=%ss symbols=%d lookback=%smin window=%s-%s ET)",
        dry_run,
        poll_secs,
        len(universe),
        os.getenv("DARKPOOL_LOOKBACK_MINUTES", "10"),
        WINDOW_OPEN_ET.strftime("%H:%M"),
        WINDOW_CLOSE_ET.strftime("%H:%M"),
    )
    try:
        await run_loop(collector, heartbeat_path, poll_secs, log=log)
    finally:
        await ingest.aclose()
        await data.aclose()
    return 1


def main() -> None:
    sys.exit(asyncio.run(_run()))


if __name__ == "__main__":
    main()
