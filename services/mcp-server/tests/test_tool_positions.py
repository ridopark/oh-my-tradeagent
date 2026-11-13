"""Tests for list_open_positions, get_position_history, get_recent_closed_trades — Seam E.

All tests use the in-memory Client(MCPServer) transport over a httpx.MockTransport
stub BFF, following the same pattern as test_tool_portfolio_summary.py.
"""
from __future__ import annotations

import asyncio
import json
from datetime import date
from pathlib import Path

import httpx
from mcp import Client

from ohmytradeagent_mcp.tools import build_server

FIXTURES = Path(__file__).parent / "fixtures"
TEST_TOKEN = "test-mcp-token-acme"
TEST_TENANT = "acme"

# Position A workflow id used as the canonical arg for get_position_history
WF_A = "t-acme/s-copytrade/pos/SMCI 261120C00050000/sig-1"
WF_B = "t-acme/s-copytrade/pos/NVDA 261220P00100000/sig-99"

# Pinned today for DTE tests — SMCI 261120 expires 2026-11-20 → DTE 7
TODAY = date(2026, 11, 13)


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


def _run(coro):
    return asyncio.run(coro)


# ---------------------------------------------------------------------------
# test 1: list_open_positions — joins floor, marks, signal for position A
# ---------------------------------------------------------------------------


def test_listOpenPositions_joinsFloorMarksAndSignal(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff, today=TODAY)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("list_open_positions", {})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)
    positions = data["open_positions"]
    assert len(positions) == 2

    # Position A — has floor, marks, signal
    pos_a = next(p for p in positions if p["workflow_id"] == WF_A)

    # Floor-breach fields
    assert pos_a["floor_status"] == "ok"
    assert pos_a["current_bid"] == 2.10
    assert pos_a["floor_line"] == 1.25

    # Marks fields
    assert pos_a["bid"] == 2.00
    assert pos_a["mid"] == 2.10
    assert pos_a["ask"] == 2.20
    assert pos_a["underlying_price"] == 95.50

    # Signal — author and raw_line wrapped as untrusted
    signal = pos_a["signal"]
    subject = signal["subject"]
    assert subject["author"] == {"untrusted": True, "text": "TradingGuru"}
    assert subject["raw_line"]["untrusted"] is True

    # Position B — no floor row, no marks, no matching signal
    pos_b = next(p for p in positions if p["workflow_id"] == WF_B)
    assert "floor_status" not in pos_b
    assert "bid" not in pos_b
    assert "signal" not in pos_b


# ---------------------------------------------------------------------------
# test 2: DTE, partial_exited_qty, unrealized_pct
# ---------------------------------------------------------------------------


def test_listOpenPositions_derivesDteAndPartialExit(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff, today=TODAY)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("list_open_positions", {})

    result = _run(run())
    assert not result.is_error
    data = json.loads(result.content[0].text)
    positions = data["open_positions"]

    pos_a = next(p for p in positions if p["workflow_id"] == WF_A)
    # SMCI 261120 expires 2026-11-20; today=2026-11-13 → DTE 7
    assert pos_a["dte"] == 7

    # entry_qty 21, remaining_qty 5 → partial_exited_qty 16
    assert pos_a["partial_exited_qty"] == 16

    # unrealized_pct = unrealized_pl / (entry_premium × remaining_qty × 100)
    # = -300.0 / (2.50 × 5 × 100) = -300.0 / 1250.0 = -0.24
    assert abs(pos_a["unrealized_pct"] - (-0.24)) < 1e-9

    # Position B has no entry_qty in fixture → no partial_exited_qty
    pos_b = next(p for p in positions if p["workflow_id"] == WF_B)
    assert "partial_exited_qty" not in pos_b


# ---------------------------------------------------------------------------
# test 3: floor-breach sub-read failure degrades that section only
# ---------------------------------------------------------------------------


def test_listOpenPositions_subReadFailure_degradesThatSectionOnly(test_binding):
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/portfolio":
            return httpx.Response(200, json=_load("portfolio.json"))
        if path == "/api/floor-breach":
            return httpx.Response(500, json={"error": "internal"})
        if path == "/api/live/marks":
            return httpx.Response(200, json=_load("live_marks.json"))
        if path == "/api/signals":
            return httpx.Response(200, json=_load("signals.json"))
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff, today=TODAY)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("list_open_positions", {})

    result = _run(run())
    assert not result.is_error
    data = json.loads(result.content[0].text)

    # Positions are still present
    assert len(data["open_positions"]) == 2
    # Floor-breach section is marked degraded
    assert "floor_breach" in data["degraded"]
    # floor_status is absent from positions
    pos_a = next(p for p in data["open_positions"] if p["workflow_id"] == WF_A)
    assert "floor_status" not in pos_a
    # Marks are still present (marks sub-read succeeded)
    assert "bid" in pos_a


# ---------------------------------------------------------------------------
# test 4: open_positions_degraded from portfolio passes through with note
# ---------------------------------------------------------------------------


def test_listOpenPositions_passesThroughOpenPositionsDegraded(test_binding):
    degraded_portfolio = dict(_load("portfolio.json"))
    degraded_portfolio["open_positions_degraded"] = True

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/portfolio":
            return httpx.Response(200, json=degraded_portfolio)
        if path in ("/api/floor-breach", "/api/live/marks", "/api/signals"):
            return httpx.Response(200, json={"positions": [], "marks": [], "items": []})
        return httpx.Response(404, json={"error": "not_found"})

    from ohmytradeagent_mcp.bff import BffClient

    bff = BffClient("http://bff.test", TEST_TOKEN, TEST_TENANT, httpx.MockTransport(handler))
    mcp = build_server(test_binding, bff, today=TODAY)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("list_open_positions", {})

    result = _run(run())
    assert not result.is_error
    data = json.loads(result.content[0].text)
    assert data["open_positions_degraded"] is True
    assert "note" in data


# ---------------------------------------------------------------------------
# test 5: position history filters by workflow_id and compact option_symbol
# ---------------------------------------------------------------------------


def test_positionHistory_filtersByWorkflowIdAndOcc(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_position_history", {"workflow_id": WF_A})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)
    dumped = json.dumps(data)

    # Only A's fills (evt-1 and evt-2)
    fills = data["fills"]
    assert len(fills) == 2
    fill_ids = {f["event_id"] for f in fills}
    assert fill_ids == {"evt-1", "evt-2"}
    # B's fill (evt-3) must not appear
    assert "evt-3" not in dumped

    # Only A's order (k-1); B's (k-99) excluded
    orders = data["orders"]
    assert len(orders) == 1
    assert orders[0]["intent_key"] == "k-1"
    assert "k-99" not in dumped

    # Subject is a dict (parsed from JSON string)
    assert isinstance(fills[0]["subject"], dict)

    # broker_order_id must be absent everywhere
    assert "broker_order_id" not in dumped


# ---------------------------------------------------------------------------
# test 6: bad workflow_id argument → ToolError mentioning /pos/
# ---------------------------------------------------------------------------


def test_positionHistory_badArgument_isToolError(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_position_history", {"workflow_id": "not-a-position"})

    result = _run(run())
    assert result.is_error
    text = result.content[0].text
    assert "/pos/" in text


# ---------------------------------------------------------------------------
# test 7: recent closed trades — newest first, both kinds present
# ---------------------------------------------------------------------------


def test_recentClosedTrades_newestFirst_keepsKind(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_recent_closed_trades", {})

    result = _run(run())
    assert not result.is_error, result.content[0].text
    data = json.loads(result.content[0].text)

    items = data["items"]
    assert len(items) == 3

    # Newest first: evt-1 (PartialExitFilled) is the most recent
    assert items[0]["occurred_at"] == "2026-11-13T12:00:00Z"
    assert items[-1]["occurred_at"] == "2026-11-09T11:00:00Z"

    # Both kinds are present
    kinds = {i["kind"] for i in items}
    assert "PartialExitFilled" in kinds
    assert "EntryFilled" in kinds


# ---------------------------------------------------------------------------
# test 8: every tool is fully redacted — sweep of sensitive literals
# ---------------------------------------------------------------------------

# Sensitive id VALUES from fixtures that must never appear in tool output
_REDACTED_VALUES = [
    "310056593",       # account_number in portfolio.json
    "brk-exit-001",   # broker_order_id in trades.json
    "brk-entry-001",  # broker_order_id in trades.json + orders.json
    "brk-nvda-001",   # broker_order_id in trades.json + orders.json
    "operator@example.com",  # operator_id in signals.json subject (D1)
]
# Sensitive KEY NAMES that must never appear in tool output
_REDACTED_KEYS = ["account_number", "broker_order_id", "operator_id"]

# Canonical args per tool (others take no arguments)
_TOOL_ARGS: dict[str, dict] = {
    "get_position_history": {"workflow_id": WF_A},
    "get_quote": {"occ": "SMCI261120C00050000"},
    "get_chain": {"underlying": "SMCI"},  # no expiration → lists expirations (safe path)
    "get_underlying_context": {"ticker": "SMCI"},
}


def test_everyTool_isRedacted(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff, today=TODAY)

    async def run():
        async with Client(mcp) as client:
            tools_result = await client.list_tools()
            tool_names = [t.name for t in tools_result.tools]
            results = {}
            for name in tool_names:
                args = _TOOL_ARGS.get(name, {})
                res = await client.call_tool(name, args)
                results[name] = res
        return tool_names, results

    tool_names, results = _run(run())

    # Must have exactly 7 tools after Step 4
    assert len(tool_names) == 7, f"Expected 7 tools; got {tool_names}"

    for name, result in results.items():
        assert not result.is_error, f"{name} unexpectedly errored: {result.content[0].text}"
        raw = result.content[0].text

        for val in _REDACTED_VALUES:
            assert val not in raw, f"Tool {name!r}: fixture value {val!r} not redacted"

        for key in _REDACTED_KEYS:
            # Check the key string as a JSON key (always followed by ": " or ":") is absent
            assert f'"{key}"' not in raw, f"Tool {name!r}: key {key!r} not redacted"

        # Finding C: email addresses must not reach the AI provider
        assert "@example.com" not in raw, f"Tool {name!r}: email address not redacted"
