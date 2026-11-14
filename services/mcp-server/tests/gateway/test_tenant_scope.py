"""Tenant scope tests — Seam H.

Tests 4-6: tenant argument semantics (implicit for one tenant,
required for two tenants, membership re-check).
"""
from __future__ import annotations

import json

import httpx
import pytest

from tests.gateway.fixtures import (
    ACME_SUB,
    make_app,
    seed_token,
    seed_two_tenant_token,
)


def _mcp_call(tool: str, args: dict | None = None) -> dict:
    return {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": args or {}},
    }


def _mcp_init() -> dict:
    return {
        "jsonrpc": "2.0",
        "id": 0,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "test", "version": "0.1"},
        },
    }


# ---------------------------------------------------------------------------
# Test 4: one-tenant grant, tenant arg is optional
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_oneTenantGrant_tenantImplicit():
    """One-tenant grant: tool succeeds without tenant arg; stub BFF sees correct headers."""
    app, store, bff_calls = make_app(return_store=True, capture_bff=True)
    token = seed_token(store, "tok-acme", sub=ACME_SUB, tenants=["acme"])

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
    body = resp.json()
    result = body.get("result", {})
    # Content must include tenant_id=acme
    for item in result.get("content", []):
        if item.get("type") == "text":
            data = json.loads(item["text"])
            assert data["tenant_id"] == "acme"
            break

    # Stub BFF must have seen X-Tenant-Id: acme and an assertion
    seen = bff_calls[-1]
    assert seen["x-tenant-id"] == "acme"
    auth = seen["authorization"]
    assert auth.startswith("Bearer ")
    jws = auth[7:]
    parts = jws.split(".")
    assert len(parts) == 3
    import base64

    def _b64url_decode(s: str) -> bytes:
        pad = 4 - len(s) % 4
        return base64.urlsafe_b64decode(s + "=" * (pad % 4))

    hdr = json.loads(_b64url_decode(parts[0]))
    pay = json.loads(_b64url_decode(parts[1]))
    assert hdr["alg"] == "EdDSA"
    assert pay["iss"] == "omt-mcp-gateway"
    assert pay["aud"] == "tenant-dashboard-bff"
    assert pay["tenant"] == "acme"
    assert pay["sub"] == ACME_SUB
    assert pay["exp"] - pay["iat"] == 60


# ---------------------------------------------------------------------------
# Test 5: two-tenant grant, tenant arg required
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_twoTenantGrant_requiresTenantArg_missingIsError():
    """Two-tenant grant + no tenant arg → ToolError with 'tenant required'."""
    app, store = make_app(return_store=True)
    token = seed_two_tenant_token(store, "tok-two", sub=ACME_SUB, tenants=["acme", "beta"])

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
    body = resp.json()
    result = body.get("result", {})
    # Must be an error
    assert result.get("isError") is True
    text = result["content"][0]["text"]
    assert "tenant required" in text
    # Must not reveal the tenant list
    assert "acme" not in text
    assert "beta" not in text


@pytest.mark.asyncio
async def test_twoTenantGrant_tenantArgBeta_works():
    """Two-tenant grant + tenant='beta' → resolves to beta tenant."""
    app, store = make_app(return_store=True)
    token = seed_two_tenant_token(store, "tok-two-b", sub=ACME_SUB, tenants=["acme", "beta"])

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary", {"tenant": "beta"}),
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp.status_code == 200
    body = resp.json()
    result = body.get("result", {})
    assert result.get("isError") is not True, result.get("content", [])
    for item in result.get("content", []):
        if item.get("type") == "text":
            data = json.loads(item["text"])
            assert data["tenant_id"] == "beta"
            break


@pytest.mark.asyncio
async def test_twoTenantGrant_tenantArgGamma_isError():
    """Two-tenant grant + tenant='gamma' (not in grant) → ToolError; gamma in text, not acme/beta."""
    app, store = make_app(return_store=True)
    token = seed_two_tenant_token(store, "tok-two-g", sub=ACME_SUB, tenants=["acme", "beta"])

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary", {"tenant": "gamma"}),
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp.status_code == 200
    body = resp.json()
    result = body.get("result", {})
    assert result.get("isError") is True
    text = result["content"][0]["text"]
    assert "gamma" in text
    assert "not authorised" in text
    assert "acme" not in text
    assert "beta" not in text


# ---------------------------------------------------------------------------
# Test 6: membership removed mid-session
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_membershipRemovedMidSession_nextCallRefused():
    """Remove membership → next tool call 401 (token invalid due to empty tenant list)."""
    app, store = make_app(return_store=True)
    token = seed_token(store, "tok-mid", sub=ACME_SUB, tenants=["acme"])

    # First call: works
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp1 = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary"),
            headers={"Authorization": f"Bearer {token}"},
        )
    assert resp1.status_code == 200
    assert resp1.json().get("result", {}).get("isError") is not True

    # Remove the membership from the store
    store.remove_membership(ACME_SUB, "acme")

    # Second call: 401 (membership re-check fails → no tenants remain)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp2 = await client.post(
            "/mcp",
            json=_mcp_call("get_portfolio_summary"),
            headers={"Authorization": f"Bearer {token}"},
        )
    assert resp2.status_code == 401
