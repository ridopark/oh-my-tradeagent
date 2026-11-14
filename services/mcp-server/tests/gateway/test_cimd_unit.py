"""CIMD security unit tests — testing fetch_cimd_document directly (Seam H unit layer).

Covers the four security fixes for items 1-4 of the fix round:

1. SSRF via redirect (HIGH): 302 responses are always rejected; no second request made.
2. DNS-rebinding TOCTOU (MEDIUM): connection pinned to vetted IP; getaddrinfo called once.
3. Size cap (MEDIUM): Content-Length > cap rejected before body read; streaming aborts early.
4. Missing guards: private/loopback/link-local/IPv6-ULA/IPv4-mapped-IPv6 addresses,
   non-JSON content-type, timeout, and proof that the guard fires on the real fetch path.

All tests that call ``fetch_cimd_document`` mock ``socket.getaddrinfo`` so tests do not
make real network calls and are deterministic across environments.
"""
from __future__ import annotations

import json
import socket
from collections.abc import AsyncIterator

import httpx
import pytest
from mcp.server.auth.provider import RegistrationError

from ohmytradeagent_mcp.gateway.cimd import _SIZE_CAP, _is_ssrf_forbidden, fetch_cimd_document

# A public TEST-NET-3 address (RFC 5737) that is not in any SSRF-forbidden range.
_PUBLIC_IP = "1.1.1.1"
_PRIVATE_IP = "10.0.0.1"
_CIMD_HOST = "example.com"
_CIMD_URL = f"https://{_CIMD_HOST}/client/app"
_VALID_DOC = {
    "client_id": _CIMD_URL,
    "redirect_uris": ["https://example.com/callback"],
}
_VALID_DOC_BYTES = json.dumps(_VALID_DOC).encode()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_public_getaddrinfo(ip: str = _PUBLIC_IP):
    """Return a socket.getaddrinfo stub that always resolves to *ip*."""

    def _stub(host, port, *args, **kwargs):
        return [(socket.AF_INET, socket.SOCK_STREAM, 0, "", (ip, 0))]

    return _stub


class _CountingStream(httpx.AsyncByteStream):
    """AsyncByteStream that records how many bytes were yielded."""

    def __init__(self, body: bytes, counter: list[int], chunk_size: int = 4096) -> None:
        self._body = body
        self._counter = counter
        self._chunk_size = chunk_size

    async def __aiter__(self) -> AsyncIterator[bytes]:
        pos = 0
        while pos < len(self._body):
            chunk = self._body[pos : pos + self._chunk_size]
            self._counter[0] += len(chunk)
            yield chunk
            pos += self._chunk_size

    async def aclose(self) -> None:
        pass


class _CapturingTransport(httpx.AsyncBaseTransport):
    """Transport that records requests and returns a configured response."""

    def __init__(self, response: httpx.Response) -> None:
        self._response = response
        self.requests: list[httpx.Request] = []

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self._response


class _RedirectTransport(httpx.AsyncBaseTransport):
    """Transport that redirects the first request to *redirect_to*, then returns *final*."""

    def __init__(self, redirect_to: str, final: httpx.Response | None = None) -> None:
        self._redirect_to = redirect_to
        self._final = final or httpx.Response(
            200,
            headers={"content-type": "application/json"},
            content=_VALID_DOC_BYTES,
        )
        self.requests: list[str] = []

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(str(request.url))
        if len(self.requests) == 1:
            return httpx.Response(
                302,
                headers={"location": self._redirect_to, "content-type": "text/html"},
                content=b"",
            )
        return self._final


# ---------------------------------------------------------------------------
# Fix 1: SSRF via redirect — 302 responses rejected, no second request made
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_redirect_to_private_ip_rejected(monkeypatch):
    """302 → private-IP URL must be rejected (RegistrationError); second request never made."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    transport = _RedirectTransport(redirect_to=f"https://{_PRIVATE_IP}/client.json")

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=transport)

    assert "redirect" in exc_info.value.error_description.lower()
    # Only 1 request made — the redirect was not followed.
    assert len(transport.requests) == 1, (
        f"Expected 1 request, got {len(transport.requests)}: {transport.requests}"
    )


@pytest.mark.asyncio
async def test_redirect_to_public_https_rejected(monkeypatch):
    """302 → public HTTPS URL must be rejected; second request never made."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    transport = _RedirectTransport(
        redirect_to="https://public.other.example.com/other-client.json"
    )

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=transport)

    assert "redirect" in exc_info.value.error_description.lower()
    assert len(transport.requests) == 1, (
        f"Expected 1 request (redirect not followed), got {len(transport.requests)}"
    )


@pytest.mark.asyncio
async def test_no_second_request_after_any_redirect(monkeypatch):
    """Any 3xx response stops the fetch; the Location target is never requested."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    for status in (301, 302, 303, 307, 308):
        transport = _CapturingTransport(
            httpx.Response(
                status,
                headers={"location": "https://example.com/other", "content-type": "text/html"},
                content=b"",
            )
        )
        with pytest.raises(RegistrationError):
            await fetch_cimd_document(_CIMD_URL, _transport=transport)

        assert len(transport.requests) == 1, (
            f"Status {status}: expected 1 request, got {len(transport.requests)}"
        )


# ---------------------------------------------------------------------------
# Fix 2: DNS-rebinding — connection pinned to vetted IP, getaddrinfo called once
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_dns_rebinding_pins_to_vetted_ip(monkeypatch):
    """After vetted IP chosen, connection goes to that IP URL; getaddrinfo called exactly once.

    Mutation: without pinning, request URL would be the hostname URL (not an IP URL),
    and a second DNS call could return a private IP.
    """
    call_count = 0

    def rebinding_getaddrinfo(host, port, *args, **kwargs):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            # First resolution: safe public IP
            return [(socket.AF_INET, socket.SOCK_STREAM, 0, "", (_PUBLIC_IP, 0))]
        # Second resolution: private IP (simulates DNS rebinding)
        return [(socket.AF_INET, socket.SOCK_STREAM, 0, "", (_PRIVATE_IP, 0))]

    monkeypatch.setattr(socket, "getaddrinfo", rebinding_getaddrinfo)

    capture = _CapturingTransport(
        httpx.Response(
            200,
            headers={"content-type": "application/json"},
            content=_VALID_DOC_BYTES,
        )
    )

    await fetch_cimd_document(_CIMD_URL, _transport=capture)

    # DNS resolved exactly once — no second resolution by httpx.
    assert call_count == 1, f"Expected 1 DNS call, got {call_count}"

    assert len(capture.requests) == 1
    req = capture.requests[0]
    request_host = req.url.host

    # Connection must go to the vetted public IP, not the hostname or private IP.
    assert request_host == _PUBLIC_IP, (
        f"Expected connection to vetted IP {_PUBLIC_IP!r}, got {request_host!r}; "
        "DNS-rebinding fix not applied (connection still uses hostname)"
    )
    assert _PRIVATE_IP not in str(req.url), (
        f"Request must not go to private IP {_PRIVATE_IP!r}"
    )

    # sni_hostname extension must be set so TLS verifies the original hostname cert.
    sni = req.extensions.get("sni_hostname")
    assert sni is not None, (
        "sni_hostname extension missing — TLS hostname verification would not apply "
        "to the original hostname"
    )


@pytest.mark.asyncio
async def test_host_header_set_to_original_hostname(monkeypatch):
    """When connecting to the pinned IP, the Host header is the original hostname."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    capture = _CapturingTransport(
        httpx.Response(
            200,
            headers={"content-type": "application/json"},
            content=_VALID_DOC_BYTES,
        )
    )

    await fetch_cimd_document(_CIMD_URL, _transport=capture)

    assert len(capture.requests) == 1
    host_header = capture.requests[0].headers.get("host", "")
    assert _CIMD_HOST in host_header, (
        f"Host header {host_header!r} should contain {_CIMD_HOST!r}"
    )


# ---------------------------------------------------------------------------
# Fix 3: Size cap — Content-Length check + streaming body abort
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_content_length_over_cap_rejected_before_body_read(monkeypatch):
    """Content-Length > cap: rejected immediately; zero body bytes consumed."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    bytes_yielded = [0]

    class _CLTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            # Content-Length is over the cap; body stream tracks bytes yielded.
            oversized_body = b"x" * (_SIZE_CAP + 1024)
            stream = _CountingStream(oversized_body, bytes_yielded)
            return httpx.Response(
                200,
                headers={
                    "content-type": "application/json",
                    "content-length": str(len(oversized_body)),
                },
                stream=stream,
            )

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=_CLTransport())

    assert "cap" in exc_info.value.error_description.lower() or "length" in exc_info.value.error_description.lower()

    # Body must NOT have been consumed — Content-Length header was enough to reject.
    assert bytes_yielded[0] == 0, (
        f"Expected 0 body bytes consumed (Content-Length rejection), "
        f"but {bytes_yielded[0]} bytes were read. "
        "Fix: check Content-Length header before streaming the body."
    )


@pytest.mark.asyncio
async def test_body_streaming_aborts_at_cap(monkeypatch):
    """Body > cap without Content-Length: streaming aborts after at most cap + one-chunk bytes."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    bytes_yielded = [0]
    _CHUNK_SIZE = 4096
    oversized_body = b"x" * (_SIZE_CAP + 10 * _CHUNK_SIZE)

    class _StreamTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            stream = _CountingStream(oversized_body, bytes_yielded, chunk_size=_CHUNK_SIZE)
            return httpx.Response(
                200,
                headers={"content-type": "application/json"},  # no Content-Length
                stream=stream,
            )

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=_StreamTransport())

    assert "cap" in exc_info.value.error_description.lower()

    # Streaming must abort: bytes consumed ≤ cap + 1 chunk, not the full body.
    max_allowed = _SIZE_CAP + _CHUNK_SIZE
    assert bytes_yielded[0] <= max_allowed, (
        f"Expected ≤ {max_allowed} bytes consumed (early abort), "
        f"but {bytes_yielded[0]} bytes were read (full body). "
        "Fix: stream the body and abort as soon as cap is exceeded."
    )
    assert bytes_yielded[0] < len(oversized_body), (
        "Full body was consumed; streaming cap not enforced."
    )


# ---------------------------------------------------------------------------
# Fix 4a: IPv4-mapped IPv6 — ::ffff:10.0.0.1 is forbidden
# ---------------------------------------------------------------------------


def test_ipv4_mapped_ipv6_is_forbidden():
    """::ffff:10.0.0.1 (IPv4-mapped private address) must be in the SSRF-forbidden set.

    This requires adding ::ffff:0:0/96 to _SSRF_FORBIDDEN_V6.
    """
    assert _is_ssrf_forbidden("::ffff:10.0.0.1"), (
        "::ffff:10.0.0.1 should be forbidden (IPv4-mapped private address), "
        "but _is_ssrf_forbidden returned False. "
        "Fix: add ::ffff:0:0/96 to _SSRF_FORBIDDEN_V6."
    )


def test_ipv4_mapped_ipv6_192_168_is_forbidden():
    """::ffff:192.168.1.1 (IPv4-mapped private address) must be forbidden."""
    assert _is_ssrf_forbidden("::ffff:192.168.1.1"), (
        "::ffff:192.168.1.1 should be forbidden (IPv4-mapped 192.168 address)"
    )


def test_ipv4_mapped_ipv6_loopback_is_forbidden():
    """::ffff:127.0.0.1 (IPv4-mapped loopback) must be forbidden."""
    assert _is_ssrf_forbidden("::ffff:127.0.0.1"), (
        "::ffff:127.0.0.1 should be forbidden (IPv4-mapped loopback)"
    )


# ---------------------------------------------------------------------------
# Fix 4b: Existing guards — adding tests for missing coverage (GREEN before fix)
# ---------------------------------------------------------------------------


def test_private_ipv4_is_forbidden():
    """Private IPv4 ranges (10.x, 172.16-31.x, 192.168.x) are forbidden."""
    for ip in ("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1"):
        assert _is_ssrf_forbidden(ip), f"{ip} should be forbidden"


def test_loopback_is_forbidden():
    """127.x.x.x loopback addresses are forbidden."""
    for ip in ("127.0.0.1", "127.0.0.2", "127.255.255.255"):
        assert _is_ssrf_forbidden(ip), f"{ip} should be forbidden"


def test_link_local_is_forbidden():
    """169.254.x.x link-local addresses are forbidden."""
    for ip in ("169.254.0.1", "169.254.169.254", "169.254.255.255"):
        assert _is_ssrf_forbidden(ip), f"{ip} should be forbidden"


def test_ipv6_loopback_is_forbidden():
    """IPv6 loopback (::1) is forbidden."""
    assert _is_ssrf_forbidden("::1")


def test_ipv6_ula_is_forbidden():
    """IPv6 ULA (fc00::/7) addresses are forbidden."""
    for ip in ("fc00::1", "fd00::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"):
        assert _is_ssrf_forbidden(ip), f"{ip} should be forbidden (ULA)"


def test_public_ip_is_not_forbidden():
    """Globally-routable IP addresses must NOT be in the forbidden set."""
    for ip in ("1.1.1.1", "8.8.8.8", "2001:4860:4860::8888"):
        assert not _is_ssrf_forbidden(ip), f"{ip} should NOT be forbidden"


@pytest.mark.asyncio
async def test_non_json_content_type_rejected(monkeypatch):
    """Non-JSON content-type (text/html) is rejected with RegistrationError."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    transport = _CapturingTransport(
        httpx.Response(
            200,
            headers={"content-type": "text/html; charset=utf-8"},
            content=b"<html>not json</html>",
        )
    )

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=transport)

    assert "json" in exc_info.value.error_description.lower(), (
        f"Error should mention JSON content-type: {exc_info.value.error_description!r}"
    )


@pytest.mark.asyncio
async def test_timeout_raises_registration_error(monkeypatch):
    """Timeout during CIMD fetch raises RegistrationError, not an httpx exception."""
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo())

    class _TimeoutTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            raise httpx.TimeoutException("timed out", request=request)

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=_TimeoutTransport())

    assert "fetch failed" in exc_info.value.error_description.lower() or "timeout" in exc_info.value.error_description.lower()


@pytest.mark.asyncio
async def test_ssrf_guard_invoked_via_fetch_cimd_document(monkeypatch):
    """The SSRF guard fires on the real fetch path: private-IP hostname is rejected."""
    # Simulate DNS returning a private IP for the target host.
    monkeypatch.setattr(socket, "getaddrinfo", _make_public_getaddrinfo(_PRIVATE_IP))

    with pytest.raises(RegistrationError) as exc_info:
        await fetch_cimd_document(_CIMD_URL, _transport=httpx.MockTransport(lambda r: httpx.Response(200)))

    err = exc_info.value.error_description.lower()
    assert "forbidden" in err, (
        f"Expected 'forbidden' in error description; got: {exc_info.value.error_description!r}. "
        "SSRF guard may not be invoked on the real fetch path."
    )


# ---------------------------------------------------------------------------
# Item 4 (fix round): parametrized SSRF predicate tests
#
# RED: hand-written lists miss multicast (224.0.0.1), reserved class-E
#      (240.0.0.1), 192.0.0.0/24, 198.18.0.0/15, NAT64 and 6to4 addresses.
# GREEN: replace lists with ipaddress predicates + NAT64/6to4 unwrapping.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("ip,expected_forbidden", [
    # IPv4 ranges the hand-written list may miss
    ("224.0.0.1",             True),   # multicast (is_multicast)
    ("240.0.0.1",             True),   # class-E / reserved (is_reserved)
    ("192.0.0.1",             True),   # IETF protocol (is_private/not global)
    ("198.18.0.1",            True),   # benchmarking (is_private/not global)
    ("100.64.0.1",            True),   # shared address space (not is_global)
    # IPv6 special ranges
    ("fd00::1",               True),   # ULA (is_private)
    ("::ffff:127.0.0.1",      True),   # IPv4-mapped loopback (after unwrap)
    # NAT64 and 6to4 — extract embedded IPv4 and apply the same rule
    ("64:ff9b::a00:1",        True),   # NAT64 → 10.0.0.1 (private)
    ("2002:a00:1::",          True),   # 6to4 → 10.0.0.1 (private)
    # Public addresses — must NOT be forbidden
    ("8.8.8.8",               False),  # Google DNS
    ("2001:4860:4860::8888",  False),  # Google DNS IPv6
])
def test_ssrf_predicate_coverage(ip, expected_forbidden):
    """Parametrized SSRF predicate coverage (fix round item 4).

    Each address is verified against expected_forbidden:
    True  → _is_ssrf_forbidden must return True (rejected)
    False → _is_ssrf_forbidden must return False (allowed)
    """
    result = _is_ssrf_forbidden(ip)
    if expected_forbidden:
        assert result, (
            f"{ip!r} should be SSRF-forbidden (expected_forbidden=True) "
            f"but _is_ssrf_forbidden returned False. "
            f"Fix: use ipaddress predicates (is_private, is_loopback, is_link_local, "
            f"is_multicast, is_reserved, is_unspecified, not is_global) "
            f"and unwrap NAT64/6to4 embedded IPv4 before checking."
        )
    else:
        assert not result, (
            f"{ip!r} should NOT be SSRF-forbidden (expected_forbidden=False) "
            f"but _is_ssrf_forbidden returned True."
        )
