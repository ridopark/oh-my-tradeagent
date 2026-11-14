"""Tests — /revoke endpoint (Seam H).

Guards:
- Revoking the refresh token kills the grant → access token also rejected
- Revoking the access token kills the grant → refresh token also rejected
- Revoking an unknown token is a no-op (200)
"""
from __future__ import annotations

import httpx
import pytest

from tests.gateway.fixtures import PUBLIC_URL, make_app
from tests.gateway.oauth_helpers import do_oauth_flow, encode_form


@pytest.mark.asyncio
async def test_revoke_refreshToken_killsGrant():
    """Revoking the refresh token kills the grant; access token is then rejected at /mcp."""
    app = make_app()
    tokens = await do_oauth_flow(app)
    access_token = tokens["access_token"]
    refresh_token = tokens["refresh_token"]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        # Revoke via refresh_token
        body, ct = encode_form([
            ("token", refresh_token),
            ("token_type_hint", "refresh_token"),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        rev_resp = await client.post(
            "/revoke", content=body, headers={"Content-Type": ct}
        )
        assert rev_resp.status_code == 200, f"Revoke failed: {rev_resp.text}"

        # Access token from same grant must now be rejected
        mcp_resp = await client.get(
            "/mcp",
            headers={
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json, text/event-stream",
                "Host": "tradeagent-mcp.ridopark.com",
            },
        )
        assert mcp_resp.status_code == 401, (
            f"Access token from revoked grant should be rejected (got {mcp_resp.status_code})"
        )


@pytest.mark.asyncio
async def test_revoke_accessToken_killsGrant():
    """Revoking the access token kills the grant; refresh token is then rejected."""
    app = make_app()
    tokens = await do_oauth_flow(app)
    access_token = tokens["access_token"]
    refresh_token = tokens["refresh_token"]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        # Revoke via access_token
        body, ct = encode_form([
            ("token", access_token),
            ("token_type_hint", "access_token"),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        rev_resp = await client.post(
            "/revoke", content=body, headers={"Content-Type": ct}
        )
        assert rev_resp.status_code == 200, f"Revoke failed: {rev_resp.text}"

        # Refresh token from same grant must now be rejected
        body2, ct2 = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        ref_resp = await client.post("/token", content=body2, headers={"Content-Type": ct2})
        assert ref_resp.status_code == 400, (
            f"Refresh token from revoked grant should fail (got {ref_resp.status_code})"
        )
        assert ref_resp.json().get("error") in ("invalid_grant", "invalid_token"), ref_resp.json()


@pytest.mark.asyncio
async def test_revoke_unknownToken_isNoOp():
    """Revoking an unknown token returns 200 (RFC 7009 §2.2)."""
    app = make_app()
    tokens = await do_oauth_flow(app)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        body, ct = encode_form([
            ("token", "not-a-real-token"),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        resp = await client.post(
            "/revoke", content=body, headers={"Content-Type": ct}
        )
    # RFC 7009: revocation of unknown tokens returns 200
    assert resp.status_code == 200, resp.text


# ---------------------------------------------------------------------------
# Finding H: revoke expired access token must still kill the grant
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_revoke_expiredAccessToken_killsGrant():
    """Revoking an expired access token must still kill the grant.

    RED: load_access_token returns None for expired tokens → SDK's revoke
         treats it as unknown (200, no-op) → refresh token remains valid.
    GREEN: custom /revoke handler revokes the grant even for expired tokens.
    """
    import time

    app, store = make_app(return_store=True)
    tokens = await do_oauth_flow(app)
    access_token = tokens["access_token"]
    refresh_token = tokens["refresh_token"]
    client_id = tokens["client_id"]

    # Expire the access token in the store
    if access_token in store._tokens:
        store._tokens[access_token].expires_at = int(time.time()) - 1

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        # Revoke via expired access_token (none-method clients omit client_secret)
        body, ct = encode_form([
            ("token", access_token),
            ("token_type_hint", "access_token"),
            ("client_id", client_id),
            ("client_secret", ""),
        ])
        rev_resp = await client.post(
            "/revoke", content=body, headers={"Content-Type": ct}
        )
        assert rev_resp.status_code == 200, f"Revoke failed: {rev_resp.text}"

        # Refresh token from same grant must now be rejected
        body2, ct2 = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("client_id", client_id),
            ("client_secret", ""),
        ])
        ref_resp = await client.post("/token", content=body2, headers={"Content-Type": ct2})
        assert ref_resp.status_code == 400, (
            f"Refresh token from grant with expired-revoked access_token should fail "
            f"(got {ref_resp.status_code}); body: {ref_resp.text[:200]}"
        )
        assert ref_resp.json().get("error") in ("invalid_grant", "invalid_token"), ref_resp.json()
