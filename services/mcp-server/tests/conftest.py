"""Shared fixtures for the MCP server test suite.

The stub BFF is an `httpx.MockTransport` that routes on path+query over JSON
fixtures.  It binds token `test-mcp-token-acme` to tenant `acme` and returns
401 for any other token.  Every request is checked for the required headers.
"""
from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

FIXTURES = Path(__file__).parent / "fixtures"

# The token the stub recognises.
TEST_TOKEN = "test-mcp-token-acme"
TEST_TENANT = "acme"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


def _json_response(data: dict, status: int = 200) -> httpx.Response:
    return httpx.Response(status, json=data)


def _stub_handler(request: httpx.Request) -> httpx.Response:
    """Route the request to the right fixture or error response."""
    # Header checks — every request must carry both headers.
    auth = request.headers.get("authorization", "")
    tenant_header = request.headers.get("x-tenant-id", "")

    expected_auth = f"Bearer {TEST_TOKEN}"
    if auth != expected_auth:
        return _json_response({"error": "unauthorized"}, 401)
    if tenant_header != TEST_TENANT:
        return _json_response({"error": "wrong_tenant"}, 403)

    path = request.url.path

    if path == "/api/mcp/whoami":
        return _json_response(_load("whoami_acme.json"))

    if path == "/api/portfolio":
        return _json_response(_load("portfolio.json"))

    if path == "/api/account-killswitch":
        return _json_response(_load("account_killswitch.json"))

    if path == "/api/notional-cap":
        return _json_response(_load("notional_cap.json"))

    if path == "/api/floor-breach":
        return _json_response(_load("floor_breach.json"))

    if path == "/api/live/marks":
        return _json_response(_load("live_marks.json"))

    if path == "/api/signals":
        return _json_response(_load("signals.json"))

    if path == "/api/trades":
        return _json_response(_load("trades.json"))

    if path == "/api/orders":
        return _json_response(_load("orders.json"))

    return _json_response({"error": "not_found"}, 404)


@pytest.fixture
def stub_transport() -> httpx.MockTransport:
    """An httpx.MockTransport that plays back the test fixtures."""
    return httpx.MockTransport(_stub_handler)


@pytest.fixture
def stub_bff(stub_transport):
    """A BffClient wired to the stub transport."""
    from ohmytradeagent_mcp.bff import BffClient

    return BffClient(
        base_url="http://bff.test",
        token=TEST_TOKEN,
        tenant=TEST_TENANT,
        transport=stub_transport,
    )


@pytest.fixture
def test_binding():
    """A TenantBinding matching the stub."""
    from ohmytradeagent_mcp.resolver import TenantBinding

    return TenantBinding(tenant_id=TEST_TENANT, token_id="87121ec9ab39")
