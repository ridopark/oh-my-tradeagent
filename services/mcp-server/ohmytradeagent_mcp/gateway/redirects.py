"""Redirect URI policy for DCR and CIMD.

Policy (OAuth 2.1 §7.5.1 + RFC 8252 §8.3):
- https:// with a hostname (not an IP literal) — no wildcards
- http://127.0.0.1[:port]/…
- http://localhost[:port]/…
- http://[::1][:port]/…
No fragments (#…), no userinfo (user@host), no wildcard hosts.
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlparse

from mcp.server.auth.provider import RegistrationError

_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})


def loopback_port_agnostic_match(registered_uri: str, request_uri: str) -> bool:
    """Return True if both URIs are loopback and match ignoring port (RFC 8252 §7.3).

    Native apps bind an ephemeral loopback port at runtime; the port is unknown at
    registration time, so the AS MUST compare loopback redirect URIs without the port.
    Both URIs must have scheme http, the same loopback host, the same path, and the
    same query string.
    """
    r = urlparse(registered_uri)
    q = urlparse(request_uri)
    if r.scheme != "http" or q.scheme != "http":
        return False
    r_host = r.hostname or ""
    q_host = q.hostname or ""
    if r_host not in _LOOPBACK_HOSTS or q_host not in _LOOPBACK_HOSTS:
        return False
    if r_host != q_host:
        return False
    return r.path == q.path and r.query == q.query


def _is_ip_address(host: str) -> bool:
    """Return True if *host* is an IPv4 or IPv6 address literal."""
    # urlparse strips brackets from IPv6; try both forms
    for candidate in (host, host.strip("[]")):
        try:
            ipaddress.ip_address(candidate)
            return True
        except ValueError:
            pass
    return False


def validate_redirect_uri(uri: str) -> None:
    """Validate *uri* against the DCR redirect-URI policy.

    Raises:
        RegistrationError("invalid_redirect_uri"): when the URI violates policy.
    """
    if not uri:
        raise RegistrationError(
            error="invalid_redirect_uri",
            error_description="Redirect URI must not be empty",
        )

    if "*" in uri:
        raise RegistrationError(
            error="invalid_redirect_uri",
            error_description="Redirect URI must not contain wildcards",
        )

    try:
        parsed = urlparse(uri)
    except Exception as exc:  # pragma: no cover
        raise RegistrationError(
            error="invalid_redirect_uri",
            error_description=f"Redirect URI cannot be parsed: {exc}",
        ) from exc

    scheme = (parsed.scheme or "").lower()
    # urlparse .hostname strips brackets + lowercases; .username/.password for userinfo
    host = parsed.hostname or ""
    fragment = parsed.fragment
    userinfo = parsed.username or parsed.password

    if fragment:
        raise RegistrationError(
            error="invalid_redirect_uri",
            error_description="Redirect URI must not contain a fragment",
        )

    if userinfo:
        raise RegistrationError(
            error="invalid_redirect_uri",
            error_description="Redirect URI must not contain userinfo",
        )

    if scheme == "https":
        if not host:
            raise RegistrationError(
                error="invalid_redirect_uri",
                error_description="HTTPS redirect URI must have a host",
            )
        if _is_ip_address(host):
            raise RegistrationError(
                error="invalid_redirect_uri",
                error_description=(
                    "HTTPS redirect URI must use a hostname, not an IP literal"
                ),
            )
        return  # valid

    if scheme == "http":
        if host in _LOOPBACK_HOSTS:
            return  # valid loopback
        raise RegistrationError(
            error="invalid_redirect_uri",
            error_description=(
                "HTTP redirect URIs are only permitted for loopback addresses "
                "(127.0.0.1, localhost, [::1])"
            ),
        )

    raise RegistrationError(
        error="invalid_redirect_uri",
        error_description=(
            f"Redirect URI scheme must be https or http (loopback only); got {scheme!r}"
        ),
    )
