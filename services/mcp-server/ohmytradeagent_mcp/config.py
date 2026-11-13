"""Configuration reader for the MCP server.

Reads OMT_TENANT, OMT_BFF_URL, and exactly one of OMT_MCP_TOKEN /
OMT_MCP_TOKEN_FILE from the environment.  Raises ConfigError (exit code 2)
if any variable is missing, both token vars are set, or the token file is
unreadable or empty.
"""
from __future__ import annotations

import os


class ConfigError(Exception):
    """Raised when a required environment variable is missing, conflicting, or invalid."""


class Config:
    """Parsed environment configuration."""

    def __init__(self, env: dict[str, str]) -> None:
        self.tenant = self._require(env, "OMT_TENANT")
        self.token = self._resolve_token(env)
        self.bff_url = self._require(env, "OMT_BFF_URL").rstrip("/")

    @staticmethod
    def _require(env: dict[str, str], key: str) -> str:
        value = env.get(key, "").strip()
        if not value:
            raise ConfigError(f"{key} is not set or empty")
        return value

    @staticmethod
    def _resolve_token(env: dict[str, str]) -> str:
        """Return the MCP token from OMT_MCP_TOKEN or OMT_MCP_TOKEN_FILE.

        Exactly one of the two must be set.  Both or neither → ConfigError.
        The file is read at process start and stripped of surrounding whitespace.
        An unreadable or empty file → ConfigError naming the path (never the contents).
        """
        has_token = bool(env.get("OMT_MCP_TOKEN", "").strip())
        has_file = bool(env.get("OMT_MCP_TOKEN_FILE", "").strip())

        if has_token and has_file:
            raise ConfigError(
                "OMT_MCP_TOKEN and OMT_MCP_TOKEN_FILE are both set; set exactly one"
            )
        if not has_token and not has_file:
            raise ConfigError(
                "exactly one of OMT_MCP_TOKEN or OMT_MCP_TOKEN_FILE must be set"
            )

        if has_token:
            return env["OMT_MCP_TOKEN"].strip()

        # Read token from file
        path = os.path.expanduser(env["OMT_MCP_TOKEN_FILE"].strip())
        try:
            with open(path) as fh:
                token = fh.read().strip()
        except OSError as exc:
            raise ConfigError(
                f"cannot read OMT_MCP_TOKEN_FILE={path!r}: {exc}"
            ) from exc

        if not token:
            raise ConfigError(f"OMT_MCP_TOKEN_FILE={path!r} is empty")

        return token
