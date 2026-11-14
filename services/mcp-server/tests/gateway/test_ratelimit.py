"""Rate limit tests — Step 3c.

Tests:
- toolRate_perSubject_61stCallInMinute_isRateLimited_withRetryAfterText
- toolRate_perClient_isIndependentOfSubject
- authRate_perIp_31stRegister_is429_withRetryAfter
- authRate_usesCfConnectingIp_whenPresent

Fake clock is injected so no sleeps are needed.
"""
from __future__ import annotations

import httpx
import pytest

from ohmytradeagent_mcp.gateway.ratelimit import AuthRateLimiter, FakeClock, ToolRateLimiter
from tests.gateway.fixtures import PUBLIC_URL, make_app

# ---------------------------------------------------------------------------
# Fake clock
# ---------------------------------------------------------------------------


def _make_clock(start: float = 0.0) -> FakeClock:
    return FakeClock(start)


# ---------------------------------------------------------------------------
# Tool rate limiter — per subject
# ---------------------------------------------------------------------------


def test_toolRate_perSubject_61stCallInMinute_isRateLimited_withRetryAfterText():
    """Subject bucket: 60 calls/min; the 61st raises ToolError with retry time."""
    from mcp.server.mcpserver.exceptions import ToolError

    clock = _make_clock(0.0)
    limiter = ToolRateLimiter(per_subject_rpm=60, per_client_rpm=300, clock=clock)

    # 60 calls should succeed
    for _ in range(60):
        limiter.check("sub-x", "cli-1")  # no exception

    # 61st call must raise ToolError
    with pytest.raises(ToolError) as exc_info:
        limiter.check("sub-x", "cli-1")

    msg = str(exc_info.value)
    assert "rate_limited" in msg
    assert "retry after" in msg
    # Must contain a positive integer number of seconds
    import re
    match = re.search(r"retry after (\d+) s", msg)
    assert match, f"Expected 'retry after N s' in: {msg!r}"
    assert int(match.group(1)) >= 1


def test_toolRate_perSubject_differentSubject_isIndependent():
    """Different subjects have independent buckets."""
    from mcp.server.mcpserver.exceptions import ToolError

    clock = _make_clock(0.0)
    limiter = ToolRateLimiter(per_subject_rpm=1, per_client_rpm=300, clock=clock)

    # Exhaust sub-a bucket
    limiter.check("sub-a", "cli-1")
    with pytest.raises(ToolError):
        limiter.check("sub-a", "cli-1")

    # sub-b is NOT affected
    limiter.check("sub-b", "cli-1")  # no exception


# ---------------------------------------------------------------------------
# Tool rate limiter — per client
# ---------------------------------------------------------------------------


def test_toolRate_perClient_isIndependentOfSubject():
    """Client bucket is independent of the subject bucket."""
    from mcp.server.mcpserver.exceptions import ToolError

    clock = _make_clock(0.0)
    # large subject budget, small client budget
    limiter = ToolRateLimiter(per_subject_rpm=300, per_client_rpm=2, clock=clock)

    # Two calls succeed for the subject
    limiter.check("sub-y", "cli-2")
    limiter.check("sub-y", "cli-2")

    # 3rd call is throttled on client bucket
    with pytest.raises(ToolError) as exc_info:
        limiter.check("sub-y", "cli-2")

    assert "rate_limited" in str(exc_info.value)


def test_toolRate_refillAfterAdvance():
    """Bucket refills as the clock advances."""
    from mcp.server.mcpserver.exceptions import ToolError

    clock = _make_clock(0.0)
    limiter = ToolRateLimiter(per_subject_rpm=1, per_client_rpm=300, clock=clock)

    # Use the one token
    limiter.check("sub-ref", "cli-1")

    # Rate limited now
    with pytest.raises(ToolError):
        limiter.check("sub-ref", "cli-1")

    # Advance clock by 61 seconds (> 60 s = 1 min) — should refill
    clock.advance(61.0)
    limiter.check("sub-ref", "cli-1")  # no exception after refill


# ---------------------------------------------------------------------------
# Auth rate limiter — per IP, HTTP 429
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_authRate_perIp_31stRegister_is429_withRetryAfter():
    """The 31st /register call from the same IP within a minute returns 429."""
    clock = _make_clock(0.0)
    auth_limiter = AuthRateLimiter(per_ip_rpm=30, clock=clock)
    app = make_app(auth_rate_limiter=auth_limiter)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # 30 calls should succeed (or fail for other reasons — not 429)
        for i in range(30):
            resp = await client.post(
                "/register",
                json={
                    "client_name": f"App {i}",
                    "redirect_uris": ["https://example.com/cb"],
                    "grant_types": ["authorization_code"],
                    "response_types": ["code"],
                },
                headers={"X-Real-IP": "1.2.3.4"},  # consistent IP
            )
            assert resp.status_code != 429, f"Unexpected 429 on call {i + 1}"

        # 31st should be 429
        resp = await client.post(
            "/register",
            json={
                "client_name": "App 31",
                "redirect_uris": ["https://example.com/cb"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
            },
            headers={"X-Real-IP": "1.2.3.4"},
        )
        assert resp.status_code == 429, f"Expected 429, got {resp.status_code}"
        assert "Retry-After" in resp.headers


@pytest.mark.asyncio
async def test_authRate_untrustedPeer_cfHeaderIgnored():
    """Security: without trusted-proxy config, CF-Connecting-IP is IGNORED.

    Two requests from the same socket peer "127.0.0.1" but with different
    CF-Connecting-IP values must share the peer's bucket, not the CF IPs'.

    RED against unfixed code: the second request with a different CF IP is
    not rate-limited (each CF IP has its own bucket in the broken version).
    """
    clock = _make_clock(0.0)
    auth_limiter = AuthRateLimiter(per_ip_rpm=1, clock=clock)
    # No OMT_TRUSTED_PROXY_CIDRS → CF header must be ignored
    app = make_app(auth_rate_limiter=auth_limiter)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # Request 1 — exhausts the peer bucket for "127.0.0.1"
        resp1 = await client.post(
            "/register",
            json={"client_name": "Spoof1", "redirect_uris": ["https://example.com/cb"],
                  "grant_types": ["authorization_code"], "response_types": ["code"]},
            headers={"CF-Connecting-IP": "10.10.10.1"},
        )
        assert resp1.status_code != 429

        # Request 2 — DIFFERENT CF IP, same socket peer "127.0.0.1"
        # CF header must be ignored → same bucket → 429
        resp2 = await client.post(
            "/register",
            json={"client_name": "Spoof2", "redirect_uris": ["https://example.com/cb"],
                  "grant_types": ["authorization_code"], "response_types": ["code"]},
            headers={"CF-Connecting-IP": "10.10.10.2"},
        )
        assert resp2.status_code == 429, (
            "CF-Connecting-IP from untrusted peer must be ignored; "
            "both requests share the peer-IP bucket so the second must be 429"
        )


@pytest.mark.asyncio
async def test_authRate_trustedPeer_cfHeaderUsed():
    """With a trusted-proxy CIDR covering the socket peer, CF-Connecting-IP is used."""
    clock = _make_clock(0.0)
    auth_limiter = AuthRateLimiter(per_ip_rpm=1, clock=clock)
    # Socket peer is "127.0.0.1" (httpx ASGITransport default) → trust it
    app = make_app(
        auth_rate_limiter=auth_limiter,
        extra_env={"OMT_TRUSTED_PROXY_CIDRS": "127.0.0.1/32"},
    )

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # Request 1 from CF IP "1.2.3.4" — trusted peer, CF header is used
        resp1 = await client.post(
            "/register",
            json={"client_name": "CF1", "redirect_uris": ["https://example.com/cb"],
                  "grant_types": ["authorization_code"], "response_types": ["code"]},
            headers={"CF-Connecting-IP": "1.2.3.4"},
        )
        assert resp1.status_code != 429

        # Request 2 from DIFFERENT CF IP "5.5.5.5" — different bucket, NOT rate-limited
        resp2 = await client.post(
            "/register",
            json={"client_name": "CF2", "redirect_uris": ["https://example.com/cb"],
                  "grant_types": ["authorization_code"], "response_types": ["code"]},
            headers={"CF-Connecting-IP": "5.5.5.5"},
        )
        assert resp2.status_code != 429, "Different CF IP from trusted peer gets its own bucket"


@pytest.mark.asyncio
async def test_authRate_ipv6TrustedPeer_cfHeaderUsed():
    """IPv6 socket peer inside a trusted /128 CIDR: CF-Connecting-IP is used."""
    clock = _make_clock(0.0)
    auth_limiter = AuthRateLimiter(per_ip_rpm=1, clock=clock)
    app = make_app(
        auth_rate_limiter=auth_limiter,
        extra_env={"OMT_TRUSTED_PROXY_CIDRS": "::1/128"},
    )

    # Simulate IPv6 socket peer "::1"
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app, client=("::1", 123)),
        base_url=PUBLIC_URL,
    ) as client:
        # Request 1 from CF IPv6 "2001:db8::1" — trusted peer, CF header used
        resp1 = await client.post(
            "/register",
            json={"client_name": "V6App1", "redirect_uris": ["https://example.com/cb"],
                  "grant_types": ["authorization_code"], "response_types": ["code"]},
            headers={"CF-Connecting-IP": "2001:db8::1"},
        )
        assert resp1.status_code != 429

        # Request 2 from DIFFERENT CF IP "2001:db8::2" — different bucket, NOT rate-limited
        resp2 = await client.post(
            "/register",
            json={"client_name": "V6App2", "redirect_uris": ["https://example.com/cb"],
                  "grant_types": ["authorization_code"], "response_types": ["code"]},
            headers={"CF-Connecting-IP": "2001:db8::2"},
        )
        assert resp2.status_code != 429, "Different CF IP from IPv6 trusted peer gets its own bucket"


@pytest.mark.asyncio
async def test_authRate_usesCfConnectingIp_whenPresent():
    """CF-Connecting-IP header is used as the client IP when peer is trusted."""
    clock = _make_clock(0.0)
    auth_limiter = AuthRateLimiter(per_ip_rpm=1, clock=clock)
    # Peer is "127.0.0.1" (httpx default); declare it trusted so CF header is honoured
    app = make_app(
        auth_rate_limiter=auth_limiter,
        extra_env={"OMT_TRUSTED_PROXY_CIDRS": "127.0.0.1/32"},
    )

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # First call from CF IP 10.0.0.1 — should work
        resp = await client.post(
            "/register",
            json={
                "client_name": "App CF1",
                "redirect_uris": ["https://example.com/cb"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
            },
            headers={"CF-Connecting-IP": "10.0.0.1"},
        )
        assert resp.status_code != 429

        # Second call from same CF IP — rate limited
        resp2 = await client.post(
            "/register",
            json={
                "client_name": "App CF2",
                "redirect_uris": ["https://example.com/cb"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
            },
            headers={"CF-Connecting-IP": "10.0.0.1"},
        )
        assert resp2.status_code == 429

        # Call from different CF IP 10.0.0.2 — should NOT be limited
        resp3 = await client.post(
            "/register",
            json={
                "client_name": "App CF3",
                "redirect_uris": ["https://example.com/cb"],
                "grant_types": ["authorization_code"],
                "response_types": ["code"],
            },
            headers={"CF-Connecting-IP": "10.0.0.2"},
        )
        assert resp3.status_code != 429, "Different IP should not be rate-limited"


@pytest.mark.asyncio
async def test_authRate_authorize_is429():
    """/authorize endpoint is also rate-limited."""
    clock = _make_clock(0.0)
    auth_limiter = AuthRateLimiter(per_ip_rpm=1, clock=clock)
    app = make_app(auth_rate_limiter=auth_limiter)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url=PUBLIC_URL,
    ) as client:
        # First call: some status (not 429)
        resp = await client.get(
            "/authorize",
            params={"response_type": "code", "client_id": "x"},
            headers={"CF-Connecting-IP": "1.2.3.5"},
        )
        assert resp.status_code != 429

        # Second call same IP: 429
        resp2 = await client.get(
            "/authorize",
            params={"response_type": "code", "client_id": "x"},
            headers={"CF-Connecting-IP": "1.2.3.5"},
        )
        assert resp2.status_code == 429
