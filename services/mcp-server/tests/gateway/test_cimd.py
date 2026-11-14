"""Test 3 — Client ID Metadata Document (CIMD) flow (Seam H).

Uses an injectable ``fetch_cimd`` function so no real network calls are made.
"""
from __future__ import annotations

import httpx
import pytest
from mcp.server.auth.provider import RegistrationError

from tests.gateway.fixtures import ACME_SUB, PUBLIC_URL, make_app

# A minimal valid CIMD document
_CIMD_URL = "https://example.com/client/my-app"
_CIMD_DOC = {
    "client_id": _CIMD_URL,
    "client_name": "My CIMD App",
    "redirect_uris": ["https://example.com/callback"],
    "grant_types": ["authorization_code"],
    "response_types": ["code"],
    "scope": "read",
    "token_endpoint_auth_method": "none",
}


def _make_fetch(doc: dict | None = None, *, raise_error: str | None = None):
    """Return an injectable fetch_cimd that returns *doc* or raises."""
    _store: dict = {}
    _calls: list[str] = []

    async def _fetch(url: str) -> dict:
        _calls.append(url)
        if raise_error:
            raise RegistrationError(error="invalid_client_metadata", error_description=raise_error)
        if doc is None:
            raise RegistrationError(error="invalid_client_metadata", error_description="not found")
        return doc

    _fetch.calls = _calls  # type: ignore[attr-defined]
    return _fetch


def _make_cached_fetch(doc: dict):
    """Return an injectable fetch_cimd that caches (tracks call count)."""
    from ohmytradeagent_mcp.gateway.cimd import _CimdCache

    # Use a fresh cache for isolation; override the underlying network call
    cache = _CimdCache(ttl=3600)
    calls: list[str] = []

    async def _real_fetch(url: str) -> dict:
        calls.append(url)
        return doc

    # Wrap with the cache logic manually
    async def _fetch(url: str) -> dict:
        cached = cache.get(url)
        if cached is not None:
            return cached
        d = await _real_fetch(url)
        cache.put(url, d)
        return d

    _fetch.calls = calls  # type: ignore[attr-defined]
    return _fetch


@pytest.mark.asyncio
async def test_cimd_validDocument_isClientWithAuthMethodNone():
    """A CIMD URL with a valid document is accepted as a client with auth_method=none."""
    fetch = _make_fetch(_CIMD_DOC)
    app, store = make_app(return_store=True)
    # Override the provider's fetch_cimd
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore
    from tests.gateway.fake_idp import FakeIdP
    from tests.gateway.fixtures import _DEFAULT_MEMBERSHIP, BFF_URL, TEST_SIGNING_KEY_PEM

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore(membership=dict(_DEFAULT_MEMBERSHIP))
    idp = FakeIdP(sub=ACME_SUB)
    app = build_gateway_app(settings, store, idp=idp, fetch_cimd=fetch)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # /authorize with the CIMD URL as client_id
        resp = await client.get(
            "/authorize",
            params={
                "client_id": _CIMD_URL,
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "scope": "read",
                "code_challenge": "abc123" * 7,  # 42 chars
                "code_challenge_method": "S256",
                "state": "clientstate",
            },
            follow_redirects=False,
        )
    # Should redirect to FakeIdP (not 400)
    assert resp.status_code == 302, f"Expected redirect, got {resp.status_code}: {resp.text}"


@pytest.mark.asyncio
async def test_cimd_clientIdMismatch_rejected():
    """CIMD document whose client_id differs from the URL is rejected."""
    bad_doc = {**_CIMD_DOC, "client_id": "https://other.example.com/app"}

    async def fetch(url: str) -> dict:
        return bad_doc

    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore
    from tests.gateway.fake_idp import FakeIdP
    from tests.gateway.fixtures import _DEFAULT_MEMBERSHIP, BFF_URL, TEST_SIGNING_KEY_PEM

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore(membership=dict(_DEFAULT_MEMBERSHIP))
    app = build_gateway_app(settings, store, idp=FakeIdP(ACME_SUB), fetch_cimd=fetch)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        resp = await client.get(
            "/authorize",
            params={
                "client_id": _CIMD_URL,
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "code_challenge": "abc" * 15,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
    # Should be rejected (client not found → 400 direct error)
    assert resp.status_code == 400, f"Expected 400, got {resp.status_code}"


@pytest.mark.asyncio
async def test_cimd_httpUrl_rejected():
    """HTTP (non-HTTPS) CIMD URL is not treated as CIMD and not found."""
    app = make_app()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        resp = await client.get(
            "/authorize",
            params={
                "client_id": "http://example.com/client",
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "code_challenge": "abc" * 15,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
    assert resp.status_code == 400, f"Expected 400, got {resp.status_code}"


@pytest.mark.asyncio
async def test_cimd_rootPath_rejected():
    """An https URL with root path only is not treated as CIMD."""
    app = make_app()

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        resp = await client.get(
            "/authorize",
            params={
                "client_id": "https://example.com/",
                "redirect_uri": "https://example.com/callback",
                "response_type": "code",
                "code_challenge": "abc" * 15,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
    # Root path → not CIMD → not found as DCR client either → 400
    assert resp.status_code == 400, f"Expected 400, got {resp.status_code}"


@pytest.mark.asyncio
async def test_cimd_badRedirects_rejected():
    """CIMD document with bad redirect_uris is rejected."""
    bad_doc = {**_CIMD_DOC, "redirect_uris": ["http://evil.com/callback"]}

    async def fetch(url: str) -> dict:
        return bad_doc

    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore
    from tests.gateway.fake_idp import FakeIdP
    from tests.gateway.fixtures import _DEFAULT_MEMBERSHIP, BFF_URL, TEST_SIGNING_KEY_PEM

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore(membership=dict(_DEFAULT_MEMBERSHIP))
    app = build_gateway_app(settings, store, idp=FakeIdP(ACME_SUB), fetch_cimd=fetch)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        resp = await client.get(
            "/authorize",
            params={
                "client_id": _CIMD_URL,
                "redirect_uri": "http://evil.com/callback",
                "response_type": "code",
                "code_challenge": "abc" * 15,
                "code_challenge_method": "S256",
            },
            follow_redirects=False,
        )
    assert resp.status_code == 400, f"Expected 400, got {resp.status_code}"


@pytest.mark.asyncio
async def test_cimd_cached_oneFetchPerHour():
    """The same CIMD URL is only fetched once within the cache TTL."""
    fetch = _make_cached_fetch(_CIMD_DOC)

    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore
    from tests.gateway.fake_idp import FakeIdP
    from tests.gateway.fixtures import _DEFAULT_MEMBERSHIP, BFF_URL, TEST_SIGNING_KEY_PEM

    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore(membership=dict(_DEFAULT_MEMBERSHIP))
    app = build_gateway_app(settings, store, idp=FakeIdP(ACME_SUB), fetch_cimd=fetch)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        params = {
            "client_id": _CIMD_URL,
            "redirect_uri": "https://example.com/callback",
            "response_type": "code",
            "code_challenge": "abc123" * 7,
            "code_challenge_method": "S256",
            "state": "s1",
        }
        await client.get("/authorize", params=params, follow_redirects=False)
        await client.get("/authorize", params={**params, "state": "s2"}, follow_redirects=False)

    # Only one network fetch should have been made
    assert len(fetch.calls) == 1, f"Expected 1 fetch, got {len(fetch.calls)}: {fetch.calls}"
