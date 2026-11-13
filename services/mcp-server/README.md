# omt-mcp — oh-my-tradeagent read-only MCP server

A stdio MCP server for the oh-my-tradeagent operator dashboard.
Gives Claude Code, Gemini CLI and Codex CLI read-only access to one tenant's
live positions, portfolio summary and risk state.

**Full client setup instructions, port-forward verification and the data-retention
note will be added in Step 5 of the implementation plan.**

## Quick start (development)

```bash
cd services/mcp-server
uv sync
OMT_TENANT=acme OMT_MCP_TOKEN=<token> OMT_BFF_URL=http://127.0.0.1:8083 uv run omt-mcp
```

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `OMT_TENANT` | yes | The tenant ID the token is bound to. |
| `OMT_MCP_TOKEN` | yes | The read-only MCP token minted by the runbook. |
| `OMT_BFF_URL` | yes | Base URL of the tenant-dashboard-bff (e.g. `http://127.0.0.1:8083`). |

## Running tests

```bash
uv run ruff check .
uv run pytest -q
```
