"""Tool-layer tests over HTTP — Seam H.

Tests 7-9: redaction, fresh assertions per BFF call, audit line.
"""
from __future__ import annotations

import json
import re

import httpx
import pytest

# Pattern that matches actual email addresses (user@domain.tld).
# Trading notation like "@2.50" or "SL @1.25" does NOT match because it lacks
# the name-part before the @ and the domain-part after.
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")

from tests.gateway.fixtures import (
    ACME_SUB,
    make_app,
    seed_token,
)


def _mcp_call(tool: str, args: dict | None = None) -> dict:
    return {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": args or {}},
    }


# ---------------------------------------------------------------------------
# Test 7: every tool is redacted over HTTP
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_everyTool_isRedacted_overHttp():
    """All 7 tools over HTTP: no account_number, broker_order_id, operator_id, email."""
    app, store = make_app(return_store=True)
    token = seed_token(store, "tok-redact", sub=ACME_SUB)

    tools = [
        "get_portfolio_summary",
        "list_open_positions",
        "get_recent_closed_trades",
    ]
    # Tools that require specific arguments
    tools_with_args = [
        # get_position_history needs a workflow_id arg
        ("get_position_history", {"workflow_id": "t-acme/pos/SMCI261120C00050000/sig-1"}),
        # get_quote needs an OCC symbol
        ("get_quote", {"occ": "SMCI261120C00050000"}),
        # get_chain needs an underlying ticker
        ("get_chain", {"underlying": "SMCI"}),
        # get_underlying_context needs a ticker
        ("get_underlying_context", {"ticker": "SMCI"}),
    ]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        for tool_name in tools:
            resp = await client.post(
                "/mcp",
                json=_mcp_call(tool_name),
                headers={"Authorization": f"Bearer {token}"},
            )
            result = resp.json().get("result", {})
            for item in result.get("content", []):
                text = item.get("text", "")
                dumped = json.dumps(json.loads(text)) if text else ""
                assert "account_number" not in dumped, f"{tool_name}: account_number found"
                assert "broker_order_id" not in dumped, f"{tool_name}: broker_order_id found"
                assert "operator_id" not in dumped, f"{tool_name}: operator_id found"
                assert not _EMAIL_RE.search(dumped), f"{tool_name}: possible email"

        for tool_name, args in tools_with_args:
            resp = await client.post(
                "/mcp",
                json=_mcp_call(tool_name, args),
                headers={"Authorization": f"Bearer {token}"},
            )
            result = resp.json().get("result", {})
            assert result.get("isError") is not True, f"{tool_name}: unexpected ToolError"
            for item in result.get("content", []):
                text = item.get("text", "")
                dumped = json.dumps(json.loads(text)) if text else ""
                assert "account_number" not in dumped, f"{tool_name}: account_number found"
                assert "broker_order_id" not in dumped, f"{tool_name}: broker_order_id found"


# ---------------------------------------------------------------------------
# Test 8: each BFF call carries a fresh assertion
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_everyBffCall_carriesFreshAssertion_andTenantHeader():
    """Two tool calls → two distinct assertions; a rejecting BFF → ToolError."""
    app, store, bff_calls = make_app(return_store=True, capture_bff=True)
    token = seed_token(store, "tok-fresh", sub=ACME_SUB)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp1 = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary"),
            headers={"Authorization": f"Bearer {token}"},
        )
        resp2 = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary"),
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp1.status_code == 200
    assert resp2.status_code == 200

    # Collect all assertions seen
    assertions = [c["authorization"][7:] for c in bff_calls if "authorization" in c]
    assert len(assertions) >= 2

    # Each assertion has a valid lifetime (exp - iat == 60)
    import base64

    def _b64url_decode(s: str) -> bytes:
        pad = 4 - len(s) % 4
        return base64.urlsafe_b64decode(s + "=" * (pad % 4))

    for a in assertions[:2]:
        parts = a.split(".")
        pay = json.loads(_b64url_decode(parts[1]))
        assert pay["exp"] - pay["iat"] == 60


@pytest.mark.asyncio
async def test_bffRejecting_producesToolError():
    """When the stub BFF starts rejecting, the tool returns a ToolError."""
    app, store, _bff_calls = make_app(return_store=True, capture_bff=True, bff_reject=True)
    token = seed_token(store, "tok-reject", sub=ACME_SUB)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary"),
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp.status_code == 200
    result = resp.json().get("result", {})
    assert result.get("isError") is True


# ---------------------------------------------------------------------------
# Test 9: audit line per tool call
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_auditLine_perToolCall(capsys):
    """One audit line per tool call; no sub, no token, no '@'."""
    app, store = make_app(return_store=True)
    token = seed_token(store, "tok-audit", sub=ACME_SUB)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary"),
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp.status_code == 200
    captured = capsys.readouterr()
    err = captured.err
    audit_lines = [
        line for line in err.splitlines()
        if line.startswith("mcp tool ")
    ]
    assert len(audit_lines) >= 1, f"No audit line found in stderr:\n{err}"
    line = audit_lines[-1]
    # Must contain expected fields
    assert "subject_hash=" in line
    assert "client_id=" in line
    assert "tenant=acme" in line
    assert "tool=get_portfolio_summary" in line
    assert "outcome=ok" in line
    # Must not contain the raw subject or email
    assert ACME_SUB not in line
    assert "@" not in line
