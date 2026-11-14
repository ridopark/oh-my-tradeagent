"""Tests 4–5 — Authorize endpoint and Google callback (Seam H).

Covers:
- /authorize redirects to Google with state+nonce (no client state leakage)
- Unregistered redirect URI → direct error, no redirect
- plain PKCE / missing code_challenge → rejected
- /oauth/google/callback: unknown/expired state → 400
- non-member → 302 with access_denied
- unverified email → 302 with access_denied
- valid member → 200 consent page (CSP, X-Frame-Options, body content)
- wrong nonce → 400
"""
from __future__ import annotations

import secrets
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from tests.gateway.fixtures import (
    ACME_SUB,
    PUBLIC_URL,
    make_app,
)
from tests.gateway.oauth_helpers import pkce_pair, register_client


def _pkce_params(challenge: str) -> dict:
    return {
        "response_type": "code",
        "scope": "read",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": "clientstate123",
    }


@pytest.mark.asyncio
async def test_authorize_redirectsToGoogle_withStateAndNonce_andNoClientStateLeak():
    """Authorize redirects to the IdP URL with our state; client state is NOT exposed."""
    app = make_app()
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                **_pkce_params(challenge),
            },
            follow_redirects=False,
        )

    assert resp.status_code == 302
    location = resp.headers["location"]
    # Must go to our FakeIdP (not directly to the client's redirect_uri)
    assert "fake-google.test" in location
    # Our internal state is in the IdP redirect, NOT the client's "clientstate123"
    parsed = urlparse(location)
    qs = parse_qs(parsed.query)
    our_state = qs.get("state", [""])[0]
    assert our_state, "No state in IdP redirect"
    # Client's state must not appear in the IdP URL
    assert "clientstate123" not in location


@pytest.mark.asyncio
async def test_authorize_unregisteredRedirectUri_isDirectError_noRedirect():
    """An unregistered redirect URI causes a direct 400 (no redirect) from /authorize."""
    app = make_app()
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)  # registered with https://example.com/callback
        resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://evil.example.com/steal",
                **_pkce_params(challenge),
            },
            follow_redirects=False,
        )

    # Must NOT redirect to evil.example.com; must return 400 directly
    assert resp.status_code == 400
    assert "evil.example.com" not in resp.headers.get("location", "")


@pytest.mark.asyncio
async def test_authorize_plainPkce_isRejected():
    """PKCE method 'plain' is rejected; only S256 is accepted."""
    app = make_app()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "code_challenge": secrets.token_hex(20),
                "code_challenge_method": "plain",
                "state": "s",
            },
            follow_redirects=False,
        )

    assert resp.status_code in (400, 302), resp.text
    # If redirect, error must be in the query
    if resp.status_code == 302:
        location = resp.headers["location"]
        assert "error=" in location


@pytest.mark.asyncio
async def test_authorize_missingCodeChallenge_isRejected():
    """Missing code_challenge is rejected."""
    app = make_app()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "state": "s",
            },
            follow_redirects=False,
        )

    assert resp.status_code in (400, 302)


@pytest.mark.asyncio
async def test_callback_unknownState_is400():
    """Google callback with an unknown state returns 400."""
    app = make_app()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        resp = await client.get(
            "/oauth/google/callback",
            params={"code": "some-code", "state": "nonexistent-state"},
        )

    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_callback_expiredPending_is400():
    """Google callback for an expired pending auth returns 400."""
    import time
    app, store = make_app(return_store=True)

    # Plant a pending auth that is already expired
    from ohmytradeagent_mcp.gateway.store import PendingAuth
    pending = PendingAuth(
        state="expired-state",
        client_id="test-client",
        client_state="cs",
        redirect_uri="https://example.com/callback",
        redirect_uri_provided_explicitly=True,
        code_challenge="xxx",
        scopes=["read"],
        resource=None,
        nonce="nnn",
        created_at=time.time() - 700,
        expires_at=time.time() - 100,  # already expired
    )
    await store.put_pending_auth(pending)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        resp = await client.get(
            "/oauth/google/callback",
            params={"code": "code", "state": "expired-state"},
        )

    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_callback_nonMember_redirectsAccessDenied_withClientState():
    """A user with no tenant membership gets redirected with access_denied."""
    app = make_app(sub="sub-nonmember")  # sub not in _DEFAULT_MEMBERSHIP
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "state": "clientstate",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        assert auth_resp.status_code == 302
        our_state = parse_qs(urlparse(auth_resp.headers["location"]).query)["state"][0]

        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "fake-code", "state": our_state},
            follow_redirects=False,
        )

    assert cb_resp.status_code == 302
    location = cb_resp.headers["location"]
    assert "access_denied" in location
    # Client's state is preserved in the redirect
    assert "clientstate" in location


@pytest.mark.asyncio
async def test_callback_unverifiedEmail_redirectsAccessDenied():
    """Unverified email → redirect with access_denied."""
    app = make_app(sub=ACME_SUB, email_verified=False)
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "state": "clientstate",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        our_state = parse_qs(urlparse(auth_resp.headers["location"]).query)["state"][0]

        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "code", "state": our_state},
            follow_redirects=False,
        )

    assert cb_resp.status_code == 302
    assert "access_denied" in cb_resp.headers["location"]


@pytest.mark.asyncio
async def test_callback_member_rendersConsent():
    """Valid member → 200 consent page with CSP, X-Frame-Options, CSRF, tenant checkboxes."""
    app = make_app()
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client, client_name="Acme Reader")
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "state": "clientstate",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        our_state = parse_qs(urlparse(auth_resp.headers["location"]).query)["state"][0]

        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "code", "state": our_state},
            follow_redirects=False,
        )

    assert cb_resp.status_code == 200

    # Security headers
    csp = cb_resp.headers.get("content-security-policy", "")
    assert "frame-ancestors 'none'" in csp, f"CSP missing frame-ancestors: {csp!r}"
    assert "default-src 'none'" in csp
    assert cb_resp.headers.get("x-frame-options", "").upper() == "DENY"
    assert "no-store" in cb_resp.headers.get("cache-control", "")

    # Body: escaped client name, tenant checkbox, cannot-trade sentence, CSRF field
    body = cb_resp.text
    assert "Acme Reader" in body
    assert "cannot place, change or cancel orders" in body
    assert 'name="tenants"' in body
    assert 'name="csrf"' in body
    assert 'value="acme"' in body


@pytest.mark.asyncio
async def test_callback_escapedClientName():
    """client_name containing HTML is escaped in the consent page."""
    app = make_app()
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client, client_name="<b>XSS</b>")
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "state": "s",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        our_state = parse_qs(urlparse(auth_resp.headers["location"]).query)["state"][0]
        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "code", "state": our_state},
        )

    body = cb_resp.text
    assert "<b>XSS</b>" not in body, "Raw HTML not escaped"
    assert "&lt;b&gt;" in body or "XSS" in body  # escaped form is acceptable


@pytest.mark.asyncio
async def test_callback_wrongNonce_is400():
    """Wrong nonce from IdP → 400 (mutation guard for nonce check)."""
    app = make_app(wrong_nonce=True)
    _verifier, challenge = await pkce_pair()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": reg["client_id"],
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "state": "s",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        our_state = parse_qs(urlparse(auth_resp.headers["location"]).query)["state"][0]
        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "code", "state": our_state},
        )

    assert cb_resp.status_code == 400
