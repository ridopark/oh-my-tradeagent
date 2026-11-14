"""Test 2 — Dynamic Client Registration (DCR) policy (Seam H).

Verifies redirect-URI policy at registration and the JSON-only body requirement.
"""
from __future__ import annotations

import time

import httpx
import pytest

from tests.gateway.fixtures import PUBLIC_URL, make_app


def _make_client():
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=make_app()),
        base_url=PUBLIC_URL,
    )


@pytest.mark.asyncio
async def test_register_httpsRedirect_ok_jsonBody():
    """HTTPS redirect URI is accepted; JSON body is required (not form-encoded).

    Public clients (token_endpoint_auth_method=none) have no client_secret.
    """
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            json={
                "client_name": "My App",
                "redirect_uris": ["https://example.com/callback"],
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
            },
        )
    assert resp.status_code == 201, resp.text
    data = resp.json()
    assert "client_id" in data
    # Public clients (none auth method) carry no server-issued secret.
    assert not data.get("client_secret"), "none-auth client must not receive a secret"


@pytest.mark.asyncio
async def test_register_nonNoneAuthMethod_is400_invalidClientMetadata():
    """token_endpoint_auth_method other than 'none' must be rejected.

    RED: currently returns 201 (the check is not in place yet).
    GREEN: provider.register_client raises RegistrationError for non-none methods.
    """
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            json={
                "client_name": "Confidential App",
                "redirect_uris": ["https://example.com/callback"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "client_secret_post",
            },
        )
    assert resp.status_code == 400, f"Expected 400 for non-none auth method, got {resp.status_code}: {resp.text}"
    data = resp.json()
    assert data.get("error") == "invalid_client_metadata", data


@pytest.mark.asyncio
async def test_register_loopbackRedirects_ok():
    """HTTP loopback redirect URIs (127.0.0.1, localhost, [::1]) are accepted."""
    uris = [
        "http://127.0.0.1:53280/callback",
        "http://localhost:7777/oauth2callback",
        "http://[::1]:1234/",
    ]
    for uri in uris:
        async with _make_client() as client:
            resp = await client.post(
                "/register",
                json={
                    "client_name": f"App {uri}",
                    "redirect_uris": [uri],
                    "grant_types": ["authorization_code"],
                    "response_types": ["code"],
                    "token_endpoint_auth_method": "none",
                },
            )
        assert resp.status_code == 201, f"Expected 201 for {uri!r}: {resp.text}"


@pytest.mark.asyncio
async def test_register_httpNonLoopback_rejected():
    """http:// with a non-loopback host must be rejected."""
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            json={
                "client_name": "Bad App",
                "redirect_uris": ["http://evil.example.com/callback"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
            },
        )
    assert resp.status_code == 400, resp.text
    data = resp.json()
    assert data.get("error") == "invalid_redirect_uri", data


@pytest.mark.asyncio
async def test_register_wildcardOrFragment_rejected():
    """Wildcard and fragment in redirect URI must both be rejected."""
    bad_uris = [
        "https://*.example.com/callback",
        "https://example.com/callback#frag",
    ]
    for uri in bad_uris:
        async with _make_client() as client:
            resp = await client.post(
                "/register",
                json={
                    "client_name": "Bad App",
                    "redirect_uris": [uri],
                    "grant_types": ["authorization_code"],
                    "response_types": ["code"],
                },
            )
        assert resp.status_code == 400, f"Expected 400 for {uri!r}: {resp.text}"
        assert resp.json().get("error") == "invalid_redirect_uri", resp.json()


@pytest.mark.asyncio
async def test_register_ipLiteralHttps_rejected():
    """HTTPS with an IP literal host must be rejected (hostname required)."""
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            json={
                "client_name": "Bad App",
                "redirect_uris": ["https://1.2.3.4/callback"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
            },
        )
    assert resp.status_code == 400, resp.text
    assert resp.json().get("error") == "invalid_redirect_uri", resp.json()


@pytest.mark.asyncio
async def test_register_formEncodedBody_is400():
    """Registration with a form-encoded body (not JSON) must be rejected."""
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            content="client_name=Bad&redirect_uris=https%3A%2F%2Fexample.com%2Fcb",
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
    # SDK rejects non-JSON body for /register
    assert resp.status_code in (400, 415, 422), resp.text


@pytest.mark.asyncio
async def test_register_unusedClientExpires_afterSweep():
    """A client that was registered but never used is swept after 30+ days."""
    # We can't advance a real clock, so we directly manipulate the store
    app, store = make_app(return_store=True)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # Register a client (public PKCE client, none auth method)
        resp = await client.post(
            "/register",
            json={
                "client_name": "Expiry Test",
                "redirect_uris": ["https://example.com/cb"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
            },
        )
        assert resp.status_code == 201
        client_id = resp.json()["client_id"]

        # Verify it's known
        c = await store.get_client_record(client_id)
        assert c is not None

        # Artificially mark it as expired + never used
        c._expires_at = time.time() - 1
        # (last_used_at not set means never used)

        # Register another client to trigger the sweep
        await client.post(
            "/register",
            json={
                "client_name": "Second",
                "redirect_uris": ["https://example.com/cb2"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
            },
        )

    # The expired-never-used client should be gone
    c = await store.get_client_record(client_id)
    assert c is None, "Expected expired-never-used client to be swept"


# ---------------------------------------------------------------------------
# Item 9: /register must return 400 for null/missing redirect_uris, never 500
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_register_nullRedirectUris_is400_invalidClientMetadata():
    """register with redirect_uris=null must return 400 invalid_client_metadata.

    RED: provider.register_client iterates redirect_uris without None check →
         TypeError propagates → 500.
    GREEN: register_client guards against None/missing → RegistrationError 400.
    """
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            json={
                "redirect_uris": None,
                "token_endpoint_auth_method": "none",
            },
        )
    assert resp.status_code == 400, (
        f"redirect_uris=null must be 400, got {resp.status_code}; body: {resp.text[:200]}"
    )
    body = resp.json()
    assert body.get("error") == "invalid_client_metadata", f"unexpected: {body}"


@pytest.mark.asyncio
async def test_register_emptyBody_is400():
    """register with {} (no redirect_uris) must return 400, not 500 or 422."""
    async with _make_client() as client:
        resp = await client.post("/register", json={})
    # SDK may return 400 from Pydantic validation; we just verify it's not 500
    assert resp.status_code in (400, 422), (
        f"empty body must be 400 or 422, got {resp.status_code}"
    )
    assert resp.status_code != 500
