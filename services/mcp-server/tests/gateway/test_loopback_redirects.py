"""Finding B — RFC 8252 §7.3 loopback redirect URI port-agnostic matching.

RED criteria:
- test_loopback_portAgnosticMatch: registered http://127.0.0.1/callback,
  authorize with http://127.0.0.1:PORT/callback — should succeed (currently fails
  with InvalidRedirectUriError / redirect to error).
- test_loopback_portAgnosticMatch_function: direct unit test of the helper.
- test_loopback_nonLoopback_notPortAgnostic: https://example.com must still require
  exact match (no port stripping for non-loopback).
"""
from __future__ import annotations

import httpx
import pytest

from ohmytradeagent_mcp.gateway.redirects import loopback_port_agnostic_match
from tests.gateway.fixtures import PUBLIC_URL, make_app
from tests.gateway.oauth_helpers import (
    extract_code_from_location,
    extract_csrf_from_html,
    extract_state_from_location,
    pkce_pair,
    register_client,
    encode_form,
)
import secrets


# ── unit tests ────────────────────────────────────────────────────────────────

def test_loopback_portAgnosticMatch_function():
    """loopback_port_agnostic_match ignores port for loopback URIs."""
    assert loopback_port_agnostic_match(
        "http://127.0.0.1/callback",
        "http://127.0.0.1:12345/callback",
    )
    assert loopback_port_agnostic_match(
        "http://localhost/callback",
        "http://localhost:9876/callback",
    )
    assert loopback_port_agnostic_match(
        "http://127.0.0.1:8080/callback",
        "http://127.0.0.1:9999/callback",
    )
    # Non-loopback: should NOT match by port-agnostic comparison
    assert not loopback_port_agnostic_match(
        "https://example.com/callback",
        "https://example.com:443/callback",
    )
    # Different paths: should NOT match
    assert not loopback_port_agnostic_match(
        "http://127.0.0.1/callback",
        "http://127.0.0.1/other",
    )


# ── integration tests ─────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_loopback_portAgnosticMatch_authorizationAccepted():
    """RFC 8252 §7.3: authorize with a port that differs from the registered loopback URI.

    RED: SDK validate_redirect_uri does exact AnyUrl comparison; port mismatch fails.
    GREEN: _LoopbackAwareClientInfo overrides validate_redirect_uri with port-agnostic check.
    """
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # Register with no port
        reg = await register_client(
            client,
            redirect_uris=["http://127.0.0.1/callback"],
        )
        client_id = reg["client_id"]

        code_verifier, code_challenge = await pkce_pair()
        client_state = secrets.token_hex(8)

        # Authorize with a different port (ephemeral loopback port)
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": client_id,
                "redirect_uri": "http://127.0.0.1:12345/callback",
                "response_type": "code",
                "scope": "read",
                "state": client_state,
                "code_challenge": code_challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        # Should redirect to Google IdP (not an error)
        assert auth_resp.status_code == 302, (
            f"Expected 302 redirect to IdP, got {auth_resp.status_code}: {auth_resp.text}"
        )
        location = auth_resp.headers.get("location", "")
        assert "error" not in location, (
            f"Expected redirect to IdP, got error redirect: {location}"
        )


@pytest.mark.asyncio
async def test_loopback_portAgnosticMatch_fullFlow():
    """Full OAuth flow with port-agnostic loopback URI (registered no-port, flow uses port)."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        reg = await register_client(
            client,
            redirect_uris=["http://127.0.0.1/callback"],
        )
        client_id = reg["client_id"]

        code_verifier, code_challenge = await pkce_pair()
        client_state = secrets.token_hex(8)
        redirect_uri = "http://127.0.0.1:12345/callback"

        # Authorize
        auth_resp = await client.get(
            "/authorize",
            params={
                "client_id": client_id,
                "redirect_uri": redirect_uri,
                "response_type": "code",
                "scope": "read",
                "state": client_state,
                "code_challenge": code_challenge,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
        assert auth_resp.status_code == 302
        our_state = extract_state_from_location(auth_resp.headers["location"])

        # Google callback
        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "fake-google-code", "state": our_state},
            headers={"Host": "tradeagent-mcp.ridopark.com"},
            follow_redirects=False,
        )
        assert cb_resp.status_code == 200
        csrf = extract_csrf_from_html(cb_resp.text)

        # Consent
        consent_body, consent_ct = encode_form([
            ("state", our_state),
            ("csrf", csrf),
            ("tenants", "acme"),
            ("action", "approve"),
        ])
        consent_resp = await client.post(
            "/oauth/consent",
            content=consent_body,
            headers={
                "Host": "tradeagent-mcp.ridopark.com",
                "Sec-Fetch-Site": "same-origin",
                "Content-Type": consent_ct,
            },
            follow_redirects=False,
        )
        assert consent_resp.status_code == 302
        code = extract_code_from_location(consent_resp.headers["location"])

        # Token exchange — same redirect_uri with same port
        token_resp = await client.post(
            "/token",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
                "client_id": client_id,
                "code_verifier": code_verifier,
            },
            headers={"Host": "tradeagent-mcp.ridopark.com"},
        )
        assert token_resp.status_code == 200, (
            f"Token exchange failed: {token_resp.status_code} {token_resp.text}"
        )
        tokens = token_resp.json()
        assert "access_token" in tokens
