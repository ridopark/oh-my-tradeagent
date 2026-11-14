"""Kill switch and health endpoint tests — Seam H.

Tests 10-11: kill switch, host allow-list, unhealthy store.
"""
from __future__ import annotations

import httpx
import pytest

from ohmytradeagent_mcp.gateway.store import InMemoryStore
from tests.gateway.fixtures import (
    BFF_URL,
    PUBLIC_URL,
    TEST_SIGNING_KEY_PEM,
    make_app,
    make_app_disabled,
)


class _UnhealthyStore(InMemoryStore):
    """InMemoryStore that always reports itself as unhealthy."""

    async def is_healthy(self) -> bool:
        return False

# ---------------------------------------------------------------------------
# Test 10: kill switch
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_killSwitch_returns503_everywhereButHealthz():
    """With OMT_GATEWAY_DISABLED=true: /mcp → 503; /healthz → 200."""
    app = make_app_disabled()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        mcp_resp = await client.post("/mcp", json={})
        health_resp = await client.get("/healthz")
        readyz_resp = await client.get("/readyz")

    assert mcp_resp.status_code == 503
    assert mcp_resp.json() == {"error": "disabled"}
    assert health_resp.status_code == 200
    assert readyz_resp.status_code == 503


@pytest.mark.asyncio
async def test_healthz_200_whenEnabled():
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.get("/healthz")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_readyz_200_whenEnabled():
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.get("/readyz")
    assert resp.status_code == 200


# ---------------------------------------------------------------------------
# Test 11: host allow-list
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_readyz_503_whenStoreUnhealthy():
    """/readyz returns 503 when the store is not healthy.

    Guard: ``if not await store.is_healthy()`` in the ``_readyz`` handler.
    RED when that check is removed (readyz always returns 200).
    """
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = _UnhealthyStore()
    app = build_gateway_app(settings, store)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        readyz_resp = await client.get("/readyz")
        healthz_resp = await client.get("/healthz")

    assert readyz_resp.status_code == 503, (
        f"/readyz with unhealthy store must return 503, got {readyz_resp.status_code}"
    )
    assert healthz_resp.status_code == 200, (
        "/healthz must remain 200 regardless of store health"
    )


@pytest.mark.asyncio
async def test_hostNotAllowed_isRejected():
    """Request with Host: evil.example → rejected."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://evil.example",
    ) as client:
        resp = await client.post(
            "/mcp",
            json={},
            headers={"Host": "evil.example"},
        )
    # TransportSecuritySettings rejects the request
    assert resp.status_code in (421, 400, 403)


@pytest.mark.asyncio
async def test_hostAllowed_isServed():
    """Request with the correct public host → bearer check (not host rejection)."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json={},
            headers={"Host": "tradeagent-mcp.ridopark.com"},
        )
    # 401 (bearer missing) rather than a host-rejection status
    assert resp.status_code == 401
