# oh-my-tradeagent remote MCP gateway — Step 3c
# Non-root, uv --locked, asyncpg C-extension pre-compiled in the builder stage.
#
# Build from the REPO ROOT:
#   docker build -f services/mcp-server/Dockerfile -t omt-mcp-gateway .
# Run:
#   docker run --env-file gateway.env -p 8000:8000 omt-mcp-gateway
#
# Required env vars at runtime:
#   OMT_PUBLIC_URL, OMT_BFF_URL, MCP_GATEWAY_SIGNING_KEY
#   OMT_GATEWAY_DB_URL, OMT_GOOGLE_CLIENT_ID, OMT_GOOGLE_CLIENT_SECRET
#   MCP_GATEWAY_DB_PASSWORD
# Optional:
#   OMT_GATEWAY_DISABLED, OMT_ALLOWED_HOSTS, ...
#   (see ohmytradeagent_mcp/gateway/settings.py for the full list)

FROM python:3.12-slim-bookworm AS builder

# Install uv
COPY --from=ghcr.io/astral-sh/uv:0.5.21 /uv /usr/local/bin/uv

WORKDIR /app
COPY services/mcp-server/pyproject.toml services/mcp-server/uv.lock ./
# Install dependencies into the .venv; --locked ensures the lockfile is used
RUN uv sync --locked --no-dev --no-install-project

# Copy source and install the project itself
COPY services/mcp-server/ohmytradeagent_mcp/ ./ohmytradeagent_mcp/
RUN uv sync --locked --no-dev


# ---------------------------------------------------------------------------
# Runtime stage — stripped down, non-root
# ---------------------------------------------------------------------------
FROM python:3.12-slim-bookworm AS runtime

# Non-root user (UID 1000 — deterministic, harmless on most Linux hosts)
RUN groupadd --gid 1000 mcp && useradd --uid 1000 --gid mcp --no-create-home mcp

WORKDIR /app

# Copy the fully-installed virtual-environment from the builder stage
COPY --from=builder --chown=mcp:mcp /app /app

# Put the venv on PATH so 'python' resolves to the installed venv python
ENV PATH=/app/.venv/bin:$PATH

USER mcp

EXPOSE 8000

# python -m package is equivalent to the omt-mcp-gateway console script
ENTRYPOINT ["python", "-m", "ohmytradeagent_mcp.gateway"]
