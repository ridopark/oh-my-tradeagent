"""Gateway configuration from environment variables.

Required:
  OMT_PUBLIC_URL       — public base URL for this gateway (e.g. https://tradeagent-mcp.ridopark.com)
  OMT_BFF_URL          — tenant-dashboard-bff base URL (e.g. https://bff.ridopark.com)
  MCP_GATEWAY_SIGNING_KEY — Ed25519 private key PEM used to sign BFF assertions

Optional:
  OMT_GATEWAY_DISABLED — set to "true" to return 503 for all /mcp requests
  OMT_ALLOWED_HOSTS    — comma-separated list of allowed Host header values
  OMT_LISTEN           — listen address in host:port form (default "0.0.0.0:8080")
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

        self.listen: str = env.get("OMT_LISTEN", "0.0.0.0:8080").strip()

    @property
    def mcp_resource_url(self) -> str:
        """The full URL of the /mcp endpoint, used as the OAuth resource indicator."""
        return f"{self.public_url}/mcp"
