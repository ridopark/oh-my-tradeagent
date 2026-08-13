"""HTTP client for the BFF's /options-chat ingest (PLAN-2026-08-12 Phase 2).

Never raises. A display-only mirror must not take its own process down, and — more importantly —
must not take the SHARED Discord session's browser down with it.

THE DEDUPE DISCIPLINE IS THE INVERSE OF ``watcher.py``. The signal watcher marks a message seen
BEFORE emitting, which is right there because Temporal's ``REJECT_DUPLICATE`` is the durable dedupe
behind it. Here there is no durable dedupe downstream, so marking-before-send would silently drop a
message forever on a transient 5xx. Callers must mark seen only on a TERMINAL outcome — see
:class:`IngestOutcome`.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from enum import Enum

import httpx

from .chat_dom import ChatMessage

INGEST_PATH = "/internal/options-chat/ingest"

# Mirrors OptionsChatIngestParser.MAX_MESSAGES; a larger batch is rejected wholesale.
MAX_BATCH = 200


class IngestOutcome(Enum):
    """Whether the caller may mark these messages seen.

    ``STORED`` and ``REJECTED`` are terminal: re-sending identical bytes cannot change the answer,
    so retrying forever would wedge the feed behind one poisoned message. ``RETRY`` is not: the
    reconcile sweep re-sends, and ingest is idempotent by snowflake.
    """

    STORED = "stored"
    REJECTED = "rejected"
    RETRY = "retry"

    @property
    def terminal(self) -> bool:
        return self is not IngestOutcome.RETRY


@dataclass(frozen=True)
class IngestResult:
    outcome: IngestOutcome
    stored: int = 0
    detail: str | None = None


def _wire_attachment(a) -> dict:
    return {
        "kind": a.kind,
        "source_url": a.source_url,
        "filename": a.filename,
        "width": a.width,
        "height": a.height,
        "byte_size": a.byte_size,
    }


def _wire_embed(e) -> dict:
    return {
        "title": e.title,
        "description": e.description,
        "url": e.url,
        "author": e.author,
        "footer": e.footer,
        "thumbnail_url": e.thumbnail_url,
    }


def to_wire(channel_id: str, messages: list[ChatMessage]) -> dict:
    """Build the ingest body.

    Snowflakes go as STRINGS: they exceed 2^53, so a JSON number would already have lost precision
    by the time the browser saw it.

    ``author_avatar_url`` is deliberately NOT sent. The BFF stores it, but rendering it in Phase 3
    would load it straight from Discord's CDN and leak every dashboard viewer's IP — violating the
    plan's own "media served only from our own endpoint" rule. It gets populated when Phase 4 has
    the media proxy to serve it through.
    """
    return {
        "channel_id": str(channel_id),
        "messages": [
            {
                "message_id": m.message_id,
                "author_name": m.author_name,
                "author_color": m.author_color,
                "posted_at": m.posted_at,
                "content": m.content,
                "reply_to_id": m.reply_to_id,
                "edited": m.edited,
                "attachments": [_wire_attachment(a) for a in m.attachments],
                "embeds": [_wire_embed(e) for e in m.embeds],
            }
            for m in messages
        ],
    }


class OptionsChatIngestClient:
    """POSTs scraped messages to the BFF. Owns its httpx client unless one is injected."""

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        log: logging.Logger,
        client: httpx.AsyncClient | None = None,
        max_attempts: int = 3,
        backoff_base_secs: float = 1.0,
    ) -> None:
        self._token = token
        self._log = log
        self._max_attempts = max_attempts
        self._backoff_base_secs = backoff_base_secs
        self._owns_client = client is None
        self._consecutive_dark = 0
        self._consecutive_auth = 0
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            # An ingest that hangs must not stall the loop past its reconcile budget.
            timeout=httpx.Timeout(connect=3.0, read=10.0, write=10.0, pool=3.0),
        )

    async def ingest(self, channel_id: str, messages: list[ChatMessage]) -> IngestResult:
        """Send one batch. Never raises."""
        if not messages:
            return IngestResult(IngestOutcome.STORED, 0)
        if len(messages) > MAX_BATCH:
            # Slicing here rather than letting the BFF 400 the whole batch.
            messages = messages[-MAX_BATCH:]

        body = to_wire(channel_id, messages)
        last_detail: str | None = None

        for attempt in range(1, self._max_attempts + 1):
            try:
                resp = await self._client.post(
                    INGEST_PATH,
                    json=body,
                    headers={"Authorization": f"Bearer {self._token}"},
                )
            except Exception as exc:  # noqa: BLE001 - transport problems are transient
                last_detail = repr(exc)
                self._log.debug("options-chat ingest transport error: %r", exc)
                if attempt < self._max_attempts:
                    await asyncio.sleep(self._backoff_base_secs * 2 ** (attempt - 1))
                    continue
                return IngestResult(IngestOutcome.RETRY, detail=last_detail)

            result = self._interpret(resp)
            if result.outcome is not IngestOutcome.RETRY or attempt == self._max_attempts:
                return result
            last_detail = result.detail
            await asyncio.sleep(self._backoff_base_secs * 2 ** (attempt - 1))

        return IngestResult(IngestOutcome.RETRY, detail=last_detail)

    def _interpret(self, resp: httpx.Response) -> IngestResult:
        code = resp.status_code

        if 200 <= code < 300:
            self._consecutive_dark = 0
            self._consecutive_auth = 0
            stored = 0
            try:
                stored = int(resp.json().get("stored") or 0)
            except Exception:  # noqa: BLE001 - a odd body is not a failure to store
                pass
            return IngestResult(IngestOutcome.STORED, stored)

        body = self._truncate(resp.text)

        if code == 404:
            # The expected steady state between deploying this pod and the operator flipping
            # OPTIONS_CHAT_ENABLED: the controller bean does not exist, so there is no route.
            # Must be quiet and cheap rather than a log flood.
            self._consecutive_dark += 1
            if self._consecutive_dark in (1, 3) or self._consecutive_dark % 60 == 0:
                self._log.warning(
                    "options-chat ingest route is 404 — is OPTIONS_CHAT_ENABLED set on the BFF? "
                    "(consecutive=%d)",
                    self._consecutive_dark,
                )
            return IngestResult(IngestOutcome.RETRY, detail="route dark (404)")

        if code in (401, 403):
            # Not the message's fault; self-heals the moment the token is provisioned. Throttled
            # like the 404 path, because this is a STEADY STATE, not a blip: the BFF fails closed on
            # a blank options-chat.ingest-token, so between deploying this pod and patching the BFF
            # every sweep 401s. Observed in production 2026-08-13 logging ~3 ERRORs every 10s
            # forever — loud enough to bury a real incident.
            self._consecutive_auth += 1
            if self._consecutive_auth in (1, 3) or self._consecutive_auth % 60 == 0:
                self._log.error(
                    "options-chat ingest rejected the token (%d) — is OPTIONS_CHAT_INGEST_TOKEN set "
                    "on the BFF and equal to this pod's? (consecutive=%d)",
                    code,
                    self._consecutive_auth,
                )
            return IngestResult(IngestOutcome.RETRY, detail=f"auth {code}")

        if code in (400, 413, 422):
            # Permanent: identical bytes fail identically forever. Mark seen so one poisoned
            # message can never wedge the mirror.
            self._log.error("options-chat ingest permanently rejected the batch (%d): %s", code, body)
            return IngestResult(IngestOutcome.REJECTED, detail=body)

        if code >= 500:
            self._log.warning("options-chat ingest server error %d: %s", code, body)
            return IngestResult(IngestOutcome.RETRY, detail=f"server {code}")

        self._log.warning("options-chat ingest unexpected status %d: %s", code, body)
        return IngestResult(IngestOutcome.RETRY, detail=f"status {code}")

    @staticmethod
    def _truncate(text: str, limit: int = 400) -> str:
        text = (text or "").replace("\n", " ").strip()
        return text if len(text) <= limit else text[:limit] + "…"

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()
