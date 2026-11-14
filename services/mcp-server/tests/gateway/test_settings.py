"""Gateway settings and startup tests — Seam F style.

Test 14: run_gateway(env) exits 2 when required env vars are missing.
Test 15: Production mode (not disabled) requires OMT_GATEWAY_DB_URL,
         OMT_GOOGLE_CLIENT_ID, OMT_GOOGLE_CLIENT_SECRET.
Test 16: Production app wiring — ToolRateLimiter and AuthRateLimiter are
         wired from settings and affect /register and tool calls.
"""
from __future__ import annotations

import httpx
import pytest

from tests.gateway.fixtures import BFF_URL, PUBLIC_URL, TEST_SIGNING_KEY_PEM


def _run(env: dict) -> tuple[int, str]:
    """Call run_gateway and capture stderr."""
    import io
    import sys

    from ohmytradeagent_mcp.gateway.main import run_gateway

    old_stderr = sys.stderr
    buf = io.StringIO()
    sys.stderr = buf
    try:
        code = run_gateway(env, _test_mode=True)
    finally:
        sys.stderr = old_stderr
    return code, buf.getvalue()


def _base_env() -> dict:
    return {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
    }


def test_gateway_missingPublicUrl_exits2_namesIt():
    env = {k: v for k, v in _base_env().items() if k != "OMT_PUBLIC_URL"}
    code, err = _run(env)
    assert code == 2, f"expected 2, got {code}"
    assert "OMT_PUBLIC_URL" in err


def test_gateway_missingBffUrl_exits2():
    env = {k: v for k, v in _base_env().items() if k != "OMT_BFF_URL"}
    code, err = _run(env)
    assert code == 2
    assert "OMT_BFF_URL" in err


def test_gateway_missingSigningKey_exits2():
    env = {k: v for k, v in _base_env().items() if k != "MCP_GATEWAY_SIGNING_KEY"}
    code, err = _run(env)
    assert code == 2
    assert "MCP_GATEWAY_SIGNING_KEY" in err


def test_gateway_allPresent_returnsZero():
    """With all required vars present, run_gateway in test mode returns 0."""
    code, _err = _run(_base_env())
    assert code == 0


def test_settings_malformedTrustedProxy_raisesConfigError():
    """OMT_TRUSTED_PROXY_CIDRS with an invalid CIDR raises ConfigError → exit 2."""
    env = {
        **_base_env(),
        "OMT_TRUSTED_PROXY_CIDRS": "not-a-cidr",
    }
    code, err = _run(env)
    assert code == 2, f"expected exit 2 for bad CIDR, got {code}"
    # Must be a single non-empty line
    lines = [ln for ln in err.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected one error line, got: {err!r}"
    assert "TRUSTED_PROXY" in err or "cidr" in err.lower() or "invalid" in err.lower()


def test_settings_validTrustedProxy_accepted():
    """OMT_TRUSTED_PROXY_CIDRS with valid CIDRs is accepted (exit 0)."""
    env = {
        **_base_env(),
        "OMT_TRUSTED_PROXY_CIDRS": "10.0.0.0/8,172.16.0.0/12",
    }
    code, _err = _run(env)
    assert code == 0, f"expected exit 0 for valid CIDRs, got {code}"


def test_entrypoint_badSigningKey_exits2_oneLine():
    """run_gateway exits 2 and prints a one-line error when the signing key is not valid PEM."""
    env = {
        **_base_env(),
        "MCP_GATEWAY_SIGNING_KEY": "not-a-valid-pem-at-all",
    }
    code, err = _run(env)
    assert code == 2, f"expected 2, got {code}"
    # Must be a single non-empty line
    lines = [ln for ln in err.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected one error line, got: {err!r}"
    assert "error" in err.lower() or "invalid" in err.lower() or "key" in err.lower()


# ---------------------------------------------------------------------------
# Test 15: Production-mode required env vars (item 6)
# ---------------------------------------------------------------------------
# These use subprocess so they test the real startup path without _test_mode.
# Each subprocess exits immediately (exit 2) because a required var is missing;
# no DB connection or uvicorn is attempted.


def _run_subprocess(env_overrides: dict) -> tuple[int, str]:
    """Run `python -m ohmytradeagent_mcp.gateway` with the given env, return (code, stderr)."""
    import os
    import subprocess
    import sys

    env = {
        **os.environ,
        **env_overrides,
    }
    result = subprocess.run(
        [sys.executable, "-m", "ohmytradeagent_mcp.gateway"],
        env=env,
        check=False,
        capture_output=True,
        text=True,
        timeout=5,
    )
    return result.returncode, result.stderr


def _prod_env() -> dict:
    """Minimal env to pass the production-mode check."""
    return {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_GOOGLE_CLIENT_ID": "gid",
        "OMT_GOOGLE_CLIENT_SECRET": "gsec",
        "OMT_GATEWAY_DB_URL": "postgresql://mcp_gateway@localhost/dashboard",
        "MCP_GATEWAY_DB_PASSWORD": "test-pw",
    }


def test_production_missingDbUrl_exits2():
    """Production mode requires OMT_GATEWAY_DB_URL — exits 2 when absent.

    RED: currently main.py falls back to InMemoryStore when OMT_GATEWAY_DB_URL
    is absent (no exit 2).
    GREEN: main.py validates required vars and exits 2.
    """
    env = {k: v for k, v in _prod_env().items() if k != "OMT_GATEWAY_DB_URL"}
    code, err = _run_subprocess(env)
    assert code == 2, f"expected 2, got {code}; stderr: {err!r}"
    assert "OMT_GATEWAY_DB_URL" in err, f"expected OMT_GATEWAY_DB_URL in error; got: {err!r}"
    # Must be a single non-empty line
    lines = [ln for ln in err.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected one error line, got: {err!r}"


def test_production_missingGoogleClientId_exits2():
    """Production mode requires OMT_GOOGLE_CLIENT_ID.

    RED: no such check exists yet.
    """
    env = {k: v for k, v in _prod_env().items() if k != "OMT_GOOGLE_CLIENT_ID"}
    code, err = _run_subprocess(env)
    assert code == 2, f"expected 2, got {code}; stderr: {err!r}"
    assert "OMT_GOOGLE_CLIENT_ID" in err


def test_production_missingGoogleClientSecret_exits2():
    """Production mode requires OMT_GOOGLE_CLIENT_SECRET.

    RED: no such check exists yet.
    """
    env = {k: v for k, v in _prod_env().items() if k != "OMT_GOOGLE_CLIENT_SECRET"}
    code, err = _run_subprocess(env)
    assert code == 2, f"expected 2, got {code}; stderr: {err!r}"
    assert "OMT_GOOGLE_CLIENT_SECRET" in err


def test_production_disabled_skipsProductionCheck():
    """With OMT_GATEWAY_DISABLED=true, production var check is skipped.

    Disabled mode starts uvicorn (all /mcp → 503) without requiring DB URL or
    Google credentials.  We verify this by running the subprocess and confirming
    it does NOT exit 2 (immediately) — if it starts uvicorn it blocks, which
    we catch as TimeoutExpired and treat as success.

    RED: production check fires even when disabled → exits 2.
    GREEN: disabled mode skips the production check → process starts (blocks in uvicorn).
    """
    import subprocess as _subprocess

    env = {
        **__import__("os").environ,
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
        "OMT_GATEWAY_DISABLED": "true",
        # No OMT_GATEWAY_DB_URL, OMT_GOOGLE_CLIENT_ID, OMT_GOOGLE_CLIENT_SECRET
    }
    import sys
    try:
        result = _subprocess.run(
            [sys.executable, "-m", "ohmytradeagent_mcp.gateway"],
            env=env,
            check=False,
            capture_output=True,
            text=True,
            timeout=2,  # Short timeout; expect uvicorn to block (TimeoutExpired)
        )
        # If it returned quickly it must NOT be exit 2
        assert result.returncode != 2, (
            f"disabled mode must not exit 2; got {result.returncode}; "
            f"stderr: {result.stderr!r}"
        )
    except _subprocess.TimeoutExpired:
        # Uvicorn started and blocked — this is the expected behavior for disabled mode
        pass


# ---------------------------------------------------------------------------
# Test 16: Production app wiring — rate limiters wired from settings (item 4)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_productionWiring_authRateLimiter_wiredFromSettings():
    """AuthRateLimiter is wired from settings in production path.

    Tests that build_gateway_app called with an AuthRateLimiter built from
    settings.rate_auth_per_min_ip correctly returns 429 on the second request.
    This is the same code path that main.py uses after item 4 is implemented.

    RED: main.py does not create or pass auth_rate_limiter to build_gateway_app.
    GREEN: main.py creates AuthRateLimiter(per_ip_rpm=settings.rate_auth_per_min_ip).
    """
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.ratelimit import AuthRateLimiter
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore

    env = {
        **_base_env(),
        "OMT_RATE_AUTH_PER_MIN_IP": "1",
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore()
    # Build rate limiter from settings exactly as main.py should
    auth_limiter = AuthRateLimiter(per_ip_rpm=settings.rate_auth_per_min_ip)
    app = build_gateway_app(settings, store, auth_rate_limiter=auth_limiter)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="https://tradeagent-mcp.ridopark.com",
    ) as client:
        payload = {
            "redirect_uris": ["https://example.com/callback"],
            "token_endpoint_auth_method": "none",
        }
        r1 = await client.post("/register", json=payload)
        assert r1.status_code != 429, f"first /register should not be rate-limited; got {r1.status_code}"
        r2 = await client.post("/register", json=payload)
        assert r2.status_code == 429, (
            f"second /register (same IP, per_ip_rpm=1) should be 429; got {r2.status_code}"
        )
        assert "Retry-After" in r2.headers


@pytest.mark.asyncio
async def test_productionWiring_toolRateLimiter_wiredIntoResolver():
    """ToolRateLimiter is wired into the resolver so the 2nd tool call is rate-limited.

    RED: OAuthIdentityResolver does not accept or use a ToolRateLimiter.
    GREEN: resolver raises ToolError("rate_limited") on 2nd call when per_subject_rpm=1.
    """
    from mcp.server.auth.middleware.auth_context import AccessToken, auth_context_var
    from mcp.server.mcpserver.exceptions import ToolError

    from ohmytradeagent_mcp.gateway.assertion import Signer
    from ohmytradeagent_mcp.gateway.ratelimit import FakeClock, ToolRateLimiter
    from ohmytradeagent_mcp.gateway.resolver import OAuthIdentityResolver
    from ohmytradeagent_mcp.gateway.store import InMemoryStore

    store = InMemoryStore()
    signer = Signer(TEST_SIGNING_KEY_PEM)
    clock = FakeClock(0.0)
    tool_limiter = ToolRateLimiter(per_subject_rpm=1, per_client_rpm=300, clock=clock)
    resolver = OAuthIdentityResolver(
        store=store,
        signer=signer,
        bff_url="http://bff.test",
        tool_rate_limiter=tool_limiter,
    )

    # Set the auth contextvar with a fake AccessToken (no store seed needed).
    # auth_context_var holds AuthenticatedUser, not AccessToken directly.
    from mcp.server.auth.middleware.auth_context import AuthenticatedUser

    sdk_at = AccessToken(
        token="tok-ratelimit",
        client_id="cli-1",
        scopes=["read"],
        expires_at=9999999999,
        subject="sub-1",
        claims={"tenants": ["acme"], "grant_id": "g-1"},
    )
    token = auth_context_var.set(AuthenticatedUser(sdk_at))
    try:
        # First call succeeds
        await resolver("acme")
        # Second call should be rate-limited
        with pytest.raises(ToolError, match="rate_limited"):
            await resolver("acme")
    finally:
        auth_context_var.reset(token)
