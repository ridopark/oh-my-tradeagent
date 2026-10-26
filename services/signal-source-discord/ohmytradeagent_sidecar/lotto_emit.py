"""Turn mirrored #lotto-alerts rows into ``CopytradeSignalWorkflow`` starts (phase 4).

``LottoPoller`` owns the cursor and the ``LottoBook``. It reads rows through a ``since`` source
(``lotto_main.BffMessagesClient``), feeds every row by the configured author to the book, and emits
the resulting ``LottoSignal``s through the same ``Emitter`` Protocol the Discord watcher uses, so
the orchestrator sees payloads shaped exactly like ``watcher.py``'s.

Three gates decide whether a signal reaches the emitter:

* **Age.** Only signals whose ``posted_at`` is at most ``emit_max_age_secs`` old are emitted.
  Older rows update the book only. That holds on boot too: the book is rebuilt from the lookback
  window so exits can be attributed to entries posted before this process started, and the few
  rows still inside the age window ARE emitted — an EXIT posted during a pod-restart window must
  still close the position, and a missed close is the costlier direction. Temporal's
  ``REJECT_DUPLICATE`` on the deterministic workflow id dedupes anything the previous process
  already started.
* **Expiry-day entry cutoff.** A BTO on a contract expiring on the posted ET date is not emitted
  at or after ``expiry_day_entry_cutoff_et`` (default 15:25 ET, five minutes before the 15:30
  flatten covers the paper pending TTL): a lot that fills after the expiry flatten gets no
  flatten timer in PositionWorkflowImpl and is marked worthless while the broker position stays
  open. The book still records the entry so later exit posts attribute
  to it (and become orchestrator no-ops) instead of landing on an older contract. Exits are never
  cut off.

Every EXIT is emitted, repeats included: the orchestrator turns an exit on a flat contract into an
audited no-op, and a second post retries a close whose first order failed.

Rows are first-scrape snapshots. The mirror never updates a stored row, so ``deleted`` is never
true and an edit never changes ``content``; there is deliberately no deleted/edited handling here.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, time, timedelta, timezone
from typing import Protocol
from zoneinfo import ZoneInfo

from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)

from .emitter import Emitter
from .lotto_parser import LottoBook, LottoSignal

DISCORD_EPOCH_MS = 1420070400000
_ET = ZoneInfo("America/New_York")
DEFAULT_EXPIRY_DAY_ENTRY_CUTOFF_ET = time(15, 25)


def parse_cutoff_et(raw: str) -> time:
    """``HH:MM`` wall-clock ET → ``time``; the orchestrator's expiry flatten is 15:30."""
    hh, mm = raw.strip().split(":")
    return time(int(hh), int(mm))


def snowflake_at(dt: datetime) -> int:
    """The smallest Discord snowflake minted at or after ``dt`` (worker/sequence bits zero)."""
    ms = int(dt.astimezone(timezone.utc).timestamp() * 1000) - DISCORD_EPOCH_MS
    return max(ms, 0) << 22


def parse_posted_at(raw: str) -> datetime:
    """ISO-8601 (``...Z`` or offset) → aware UTC. A naive timestamp is a contract violation."""
    dt = datetime.fromisoformat(raw)
    if dt.tzinfo is None:
        raise ValueError(f"posted_at must be timezone-aware: {raw!r}")
    return dt.astimezone(timezone.utc)


@dataclass(frozen=True)
class ChatRow:
    """One mirrored message, as the BFF's ``GET /internal/options-chat/messages`` returns it."""

    message_id: str
    author_name: str
    posted_at: datetime  # aware UTC
    content: str
    reply_to_id: str | None

    @staticmethod
    def snowflake_time(message_id: str) -> datetime:
        ms = (int(message_id) >> 22) + DISCORD_EPOCH_MS
        return datetime.fromtimestamp(ms / 1000, tz=timezone.utc)

    @classmethod
    def from_json(cls, item: dict) -> "ChatRow":
        message_id = str(item["message_id"])
        raw = item.get("posted_at")
        posted_at = parse_posted_at(raw) if raw else cls.snowflake_time(message_id)
        reply_to = item.get("reply_to_id")
        return cls(
            message_id=message_id,
            author_name=item.get("author_name") or "",
            posted_at=posted_at,
            content=item.get("content") or "",
            reply_to_id=str(reply_to) if reply_to else None,
        )


class RowSource(Protocol):
    async def since(
        self, channel_id: str, after_id: int, limit: int = ...
    ) -> list[ChatRow] | None: ...


def to_payload(
    sig: LottoSignal,
    *,
    message_id: str,
    posted_at: datetime,
    tenant_id: str,
    strategy_id: str,
    author: str,
) -> CopytradeSignalPayload:
    """Mirror of ``watcher.py``'s ``_build_payload``. ``price`` is the signal's ``ref_price``: the
    posted price, or for a priceless exit the author's last posted price for that contract
    (plan fork F2). ``tail`` and ``raw_line`` both carry the whole message text."""
    return CopytradeSignalPayload(
        schema_version=1,
        tenant_id=tenant_id,
        strategy_id=strategy_id,
        signal_id=sig.signal_id,
        message_id=message_id,
        author=author,
        posted_at=posted_at,
        action=Action(sig.action),
        ticker=sig.ticker,
        expiry=sig.expiry,
        strike=sig.strike,
        right=Right(sig.right),
        price=sig.ref_price,
        tail=sig.text,
        raw_line=sig.text,
    )


class LottoPoller:
    def __init__(
        self,
        *,
        source: RowSource,
        emitter: Emitter,
        book: LottoBook,
        channel_id: str,
        author: str,
        tenant_id: str,
        strategy_id: str,
        lookback_days: float,
        emit_max_age_secs: float,
        dry_run: bool,
        log: logging.Logger,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        expiry_day_entry_cutoff_et: time = DEFAULT_EXPIRY_DAY_ENTRY_CUTOFF_ET,
    ) -> None:
        self._source = source
        self._emitter = emitter
        self._book = book
        self._channel_id = channel_id
        self._author = author
        self._tenant_id = tenant_id
        self._strategy_id = strategy_id
        self._lookback = timedelta(days=lookback_days)
        self._emit_max_age = timedelta(seconds=emit_max_age_secs)
        self._dry_run = dry_run
        self._log = log
        self._clock = clock
        self._entry_cutoff_et = expiry_day_entry_cutoff_et
        self.cursor: int = 0

    def _late_expiry_day_entry(self, payload: CopytradeSignalPayload) -> bool:
        if payload.action is not Action.bto:
            return False
        posted_et = payload.posted_at.astimezone(_ET)
        return payload.expiry == posted_et.date() and posted_et.time() >= self._entry_cutoff_et

    async def boot(self) -> bool:
        """Rebuild the book from the lookback window; only rows still inside the age window emit.
        False when the BFF did not answer, so the caller can retry; the cursor is left at the
        lookback snowflake."""
        self.cursor = snowflake_at(self._clock() - self._lookback)
        rows = await self._source.since(self._channel_id, self.cursor)
        if rows is None:
            return False
        emitted = await self._process(rows)
        self._log.info(
            "lotto boot: rebuilt book from %d rows, %d fresh signal(s) emitted, cursor=%s",
            len(rows),
            len(emitted),
            self.cursor,
        )
        return True

    async def poll_once(self) -> list[CopytradeSignalPayload]:
        rows = await self._source.since(self._channel_id, self.cursor)
        if rows is None:
            return []
        return await self._process(rows)

    async def replay(self, since: datetime) -> list[CopytradeSignalPayload] | None:
        """Fresh book from ``since``; every signal counts as fresh. For the ``--replay`` CLI.
        None when the BFF did not answer, so the CLI can fail instead of reporting zero."""
        self._book = LottoBook(author=self._author)
        self.cursor = snowflake_at(since)
        rows = await self._source.since(self._channel_id, self.cursor)
        if rows is None:
            return None
        return await self._process(rows, age_gated=False)

    async def _process(
        self, rows: list[ChatRow], *, age_gated: bool = True
    ) -> list[CopytradeSignalPayload]:
        emitted: list[CopytradeSignalPayload] = []
        for row in rows:
            self.cursor = max(self.cursor, int(row.message_id))
            if row.author_name != self._author:
                continue
            signals = self._book.apply(
                row.message_id, row.author_name, row.posted_at, row.content, row.reply_to_id
            )
            fresh = not age_gated or self._clock() - row.posted_at <= self._emit_max_age
            for sig in signals:
                payload = to_payload(
                    sig,
                    message_id=row.message_id,
                    posted_at=row.posted_at,
                    tenant_id=self._tenant_id,
                    strategy_id=self._strategy_id,
                    author=self._author,
                )
                if not fresh:
                    self._log.info(
                        "stale signal %s (%s %s posted %s) updated state only, not emitted",
                        payload.signal_id,
                        payload.action.value,
                        payload.ticker,
                        payload.posted_at.isoformat(),
                    )
                    continue
                if self._late_expiry_day_entry(payload):
                    self._log.warning(
                        "skipped_late_expiry_day_entry %s (%s %s %s expires today, posted %s ET "
                        ">= cutoff %s) book updated, not emitted",
                        payload.signal_id,
                        payload.ticker,
                        payload.strike,
                        payload.right.value,
                        payload.posted_at.astimezone(_ET).strftime("%H:%M"),
                        self._entry_cutoff_et.strftime("%H:%M"),
                    )
                    continue
                await self._emit_one(payload)
                emitted.append(payload)
        return emitted

    async def _emit_one(self, payload: CopytradeSignalPayload) -> None:
        result = await self._emitter.emit(payload)
        if result.deduped:
            self._log.info("deduped %s (workflow_id=%s)", payload.signal_id, result.workflow_id)
            return
        self._log.info(
            "%s %s %s %s %s%s @ %s signal_id=%s workflow_id=%s text=%r",
            "dry-run would-emit" if self._dry_run else "emitted",
            payload.action.value,
            payload.ticker,
            payload.expiry,
            payload.strike,
            payload.right.value,
            payload.price,
            payload.signal_id,
            result.workflow_id,
            payload.raw_line,
        )
