"""HTTP auth tests for the gateway — Seam H.

Uses httpx.ASGITransport against build_gateway_app(InMemoryStore, test key, stub BFF).
No AS routes in this step (3a); token_verifier=StoreTokenVerifier with tokens seeded
directly into InMemoryStore.
"""
from __future__ import annotations

import time

import httpx
import pytest

from tests.gateway.fixtures import (
    PUBLIC_URL,
    make_app,
    seed_token,
)

# ---------------------------------------------------------------------------
# Test 1: 401 without bearer, WWW-Authenticate contains resource_metadata hint
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_mcp_withoutBearer_is401_withResourceMetadataHint():
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post("/mcp", json={})
    assert resp.status_code == 401
    www_auth = resp.headers.get("www-authenticate", "")
    assert "resource_metadata=" in www_auth
    assert "/.well-known/oauth-protected-resource/mcp" in www_auth


@pytest.mark.asyncio
async def test_bothMetadataPaths_returnDocument():
    """Both /.well-known/oauth-protected-resource/mcp and /mcp return the metadata doc."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp1 = await client.get("/.well-known/oauth-protected-resource/mcp")
        resp2 = await client.get("/.well-known/oauth-protected-resource")
    assert resp1.status_code == 200
    doc1 = resp1.json()
    # Pydantic AnyHttpUrl normalises URLs to have a trailing slash; strip it.
    assert any(a.rstrip("/") == PUBLIC_URL for a in doc1.get("authorization_servers", []))
    assert resp2.status_code == 200
    doc2 = resp2.json()
    assert any(a.rstrip("/") == PUBLIC_URL for a in doc2.get("authorization_servers", []))


# ---------------------------------------------------------------------------
# Test 2: unknown / expired / revoked token → 401
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_mcp_unknownToken_is401():
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json={},
            headers={"Authorization": "Bearer totally-unknown-token"},
        )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_mcp_expiredToken_is401():
    app, store = make_app(return_store=True)
    past = int(time.time()) - 1
    seed_token(store, "expired-tok", expires_at=past)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json={},
            headers={"Authorization": "Bearer expired-tok"},
        )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_mcp_revokedGrant_is401():
    app, store = make_app(return_store=True)
    seed_token(store, "revoked-tok", revoked=True)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        resp = await client.post(
            "/mcp",
            json={},
            headers={"Authorization": "Bearer revoked-tok"},
        )
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# Test 3: resource indicator
# (RFC 8707 resource-column check removed: V17 mcp_oauth_access_token has no
#  resource column, so the check never fires in production — item 8 fix)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_mcp_absentResource_isAccepted():
    """A token with no resource field is accepted (some clients omit it)."""
    app, store = make_app(return_store=True)
    token = seed_token(store, "no-res-tok", resource=None)
    # just verify the stub BFF responds — not checking tool output here
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        # Initialize MCP to test bearer is accepted (server responds, not 401)
        resp = await client.post(
            "/mcp",
            json={
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "test", "version": "0.1"},
                },
            },
            headers={"Authorization": f"Bearer {token}"},
        )
    # Not 401 — the bearer was accepted
    assert resp.status_code != 401
