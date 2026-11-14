"""Gateway configuration from environment variables.

Required:
  OMT_PUBLIC_URL       — public base URL for this gateway (e.g. https://tradeagent-mcp.ridopark.com)
  OMT_BFF_URL          — tenant-dashboard-bff base URL (e.g. https://bff.ridopark.com)
  MCP_GATEWAY_SIGNING_KEY — Ed25519 private key PEM used to sign BFF assertions

Optional:
  OMT_GATEWAY_DISABLED    — set to "true" to return 503 for all /mcp requests
  OMT_ALLOWED_HOSTS       — comma-separated list of allowed Host header values
  OMT_LISTEN              — listen address in host:port form (default "0.0.0.0:8000")
  OMT_GOOGLE_CLIENT_ID    — Google OAuth 2.0 client id (required for production AS)
  OMT_GOOGLE_CLIENT_SECRET — Google OAuth 2.0 client secret (required for production AS)
  OMT_GATEWAY_DB_URL      — asyncpg DSN for PostgresStore; uses InMemoryStore when absent
  MCP_GATEWAY_DB_PASSWORD — optional password override for the mcp_gateway DB role
  OMT_RATE_TOOLS_PER_MIN_SUBJECT — per-subject tool call RPM (default 60)
  OMT_RATE_TOOLS_PER_MIN_CLIENT  — per-client tool call RPM (default 300)
  OMT_RATE_AUTH_PER_MIN_IP       — per-IP auth endpoint RPM (default 30)
"""
from __future__ import annotations


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
            self.allowed_hosts = []

        self.listen: str = env.get("OMT_LISTEN", "0.0.0.0:8000").strip()

        # Google OAuth — optional (tests inject a FakeIdP instead)
        self.google_client_id: str | None = env.get("OMT_GOOGLE_CLIENT_ID") or None
        self.google_client_secret: str | None = env.get("OMT_GOOGLE_CLIENT_SECRET") or None

        # PostgresStore DSN — absent → InMemoryStore (default for local dev + tests)
        self.db_url: str | None = env.get("OMT_GATEWAY_DB_URL", "").strip() or None

        # Rate limit defaults (override with env vars)
        def _int(key: str, default: int) -> int:
            try:
                return int(env.get(key, str(default)))
            except ValueError:
                return default

        self.rate_tools_per_min_subject: int = _int("OMT_RATE_TOOLS_PER_MIN_SUBJECT", 60)
        self.rate_tools_per_min_client: int = _int("OMT_RATE_TOOLS_PER_MIN_CLIENT", 300)
        self.rate_auth_per_min_ip: int = _int("OMT_RATE_AUTH_PER_MIN_IP", 30)

    @property
    def mcp_resource_url(self) -> str:
        """The full URL of the /mcp endpoint, used as the OAuth resource indicator."""
        return f"{self.public_url}/mcp"

    @property
    def google_callback_url(self) -> str:
        """The URL Google redirects to after authentication."""
        return f"{self.public_url}/oauth/google/callback"
