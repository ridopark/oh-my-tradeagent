"""Tenant resolver — verifies the token is bound to the expected tenant.

Seam: `StaticTokenResolver.resolve()` calls `/api/mcp/whoami` on the BFF
and raises typed exceptions on every failure path so `main.run()` can print
a one-line message and exit non-zero without a traceback.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

import httpx

from ohmytradeagent_mcp.bff import BffClient, BffError


@dataclass(frozen=True)
class TenantBinding:
    """The tenant and token-id the credential is bound to, as reported by the BFF."""

    tenant_id: str
    token_id: str
    client_id: str | None = None


class TenantMismatch(Exception):
    """The token is bound to a different tenant than OMT_TENANT."""

    def __init__(self, expected: str, bound: str) -> None:
        self.expected = expected
        self.bound = bound
        super().__init__(f"tenant mismatch: configured={expected!r} bound={bound!r}")


class Unauthorized(Exception):
    """The BFF returned 401 or 403."""

    def __init__(self, status: int, detail: str = "") -> None:
        self.status = status
        super().__init__(f"unauthorized: BFF returned {status}" + (f" ({detail})" if detail else ""))


class BffUnreachable(Exception):
    """The BFF could not be reached (connection error or timeout)."""

    def __init__(self, url: str, cause: str) -> None:
        self.url = url
        super().__init__(f"BFF unreachable at {url}: {cause}")


class BffConfigError(Exception):
    """The BFF URL is misconfigured (e.g. missing http:// scheme)."""

    def __init__(self, url: str, detail: str) -> None:
        self.url = url
        self.detail = detail
        super().__init__(f"BFF config error for OMT_BFF_URL={url!r}: {detail}")


@runtime_checkable
class TenantResolver(Protocol):
    async def resolve(self) -> TenantBinding: ...


class StaticTokenResolver:
    """Resolves a static MCP read token by calling `/api/mcp/whoami`.

    On success returns a `TenantBinding`; on failure raises one of:
    - `Unauthorized` (401 or 403 from the BFF)
    - `TenantMismatch` (token bound to a different tenant)
    - `BffUnreachable` (connection error)
    """

    def __init__(self, bff: BffClient, expected_tenant: str) -> None:
        self._bff = bff
        self._expected = expected_tenant

    async def resolve(self) -> TenantBinding:
        try:
            data = await self._bff.get("/api/mcp/whoami")
        except BffError as exc:
            if exc.status in (401, 403):
                detail = ""
                if isinstance(exc.body, dict):
                    detail = exc.body.get("error", "")
                elif isinstance(exc.body, str):
                    detail = exc.body
                raise Unauthorized(exc.status, detail) from exc
            raise
        except (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError) as exc:
            raise BffUnreachable(self._bff.base_url, str(exc)) from exc
        except httpx.UnsupportedProtocol as exc:
            raise BffConfigError(self._bff.base_url, str(exc)) from exc

        if not isinstance(data, dict):  # e.g. a proxy/captive page: report as a binding mismatch
            data = {}
        bound = data.get("tenant_id", "")
        token_id = data.get("token_id", "")

        if bound != self._expected:
            raise TenantMismatch(self._expected, bound)

        return TenantBinding(tenant_id=bound, token_id=token_id)
