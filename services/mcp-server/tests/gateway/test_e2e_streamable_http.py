"""Seam I: real mcp.Client over streamable_http_client against uvicorn.

Verifies transport framing, Host allow-list, stateless_http, 7 tools + 1 prompt.
The bearer is obtained by seeding the InMemoryStore directly (no AS in Step 3a).
"""
from __future__ import annotations

import threading

import httpx as httpx2
import pytest
import uvicorn
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from tests.gateway.fixtures import ACME_SUB, make_app, seed_token


def _start_uvicorn(app, host: str, port: int) -> threading.Thread:
    config = uvicorn.Config(app, host=host, port=port, log_level="error")
    server = uvicorn.Server(config)
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    # Wait for startup
    import time

    deadline = time.time() + 10
    while time.time() < deadline:
        try:
            import socket

            with socket.create_connection((host, port), timeout=0.3):
                break
        except (OSError, ConnectionRefusedError):
            time.sleep(0.1)
    return t, server


@pytest.mark.asyncio
async def test_streamableHttp_realClient_listsSevenToolsAndOnePrompt_andCallsOne():
    """Seam I: uvicorn thread + mcp.Client + streamable_http_client."""
    # Use a fresh InMemoryStore with an ephemeral port.
    # Include 127.0.0.1:* in allowed_hosts since uvicorn binds to loopback and
    # the client's Host header will be 127.0.0.1:{port} (no loopback auto-append
    # any more — item 5 fix).
    app, store = make_app(
        return_store=True,
        extra_env={"OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com,127.0.0.1:*"},
    )
    token = seed_token(store, "tok-e2e", sub=ACME_SUB)

    import socket

    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()

    _t, server = _start_uvicorn(app, "127.0.0.1", port)
    url = f"http://127.0.0.1:{port}/mcp"

    try:
        http_client = httpx2.AsyncClient(
            headers={"Authorization": f"Bearer {token}"},
            # stateless_http=True: no session affinity needed
        )
        # MCP 2.3.0: pass the Transport directly to Client instead of the old
        # (r, w) pattern.  Client.__aenter__ performs the initialize handshake.
        transport = streamable_http_client(url, http_client=http_client)
        async with Client(transport) as client:
            tools = await client.list_tools()
            tool_names = {t.name for t in tools.tools}
            prompts = await client.list_prompts()
            prompt_names = {p.name for p in prompts.prompts}
            # Call one tool
            result = await client.call_tool("get_portfolio_summary", {})
    finally:
        server.should_exit = True

    expected_tools = {
        "get_portfolio_summary",
        "list_open_positions",
        "get_position_history",
        "get_quote",
        "get_chain",
        "get_underlying_context",
        "get_recent_closed_trades",
    }
    assert tool_names == expected_tools, f"got {tool_names}"
    assert prompt_names == {"analyze_positions"}, f"got {prompt_names}"
    assert not result.is_error, result
    # Schema for every tool must include the optional `tenant` parameter.
    # MCP 2.3.0 uses snake_case input_schema; fall back to inputSchema for older SDK.
    for tool in tools.tools:
        schema = getattr(tool, "input_schema", None) or getattr(tool, "inputSchema", {})
        props = schema.get("properties", {})
        assert "tenant" in props, f"tool {tool.name!r} missing tenant param in schema"
