"""Configuration reader for the MCP server.

Reads OMT_TENANT, OMT_MCP_TOKEN and OMT_BFF_URL from the environment.
Raises ConfigError (exit code 2) if any variable is missing or empty.
"""
from __future__ import annotations


class ConfigError(Exception):
    """Raised when a required environment variable is missing or empty."""


class Config:
    """Parsed environment configuration."""

    def __init__(self, env: dict[str, str]) -> None:
        self.tenant = self._require(env, "OMT_TENANT")
        self.token = self._require(env, "OMT_MCP_TOKEN")
        self.bff_url = self._require(env, "OMT_BFF_URL").rstrip("/")

    @staticmethod
    def _require(env: dict[str, str], key: str) -> str:
        value = env.get(key, "").strip()
        if not value:
            raise ConfigError(key)
        return value
