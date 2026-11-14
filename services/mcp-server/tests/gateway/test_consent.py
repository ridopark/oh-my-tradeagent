"""Tests — Consent endpoint (Seam H).

Covers:
- CSRF token mismatch → 400 (mutation guard)
- Cross-site request (Sec-Fetch-Site != same-origin) → 403
- Cross-origin (Origin header mismatch) → 403
- Deny action → redirects with access_denied
- Approve with no tenant selected → 400
- Approve with tenant not in pending set → 400
- Double consent (second POST with same state) → 400 (single-use CSRF)
- Expired pending auth at consent POST → 400 expired_pending (Finding G)
"""
from __future__ import annotations

from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from tests.gateway.fixtures import PUBLIC_URL, make_app
from tests.gateway.oauth_helpers import (
    encode_form,
    extract_csrf_from_html,
    extract_state_from_location,
    pkce_pair,
    register_client,
)

_SAME_ORIGIN_HEADERS = {"Sec-Fetch-Site": "same-origin"}


async def _get_consent_page(client: httpx.AsyncClient, *, client_name: str = "TC") -> tuple[str, str]:
    """Run authorize → callback, return (our_state, csrf_token)."""
    reg = await register_client(client, client_name=client_name)
    _verifier, challenge = await pkce_pair()

    auth_resp = await client.get(
        "/authorize",
        params={
            "client_id": reg["client_id"],
            "redirect_uri": "https://example.com/callback",
            "response_type": "code",
            "scope": "read",
            "state": "cs",
            "code_challenge": challenge,
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
    return our_state, csrf


def _post_consent(
    client: httpx.AsyncClient,
    *,
    state: str,
    csrf: str,
    tenants: list[str] | None = None,
    action: str = "approve",
    extra_headers: dict | None = None,
    follow_redirects: bool = False,
):
    """Helper: POST to /oauth/consent with encoded form data."""
    fields = [("state", state), ("csrf", csrf)]
    for t in (tenants or []):
        fields.append(("tenants", t))
    fields.append(("action", action))
    body, ct = encode_form(fields)
    headers = {"Content-Type": ct, **_SAME_ORIGIN_HEADERS, **(extra_headers or {})}
    return client.post(
        "/oauth/consent",
        content=body,
        headers=headers,
        follow_redirects=follow_redirects,
    )


@pytest.mark.asyncio
async def test_consent_invalidCsrf_is400():
    """CSRF token mismatch must return 400 (mutation guard for CSRF check)."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, _csrf = await _get_consent_page(client)
        body, ct = encode_form([
            ("state", our_state),
            ("csrf", "WRONG_CSRF_TOKEN"),
            ("tenants", "acme"),
            ("action", "approve"),
        ])
        resp = await client.post(
            "/oauth/consent",
            content=body,
            headers={"Content-Type": ct, **_SAME_ORIGIN_HEADERS},
        )
    assert resp.status_code == 400
    assert "csrf" in resp.text.lower() or "invalid" in resp.text.lower()


@pytest.mark.asyncio
async def test_consent_crossSiteSecFetchSite_is403():
    """Sec-Fetch-Site: cross-site on consent POST returns 403."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)
        body, ct = encode_form([
            ("state", our_state),
            ("csrf", csrf),
            ("tenants", "acme"),
            ("action", "approve"),
        ])
        resp = await client.post(
            "/oauth/consent",
            content=body,
            headers={"Content-Type": ct, "Sec-Fetch-Site": "cross-site"},
        )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_consent_originMismatch_is403():
    """Wrong Origin header on consent POST (no Sec-Fetch-Site) returns 403."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)
        body, ct = encode_form([
            ("state", our_state),
            ("csrf", csrf),
            ("tenants", "acme"),
            ("action", "approve"),
        ])
        resp = await client.post(
            "/oauth/consent",
            content=body,
            headers={"Content-Type": ct, "Origin": "https://evil.example.com"},
        )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_consent_deny_redirectsAccessDenied():
    """Clicking Deny redirects with access_denied."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)
        resp = await _post_consent(client, state=our_state, csrf=csrf, action="deny",
                                   follow_redirects=False)
    assert resp.status_code == 302
    assert "access_denied" in resp.headers["location"]


@pytest.mark.asyncio
async def test_consent_noTenantSelected_is400():
    """Submitting approve with no tenant checkbox → 400."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)
        resp = await _post_consent(client, state=our_state, csrf=csrf, tenants=[])
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_consent_tenantNotInPendingSet_is400():
    """Tenant not in the user's membership set → 400."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)
        resp = await _post_consent(
            client, state=our_state, csrf=csrf, tenants=["tenant-not-in-membership"]
        )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_consent_doublePost_secondIs400():
    """Second POST with the same state/CSRF is rejected (single-use CSRF)."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)

        # First POST: should succeed (redirect to client)
        resp1 = await _post_consent(
            client, state=our_state, csrf=csrf, tenants=["acme"], follow_redirects=False
        )
        assert resp1.status_code == 302, f"First POST failed: {resp1.status_code} {resp1.text}"

        # Second POST: pending auth consumed → must fail
        resp2 = await _post_consent(
            client, state=our_state, csrf=csrf, tenants=["acme"], follow_redirects=False
        )
        assert resp2.status_code == 400, (
            f"Second POST should fail but got {resp2.status_code}: {resp2.text}"
        )


@pytest.mark.asyncio
async def test_consent_approve_yieldsCodeAndClientState():
    """Valid approve redirects to redirect_uri with code and client state."""
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)
        resp = await _post_consent(
            client, state=our_state, csrf=csrf, tenants=["acme"], follow_redirects=False
        )
    assert resp.status_code == 302
    location = resp.headers["location"]
    parsed = parse_qs(urlparse(location).query)
    assert "code" in parsed, f"No code in redirect: {location}"
    assert parsed.get("state", [""])[0] == "cs", f"Wrong state in redirect: {location}"


# ---------------------------------------------------------------------------
# Item 13: Reject consent POST when both Origin and Sec-Fetch-Site are absent
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_consent_neitherHeader_is403():
    """Consent POST with neither Sec-Fetch-Site nor Origin must be rejected (403).

    RED: app.py proceeds when both headers are absent ("direct API calls").
    GREEN: app.py rejects with 403 when both headers are absent.
    """
    app = make_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)

        # Send consent POST with no Sec-Fetch-Site and no Origin header
        fields = [("state", our_state), ("csrf", csrf), ("tenants", "acme"), ("action", "approve")]
        body, ct = encode_form(fields)
        resp = await client.post(
            "/oauth/consent",
            content=body,
            headers={"Content-Type": ct},  # no Sec-Fetch-Site, no Origin
            follow_redirects=False,
        )

    assert resp.status_code == 403, (
        f"expected 403 when both headers absent, got {resp.status_code}; "
        f"body: {resp.text[:200]}"
    )

# ---------------------------------------------------------------------------
# Finding G: consent_post must reject expired pending auths
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_consent_expiredPending_is400():
    """Submitting consent after the pending auth expires must return 400 expired_pending.

    RED: consent_post has no expiry check — the form is processed even when
         the pending auth's expires_at has passed.
    GREEN: consent_post checks pending.expires_at < time.time() → 400.
    """
    import time

    app, store = make_app(return_store=True)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        our_state, csrf = await _get_consent_page(client)

        # Expire the pending auth
        store._pending_auths[our_state].expires_at = time.time() - 1

        resp = await _post_consent(client, state=our_state, csrf=csrf, tenants=["acme"])

    assert resp.status_code == 400, (
        f"expected 400 for expired pending, got {resp.status_code}; body: {resp.text[:200]}"
    )
    assert resp.json().get("error") == "expired_pending", resp.json()
