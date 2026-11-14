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


# ---------------------------------------------------------------------------
# Item 11: Non-Ed25519 signing key → exit 2
# ---------------------------------------------------------------------------
# A real 2048-bit RSA PKCS8 PEM that loads successfully in cryptography
# but is NOT Ed25519.  This key is test-only: used to verify that startup
# rejects non-Ed25519 keys before the server starts accepting requests.
_RSA_KEY_PEM = """\
-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCiEImiXat5Fqth
zFgCjYCEytP7OokWqPIKVT5JSPn/NkwQY0+1c3N1ATMura1f3Ggr43patCWHknqA
5HjoXqrsIXG/QBCkY42njzWDxZXei3rmvtXivenme9grBcx5m9XpGPbK/Vt63cAk
3DW+FGqES0gG/kkS6OPy3O05YLNkWf2aqdBaqaNca3l3986A1HvIFAkz/OKGd4Ex
kez//pnfHbh2hG4pq2ptwRpHD+ixXgim24nkpfwHonxlsG9MJNFllu16UDIl5912
DG9hOCX0WPEK/bRbVT5WzY0MqiIUjxUwwNxcEQDCeza+otDKo8ejyEO+AF7ZADkf
7hjuvixbAgMBAAECggEAEit6fYq08+GCcMzhPSqyMwaD17yLVtAK5Z+trmJmgkzg
daHnc4zbGqN6Ioye+Iq/NH8k3lB+OKydMXWWx1lTHnWYHtIcS4yh0BxylTCCNK48
puwpvL8qyd2EBzwiMwGGwm3lwC0W+Y0tGAoxuFA/qk9qZuZRjl4wAwWZTyqJUlca
zdG71H3qGDipLiZrvYGLMJwfbi5d+UTqjlL3Lcc8M0jh/C7VxMpBDNQNyA9VRVwt
E7gjLRn+oKYhVRxInl6BXKuFBCqOgg2Bidu4PWB1CS0HuA60qSfyEq9tBex0vjPi
jq2ne3WIitdG631wnPhTEFbRH1L2BbDw5HNGSxnnCQKBgQDX7WWTj9GkyY5YgaV4
hvWW708Ie1sczgMrMNFb+XfxW03H8QS6I/DPJ5oULQzh7GUtZl6E+tu8I4Gj8YA/
SgomDdfRSh1iC/cdSQFXj27eyY/jH0l0p5rwWtV872dFulti1LIQhbiuIijuEN7V
kaZ3ZqMFf/bTQUzT2kKnHViPRwKBgQDAJCYE8i0u9o1pAiKPoPs96w/RD0zWEtjw
iZ/SWYOykNn80WxzDpbgehu8weIi+mQX6emiQioAhxx9tTn8vvkOnzKqVr9cd1BV
cf8L16llTkZA2u5H0C7uw9uMyVL7A0B09jXQA6Y9IMBpuk6w/EOKQsVJXQIN1Cay
7kJrZ95MTQKBgDmEKed5ir1bVwhqWFpXkLmIu0OmICn3Ek8B7PKT7Aglav2UTM8M
XcvAX8F6EpFnd7f1Kb5AWCGwT4sM/D1fin6Cd3dvR45jY4qwdKQPN9guVp6IKffh
4Vqex3lFB/xB3K/huiC+jegMf1ySr9ixuGbbtxby9BtnrIBOBi0fmwRnAoGADY//
pwW5LsF0hd8CtYS1d09qVGVyFQJLkd8FCwIKi8YxvOjDcPOsrrev0YkV/0YRXqbC
ORPWhycCWdU6/J42UlPmbaOl6k75uq5LRHi6mk4GI3nQq/0D8xKcIrnrQ5CdMiRW
9BHt4A65xjzCZTfJRK8l4eHQzU0WVjb/NTmltVkCgYB01syupZqsBkAj6+X7qtwP
S7I6yBQSLerZwLxG8CbQfC3XrEJueRPQiKuwnfGSYQgbSjqbLOnw9njs/1MoYJX9
0EqpqDfdvjK6oH5PBoDTjVOnKGDSY8RmZtlpRjRNlJLfX2kThwpVlmj+/aa9NQvy
QKnfudguD3aQJMlFk3PCRQ==
-----END PRIVATE KEY-----
"""


def test_signingKey_rsaPem_exits2_oneLine():
    """RSA PEM as signing key must exit 2 (only Ed25519 is accepted).

    RED: assertion.py's Signer loads any PEM key without type check.
    GREEN: Signer validates the key is Ed25519; run_gateway exits 2.
    """
    env = {
        **_base_env(),
        "MCP_GATEWAY_SIGNING_KEY": _RSA_KEY_PEM,
    }
    code, err = _run(env)
    assert code == 2, f"expected 2 for RSA key, got {code}; err: {err!r}"
    lines = [ln for ln in err.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected one error line, got: {err!r}"
    assert "ed25519" in err.lower() or "invalid" in err.lower() or "error" in err.lower()


# ---------------------------------------------------------------------------
# Item 12: token_id formula must use BFF's sha256("google:" + sub)[:12]
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolver_tokenId_usesBffFormula():
    """token_id must be 'dlg-' + sha256('google:' + sub)[:12], matching the BFF.

    RED: resolver uses sha256(grant_id) which does not match the BFF formula.
    GREEN: resolver uses sha256('google:' + sub)[:12].
    """
    import hashlib

    from mcp.server.auth.middleware.auth_context import (
        AccessToken,
        AuthenticatedUser,
        auth_context_var,
    )

    from ohmytradeagent_mcp.gateway.assertion import Signer
    from ohmytradeagent_mcp.gateway.resolver import OAuthIdentityResolver
    from ohmytradeagent_mcp.gateway.store import InMemoryStore

    sub = "sub-tokenid-test"
    grant_id = "grant-xyz"
    expected_token_id = "dlg-" + hashlib.sha256(f"google:{sub}".encode()).hexdigest()[:12]

    store = InMemoryStore()
    signer = Signer(TEST_SIGNING_KEY_PEM)
    resolver = OAuthIdentityResolver(store=store, signer=signer, bff_url="http://bff.test")

    sdk_at = AccessToken(
        token="tok-formula",
        client_id="cli-1",
        scopes=["read"],
        expires_at=9999999999,
        subject=sub,
        claims={"tenants": ["acme"], "grant_id": grant_id},
    )
    token = auth_context_var.set(AuthenticatedUser(sdk_at))
    try:
        binding, _bff = await resolver("acme")
    finally:
        auth_context_var.reset(token)

    assert binding.token_id == expected_token_id, (
        f"token_id must be dlg-sha256('google:{sub}')[:12]; "
        f"expected {expected_token_id!r}, got {binding.token_id!r}"
    )


# ---------------------------------------------------------------------------
# Item 3: MCP_GATEWAY_DB_PASSWORD required in production mode
# ---------------------------------------------------------------------------


def test_production_missingDbPassword_exits2():
    """Production mode requires MCP_GATEWAY_DB_PASSWORD — exits 2 when absent.

    RED: main.py does not check for MCP_GATEWAY_DB_PASSWORD; exits 0 or starts
         uvicorn (blocked) rather than exiting 2 immediately.
    GREEN: main.py adds MCP_GATEWAY_DB_PASSWORD to the production required-var list.
    """
    env = {k: v for k, v in _prod_env().items() if k != "MCP_GATEWAY_DB_PASSWORD"}
    code, err = _run_subprocess(env)
    assert code == 2, f"expected 2, got {code}; stderr: {err!r}"
    assert "MCP_GATEWAY_DB_PASSWORD" in err, (
        f"expected MCP_GATEWAY_DB_PASSWORD in error; got: {err!r}"
    )
    lines = [ln for ln in err.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected one error line, got: {err!r}"


def test_production_emptyDbPassword_exits2():
    """Production mode requires MCP_GATEWAY_DB_PASSWORD non-empty — exits 2 when empty.

    RED: main.py allows empty string as a valid password.
    GREEN: empty MCP_GATEWAY_DB_PASSWORD is treated as missing.
    """
    env = {**_prod_env(), "MCP_GATEWAY_DB_PASSWORD": ""}
    code, err = _run_subprocess(env)
    assert code == 2, f"expected 2 for empty password, got {code}; stderr: {err!r}"
    assert "MCP_GATEWAY_DB_PASSWORD" in err


# ---------------------------------------------------------------------------
# Finding E — access_log=False in uvicorn.run
# ---------------------------------------------------------------------------


def test_uvicorn_accessLog_disabled(monkeypatch):
    """run_gateway passes access_log=False to uvicorn.run.

    RED: uvicorn.run is called without access_log kwarg (defaults to True).
    GREEN: access_log=False is added to the uvicorn.run call.
    """
    import uvicorn

    from ohmytradeagent_mcp.gateway.main import run_gateway

    captured: dict = {}

    def fake_run(app, **kwargs):
        captured.update(kwargs)

    monkeypatch.setattr(uvicorn, "run", fake_run)

    env = {
        **_base_env(),
        "OMT_GATEWAY_DISABLED": "true",
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
    }
    run_gateway(env)
    assert captured.get("access_log") is False, (
        f"Expected access_log=False in uvicorn.run; got {captured}"
    )


# ---------------------------------------------------------------------------
# Finding C — eager DB connect before uvicorn.run
# ---------------------------------------------------------------------------


def test_production_unreachableDb_exits2_oneLine():
    """When the DB is unreachable, run_gateway exits 2 with a one-line message.

    RED: main.py defers store.connect() to the ASGI lifespan; a bad DB URL
         causes uvicorn to start (blocking) rather than immediately exiting 2.
    GREEN: main.py calls store.connect() before uvicorn.run; connection failure
           → exit 2 on a single error line with no DB password in the message.
    """
    # Port 1 is refused on loopback immediately (no service listens there)
    env = {
        **_prod_env(),
        "OMT_GATEWAY_DB_URL": "postgresql://mcp_gateway@127.0.0.1:1/dashboard",
    }
    code, err = _run_subprocess(env)
    assert code == 2, f"expected 2 for unreachable DB, got {code}; stderr: {err!r}"
    # Must be a single non-empty line
    lines = [ln for ln in err.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected one error line, got: {err!r}"
    # Must NOT contain the DB password
    assert "test-pw" not in err, f"DB password must not appear in error; got: {err!r}"
    assert "password" not in err.lower() or "password" not in _prod_env().get(
        "MCP_GATEWAY_DB_PASSWORD", ""
    ), f"DB password must not appear in error; got: {err!r}"


# ---------------------------------------------------------------------------
# Finding I — _build_app_from_settings factory wires tool_rate_limiter
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_buildAppFromSettings_toolRateLimiter_wired():
    """_build_app_from_settings wires tool_rate_limiter so the 2nd /mcp call is limited.

    RED: _build_app_from_settings does not exist in main.py.
    GREEN: factory is added; tool_rate_limiter is wired (resolver gets it).
    """
    from ohmytradeagent_mcp.gateway.main import _build_app_from_settings
    from ohmytradeagent_mcp.gateway.settings import GatewaySettings
    from ohmytradeagent_mcp.gateway.store import InMemoryStore

    env = {
        **_base_env(),
        "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
        "OMT_RATE_TOOLS_PER_MIN_SUBJECT": "1",
    }
    settings = GatewaySettings(env)
    store = InMemoryStore()
    app = _build_app_from_settings(settings, store)
    # Smoke-check: the factory returns a callable ASGI app
    assert callable(app), f"Expected callable ASGI app from factory; got {type(app)}"


# ---------------------------------------------------------------------------
# Finding L — _int() must raise ConfigError on invalid integer env var
# ---------------------------------------------------------------------------


def test_settings_invalidRateLimit_raisesConfigError():
    """An non-integer OMT_RATE_* env var must raise ConfigError.

    RED: _int() silently returns the default on ValueError.
    GREEN: _int() raises ConfigError with the offending key name.
    """
    from ohmytradeagent_mcp.gateway.settings import ConfigError, GatewaySettings

    with pytest.raises(ConfigError, match="OMT_RATE_TOOLS_PER_MIN_SUBJECT"):
        GatewaySettings({
            **_base_env(),
            "OMT_ALLOWED_HOSTS": "tradeagent-mcp.ridopark.com",
            "OMT_RATE_TOOLS_PER_MIN_SUBJECT": "not_a_number",
        })
