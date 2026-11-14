"""Client ID Metadata Document (CIMD) fetcher with SSRF guard.

A ``client_id`` that is an ``https://`` URL with a non-root path is treated as
a CIMD URL.  ``fetch_cimd_document(url)`` fetches it with:

- 10-second timeout
- 64 KiB body cap (checked via Content-Length header first, then while streaming)
- No redirects: any 3xx response is a hard error
- DNS resolved once; connection pinned to the vetted IP; original hostname used
  as TLS SNI and HTTP Host header to preserve certificate verification
- HTTPS only
- Content-Type must be JSON
- ``document["client_id"]`` must equal the URL
- SSRF guard: reject private/loopback/link-local/ULA/IPv4-mapped resolved IPs
- DNS resolution runs off the event loop via asyncio.to_thread

Results are cached in process for 1 hour.

The public entry point for the provider is ``make_cimd_fetcher()`` which wraps
the real fetcher with the cache; for tests, inject a custom ``fetch_cimd_fn``
that returns a dict or raises ``RegistrationError``.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import socket
import time
from urllib.parse import urlparse

from mcp.server.auth.provider import RegistrationError

_SIZE_CAP = 64 * 1024  # 64 KiB
_TIMEOUT = 10.0  # seconds

# Private / link-local / loopback ranges to block for SSRF protection
_SSRF_FORBIDDEN_V4 = [
    ipaddress.IPv4Network("10.0.0.0/8"),
    ipaddress.IPv4Network("172.16.0.0/12"),
    ipaddress.IPv4Network("192.168.0.0/16"),
    ipaddress.IPv4Network("127.0.0.0/8"),
    ipaddress.IPv4Network("169.254.0.0/16"),
    ipaddress.IPv4Network("0.0.0.0/8"),
    ipaddress.IPv4Network("100.64.0.0/10"),  # shared address space
]
_SSRF_FORBIDDEN_V6 = [
    ipaddress.IPv6Network("::1/128"),
    ipaddress.IPv6Network("fe80::/10"),
    ipaddress.IPv6Network("fc00::/7"),
    ipaddress.IPv6Network("::/128"),
    ipaddress.IPv6Network("::ffff:0:0/96"),  # IPv4-mapped: ::ffff:10.0.0.1 → 10.0.0.1
]


def _is_ssrf_forbidden(ip_str: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip_str)
    except ValueError:
        return True  # unparseable → reject
    if isinstance(addr, ipaddress.IPv4Address):
        return any(addr in net for net in _SSRF_FORBIDDEN_V4)
    return any(addr in net for net in _SSRF_FORBIDDEN_V6)


def is_cimd_url(client_id: str) -> bool:
    """Return True if *client_id* looks like a CIMD URL (https:// with a non-root path)."""
    if not client_id.startswith("https://"):
        return False
    parsed = urlparse(client_id)
    path = parsed.path or "/"
    return path not in ("", "/")


async def fetch_cimd_document(url: str, *, _transport=None) -> dict:
    """Fetch and validate a CIMD document.

    Security model:
    - DNS resolved once via asyncio.to_thread; all addresses checked for SSRF.
    - Connection pinned to vetted IP; TLS SNI and Host header retain hostname.
    - No redirects followed: any 3xx is an immediate RegistrationError.
    - Body cap enforced via Content-Length header first, then while streaming.

    Args:
        url: The HTTPS URL to fetch.
        _transport: Optional httpx transport override for tests.

    Returns:
        The parsed JSON document.

    Raises:
        RegistrationError: On any validation failure.
    """
    import httpx

    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description="CIMD URL must use HTTPS",
        )

    host = parsed.hostname or ""
    if not host:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description="CIMD URL has no host",
        )

    # Resolve DNS once, off the event loop, to avoid blocking.
    try:
        addrs = await asyncio.to_thread(socket.getaddrinfo, host, None)
    except socket.gaierror as exc:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD host {host!r} could not be resolved: {exc}",
        ) from exc

    # Check ALL resolved addresses; pin connection to the first vetted IP.
    vetted_ip: str | None = None
    for _family, _type, _proto, _canonname, sockaddr in addrs:
        ip = sockaddr[0]
        if _is_ssrf_forbidden(ip):
            raise RegistrationError(
                error="invalid_client_metadata",
                error_description=f"CIMD host {host!r} resolved to a forbidden address ({ip})",
            )
        if vetted_ip is None:
            vetted_ip = ip

    if vetted_ip is None:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD host {host!r} resolved to no addresses",
        )

    # Build the connection URL using the vetted IP, preserving path and query.
    # IPv6 addresses must be wrapped in brackets per RFC 2732.
    port = parsed.port or 443
    path = parsed.path or "/"
    query_part = f"?{parsed.query}" if parsed.query else ""
    ip_bracket = f"[{vetted_ip}]" if ":" in vetted_ip else vetted_ip
    ip_url = f"https://{ip_bracket}:{port}{path}{query_part}"

    # Set the Host header to the original hostname (not the IP).
    host_header = host if port == 443 else f"{host}:{port}"

    # RegistrationError is a frozen dataclass; raising it inside an
    # asynccontextmanager block causes contextlib to try to assign __traceback__
    # on the frozen object, which raises FrozenInstanceError.  Avoid that by
    # accumulating errors in a local variable and raising after the context exits.
    _pending_error: RegistrationError | None = None
    raw: bytes = b""
    resp = None

    try:
        async with httpx.AsyncClient(
            transport=_transport,
            follow_redirects=False,
        ) as client, client.stream(
            "GET",
            ip_url,
            timeout=_TIMEOUT,
            headers={"Accept": "application/json", "Host": host_header},
            # sni_hostname tells httpcore to verify TLS against the original
            # hostname, not the IP — preserving certificate verification.
            extensions={"sni_hostname": host.encode()},
        ) as resp:
            # Never follow redirects: CIMD documents are fetched directly.
            if 300 <= resp.status_code < 400:
                location = resp.headers.get("location", "?")
                _pending_error = RegistrationError(
                    error="invalid_client_metadata",
                    error_description=(
                        f"CIMD redirect to {location!r} is not allowed"
                    ),
                )
            elif resp.status_code != 200:
                _pending_error = RegistrationError(
                    error="invalid_client_metadata",
                    error_description=f"CIMD fetch returned HTTP {resp.status_code}",
                )
            else:
                # Content-Length pre-flight: reject oversized documents before
                # consuming the body.
                cl_header = resp.headers.get("content-length")
                if cl_header is not None:
                    try:
                        cl = int(cl_header)
                    except ValueError:
                        pass  # ignore malformed header; streaming cap catches it
                    else:
                        if cl > _SIZE_CAP:
                            _pending_error = RegistrationError(
                                error="invalid_client_metadata",
                                error_description=(
                                    f"CIMD document Content-Length {cl} "
                                    f"exceeds cap ({_SIZE_CAP} bytes)"
                                ),
                            )

                if _pending_error is None:
                    # Stream body with running cap; break early on overflow.
                    chunks: list[bytes] = []
                    total = 0
                    async for chunk in resp.aiter_bytes():
                        total += len(chunk)
                        if total > _SIZE_CAP:
                            _pending_error = RegistrationError(
                                error="invalid_client_metadata",
                                error_description=(
                                    f"CIMD document exceeds size cap "
                                    f"({_SIZE_CAP} bytes)"
                                ),
                            )
                            break
                        chunks.append(chunk)
                    if _pending_error is None:
                        raw = b"".join(chunks)
                # Context exits cleanly regardless; response.aclose() runs in finally.

    except httpx.HTTPError as exc:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD fetch failed: {exc}",
        ) from exc

    if _pending_error is not None:
        raise _pending_error

    # Content-Type must be JSON (checked after stream completes; headers are eager).
    ct = resp.headers.get("content-type", "")
    if "json" not in ct.lower():
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD document content-type is not JSON: {ct!r}",
        )

    try:
        doc = json.loads(raw)
    except Exception as exc:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD document is not valid JSON: {exc}",
        ) from exc

    if not isinstance(doc, dict):
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description="CIMD document must be a JSON object",
        )

    # client_id in the document must equal the URL
    if doc.get("client_id") != url:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=(
                f"CIMD document client_id {doc.get('client_id')!r} "
                f"does not match the URL {url!r}"
            ),
        )

    return doc


# ------------------------------------------------------------------
# In-process cache (1 hour TTL)
# ------------------------------------------------------------------

_CACHE_TTL = 3600  # seconds


class _CimdCache:
    def __init__(self, ttl: float = _CACHE_TTL) -> None:
        self._ttl = ttl
        self._store: dict[str, tuple[dict, float]] = {}

    def get(self, url: str) -> dict | None:
        entry = self._store.get(url)
        if entry is None:
            return None
        doc, cached_at = entry
        if time.monotonic() - cached_at >= self._ttl:
            del self._store[url]
            return None
        return doc

    def put(self, url: str, doc: dict) -> None:
        self._store[url] = (doc, time.monotonic())


_default_cache = _CimdCache()


def make_cimd_fetcher(
    *,
    cache: _CimdCache | None = None,
    _transport=None,
):
    """Return an async function that fetches+caches CIMD documents.

    Args:
        cache: Cache instance (defaults to the module-level singleton).
        _transport: httpx transport override for tests.

    Returns:
        Async callable ``(url: str) -> dict``.
    """
    _cache = cache if cache is not None else _default_cache

    async def _fetch(url: str) -> dict:
        cached = _cache.get(url)
        if cached is not None:
            return cached
        doc = await fetch_cimd_document(url, _transport=_transport)
        _cache.put(url, doc)
        return doc

    return _fetch
