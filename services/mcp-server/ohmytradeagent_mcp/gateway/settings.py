"""Gateway configuration from environment variables.

Required:
  OMT_PUBLIC_URL       — public base URL for this gateway (e.g. https://tradeagent-mcp.ridopark.com)
  OMT_BFF_URL          — tenant-dashboard-bff base URL (e.g. https://bff.ridopark.com)
  MCP_GATEWAY_SIGNING_KEY — Ed25519 private key PEM used to sign BFF assertions

Optional:
  OMT_GATEWAY_DISABLED    — set to "true" to return 503 for all /mcp requests
  OMT_ALLOWED_HOSTS       — comma-separated list of allowed Host header values;
                            defaults to the host of OMT_PUBLIC_URL when absent
  OMT_LISTEN              — listen address in host:port form (default "0.0.0.0:8000")
  OMT_GOOGLE_CLIENT_ID    — Google OAuth 2.0 client id (required in production)
  OMT_GOOGLE_CLIENT_SECRET — Google OAuth 2.0 client secret (required in production)
  OMT_GATEWAY_DB_URL      — asyncpg DSN for PostgresStore (required in production)
  MCP_GATEWAY_DB_PASSWORD — password for the mcp_gateway DB role (required in production);
                            passed to asyncpg as a keyword argument, never spliced into the DSN
  OMT_RATE_TOOLS_PER_MIN_SUBJECT — per-subject tool call RPM (default 60)
  OMT_RATE_TOOLS_PER_MIN_CLIENT  — per-client tool call RPM (default 300)
  OMT_RATE_AUTH_PER_MIN_IP       — per-IP auth endpoint RPM (default 30)
  OMT_TRUSTED_PROXY_CIDRS — comma-separated CIDRs of trusted reverse proxies
      (e.g. "10.42.0.0/16").  When a request arrives from a socket peer inside
      one of these CIDRs, the ``CF-Connecting-IP`` header is trusted and used as
      the rate-limit key; otherwise the socket peer address is used.  Default is
      empty (never trust the header).  PR 5 sets this to the cloudflared pod CIDR.
      An invalid CIDR causes startup to abort with exit code 2.
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlparse


class ConfigError(Exception):
    """Raised when a required env var is missing or invalid."""


class GatewaySettings:
    """Read and validate gateway configuration from an env dict."""

    def __init__(self, env: dict[str, str]) -> None:
        missing = []
        for var in ("OMT_PUBLIC_URL", "OMT_BFF_URL", "MCP_GATEWAY_SIGNING_KEY"):
            if not env.get(var):
                missing.append(var)
        if missing:
            raise ConfigError(f"Required env var(s) missing: {', '.join(missing)}")

        self.public_url: str = env["OMT_PUBLIC_URL"].rstrip("/")
        self.bff_url: str = env["OMT_BFF_URL"].rstrip("/")
        self.signing_key_pem: str = env["MCP_GATEWAY_SIGNING_KEY"]

        disabled_raw = env.get("OMT_GATEWAY_DISABLED", "").strip().lower()
        self.disabled: bool = disabled_raw in ("true", "1", "yes")

        allowed_raw = env.get("OMT_ALLOWED_HOSTS", "").strip()
        if allowed_raw:
            self.allowed_hosts: list[str] = [h.strip() for h in allowed_raw.split(",") if h.strip()]
        else:
            # Default to the host of OMT_PUBLIC_URL so a misconfigured deploy
            # never silently accepts arbitrary Host headers.
            parsed = urlparse(self.public_url)
            self.allowed_hosts = [parsed.hostname or ""] if parsed.hostname else []

        self.listen: str = env.get("OMT_LISTEN", "0.0.0.0:8000").strip()

        # Google OAuth — optional (tests inject a FakeIdP instead)
        self.google_client_id: str | None = env.get("OMT_GOOGLE_CLIENT_ID") or None
        self.google_client_secret: str | None = env.get("OMT_GOOGLE_CLIENT_SECRET") or None

        # PostgresStore DSN — absent → InMemoryStore (default for local dev + tests)
        self.db_url: str | None = env.get("OMT_GATEWAY_DB_URL", "").strip() or None

        # Rate limit defaults (override with env vars)
        def _int(key: str, default: int) -> int:
            val = env.get(key, str(default))
            try:
                return int(val)
            except ValueError:
                raise ConfigError(f"Invalid integer for {key}: {val!r}") from None

        self.rate_tools_per_min_subject: int = _int("OMT_RATE_TOOLS_PER_MIN_SUBJECT", 60)
        self.rate_tools_per_min_client: int = _int("OMT_RATE_TOOLS_PER_MIN_CLIENT", 300)
        self.rate_auth_per_min_ip: int = _int("OMT_RATE_AUTH_PER_MIN_IP", 30)

        # Trusted reverse-proxy CIDRs — CF-Connecting-IP is only honoured when
        # the socket peer falls inside one of these networks.
        raw_cidrs = env.get("OMT_TRUSTED_PROXY_CIDRS", "").strip()
        if raw_cidrs:
            trusted: list[ipaddress.IPv4Network | ipaddress.IPv6Network] = []
            for token in raw_cidrs.split(","):
                token = token.strip()
                if not token:
                    continue
                try:
                    trusted.append(ipaddress.ip_network(token, strict=False))
                except ValueError as exc:
                    raise ConfigError(
                        f"Invalid OMT_TRUSTED_PROXY_CIDRS entry {token!r}: {exc}"
                    ) from exc
            self.trusted_proxy_cidrs: list[ipaddress.IPv4Network | ipaddress.IPv6Network] = trusted
        else:
            self.trusted_proxy_cidrs = []

    @property
    def mcp_resource_url(self) -> str:
        """The full URL of the /mcp endpoint, used as the OAuth resource indicator."""
        return f"{self.public_url}/mcp"

    @property
    def google_callback_url(self) -> str:
        """The URL Google redirects to after authentication."""
        return f"{self.public_url}/oauth/google/callback"
