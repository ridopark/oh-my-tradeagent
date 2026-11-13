"""BFF HTTP client.

BffClient makes GET requests to the tenant-dashboard-bff, injecting
`Authorization: Bearer` and `X-Tenant-Id` headers on every call.
An optional httpx transport (MockTransport in tests) is wired at construction
time and used for every request.
"""
from __future__ import annotations

from typing import Any

import httpx

_TIMEOUT = 25.0


class BffError(Exception):
    """Raised when the BFF returns a non-2xx response."""

    def __init__(self, status: int, body: Any) -> None:
        self.status = status
        self.body = body
        super().__init__(f"BFF {status}: {body}")


class BffClient:
    """Thin async wrapper around the BFF REST API."""

    def __init__(
        self,
        base_url: str,
        token: str,
        tenant: str,
        transport: httpx.AsyncBaseTransport | httpx.MockTransport | None = None,
    ) -> None:
        self._base_url = base_url
        self._headers = {
            "Authorization": f"Bearer {token}",
            "X-Tenant-Id": tenant,
        }
        self._transport = transport

    async def get(self, path: str, params: dict[str, str] | None = None) -> dict:
        """Make a GET request to `path` and return the JSON body.

        Raises:
            BffError: on any non-2xx response.
            httpx.ConnectError / httpx.TimeoutException: on network errors
                (callers map these to BffUnreachable).
        """
        async with httpx.AsyncClient(
            base_url=self._base_url,
            headers=self._headers,
            timeout=_TIMEOUT,
            transport=self._transport,  # type: ignore[arg-type]
        ) as client:
            response = await client.get(path, params=params)

        if not response.is_success:
            try:
                body = response.json()
            except (ValueError, KeyError):
                body = response.text
            raise BffError(response.status_code, body)

        try:
            return response.json()
        except ValueError:
            return {}
