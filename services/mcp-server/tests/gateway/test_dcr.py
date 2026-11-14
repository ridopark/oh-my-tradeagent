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
    """HTTPS redirect URI is accepted; JSON body is required (not form-encoded)."""
    async with _make_client() as client:
        resp = await client.post(
            "/register",
            json={
                "client_name": "My App",
                "redirect_uris": ["https://example.com/callback"],
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
            },
        )
    assert resp.status_code == 201, resp.text
    data = resp.json()
    assert "client_id" in data
    assert "client_secret" in data


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
        # Register a client
        resp = await client.post(
            "/register",
            json={
                "client_name": "Expiry Test",
                "redirect_uris": ["https://example.com/cb"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
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
            },
        )

    # The expired-never-used client should be gone
    c = await store.get_client_record(client_id)
    assert c is None, "Expected expired-never-used client to be swept"
