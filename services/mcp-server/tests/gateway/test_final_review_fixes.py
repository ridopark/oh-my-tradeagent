"""Regression tests for the final (round-3) review findings on the gateway.

Each test failed against the code it was written for:
- the startup DB probe left the pool bound to a closed event loop (gateway served 503/500);
- the patched /register lost the SDK's request-body cap and 500'd on non-object JSON;
- the loopback redirect matcher ignored userinfo and fragments;
- a store exception on a request path surfaced as a 500;
- the root protected-resource document's authorization_servers differed from the issuer;
- the Postgres test harness dropped the superuser password (CI red).
"""
from __future__ import annotations

import asyncio
from urllib.parse import urlparse

import httpx
import pytest

from ohmytradeagent_mcp.gateway.redirects import loopback_port_agnostic_match
from tests.gateway.conftest import MCP_GW_TEST_PW, _dsn_for_db
from tests.gateway.fixtures import PUBLIC_URL, TEST_SIGNING_KEY_PEM, make_app, seed_token

BASE = "https://tradeagent-mcp.ridopark.com"


def _client(app) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE)


# ---------------------------------------------------------------------------
# Test harness: the superuser DSN for the fresh database keeps its credentials
# ---------------------------------------------------------------------------


def test_harness_dsnForDb_keepsUserAndPassword():
    dsn = _dsn_for_db("postgresql://postgres:postgres@localhost:5432/postgres", "mcp_test_x")
    p = urlparse(dsn)
    assert (p.username, p.password, p.hostname, p.port) == ("postgres", "postgres", "localhost", 5432)
    assert p.path == "/mcp_test_x"


def test_harness_dsnForDb_noPassword_staysPasswordless():
    dsn = _dsn_for_db("postgresql://postgres@127.0.0.1:55432/postgres", "mcp_test_y")
    p = urlparse(dsn)
    assert (p.username, p.password, p.port, p.path) == ("postgres", None, 55432, "/mcp_test_y")


# ---------------------------------------------------------------------------
# Startup probe must not leave a pool bound to a closed event loop
# ---------------------------------------------------------------------------


def test_runGateway_afterStartupProbe_servesReadyOnTheServerLoop(pg_dsns, monkeypatch):
    """run_gateway probes the DB, then the app must be healthy on uvicorn's (another) loop."""
    _super_dsn, gw_dsn = pg_dsns
    if gw_dsn is None:
        pytest.skip("OMT_TEST_DATABASE_URL not set")

    import uvicorn

    from ohmytradeagent_mcp.gateway.main import run_gateway

    p = urlparse(gw_dsn)
    captured: dict = {}
    monkeypatch.setattr(uvicorn, "run", lambda app, **kw: captured.update(app=app))

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": "http://bff.test",
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_GOOGLE_CLIENT_ID": "gid",
        "OMT_GOOGLE_CLIENT_SECRET": "gsec",
        "OMT_GATEWAY_DB_URL": f"postgresql://mcp_gateway@{p.hostname}:{p.port}{p.path}",
        "MCP_GATEWAY_DB_PASSWORD": MCP_GW_TEST_PW,
    }
    assert run_gateway(env) == 0
    app = captured["app"]

    async def probe() -> int:
        # Drive the ASGI lifespan as uvicorn does (startup connects the store's pool on THIS
        # loop), make a request, then shut down.
        to_app: asyncio.Queue = asyncio.Queue()
        from_app: asyncio.Queue = asyncio.Queue()
        lifespan = asyncio.create_task(app({"type": "lifespan"}, to_app.get, from_app.put))
        await to_app.put({"type": "lifespan.startup"})
        assert (await from_app.get())["type"] == "lifespan.startup.complete"
        try:
            async with _client(app) as client:
                return (await client.get("/readyz")).status_code
        finally:
            await to_app.put({"type": "lifespan.shutdown"})
            await from_app.get()
            await lifespan

    # A fresh loop, as uvicorn would create after run_gateway's synchronous startup.
    assert asyncio.run(probe()) == 200


# ---------------------------------------------------------------------------
# /register: body cap kept, non-object JSON is a 400
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_register_oversizedBody_is413():
    app = make_app()
    body = b'{"redirect_uris": ["https://example.com/cb"], "pad": "' + b"x" * (5 * 1024 * 1024) + b'"}'
    async with _client(app) as client:
        resp = await client.post(
            "/register", content=body, headers={"Content-Type": "application/json"}
        )
    assert resp.status_code == 413, resp.status_code


@pytest.mark.asyncio
@pytest.mark.parametrize("body", ["[]", '"x"', "1", "null", "true"])
async def test_register_nonObjectJson_is400(body):
    app = make_app()
    async with _client(app) as client:
        resp = await client.post(
            "/register", content=body, headers={"Content-Type": "application/json"}
        )
    assert resp.status_code == 400, (body, resp.status_code)
    assert resp.json()["error"] == "invalid_client_metadata"


# ---------------------------------------------------------------------------
# Loopback matcher: no userinfo, no fragment
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "request_uri",
    [
        "http://evil.com@127.0.0.1:9/cb",
        "http://user:pw@127.0.0.1:9/cb",
        "http://127.0.0.1:9/cb#frag",
    ],
)
def test_loopbackMatch_rejectsUserinfoAndFragment(request_uri):
    assert loopback_port_agnostic_match("http://127.0.0.1:1234/cb", request_uri) is False


def test_loopbackMatch_stillAcceptsAnotherPort():
    assert loopback_port_agnostic_match("http://127.0.0.1/cb", "http://127.0.0.1:53280/cb") is True


# ---------------------------------------------------------------------------
# Store failure on a request path → 503, generic body, no detail
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_storeFailure_onBearerPath_is503_withoutDetail():
    app, store = make_app(return_store=True)
    seed_token(store, "tok-1")

    async def boom(*_a, **_kw):
        raise RuntimeError("postgresql://mcp_gateway:s3cret@db/dashboard is down")

    store.get_access_token = boom  # type: ignore[method-assign]
    async with _client(app) as client:
        resp = await client.post("/mcp", json={}, headers={"Authorization": "Bearer tok-1"})
    assert resp.status_code == 503, resp.status_code
    assert "s3cret" not in resp.text and "Traceback" not in resp.text
    assert resp.json() == {"error": "temporarily_unavailable"}


# ---------------------------------------------------------------------------
# Root protected-resource metadata names the issuer exactly
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_rootResourceMetadata_authorizationServer_equalsIssuerExactly():
    app = make_app()
    async with _client(app) as client:
        issuer = (await client.get("/.well-known/oauth-authorization-server")).json()["issuer"]
        root = (await client.get("/.well-known/oauth-protected-resource")).json()
        mcp = (await client.get("/.well-known/oauth-protected-resource/mcp")).json()
    assert root["authorization_servers"] == [issuer]
    assert mcp["authorization_servers"] == [issuer]
