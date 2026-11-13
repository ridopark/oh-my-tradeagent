"""Tests for the analyze_positions prompt — Seam E.

Verifies that the prompt message names all 7 tool names and states that the
server cannot trade.
"""
from __future__ import annotations

import asyncio

from mcp import Client

from ohmytradeagent_mcp.tools import build_server

_ALL_TOOLS = [
    "get_portfolio_summary",
    "list_open_positions",
    "get_position_history",
    "get_quote",
    "get_chain",
    "get_underlying_context",
    "get_recent_closed_trades",
]


def _run(coro):
    return asyncio.run(coro)


# ---------------------------------------------------------------------------
# test 7: analyze_positions names every tool and states the no-trade constraint
# ---------------------------------------------------------------------------


def test_prompt_analyzePositions_namesEveryTool(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            result = await client.get_prompt("analyze_positions", {})
        return result

    result = _run(run())
    messages = result.messages
    assert messages, "Prompt returned no messages"
    text = messages[0].content.text

    # All 7 tool names must appear
    for tool in _ALL_TOOLS:
        assert tool in text, f"Prompt missing tool name: {tool!r}"

    # Must state the server cannot trade
    assert "cannot trade" in text.lower(), "Prompt must say the server cannot trade"


def test_prompt_analyzePositions_appendsFocus(test_binding, stub_bff):
    mcp = build_server(test_binding, stub_bff)

    async def run():
        async with Client(mcp) as client:
            result = await client.get_prompt("analyze_positions", {"focus": "SMCI delta risk"})
        return result

    result = _run(run())
    text = result.messages[0].content.text
    assert "SMCI delta risk" in text
    assert "Pay particular attention to" in text
