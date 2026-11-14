"""Shared helpers for the OAuth 2.1 flow tests (Step 3b).

``do_oauth_flow`` drives the full authorize → callback → consent → token
sequence using an ASGI transport client (Seam H) and returns the token set.
"""
from __future__ import annotations

import base64
import hashlib
import re
import secrets
from urllib.parse import parse_qs, urlencode, urlparse

import httpx

from tests.gateway.fixtures import PUBLIC_URL


def encode_form(fields: list[tuple[str, str]]) -> tuple[bytes, str]:
    """Encode a list of (name, value) tuples as application/x-www-form-urlencoded.

    Returns (body_bytes, content_type_header_value).
    httpx 0.28 creates a sync stream from list-of-tuples passed to ``data=``,
    which breaks AsyncClient.  Pass the result as ``content=body, headers=...``.
    """
    return urlencode(fields).encode(), "application/x-www-form-urlencoded"


async def pkce_pair() -> tuple[str, str]:
    """Return (code_verifier, code_challenge) for S256."""
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


async def register_client(
    client: httpx.AsyncClient,
    *,
    redirect_uris: list[str] | None = None,
    client_name: str = "Test Client",
) -> dict:
    """Register a DCR client and return the full response JSON."""
    if redirect_uris is None:
        redirect_uris = ["https://example.com/callback"]
    resp = await client.post(
        "/register",
        json={
            "client_name": client_name,
            "redirect_uris": redirect_uris,
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
            "scope": "read",
            "token_endpoint_auth_method": "client_secret_post",
        },
    )
    assert resp.status_code == 201, f"DCR failed: {resp.status_code} {resp.text}"
    return resp.json()


def extract_state_from_location(location: str) -> str:
    """Extract the ``state`` query param from a redirect URL."""
    parsed = urlparse(location)
    params = parse_qs(parsed.query)
    states = params.get("state", [])
    assert states, f"No state in location: {location!r}"
    return states[0]


def extract_csrf_from_html(html_body: str) -> str:
    """Extract the CSRF token from the hidden input in the consent page."""
    m = re.search(r'<input[^>]+name="csrf"[^>]+value="([^"]+)"', html_body)
    assert m, f"No CSRF field found in HTML:\n{html_body[:500]}"
    return m.group(1)


def extract_code_from_location(location: str) -> str:
    """Extract the authorization ``code`` query param from a redirect URL."""
    parsed = urlparse(location)
    params = parse_qs(parsed.query)
    codes = params.get("code", [])
    assert codes, f"No code in location: {location!r}"
    return codes[0]


async def do_oauth_flow(
    app,
    *,
    redirect_uri: str = "https://example.com/callback",
    tenants: list[str] | None = None,
    client_name: str = "Test Client",
) -> dict:
    """Run the full OAuth 2.1 flow and return the token response dict.

    Returns a dict with at minimum ``access_token``, ``refresh_token``,
    ``client_id``, ``client_secret``, ``redirect_uri``.
    """
    if tenants is None:
        tenants = ["acme"]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # 1. Register DCR client
        reg = await register_client(
            client, redirect_uris=[redirect_uri], client_name=client_name
        )
        client_id = reg["client_id"]
        client_secret = reg.get("client_secret", "")

        # 2. PKCE
        code_verifier, code_challenge = await pkce_pair()
        client_state = secrets.token_hex(8)

        # 3. Authorize → redirect to FakeIdP
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
        assert auth_resp.status_code == 302, (
            f"/authorize returned {auth_resp.status_code}: {auth_resp.text}"
        )
        our_state = extract_state_from_location(auth_resp.headers["location"])

        # 4. Google callback → consent page
        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "fake-google-code", "state": our_state},
            headers={"Host": "tradeagent-mcp.ridopark.com"},
            follow_redirects=False,
        )
        assert cb_resp.status_code == 200, (
            f"/oauth/google/callback returned {cb_resp.status_code}: {cb_resp.text}"
        )
        csrf = extract_csrf_from_html(cb_resp.text)

        # 5. Submit consent
        # Use urlencode so multiple tenants are all sent (not just the last).
        # httpx 0.28 creates a sync stream from list-of-tuples passed to data=,
        # which breaks AsyncClient; encode manually and pass as content=.
        consent_fields = [
            ("state", our_state),
            ("csrf", csrf),
            *[("tenants", t) for t in tenants],
            ("action", "approve"),
        ]
        consent_body, consent_ct = encode_form(consent_fields)
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
        assert consent_resp.status_code == 302, (
            f"/oauth/consent returned {consent_resp.status_code}: {consent_resp.text}"
        )
        code = extract_code_from_location(consent_resp.headers["location"])

        # 6. Exchange code for tokens
        token_resp = await client.post(
            "/token",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
                "client_id": client_id,
                "client_secret": client_secret,
                "code_verifier": code_verifier,
            },
            headers={"Host": "tradeagent-mcp.ridopark.com"},
        )
        assert token_resp.status_code == 200, (
            f"/token returned {token_resp.status_code}: {token_resp.text}"
        )
        tokens = token_resp.json()
        return {
            **tokens,
            "client_id": client_id,
            "client_secret": client_secret,
            "redirect_uri": redirect_uri,
            "code_verifier": code_verifier,
        }
