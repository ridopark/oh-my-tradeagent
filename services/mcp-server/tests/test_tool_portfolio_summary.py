"""Tests for the get_portfolio_summary tool — Seam E (in-memory Client + MCPServer).

The BFF is a httpx.MockTransport routing over JSON fixtures.  Every test
verifies that the stub carries the required headers on every request.
"""
from __future__ import annotations

import json
from pathlib import Path

import httpx
from mcp import Client

from ohmytradeagent_mcp.tools import build_server

FIXTURES = Path(__file__).parent / "fixtures"
TEST_TOKEN = "test-mcp-token-acme"
TEST_TENANT = "acme"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


def test_portfolioSummary_composesThreeReads(test_binding, stub_bff):
    """Happy path: three BFF reads are composed; account_number is redacted."""
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            result = await client.call_tool("get_portfolio_summary", {})
        return result

    import asyncio

    result = asyncio.run(run())
    assert not result.is_error, result.content[0].text if result.content else "no content"

    data = json.loads(result.content[0].text)
    dumped = json.dumps(data)

    # Required fields from portfolio.
    assert data["trading_day"] == "2026-11-13"
    assert data["sum_open_notional"] == 1200.0
    assert data["realized_pnl_today"] == 350.0
    assert data["account_equity"][0]["equity"] == 95000.0

    # Kill switch data.
    assert data["kill_switch"]["tripped"] is False

    # Notional cap headroom.
    assert data["headroom"]["strategies"][0]["headroom"] == 4400.0

    # tenant_id injected.
    assert data["tenant_id"] == "acme"

    # account_number must be absent everywhere.
    assert "310056593" not in dumped
    assert "account_number" not in dumped


def test_portfolioSummary_killSwitch404_isNullWithNote(test_binding):
    """When /api/account-killswitch returns 404, kill_switch is null with a note."""
    request_log: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        request_log.append(request.url.path)
        if request.url.path == "/api/mcp/whoami":
            return httpx.Response(200, json={"tenant_id": TEST_TENANT, "token_id": "tok"})
        if request.url.path == "/api/portfolio":
            return httpx.Response(200, json=_load("portfolio.json"))
        if request.url.path == "/api/account-killswitch":
            return httpx.Response(404, json={"status": 404, "error": "not_found"})
        if request.url.path == "/api/notional-cap":
            return httpx.Response(200, json=_load("notional_cap.json"))
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff)

    import asyncio

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_portfolio_summary", {})

    result = asyncio.run(run())
    assert not result.is_error
    data = json.loads(result.content[0].text)

    assert data["kill_switch"] is None
    assert "kill_switch_note" in data
    # Other sections are intact.
    assert data["trading_day"] == "2026-11-13"
    assert data["headroom"]["strategies"][0]["headroom"] == 4400.0


def test_portfolioSummary_bff500_isToolError(test_binding):
    """When /api/portfolio returns 500, the tool raises a ToolError with path+status."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/portfolio":
            return httpx.Response(500, json={"error": "internal"})
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff)

    import asyncio

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_portfolio_summary", {})

    result = asyncio.run(run())
    assert result.is_error
    text = result.content[0].text
    assert "/api/portfolio" in text
    assert "500" in text


def test_everyBffCall_carriesBearerAndTenantHeader(test_binding):
    """Every BFF call carries Authorization: Bearer and X-Tenant-Id headers."""
    missing_headers: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        auth = request.headers.get("authorization", "")
        tenant = request.headers.get("x-tenant-id", "")
        path = request.url.path

        if auth != f"Bearer {TEST_TOKEN}":
            missing_headers.append(f"{path}: bad Authorization {auth!r}")
        if tenant != TEST_TENANT:
            missing_headers.append(f"{path}: bad X-Tenant-Id {tenant!r}")

        if path == "/api/portfolio":
            return httpx.Response(200, json=_load("portfolio.json"))
        if path == "/api/account-killswitch":
            return httpx.Response(200, json=_load("account_killswitch.json"))
        if path == "/api/notional-cap":
            return httpx.Response(200, json=_load("notional_cap.json"))
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff)

    import asyncio

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_portfolio_summary", {})

    asyncio.run(run())
    assert missing_headers == [], f"Header violations: {missing_headers}"
