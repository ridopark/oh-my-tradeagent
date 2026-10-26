"""Turn mirrored #lotto-alerts rows into ``CopytradeSignalWorkflow`` starts (phase 4).

``LottoPoller`` owns the cursor and the ``LottoBook``. It reads rows through a ``since`` source
(``lotto_main.BffMessagesClient``), feeds every row by the configured author to the book, and emits
the resulting ``LottoSignal``s through the same ``Emitter`` Protocol the Discord watcher uses, so
the orchestrator sees payloads shaped exactly like ``watcher.py``'s.

Two gates decide whether a signal reaches the emitter:

* **Boot emits nothing.** The book is rebuilt from the lookback window so exits can be attributed
  to entries posted before this process started; none of those signals is emitted.
* **Age.** After boot, only signals whose ``posted_at`` is at most ``emit_max_age_secs`` old are
  emitted. Older rows update the book only, so a restart never re-emits stale history; Temporal's
  ``REJECT_DUPLICATE`` on the deterministic workflow id is the durable backstop behind that.

Every EXIT is emitted, repeats included: the orchestrator turns an exit on a flat contract into an
audited no-op, and a second post retries a close whose first order failed.

Rows are first-scrape snapshots. The mirror never updates a stored row, so ``deleted`` is never
true and an edit never changes ``content``; there is deliberately no deleted/edited handling here.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Protocol

from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)

from .emitter import Emitter
from .lotto_parser import LottoBook, LottoSignal

DISCORD_EPOCH_MS = 1420070400000


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
        self.cursor: int = 0

    async def boot(self) -> bool:
        """Rebuild the book from the lookback window without emitting. False when the BFF did
        not answer, so the caller can retry; the cursor is left at the lookback snowflake."""
        self.cursor = snowflake_at(self._clock() - self._lookback)
        rows = await self._source.since(self._channel_id, self.cursor)
        if rows is None:
            return False
        await self._process(rows, emit=False)
        self._log.info(
            "lotto boot: rebuilt book from %d rows, none emitted, cursor=%s", len(rows), self.cursor
        )
        return True

    async def poll_once(self) -> list[CopytradeSignalPayload]:
        rows = await self._source.since(self._channel_id, self.cursor)
        if rows is None:
            return []
        return await self._process(rows, emit=True)

    async def replay(self, since: datetime) -> list[CopytradeSignalPayload] | None:
        """Fresh book from ``since``; every signal counts as fresh. For the ``--replay`` CLI.
        None when the BFF did not answer, so the CLI can fail instead of reporting zero."""
        self._book = LottoBook(author=self._author)
        self.cursor = snowflake_at(since)
        rows = await self._source.since(self._channel_id, self.cursor)
        if rows is None:
            return None
        return await self._process(rows, emit=True, age_gated=False)

    async def _process(
        self, rows: list[ChatRow], *, emit: bool, age_gated: bool = True
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
                if not emit:
                    continue
                if not fresh:
                    self._log.info(
                        "stale signal %s (%s %s posted %s) updated state only, not emitted",
                        payload.signal_id,
                        payload.action.value,
                        payload.ticker,
                        payload.posted_at.isoformat(),
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
