"""End-to-end stdio test — Seam G.

Launches the actual `omt-mcp` process via `Client(StdioServerParameters(...))`
against a real `http.server` stub on an ephemeral port. Verifies:
- The entrypoint starts, connects to the stub BFF and serves.
- `list_tools()` includes `get_portfolio_summary`.
- `list_prompts()` includes `analyze_positions`.
- stdout is clean (the MCP protocol handshake succeeding is the proof).
- stderr may be non-empty (logging goes there).
"""
from __future__ import annotations

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

# The mcp-server package root (services/mcp-server/).
PACKAGE_ROOT = str(Path(__file__).parent.parent)

import pytest
from mcp import Client
from mcp.client.stdio import StdioServerParameters

TEST_TOKEN = "test-mcp-token-acme"
TEST_TENANT = "acme"

# Fixture responses served by the local stub HTTP server.
_WHOAMI = json.dumps({"tenant_id": TEST_TENANT, "token_id": "87121ec9ab39"}).encode()
_PORTFOLIO = json.dumps(
    {
        "trading_day": "2026-11-13",
        "open_positions_count": 0,
        "open_positions_degraded": False,
        "sum_open_notional": 0.0,
        "sum_open_notional_basis": "cost_basis_at_entry",
        "realized_pnl_today": 0.0,
        "realized_pnl_all_time": 0.0,
        "account_equity": [],
        "open_positions": [],
    }
).encode()
_KILLSWITCH = json.dumps(
    {"tripped": False, "trippedAt": None, "reason": None, "resettableAt": None}
).encode()
_NOTIONAL_CAP = json.dumps(
    {
        "tenant_id": TEST_TENANT,
        "formula": "...",
        "sum_open_notional": 0.0,
        "sum_open_notional_basis": "cost_basis_at_entry",
        "open_positions_count": 0,
        "strategies": [],
    }
).encode()


class StubHandler(BaseHTTPRequestHandler):
    def log_message(self, *args, **kwargs):  # silence server logs
        pass

    def do_GET(self):
        # Only check the Authorization header.
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {TEST_TOKEN}":
            self._send(401, b'{"error":"unauthorized"}')
            return

        path = self.path.split("?")[0]
        if path == "/api/mcp/whoami":
            self._send(200, _WHOAMI)
        elif path == "/api/portfolio":
            self._send(200, _PORTFOLIO)
        elif path == "/api/account-killswitch":
            self._send(200, _KILLSWITCH)
        elif path == "/api/notional-cap":
            self._send(200, _NOTIONAL_CAP)
        else:
            self._send(404, b'{"error":"not_found"}')

    def _send(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture(scope="module")
def stub_bff_port() -> int:
    """Start a stub HTTP server on an ephemeral port and return the port."""
    server = HTTPServer(("127.0.0.1", 0), StubHandler)
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return port


async def test_stdio_endToEnd_listsToolsAndPrompt(stub_bff_port):
    """Seam G: subprocess start, tools+prompts listing, stdout cleanliness."""
    env = {
        **os.environ,
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN": TEST_TOKEN,
        "OMT_BFF_URL": f"http://127.0.0.1:{stub_bff_port}",
    }

    params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "ohmytradeagent_mcp"],
        env=env,
        # cwd = package root so the editable `ohmytradeagent_mcp/` directory is importable.
        cwd=PACKAGE_ROOT,
    )

    async with Client(params) as client:
        tools_result = await client.list_tools()
        tool_names = {t.name for t in tools_result.tools}

        prompts_result = await client.list_prompts()
        prompt_names = {p.name for p in prompts_result.prompts}

    # Step 2 ships only get_portfolio_summary; steps 3-4 add more.
    assert "get_portfolio_summary" in tool_names, f"got: {tool_names}"
    assert "analyze_positions" in prompt_names, f"got: {prompt_names}"
