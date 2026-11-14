"""Store error handling — Finding F.

Covers:
- google_callback must check update_pending_after_callback return value;
  False (state consumed between get and update) → 400 race_on_state.
"""
from __future__ import annotations

import httpx
import pytest

from ohmytradeagent_mcp.gateway.app import build_gateway_app
from ohmytradeagent_mcp.gateway.settings import GatewaySettings
from ohmytradeagent_mcp.gateway.store import InMemoryStore
from tests.gateway.fake_idp import FakeIdP
from tests.gateway.fixtures import (
    PUBLIC_URL,
    TEST_SIGNING_KEY_PEM,
    _DEFAULT_MEMBERSHIP,
)
from tests.gateway.oauth_helpers import (
    extract_state_from_location,
    pkce_pair,
    register_client,
)


class _RacingStore(InMemoryStore):
    """InMemoryStore that returns False from update_pending_after_callback
    to simulate a concurrent state-consumption race."""

    async def update_pending_after_callback(self, state, **kwargs) -> bool:
        return False


def _make_racing_app() -> object:
    env = {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": "http://bff.test",
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = _RacingStore(membership=dict(_DEFAULT_MEMBERSHIP))
    return build_gateway_app(settings, store, idp=FakeIdP(sub="sub-1"))


@pytest.mark.asyncio
async def test_callback_racingState_is400():
    """google_callback must return 400 race_on_state when update_pending_after_callback
    returns False (state was consumed concurrently between get and update).

    RED: return value is ignored — callback renders the consent page (200).
    GREEN: callback checks the return value and returns 400.
    """
    app = _make_racing_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=PUBLIC_URL
    ) as client:
        reg = await register_client(client)
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

        # Trigger the callback — the store will return False from update_pending
        cb_resp = await client.get(
            "/oauth/google/callback",
            params={"code": "code", "state": our_state},
        )

    assert cb_resp.status_code == 400, (
        f"expected 400 for race_on_state, got {cb_resp.status_code}; "
        f"body: {cb_resp.text[:200]}"
    )
    assert cb_resp.json().get("error") == "race_on_state", cb_resp.json()
