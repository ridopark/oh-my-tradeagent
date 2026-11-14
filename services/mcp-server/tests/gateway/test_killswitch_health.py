"""Kill switch and health endpoint tests — Seam H.

Tests 10-11: kill switch, host allow-list.
"""
from __future__ import annotations

import httpx
import pytest

from tests.gateway.fixtures import make_app, make_app_disabled

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
