"""Tests — Refresh token rotation and reuse detection (Seam H).

Guards:
- Happy path: refresh yields new access + refresh token
- Old refresh token is invalidated after rotation
- Refresh token reuse revokes the whole grant
- Expired refresh token → invalid_grant
- Wrong client on refresh → invalid_client
"""
from __future__ import annotations

import time

import httpx
import pytest

from tests.gateway.fixtures import PUBLIC_URL, make_app
from tests.gateway.oauth_helpers import do_oauth_flow, encode_form, register_client


@pytest.mark.asyncio
async def test_refresh_happyPath_yieldsNewPair():
    """Refresh grant: new access_token + new refresh_token returned."""
    app = make_app()
    tokens = await do_oauth_flow(app)
    access_token = tokens["access_token"]
    refresh_token = tokens["refresh_token"]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        body, ct = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        resp = await client.post("/token", content=body, headers={"Content-Type": ct})

    assert resp.status_code == 200, resp.text
    new_tokens = resp.json()
    assert "access_token" in new_tokens
    assert "refresh_token" in new_tokens
    # New tokens should differ from the original
    assert new_tokens["access_token"] != access_token
    assert new_tokens["refresh_token"] != refresh_token


@pytest.mark.asyncio
async def test_refresh_oldRefreshTokenInvalidated():
    """After rotation, the original refresh token is no longer accepted."""
    app = make_app()
    tokens = await do_oauth_flow(app)
    original_refresh = tokens["refresh_token"]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        # First refresh: rotate
        body, ct = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", original_refresh),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        resp1 = await client.post("/token", content=body, headers={"Content-Type": ct})
        assert resp1.status_code == 200, resp1.text

        # Try to use the original refresh token again: must fail
        resp2 = await client.post("/token", content=body, headers={"Content-Type": ct})
    assert resp2.status_code == 400, f"Expected 400 for rotated token, got {resp2.status_code}: {resp2.text}"
    assert resp2.json().get("error") == "invalid_grant", resp2.json()


@pytest.mark.asyncio
async def test_refresh_tokenReuse_revokesGrant():
    """Presenting a rotated refresh token revokes the whole grant (reuse detection)."""
    app = make_app()
    tokens = await do_oauth_flow(app)
    original_refresh = tokens["refresh_token"]

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        # Rotate: original → new_refresh
        body, ct = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", original_refresh),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        resp1 = await client.post("/token", content=body, headers={"Content-Type": ct})
        assert resp1.status_code == 200, resp1.text
        new_access = resp1.json()["access_token"]
        new_refresh = resp1.json()["refresh_token"]

        # Reuse the original refresh token: triggers reuse detection → grant revoked
        resp_reuse = await client.post("/token", content=body, headers={"Content-Type": ct})
        assert resp_reuse.status_code == 400, resp_reuse.text
        assert resp_reuse.json().get("error") == "invalid_grant"

        # The new_access token (from the rotation) must now also be rejected
        mcp_resp = await client.get(
            "/mcp",
            headers={
                "Authorization": f"Bearer {new_access}",
                "Accept": "application/json, text/event-stream",
                "Host": "tradeagent-mcp.ridopark.com",
            },
        )
        assert mcp_resp.status_code == 401, (
            f"Token from revoked grant should be rejected (got {mcp_resp.status_code})"
        )

        # The new_refresh should also fail
        body2, ct2 = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", new_refresh),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        resp_new_refresh = await client.post("/token", content=body2, headers={"Content-Type": ct2})
        assert resp_new_refresh.status_code == 400, (
            f"Revoked grant's refresh token should fail (got {resp_new_refresh.status_code})"
        )


@pytest.mark.asyncio
async def test_refresh_expiredToken_isInvalidGrant():
    """An expired refresh token returns invalid_grant."""
    app, store = make_app(return_store=True)
    tokens = await do_oauth_flow(app)
    refresh_token = tokens["refresh_token"]

    # Expire the refresh token
    rt_rec = await store.get_refresh_token(refresh_token)
    assert rt_rec is not None
    rt_rec.expires_at = int(time.time()) - 10

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        body, ct = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("client_id", tokens["client_id"]),
            ("client_secret", tokens["client_secret"]),
        ])
        resp = await client.post("/token", content=body, headers={"Content-Type": ct})

    assert resp.status_code == 400, resp.text
    assert resp.json().get("error") in ("invalid_grant", "invalid_token"), resp.json()


@pytest.mark.asyncio
async def test_refresh_concurrentDoubleRefresh_revokesGrant():
    """Atomic-rotate returning False (concurrent double-refresh race loser) → invalid_grant + grant revoked.

    Simulates the race: Thread A and Thread B both call load_refresh_token before
    either rotation is committed.  Thread A wins the atomic UPDATE; Thread B's
    exchange_refresh_token sees rotate_refresh_token return False.  Thread B must
    get invalid_grant AND the whole grant is revoked so Thread A's new tokens die too.

    Guard: ``if not rotated: revoke_grant_by_id`` in
    ``OmtAuthorizationServerProvider.exchange_refresh_token``.
    RED when that branch is removed (grant stays alive, no TokenError).
    """
    import time as _time

    from mcp.server.auth.provider import RefreshToken, TokenError
    from mcp.shared.auth import OAuthClientInformationFull
    from pydantic import AnyUrl

    from ohmytradeagent_mcp.gateway.provider import OmtAuthorizationServerProvider
    from ohmytradeagent_mcp.gateway.store import InMemoryStore, RefreshTokenRecord

    store = InMemoryStore(membership={"sub-1": ["acme"]})

    grant_id = await store.create_grant(
        client_id="cli-race",
        provider="google",
        subject="sub-1",
        tenants=["acme"],
        client_name="Race Test",
    )
    rt = RefreshTokenRecord(
        token="rt-race",
        grant_id=grant_id,
        client_id="cli-race",
        scopes=["read"],
        subject="sub-1",
        resource=None,
        expires_at=int(_time.time()) + 86400 * 30,
    )
    await store.put_refresh_token(rt)

    # Simulate Thread A winning the atomic rotate first
    ok = await store.rotate_refresh_token("rt-race", "thread-a-new-token")
    assert ok is True, "Thread A's rotation should succeed"

    # Thread B now calls exchange_refresh_token with the same (now-rotated) token.
    # It saw rotated_at=None when it called load_refresh_token (the race),
    # but rotate_refresh_token returns False → must revoke + raise invalid_grant.
    provider = OmtAuthorizationServerProvider(
        store=store,
        idp=None,
        resource="https://example.com/mcp",
        google_callback_url="https://example.com/oauth/google/callback",
    )
    client_info = OAuthClientInformationFull(
        client_id="cli-race",
        redirect_uris=[AnyUrl("https://example.com/cb")],
        grant_types=["authorization_code"],
        response_types=["code"],
        scope="read",
        token_endpoint_auth_method="none",
    )
    refresh_token_obj = RefreshToken(
        token="rt-race",
        client_id="cli-race",
        scopes=["read"],
        expires_at=int(_time.time()) + 86400 * 30,
        resource=None,
        subject="sub-1",
    )

    with pytest.raises(TokenError) as exc_info:
        await provider.exchange_refresh_token(client_info, refresh_token_obj, ["read"])

    assert exc_info.value.error == "invalid_grant", (
        f"Concurrent race loser must raise invalid_grant, got: {exc_info.value.error}"
    )

    # Grant must be revoked so Thread A's new tokens are dead too
    grant = await store.get_grant(grant_id)
    assert grant is not None
    assert grant.revoked_at is not None, (
        "Grant must be revoked after concurrent double-refresh is detected"
    )


@pytest.mark.asyncio
async def test_refresh_wrongClient_isInvalidClient():
    """Using a refresh token with a different client_id is rejected."""
    app = make_app()
    tokens = await do_oauth_flow(app)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        # Register a second client
        reg2 = await register_client(client, client_name="Second Client")

        body, ct = encode_form([
            ("grant_type", "refresh_token"),
            ("refresh_token", tokens["refresh_token"]),
            ("client_id", reg2["client_id"]),       # wrong client
            ("client_secret", reg2.get("client_secret", "")),
        ])
        resp = await client.post("/token", content=body, headers={"Content-Type": ct})

    assert resp.status_code in (400, 401), resp.text
    assert resp.json().get("error") in (
        "invalid_client", "invalid_grant", "unauthorized_client"
    ), resp.json()
