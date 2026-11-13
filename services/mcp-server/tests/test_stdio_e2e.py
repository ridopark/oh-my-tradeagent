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
import subprocess
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

    # All 7 tools must be present after Step 4.
    expected_tools = {
        "get_portfolio_summary",
        "list_open_positions",
        "get_position_history",
        "get_quote",
        "get_chain",
        "get_underlying_context",
        "get_recent_closed_trades",
    }
    assert expected_tools == tool_names, (
        f"Expected exactly {expected_tools}; got {tool_names}"
    )
    assert prompt_names == {"analyze_positions"}, f"got: {prompt_names}"


def test_stdout_cleanliness_rawSubprocess(stub_bff_port):
    """Seam G (raw subprocess): every stdout line must be valid JSON-RPC 2.0.

    Unlike `test_stdio_endToEnd_listsToolsAndPrompt`, which drives the server
    through the MCP SDK's stdio client (which tolerates non-JSON stdout lines by
    wrapping them as Exception values and continuing), this test reads the raw
    bytes from the subprocess stdout pipe and asserts that every non-empty line
    is a valid JSON-RPC 2.0 object.

    A bare ``print("starting")`` or any stdout log handler causes this test to
    fail.  Stderr is captured separately via PIPE — logging output there does
    NOT cause failure.
    """
    env = {
        **os.environ,
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN": TEST_TOKEN,
        "OMT_BFF_URL": f"http://127.0.0.1:{stub_bff_port}",
    }

    # MCP stdio format: one JSON-RPC message per line (\n-delimited).
    # Sequence: initialize → notifications/initialized → tools/list
    messages = (
        json.dumps(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "test-raw", "version": "0.1"},
                },
            }
        )
        + "\n"
        + json.dumps(
            {
                "jsonrpc": "2.0",
                "method": "notifications/initialized",
                "params": {},
            }
        )
        + "\n"
        + json.dumps(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/list",
                "params": {},
            }
        )
        + "\n"
    ).encode()

    proc = subprocess.Popen(
        [sys.executable, "-m", "ohmytradeagent_mcp"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        cwd=PACKAGE_ROOT,
    )

    # Keep stdin OPEN until the tools/list reply (id=2) has arrived, as a real client does.
    # Closing stdin right after writing (communicate(input=...)) races the server: on EOF
    # it may shut down before handling tools/list, which made this test fail ~1 run in 4.
    seen: list[bytes] = []
    got_reply = threading.Event()

    def _read_stdout() -> None:
        for raw in iter(proc.stdout.readline, b""):
            seen.append(raw)
            if b'"id":2' in raw.replace(b" ", b""):
                got_reply.set()

    reader = threading.Thread(target=_read_stdout, daemon=True)
    reader.start()
    proc.stdin.write(messages)
    proc.stdin.flush()
    replied = got_reply.wait(timeout=15)
    proc.stdin.close()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
    reader.join(timeout=5)
    stderr_bytes = proc.stderr.read()
    stdout_bytes = b"".join(seen)
    if not replied:
        pytest.fail(
            "tools/list reply (id=2) never arrived within 15s.\n"
            f"stdout: {stdout_bytes!r}\nstderr: {stderr_bytes.decode(errors='replace')}"
        )

    # Split on newlines; ignore empty lines (trailing \n etc.).
    lines = [line for line in stdout_bytes.decode("utf-8").split("\n") if line.strip()]

    assert lines, (
        f"Server produced no stdout output.\n"
        f"stderr: {stderr_bytes.decode(errors='replace')}"
    )

    # Every non-empty stdout line must be a valid JSON-RPC 2.0 object.
    # This assertion catches both bare print() calls and stdout log handlers:
    # any stray text that is not a JSON object with "jsonrpc":"2.0" fails here.
    parsed: list[dict] = []
    for line in lines:
        try:
            msg = json.loads(line)
        except json.JSONDecodeError as exc:
            pytest.fail(
                f"stdout line is not valid JSON — this would happen with a bare "
                f"print() or a stdout log handler.\n"
                f"Line: {line!r}\n"
                f"Error: {exc}\n"
                f"Full stderr: {stderr_bytes.decode(errors='replace')}"
            )
        assert isinstance(msg, dict) and msg.get("jsonrpc") == "2.0", (
            f"stdout line is not JSON-RPC 2.0: {line!r}"
        )
        parsed.append(msg)

    # The tools/list response (id=2) must arrive.
    tools_responses = [m for m in parsed if m.get("id") == 2]
    assert tools_responses, (
        f"tools/list response (id=2) not found in stdout.\n"
        f"stdout lines: {lines}\n"
        f"stderr: {stderr_bytes.decode(errors='replace')}"
    )
    tools_result = tools_responses[0].get("result", {})
    tool_names = {t["name"] for t in tools_result.get("tools", [])}
    expected = {
        "get_portfolio_summary",
        "list_open_positions",
        "get_position_history",
        "get_quote",
        "get_chain",
        "get_underlying_context",
        "get_recent_closed_trades",
    }
    assert expected == tool_names, f"expected {expected}, got: {tool_names}"
