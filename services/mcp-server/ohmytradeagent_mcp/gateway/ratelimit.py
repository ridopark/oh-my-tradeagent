"""Token-bucket rate limiters for the MCP gateway — Step 3c.

``ToolRateLimiter``  — per subject + per client, raises ToolError.
``AuthRateLimiter``  — per IP, returns (allowed, retry_after_secs).

Both accept an injectable *clock* callable so tests can advance time without
sleeping.  The production default is ``time.time``.

``FakeClock`` is exported so test modules can import it directly from here.
"""
from __future__ import annotations

import math
import time
from collections.abc import Callable

from mcp.server.mcpserver.exceptions import ToolError

# ---------------------------------------------------------------------------
# Fake clock (test helper, also exported for use in test files)
# ---------------------------------------------------------------------------


class FakeClock:
    """Mutable clock for deterministic rate-limit tests."""

    def __init__(self, start: float = 0.0) -> None:
        self._t = start

    def __call__(self) -> float:
        return self._t

    def advance(self, seconds: float) -> None:
        self._t += seconds


# ---------------------------------------------------------------------------
# Token bucket
# ---------------------------------------------------------------------------


class _TokenBucket:
    """Single-key token bucket.

    Starts full (``capacity`` tokens).  Each ``consume`` uses one token and
    refills linearly at ``capacity / 60.0`` tokens per second (i.e. the bucket
    empties after one sustained minute at the full rate and refills over the
    next minute).

    Thread-safety: not needed — single-process asyncio.
    """

    __slots__ = ("_buckets", "_capacity", "_clock", "_rate")

    def __init__(self, capacity: int, clock: Callable[[], float]) -> None:
        self._capacity = float(capacity)
        # Tokens-per-second refill rate so the bucket empties after one minute
        self._rate: float = capacity / 60.0
        self._clock = clock
        # key → (tokens_remaining, last_refill_time)
        self._buckets: dict[str, tuple[float, float]] = {}

    def consume(self, key: str) -> tuple[bool, float]:
        """Try to consume one token for *key*.

        Returns:
            (True, 0.0)  when the request is allowed.
            (False, n)   when rate-limited; *n* is the number of seconds to
                         wait before the next token is available (rounded up).
        """
        now = self._clock()
        tokens, last = self._buckets.get(key, (self._capacity, now))

        # Refill based on elapsed time
        elapsed = now - last
        tokens = min(self._capacity, tokens + elapsed * self._rate)

        if tokens >= 1.0:
            self._buckets[key] = (tokens - 1.0, now)
            return True, 0.0

        # Rate-limited: how many seconds until the next token arrives?
        wait = math.ceil((1.0 - tokens) / self._rate)
        self._buckets[key] = (tokens, now)
        return False, float(wait)


# ---------------------------------------------------------------------------
# Public rate limiter classes
# ---------------------------------------------------------------------------


class ToolRateLimiter:
    """Rate limiter for MCP tool calls.

    Enforces two independent token buckets:
    - per *subject* (sha256-hashed Google sub, or whatever the caller passes)
    - per *client_id*

    Both must allow the call; if either bucket is exhausted a ``ToolError``
    is raised with a human-readable "retry after N s" message.

    Args:
        per_subject_rpm: Max calls per minute per subject (default 60).
        per_client_rpm: Max calls per minute per client_id (default 300).
        clock: Time source (default ``time.time``; override in tests).
    """

    def __init__(
        self,
        per_subject_rpm: int = 60,
        per_client_rpm: int = 300,
        clock: Callable[[], float] | None = None,
    ) -> None:
        _clock = clock if clock is not None else time.time
        self._subject = _TokenBucket(per_subject_rpm, _clock)
        self._client = _TokenBucket(per_client_rpm, _clock)

    def check(self, subject: str, client_id: str) -> None:
        """Check rate limits and raise ``ToolError`` if either bucket is empty.

        Call this at the start of every tool invocation (before any BFF I/O).
        """
        ok_s, wait_s = self._subject.consume(subject)
        if not ok_s:
            raise ToolError(f"rate_limited: retry after {int(wait_s)} s")

        ok_c, wait_c = self._client.consume(client_id)
        if not ok_c:
            raise ToolError(f"rate_limited: retry after {int(wait_c)} s")


class AuthRateLimiter:
    """Rate limiter for auth endpoints (HTTP 429 + Retry-After).

    One token bucket per client IP; enforced at the ASGI layer for
    /authorize, /token, /register, /revoke and /oauth/* paths.

    Args:
        per_ip_rpm: Max requests per minute per IP (default 30).
        clock: Time source (default ``time.time``; override in tests).
    """

    def __init__(
        self,
        per_ip_rpm: int = 30,
        clock: Callable[[], float] | None = None,
    ) -> None:
        _clock = clock if clock is not None else time.time
        self._bucket = _TokenBucket(per_ip_rpm, _clock)

    def check(self, ip: str) -> tuple[bool, float]:
        """Return (allowed, retry_after_seconds).

        The caller is responsible for returning HTTP 429 when ``allowed`` is
        False.
        """
        return self._bucket.consume(ip)
