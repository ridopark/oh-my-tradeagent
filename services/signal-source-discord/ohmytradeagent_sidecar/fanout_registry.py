"""Registry-driven copytrade fan-out (Phase B2).

The sidecar's FIRST backend HTTP dependency. Kept in its own module and made
NON-FATAL to signal emission: an HTTP problem here must never crash the watcher
or block emits. Two pieces:

- ``FanoutRegistryClient`` — a thin httpx wrapper that GETs api-gateway's
  ``/internal/copytrade-fanout-targets`` (Phase B1) with the service-token
  bearer and parses the ``(tenant_id, strategy_id)`` list.
- ``FanoutRefresher`` — the fail-safe poll loop. On success it applies the new
  targets to the watcher (via an injected ``apply_targets`` callback, which does
  the union-with-primary + dedupe). On ANY failure — HTTP error, non-2xx,
  malformed body, or an *empty* list (a healthy registry always contains at
  least the primary's own enabled row, so empty signals a blip) — it RETAINS the
  last good set: it simply does not apply, so the watcher keeps delivering to the
  previously-known tenants and never drops to zero. Repeated consecutive failures
  escalate from WARN to an alert-worthy ERROR.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Callable

import httpx

FANOUT_TARGETS_PATH = "/internal/copytrade-fanout-targets"

_Target = tuple[str, str]


def parse_targets(body: Any) -> list[_Target]:
    """Map a B1 response body into a list of ``(tenant_id, strategy_id)`` tuples.

    B1 returns the wrapped shape ``{"targets": [...], "count": n}``. Raises
    ``ValueError`` on anything malformed so the caller can treat it as a failed
    poll and retain the last good set.
    """
    if not isinstance(body, dict):
        raise ValueError(f"unexpected fan-out body type: {type(body).__name__}")
    raw = body.get("targets")
    if not isinstance(raw, list):
        raise ValueError("fan-out body 'targets' is not a list")

    out: list[_Target] = []
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError(f"fan-out target entry is not an object: {item!r}")
        tenant = item.get("tenant_id")
        strategy = item.get("strategy_id")
        if not tenant or not strategy:
            raise ValueError(f"fan-out target missing tenant_id/strategy_id: {item!r}")
        out.append((str(tenant), str(strategy)))
    return out


class FanoutRegistryClient:
    """Isolated HTTP client for the B1 fan-out registry endpoint.

    Owns its httpx client unless one is injected (tests inject a MockTransport
    client). ``fetch_targets`` raises on any transport / status / parse problem;
    the refresher decides what to do with that.
    """

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        client: httpx.AsyncClient | None = None,
        timeout: float = 10.0,
    ) -> None:
        self._token = token
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"), timeout=timeout
        )

    async def fetch_targets(self) -> list[_Target]:
        resp = await self._client.get(
            FANOUT_TARGETS_PATH,
            headers={"Authorization": f"Bearer {self._token}"},
        )
        resp.raise_for_status()
        return parse_targets(resp.json())

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


class FanoutRefresher:
    """Fail-safe poll loop that swaps the watcher's fan-out targets on an interval.

    ``apply_targets`` is the watcher's ``update_targets`` (union-with-primary +
    dedupe + atomic rebind). We never call it on a failed/empty poll, so the last
    good set survives.
    """

    def __init__(
        self,
        *,
        client: FanoutRegistryClient,
        apply_targets: Callable[[list[_Target]], None],
        log: logging.Logger,
        refresh_secs: float,
        error_threshold: int = 3,
    ) -> None:
        self._client = client
        self._apply = apply_targets
        self._log = log
        self._refresh_secs = refresh_secs
        self._error_threshold = error_threshold
        self._consecutive_failures = 0

    async def refresh_once(self) -> bool:
        """One poll cycle. Returns True on a successful apply, False otherwise
        (last good retained). Never raises — an HTTP problem must not propagate
        into the emit path."""
        try:
            targets = await self._client.fetch_targets()
        except Exception as exc:  # noqa: BLE001 — non-fatal by design
            return self._on_failure(f"fan-out poll failed: {exc!r}")

        if not targets:
            # A healthy registry always includes the primary's own enabled row;
            # an empty list means a blip/bad query — retain last good rather than
            # collapse the fan-out.
            return self._on_failure("fan-out poll returned an empty target set")

        self._consecutive_failures = 0
        self._apply(targets)
        self._log.info("fan-out targets refreshed (%d from registry)", len(targets))
        return True

    def _on_failure(self, msg: str) -> bool:
        self._consecutive_failures += 1
        if self._consecutive_failures >= self._error_threshold:
            self._log.error(
                "%s — %d consecutive failures; RETAINING last good fan-out set",
                msg,
                self._consecutive_failures,
            )
        else:
            self._log.warning("%s — retaining last good fan-out set", msg)
        return False

    async def run(self) -> None:
        """Poll forever on the refresh interval. Isolated/non-fatal: the caller
        adds a done-callback so a crash here is logged, never propagated to the
        trading-critical signal watcher."""
        while True:
            await self.refresh_once()
            await asyncio.sleep(self._refresh_secs)
