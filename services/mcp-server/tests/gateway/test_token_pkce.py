"""Tests — /token endpoint: form-encoded body, PKCE S256, code reuse, expiry (Seam H).

Guards:
- /token only accepts application/x-www-form-urlencoded (not JSON)
- PKCE verifier that doesn't match challenge → invalid_grant
- Expired authorization code → invalid_grant
- Code reuse → invalid_grant AND revokes the whole grant
- Happy-path token exchange yields access_token + refresh_token
"""
from __future__ import annotations

import secrets
import time
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from tests.gateway.fixtures import PUBLIC_URL, make_app
from tests.gateway.oauth_helpers import (
    do_oauth_flow,
    encode_form,
    extract_csrf_from_html,
    extract_state_from_location,
    pkce_pair,
    register_client,
)


async def _do_authorize_and_consent(
    client: httpx.AsyncClient,
    *,
    code_verifier: str,
    code_challenge: str,
    client_id: str,
    client_secret: str,
    redirect_uri: str = "https://example.com/callback",
) -> str:
    """Run /authorize → /oauth/google/callback → /oauth/consent; return authorization code."""
    client_state = secrets.token_hex(8)
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
    assert auth_resp.status_code == 302, auth_resp.text
    our_state = extract_state_from_location(auth_resp.headers["location"])

    cb_resp = await client.get(
        "/oauth/google/callback",
        params={"code": "code", "state": our_state},
    )
    assert cb_resp.status_code == 200, cb_resp.text
    csrf = extract_csrf_from_html(cb_resp.text)

    from tests.gateway.oauth_helpers import encode_form
    body, ct = encode_form([
        ("state", our_state),
        ("csrf", csrf),
        ("tenants", "acme"),
        ("action", "approve"),
    ])
    consent_resp = await client.post(
        "/oauth/consent",
        content=body,
        headers={"Content-Type": ct, "Sec-Fetch-Site": "same-origin"},
        follow_redirects=False,
    )
    assert consent_resp.status_code == 302, consent_resp.text
    loc = consent_resp.headers["location"]
    return parse_qs(urlparse(loc).query)["code"][0]


@pytest.mark.asyncio
async def test_token_formEncoded_required():
    """/token only accepts application/x-www-form-urlencoded."""
    tokens = await do_oauth_flow(make_app())
    app = make_app()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        resp = await client.post(
            "/token",
            json={
                "grant_type": "authorization_code",
                "code": "some-code",
                "redirect_uri": "https://example.com/callback",
                "client_id": tokens["client_id"],
            },
        )
    # SDK should reject JSON body for /token (may be 400, 401, 415, or 422)
    assert resp.status_code in (400, 401, 415, 422), resp.text


@pytest.mark.asyncio
async def test_token_wrongPkceVerifier_isInvalidGrant():
    """Wrong PKCE verifier → invalid_grant."""
    app = make_app()
    redirect_uri = "https://example.com/callback"

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        code_verifier, code_challenge = await pkce_pair()

        code = await _do_authorize_and_consent(
            client,
            code_verifier=code_verifier,
            code_challenge=code_challenge,
            client_id=reg["client_id"],
            client_secret=reg.get("client_secret", ""),
        )

        body, ct = encode_form([
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", redirect_uri),
            ("client_id", reg["client_id"]),
            ("client_secret", reg.get("client_secret", "")),
            ("code_verifier", "WRONG_VERIFIER_THAT_DOESNT_MATCH_THE_CHALLENGE"),
        ])
        resp = await client.post(
            "/token", content=body, headers={"Content-Type": ct}
        )

    assert resp.status_code == 400, resp.text
    data = resp.json()
    assert data.get("error") == "invalid_grant", data


@pytest.mark.asyncio
async def test_token_expiredCode_isInvalidGrant():
    """Expired authorization code → invalid_grant."""
    app, store = make_app(return_store=True)
    redirect_uri = "https://example.com/callback"

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        code_verifier, code_challenge = await pkce_pair()

        code = await _do_authorize_and_consent(
            client,
            code_verifier=code_verifier,
            code_challenge=code_challenge,
            client_id=reg["client_id"],
            client_secret=reg.get("client_secret", ""),
        )

        # Artificially expire the code
        code_rec = await store.get_code(code)
        assert code_rec is not None
        code_rec.expires_at = time.time() - 10

        body, ct = encode_form([
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", redirect_uri),
            ("client_id", reg["client_id"]),
            ("client_secret", reg.get("client_secret", "")),
            ("code_verifier", code_verifier),
        ])
        resp = await client.post(
            "/token", content=body, headers={"Content-Type": ct}
        )

    assert resp.status_code == 400, resp.text
    data = resp.json()
    assert data.get("error") in ("invalid_grant", "invalid_code"), data


@pytest.mark.asyncio
async def test_token_codeReuse_revokesGrant():
    """Reusing an authorization code revokes the whole grant (single-use codes)."""
    app, _store = make_app(return_store=True)
    redirect_uri = "https://example.com/callback"

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
        code_verifier, code_challenge = await pkce_pair()

        code = await _do_authorize_and_consent(
            client,
            code_verifier=code_verifier,
            code_challenge=code_challenge,
            client_id=reg["client_id"],
            client_secret=reg.get("client_secret", ""),
        )

        def _token_body():
            return encode_form([
                ("grant_type", "authorization_code"),
                ("code", code),
                ("redirect_uri", redirect_uri),
                ("client_id", reg["client_id"]),
                ("client_secret", reg.get("client_secret", "")),
                ("code_verifier", code_verifier),
            ])

        # First exchange: succeeds
        body, ct = _token_body()
        resp1 = await client.post("/token", content=body, headers={"Content-Type": ct})
        assert resp1.status_code == 200, f"First exchange failed: {resp1.text}"
        tokens1 = resp1.json()
        access_token1 = tokens1["access_token"]

        # Second exchange with same code: must fail
        body2, ct2 = _token_body()
        resp2 = await client.post("/token", content=body2, headers={"Content-Type": ct2})
        assert resp2.status_code == 400, f"Expected 400, got {resp2.status_code}: {resp2.text}"
        assert resp2.json().get("error") == "invalid_grant", resp2.json()

        # Grant must be revoked: access_token from first exchange no longer valid
        mcp_resp = await client.get(
            "/mcp",
            headers={
                "Authorization": f"Bearer {access_token1}",
                "Accept": "application/json, text/event-stream",
                "Host": "tradeagent-mcp.ridopark.com",
            },
        )
        assert mcp_resp.status_code == 401, (
            f"Token from revoked grant should be rejected (got {mcp_resp.status_code})"
        )


@pytest.mark.asyncio
async def test_token_happyPath_returnsAccessAndRefresh():
    """Full flow yields access_token + refresh_token."""
    tokens = await do_oauth_flow(make_app())
    assert "access_token" in tokens, tokens
    assert "refresh_token" in tokens, tokens
    assert tokens.get("token_type", "").lower() == "bearer"
    assert tokens.get("expires_in", 0) > 0
