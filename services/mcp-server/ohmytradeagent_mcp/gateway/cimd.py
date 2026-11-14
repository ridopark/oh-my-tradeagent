"""Client ID Metadata Document (CIMD) fetcher with SSRF guard.

A ``client_id`` that is an ``https://`` URL with a non-root path is treated as
a CIMD URL.  ``fetch_cimd_document(url)`` fetches it with:

- 10-second timeout
- 64 KiB body cap
- HTTPS only (no non-HTTPS redirect targets)
- Content-Type must be JSON
- ``document["client_id"]`` must equal the URL
- SSRF guard: reject private/loopback/link-local resolved IPs

Results are cached in process for 1 hour.

The public entry point for the provider is ``make_cimd_fetcher()`` which wraps
the real fetcher with the cache; for tests, inject a custom ``fetch_cimd_fn``
that returns a dict or raises ``RegistrationError``.
"""
from __future__ import annotations

import ipaddress
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
]


def _is_ssrf_forbidden(ip_str: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip_str)
    except ValueError:
        return True  # unparseable → reject
    if isinstance(addr, ipaddress.IPv4Address):
        return any(addr in net for net in _SSRF_FORBIDDEN_V4)
    return any(addr in net for net in _SSRF_FORBIDDEN_V6)


def _ssrf_guard(host: str) -> None:
    """Resolve *host* and raise RegistrationError if any resolved IP is forbidden."""
    try:
        addrs = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD host {host!r} could not be resolved: {exc}",
        ) from exc

    for _family, _type, _proto, _canonname, sockaddr in addrs:
        ip = sockaddr[0]
        if _is_ssrf_forbidden(ip):
            raise RegistrationError(
                error="invalid_client_metadata",
                error_description=(
                    f"CIMD host {host!r} resolved to a forbidden address ({ip})"
                ),
            )


def is_cimd_url(client_id: str) -> bool:
    """Return True if *client_id* looks like a CIMD URL (https:// with a non-root path)."""
    if not client_id.startswith("https://"):
        return False
    parsed = urlparse(client_id)
    path = parsed.path or "/"
    return path not in ("", "/")


async def fetch_cimd_document(url: str, *, _transport=None) -> dict:
    """Fetch and validate a CIMD document.

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

    # SSRF guard: resolve IPs and block private ranges
    _ssrf_guard(host)

    async def _no_non_https_redirect(request, response):
        if response.is_redirect:
            location = response.headers.get("location", "")
            if not location.startswith("https://"):
                raise RegistrationError(
                    error="invalid_client_metadata",
                    error_description=(
                        f"CIMD redirect to non-HTTPS URL is forbidden: {location!r}"
                    ),
                )

    try:
        async with httpx.AsyncClient(
            transport=_transport,
            follow_redirects=True,
            event_hooks={"response": [_no_non_https_redirect]},
        ) as client:
            resp = await client.get(
                url,
                timeout=_TIMEOUT,
                headers={"Accept": "application/json"},
            )
    except httpx.HTTPError as exc:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD fetch failed: {exc}",
        ) from exc

    if resp.status_code != 200:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD fetch returned HTTP {resp.status_code}",
        )

    # Size cap
    raw = resp.content
    if len(raw) > _SIZE_CAP:
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD document exceeds size cap ({_SIZE_CAP} bytes)",
        )

    # Content-Type must be JSON
    ct = resp.headers.get("content-type", "")
    if "json" not in ct.lower():
        raise RegistrationError(
            error="invalid_client_metadata",
            error_description=f"CIMD document content-type is not JSON: {ct!r}",
        )

    try:
        doc = resp.json()
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
