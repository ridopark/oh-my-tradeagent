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
nothing; flipping that env is the go-live. Cursors are saved to ``STATE_DIR/cursors.json`` after
every cycle whose batch was stored (#1032). The state dir is an emptyDir, so this survives a
CONTAINER restart (a liveness kill) but not a new pod. On start a symbol with a saved cursor resumes
at ``bucket5m(last_t)``; one without starts at ``bucket5m(now - DARKPOOL_LOOKBACK_MINUTES)``
(default 10 min). Either way the start is a 5-minute boundary and no trade before it is ever
aggregated or posted. That alignment is what makes a re-read SAFE: the BFF upserts a bar WHOLE on (symbol, bucket), so a bucket rebuilt from a
partial window (a restart at 10:07 rebuilding 09:55 from 09:57 on) would OVERWRITE the complete
row the previous process had stored — one corrupted bucket per symbol on every image roll.
Prints dedupe on (symbol, ts, trade_id), so re-reading them is harmless.

Trades are fetched up to ``now - 5 s``: TRF prints are indexed with a lag, and a print whose
``t`` is already behind the cursor when it lands would otherwise be lost for good.

Per-symbol isolation: a symbol whose fetch fails (any HTTP or transport error, a malformed row)
is logged and skipped for this cycle with its cursor untouched; the others proceed. A symbol is
committed (aggregated, cursor advanced, prints queued) only from requests that succeeded: when
the budget runs out during its NBBO lookups, the time-ordered PREFIX of its trades whose lookups
completed is committed and the cursor stops there, so a budget stop mid-symbol loses nothing and
a symbol that alone needs more than one cycle's budget still advances every cycle instead of
restarting from the same cursor forever. When the cap stops a cycle, the NEXT cycle starts at
the symbol that was cut (round-robin), so a fixed universe order cannot starve its tail; a cycle
that made NO progress at all (the very first symbol's trades pages already exceeded the budget)
logs an error and rotates past that symbol so it cannot pin the whole universe.

Cursors persist across the poll window: Monday's first cycle fetches from Friday's ~19:59 cursor,
which is a small, cheap read (the tape between is empty); the floor only matters right after a
restart. Prints that the BFF refused (5xx after retries, transport error) are kept in a bounded
buffer and re-sent with the next batch — the BFF inserts prints ON CONFLICT DO NOTHING, so a
re-send is idempotent — because a BFF roll is exactly when coverage would otherwise be lost.
"""

from __future__ import annotations

import asyncio
import json
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
    Trade,
    aggregate,
    format_ts,
    bucket5m,
    keep,
    parse_ts,
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
# Kept prints the BFF refused wait here for the next post; beyond this the OLDEST are dropped
# (~150 bytes each; a whole busy session of kept prints is a few thousand).
UNPOSTED_MAX = 20_000


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
        state_path: pathlib.Path | None = None,
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
        # Prints the BFF refused, re-sent with the next batch (bounded, see UNPOSTED_MAX).
        self.unposted: list[dict] = []
        self._state_path = state_path
        self._load_cursors()

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
            exhausted: BudgetExhausted | None = None
            try:
                fresh, sides, quotes, exhausted = await self._fetch(
                    symbol, cursor, now - SETTLE_LAG
                )
            except BudgetExhausted as exc:
                # Nothing of this symbol could be committed (its trades pages alone used up the
                # budget). Resume here next cycle — unless this was the cycle's FIRST symbol, in
                # which case the cycle made zero progress and the symbol would pin the universe
                # forever: rotate past it and say so loudly.
                stats.skipped = len(order) - n
                if n == 0:
                    self._next_start = (self._universe.index(symbol) + 1) % len(self._universe)
                    self._log.error(
                        "darkpool cycle made NO progress: %s alone exceeded the request budget "
                        "(%s); rotating past it — raise DARKPOOL_MAX_REQUESTS or shrink the "
                        "universe",
                        symbol,
                        exc,
                    )
                else:
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
            # Commit what succeeded: everything, or the time-ordered prefix before the budget ran
            # out (then the cycle stops and the next one resumes this symbol from the new cursor).
            touched |= aggregate(
                fresh,
                sides,
                self.accumulators,
                min_notional=self._min_notional,
                min_size=self._min_size,
            )
            self.cursors[symbol] = cursor.advance(fresh)
            for t in fresh:
                if t in sides:
                    prints.append(print_to_wire(t, quotes[t], sides[t]))
            if exhausted is not None:
                stats.skipped = len(order) - n
                self._next_start = self._universe.index(symbol)
                self._log.warning(
                    "darkpool request budget hit (%s) during %s; committed %d of its trades, "
                    "skipping %d symbols",
                    exhausted,
                    symbol,
                    len(fresh),
                    stats.skipped,
                )
                break
        bars = [self.accumulators[k].to_wire() for k in sorted(touched)]
        prune_accumulators(self.accumulators)
        stats.requests = self._data.requests_this_cycle
        stats.kept_prints = len(prints)
        stats.bars = len(bars)
        stored = True
        if self._dry_run:
            stats.posted = False
        elif prints or bars or self.unposted:
            to_post = self.unposted + prints
            stats.posted = await self._ingest.post(to_post, bars)
            # A partial failure re-sends the stored chunks too: ON CONFLICT DO NOTHING on the BFF.
            self.unposted = [] if stats.posted else to_post[-UNPOSTED_MAX:]
            stored = stats.posted
        # On a failed post keep the last saved cursors: a restart re-reads what never landed.
        if stored:
            self._save_cursors()
        self._log.info(
            "darkpool cycle: symbols=%d requests=%d kept_prints=%d bars=%d dry_run=%s "
            "errors=%d skipped=%d posted=%s unposted=%d",
            stats.symbols,
            stats.requests,
            stats.kept_prints,
            stats.bars,
            self._dry_run,
            stats.errors,
            stats.skipped,
            stats.posted,
            len(self.unposted),
        )
        return stats

    def _load_cursors(self) -> None:
        """Resume each saved symbol at the START of its cursor's bucket: that bucket is re-read
        whole, so its re-posted bar cannot overwrite a complete row with a partial one."""
        if self._state_path is None or not self._state_path.exists():
            return
        try:
            saved = json.loads(self._state_path.read_text())
            starts = {sym: bucket5m(parse_ts(ts)) for sym, ts in saved.items()}
        except Exception as exc:  # noqa: BLE001 - a bad file means a cold start, never a crash
            self._log.warning("darkpool cursor state unreadable, starting cold: %r", exc)
            return
        for sym, start in starts.items():
            self.floors[sym] = start
            self.cursors[sym] = Cursor.start(start)
        self._log.info("darkpool cursor state loaded for %d symbols", len(starts))

    def _save_cursors(self) -> None:
        if self._state_path is None:
            return
        tmp = self._state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps({sym: format_ts(c.last_t) for sym, c in self.cursors.items()}))
        tmp.replace(self._state_path)

    async def _fetch(self, symbol: str, cursor: Cursor, end: datetime):
        """Trades in (cursor, end] in time order plus the NBBO side of every kept print, and the
        BudgetExhausted that cut the NBBO lookups short (then only the committable prefix of the
        trades is returned), else None. Raises on any failure, and on a budget stop before any
        trade could be committed. Anything before the symbol's floor is dropped even if the API
        returns it. No trade-condition filter: the bars are consolidated volume (odd lots,
        extended-hours prints included) in numerator and denominator alike, and the only
        selection is exchange D, so the dark share is unbiased by conditions."""
        floor = self.floors[symbol]
        fresh = sorted(
            (
                t
                for t in cursor.filter_new(await self._data.trades(symbol, cursor.last_t, end))
                if t.ts >= floor
            ),
            key=lambda t: t.ts,
        )
        sides: dict[Trade, str] = {}
        quotes: dict[Trade, object] = {}
        for i, t in enumerate(fresh):
            if keep(t, min_notional=self._min_notional, min_size=self._min_size):
                try:
                    quote = await self._data.quote_before(symbol, t.ts)
                except BudgetExhausted as exc:
                    if i == 0:
                        raise
                    return fresh[:i], sides, quotes, exc
                self._heartbeat()
                quotes[t] = quote
                sides[t] = side(t.price, quote, t.ts)
        return fresh, sides, quotes, None


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
        state_path=state_dir / "cursors.json",
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
