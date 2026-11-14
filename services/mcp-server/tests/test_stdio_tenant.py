"""Stdio tenant arg tests — Test 13.

The constant stdio scope must raise ToolError when the tenant arg mismatches
the bound tenant, and succeed (ignoring the arg) when it matches.
"""
from __future__ import annotations

import json

from mcp import Client

from ohmytradeagent_mcp.tools import build_server, make_constant_scope


def _run(coro):
    import asyncio

    return asyncio.run(coro)


def test_stdio_tenantArgMismatch_isToolError(test_binding, stub_bff):
    """tenant arg that doesn't match the bound tenant → ToolError."""
    scope = make_constant_scope(test_binding, stub_bff, strict=True)
    mcp = build_server(scope)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_portfolio_summary", {"tenant": "other-tenant"})

    result = _run(run())
    assert result.is_error
    text = result.content[0].text if result.content else ""
    assert "other-tenant" in text or "not authorised" in text.lower()


def test_stdio_tenantArgMatch_isAllowed(test_binding, stub_bff):
    """tenant arg that matches the bound tenant → succeeds."""
    scope = make_constant_scope(test_binding, stub_bff, strict=True)
    mcp = build_server(scope)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool(
                "get_portfolio_summary", {"tenant": test_binding.tenant_id}
            )

    result = _run(run())
    assert not result.is_error, result.content[0].text if result.content else "no content"
    data = json.loads(result.content[0].text)
    assert data["tenant_id"] == test_binding.tenant_id


def test_stdio_noTenantArg_isAllowed(test_binding, stub_bff):
    """No tenant arg in stdio mode → uses the bound tenant (unchanged behaviour)."""
    scope = make_constant_scope(test_binding, stub_bff, strict=True)
    mcp = build_server(scope)

    async def run():
        async with Client(mcp) as client:
            return await client.call_tool("get_portfolio_summary", {})

    result = _run(run())
    assert not result.is_error, result.content[0].text if result.content else "no content"
