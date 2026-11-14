"""Shared test fixtures for gateway tests.

Provides:
- make_app(): creates a build_gateway_app Starlette app with InMemoryStore + test key
- make_app_disabled(): same but with OMT_GATEWAY_DISABLED=true
- seed_token(): insert an access token into InMemoryStore
- seed_two_tenant_token(): convenience for two-tenant grants
- TEST_SIGNING_KEY_PEM: Ed25519 private key PEM for the Python signer (uses the
  same key pair as Java DelegatedAssertionTestKeys.PRIMARY_*)
- PUBLIC_URL: the test gateway public URL
- ACME_SUB, BETA_SUB: stable test subject identifiers
"""
from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import httpx

PUBLIC_URL = "https://tradeagent-mcp.ridopark.com"
BFF_URL = "http://bff.test"

# Subject identifiers for tests
ACME_SUB = "sub-1"
BETA_SUB = "sub-2"

# Ed25519 key pair — the same as Java DelegatedAssertionTestKeys.PRIMARY_*
# PKCS8 DER base64 (private): "MC4CAQAwBQYDK2VwBCIEIGdpg7tnNVS+T9MsfJWD1DkvHlJjdTD8p+pxsDvWMNZ7"
# SPKI DER base64 (public):   "MCowBQYDK2VwAyEAHisUEeiCZJqA+lLdttZH5el0TQdI3kdV7z9sDK1X0Ck="
#
# openssl ec / ed25519 PEM produced from the PKCS8 DER above:
TEST_SIGNING_KEY_PEM = """\
-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEIGdpg7tnNVS+T9MsfJWD1DkvHlJjdTD8p+pxsDvWMNZ7
-----END PRIVATE KEY-----
"""

TEST_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAHisUEeiCZJqA+lLdttZH5el0TQdI3kdV7z9sDK1X0Ck="

# Default membership: sub-1 belongs to acme; sub-2 belongs to acme + beta
_DEFAULT_MEMBERSHIP: dict[str, list[str]] = {
    ACME_SUB: ["acme"],
    BETA_SUB: ["acme", "beta"],
}

FIXTURES = Path(__file__).parent.parent / "fixtures"


def _load_fixture(name: str) -> dict:
    import json

    return json.loads((FIXTURES / name).read_text())


def _make_bff_handler(
    bff_calls: list[dict] | None = None,
    reject: bool = False,
) -> httpx.MockTransport:
    """Return a MockTransport that plays back fixtures, optionally capturing requests."""

    def handler(request: httpx.Request) -> httpx.Response:
        def _json_response(data: dict, status: int = 200) -> httpx.Response:
            return httpx.Response(status, json=data)

        auth = request.headers.get("authorization", "")
        tenant = request.headers.get("x-tenant-id", "")

        # Capture request metadata for assertions
        if bff_calls is not None:
            bff_calls.append({"authorization": auth, "x-tenant-id": tenant, "path": request.url.path})

        if reject:
            return _json_response({"error": "rejected"}, 403)

        # Verify bearer is present (not empty); in HTTP mode it's an assertion
        if not auth.startswith("Bearer "):
            return _json_response({"error": "unauthorized"}, 401)

        path = request.url.path

        if path == "/api/mcp/whoami":
            return _json_response({"tenant_id": tenant, "token_id": "dlg-test", "scope": "read"})
        if path == "/api/portfolio":
            return _json_response(_load_fixture("portfolio.json"))
        if path == "/api/account-killswitch":
            return _json_response(_load_fixture("account_killswitch.json"))
        if path == "/api/notional-cap":
            return _json_response(_load_fixture("notional_cap.json"))
        if path == "/api/floor-breach":
            return _json_response(_load_fixture("floor_breach.json"))
        if path == "/api/live/marks":
            return _json_response(_load_fixture("live_marks.json"))
        if path == "/api/signals":
            return _json_response(_load_fixture("signals.json"))
        if path == "/api/trades":
            return _json_response(_load_fixture("trades.json"))
        if path == "/api/orders":
            return _json_response(_load_fixture("orders.json"))
        if path == "/api/entries/quote":
            return _json_response(_load_fixture("entries_quote.json"))
        if path == "/api/entries/chain/expirations":
            return _json_response(_load_fixture("entries_chain_expirations.json"))
        if path == "/api/entries/chain":
            return _json_response(_load_fixture("entries_chain.json"))
        if path == "/api/flow/summary":
            return _json_response(_load_fixture("flow_summary.json"))
        if path == "/api/flow/orders":
            return _json_response(_load_fixture("flow_orders.json"))
        if path == "/api/darkpool/summary":
            return _json_response(_load_fixture("darkpool_summary.json"))
        if path == "/api/darkpool/prints":
            return _json_response(_load_fixture("darkpool_prints.json"))
        return _json_response({"error": "not_found"}, 404)

    return httpx.MockTransport(handler)


def make_app(
    *,
    return_store: bool = False,
    capture_bff: bool = False,
    bff_reject: bool = False,
    sub: str = ACME_SUB,
    wrong_nonce: bool = False,
    email_verified: bool = True,
    idp=None,
    auth_rate_limiter=None,
) -> Any:
    """Create a gateway Starlette app for testing.

    Returns:
        - ``app`` when both flags are False
        - ``(app, store)`` when ``return_store=True``
        - ``(app, store, bff_calls)`` when both flags are True
    """
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore
    from tests.gateway.fake_idp import FakeIdP

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore(membership=dict(_DEFAULT_MEMBERSHIP))

    bff_calls: list[dict] = []
    bff_transport = _make_bff_handler(
        bff_calls=bff_calls if capture_bff else None,
        reject=bff_reject,
    )

    if idp is None:
        idp = FakeIdP(sub=sub, wrong_nonce=wrong_nonce, email_verified=email_verified)

    app = build_gateway_app(
        settings, store, bff_transport=bff_transport, idp=idp,
        auth_rate_limiter=auth_rate_limiter,
    )

    if capture_bff:
        return app, store, bff_calls
    if return_store:
        return app, store
    return app


def make_app_disabled() -> Any:
    """Create a gateway app with OMT_GATEWAY_DISABLED=true."""
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_GATEWAY_DISABLED": "true",
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore(membership=dict(_DEFAULT_MEMBERSHIP))
    return build_gateway_app(settings, store)


def seed_token(
    store: Any,
    token: str,
    *,
    sub: str = ACME_SUB,
    tenants: list[str] | None = None,
    expires_at: int | None = None,
    revoked: bool = False,
    resource: str | None = "_default",
    client_id: str = "test-client",
) -> str:
    """Seed a token into InMemoryStore; returns the token string."""
    if tenants is None:
        tenants = [_DEFAULT_MEMBERSHIP[sub][0]] if sub in _DEFAULT_MEMBERSHIP else ["acme"]
    if expires_at is None:
        expires_at = int(time.time()) + 3600
    if resource == "_default":
        resource = f"{PUBLIC_URL}/mcp"

    store.put_access_token(
        token=token,
        sub=sub,
        tenants=tenants,
        client_id=client_id,
        expires_at=expires_at,
        resource=resource,
        revoked=revoked,
    )
    return token


def seed_two_tenant_token(
    store: Any,
    token: str,
    *,
    sub: str = BETA_SUB,
    tenants: list[str] | None = None,
    client_id: str = "test-client",
) -> str:
    """Seed a two-tenant token into InMemoryStore; ensures membership for both tenants."""
    if tenants is None:
        tenants = ["acme", "beta"]
    # Ensure membership for this subject covers both tenants
    store.set_membership(sub, tenants)
    return seed_token(store, token, sub=sub, tenants=tenants, client_id=client_id)
