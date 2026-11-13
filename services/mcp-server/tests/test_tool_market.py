"""Tests for get_quote, get_chain, get_underlying_context — Seam E.

All tests use the in-memory Client(MCPServer) transport over a httpx.MockTransport
stub BFF, following the same pattern as test_tool_positions.py.
"""
from __future__ import annotations

import asyncio
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


def _run(coro):
    return asyncio.run(coro)


# ---------------------------------------------------------------------------
# test 1: get_quote — all BFF fields pass through
# ---------------------------------------------------------------------------


def test_getQuote_returnsBffFields(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_quote", {"occ": "SMCI261120C00050000"})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)

    assert data["occ"] == "SMCI261120C00050000"
    assert data["underlying"] == "SMCI"
    assert data["expiry"] == "2026-11-20"
    assert data["strike"] == "500.00"
    assert data["right"] == "C"
    assert data["bid"] == 2.00
    assert data["mid"] == 2.10
    assert data["ask"] == 2.20
    assert data["quoted_at"] == "2026-11-13T15:30:00Z"
    assert data["tenant_id"] == "acme"


# ---------------------------------------------------------------------------
# test 2: get_quote — 404 manual_entry_disabled is a clear ToolError
# ---------------------------------------------------------------------------


def test_getQuote_manualEntryDisabled404_isClearToolError(test_binding):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/entries/quote":
            return httpx.Response(404, json={"error": "manual_entry_disabled"})
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_quote", {"occ": "SMCI261120C00050000"})

    result = _run(run())
    assert result.is_error
    text = result.content[0].text
    assert "manual entry is disabled" in text
    assert "manual_entry_disabled" in text
    # Must not raise as a bare Python exception
    assert "Traceback" not in text


# ---------------------------------------------------------------------------
# test 3: get_quote — 503 and 400 are ToolErrors with BFF detail
# ---------------------------------------------------------------------------


def test_getQuote_503_and_400_areToolErrorsWithBffDetail(test_binding):
    # Test 503 quote_unavailable
    def handler_503(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"error": "quote_unavailable", "occ": "SMCI261120C00050000"})

    from ohmytradeagent_mcp.bff import BffClient

    bff_503 = BffClient(
        "http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler_503)
    )
    mcp_503 = build_server(test_binding, bff_503)

    async def run_503():
        async with Client(mcp_503) as client:
            return await client.call_tool("get_quote", {"occ": "SMCI261120C00050000"})

    result_503 = _run(run_503())
    assert result_503.is_error
    assert "quote_unavailable" in result_503.content[0].text

    # Test 400 invalid_occ
    def handler_400(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400,
            json={"error": "invalid_occ", "detail": "symbol must be 1-6 letters"},
        )

    bff_400 = BffClient(
        "http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler_400)
    )
    mcp_400 = build_server(test_binding, bff_400)

    async def run_400():
        async with Client(mcp_400) as client:
            return await client.call_tool("get_quote", {"occ": "BAD!!!"})

    result_400 = _run(run_400())
    assert result_400.is_error
    text_400 = result_400.content[0].text
    assert "invalid_occ" in text_400
    assert "symbol must be 1-6 letters" in text_400


# ---------------------------------------------------------------------------
# test 4a: get_chain — without expiration lists expirations
# ---------------------------------------------------------------------------


def test_getChain_withoutExpiration_listsExpirations(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_chain", {"underlying": "SMCI"})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)

    assert data["underlying"] == "SMCI"
    assert data["spot"] == 95.50
    assert "2026-11-20" in data["expirations"]
    assert "contracts" not in data


# ---------------------------------------------------------------------------
# test 4b: get_chain — with expiration and right lists contracts (iv, delta, volume)
# ---------------------------------------------------------------------------


def test_getChain_withExpirationAndRight_listsContracts(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool(
                "get_chain",
                {"underlying": "SMCI", "expiration": "2026-11-20", "right": "C"},
            )

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)

    assert data["underlying"] == "SMCI"
    assert data["expiration"] == "2026-11-20"
    assert data["right"] == "C"
    contracts = data["contracts"]
    assert len(contracts) == 2
    # iv, delta, volume must be present
    for c in contracts:
        assert "iv" in c
        assert "delta" in c
        assert "volume" in c


# ---------------------------------------------------------------------------
# test 4c: get_chain — expiration without right is ToolError
# ---------------------------------------------------------------------------


def test_getChain_expirationWithoutRight_isToolError(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool(
                "get_chain", {"underlying": "SMCI", "expiration": "2026-11-20"}
            )

    result = _run(run())
    assert result.is_error
    assert "right" in result.content[0].text.lower()


# ---------------------------------------------------------------------------
# test 5: get_underlying_context — composes flow and darkpool
# ---------------------------------------------------------------------------


def test_underlyingContext_composesFlowAndDarkpool(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_underlying_context", {"ticker": "SMCI"})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)

    assert data["ticker"] == "SMCI"

    # flow_summary: the SMCI row
    fs = data["flow_summary"]
    assert fs["ticker"] == "SMCI"
    assert fs["call_premium"] == 1500000.0
    assert fs["quoted_premium_share"] == 0.85

    # flow_orders: 2 items
    orders = data["flow_orders"]
    assert len(orders) == 2

    # underlying_price from latest (first) flow order
    assert data["underlying_price"] == 95.50
    assert data["underlying_price_source"] == "latest_flow_order"

    # day_move is explicitly null
    assert data["day_move"] is None

    # darkpool: summary filtered to SMCI, prints
    dp = data["darkpool"]
    assert dp is not None
    summary = dp["summary"]
    assert summary["symbol"] == "SMCI"
    assert summary["dp_share"] == 0.25
    assert summary["buy_ratio"] == 0.60
    assert len(summary["levels"]) == 2

    prints = dp["prints"]
    assert len(prints) == 2


# ---------------------------------------------------------------------------
# test 6: get_underlying_context — darkpool 404 → null with note
# ---------------------------------------------------------------------------


def test_underlyingContext_darkpool404_isNullWithNote(test_binding):
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/flow/summary":
            return httpx.Response(200, json=_load("flow_summary.json"))
        if path == "/api/flow/orders":
            return httpx.Response(200, json=_load("flow_orders.json"))
        if path in ("/api/darkpool/summary", "/api/darkpool/prints"):
            # Spring default problem body
            return httpx.Response(
                404,
                json={"timestamp": "2026-11-13T15:00:00.000+00:00", "status": 404, "error": "Not Found"},
            )
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_underlying_context", {"ticker": "SMCI"})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)

    # darkpool must be None (not {})
    assert data["darkpool"] is None
    assert "darkpool_note" in data
    assert "BFF 404" in data["darkpool_note"]

    # Flow sections are still intact
    assert data["flow_summary"] is not None
    assert len(data["flow_orders"]) == 2
