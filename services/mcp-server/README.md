# omt-mcp — oh-my-tradeagent read-only MCP server

A stdio MCP server that gives Claude Code, Gemini CLI and Codex CLI read-only access to one
oh-my-tradeagent tenant's live positions, portfolio summary, risk state and market context.

**Cannot trade.** The server calls only allowlisted read routes on the BFF; there is no write
path of any kind.

**Token bound to one tenant.** The BFF verifies at startup that the configured token is bound to
the configured tenant. A mis-pasted token or a wrong `OMT_TENANT` causes a startup refusal, not
a silent access to another account.

---

## Quick start (development)

```bash
cd services/mcp-server
uv sync
OMT_TENANT=acme \
OMT_MCP_TOKEN=<token> \
OMT_BFF_URL=http://127.0.0.1:18080 \
  uv run omt-mcp
```

For the live cluster, start a port-forward first (see §Port-forward) and use
`OMT_BFF_URL=http://127.0.0.1:18080`.

---

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `OMT_TENANT` | yes | The tenant ID the token is bound to (e.g. `prod_real`). |
| `OMT_MCP_TOKEN` | yes | The read-only MCP token minted by the runbook. |
| `OMT_BFF_URL` | yes | Base URL of the tenant-dashboard-bff (e.g. `http://127.0.0.1:18080`). |

Any missing variable causes the server to exit with code 2 and a one-line error naming the
variable. A token that the BFF rejects (401) or that is bound to a different tenant (mismatch)
causes exit code 1 with a one-line message naming the problem.

---

## Tools and prompt

Seven read-only tools and one prompt are registered:

| Tool | What it returns |
|---|---|
| `get_portfolio_summary` | Equity, cash, open exposure, notional-cap headroom per strategy, realized P&L, kill-switch state. |
| `list_open_positions` | Each open position with contract, DTE, qty, entry/current premium, unrealized $/%, trail/floor status, partial exits, and the author's entry message (labelled untrusted). |
| `get_position_history(workflow_id)` | Fills, partial exits and orders for one position. |
| `get_quote(occ)` | Live bid/mid/ask for one OCC contract (requires manual-entry to be enabled for the tenant). |
| `get_chain(underlying, expiration?, right?)` | Option expirations, or contracts with greeks for one expiry+right. |
| `get_underlying_context(ticker, date?)` | Options-flow summary and dark-pool prints for one underlying. |
| `get_recent_closed_trades(limit?)` | Newest closed trade events (entries + exits), newest first. |

Prompt `analyze_positions(focus?)` assembles all of the above into a structured question asking
the AI to assess exposure vs cap headroom, per-position risk, candidate actions to discuss
(never to execute), and data caveats.

**Redacted from every tool result:** `account_number`, `broker_order_id`, `operator_id` are
removed at all nesting depths, including inside `subject` JSON strings. Author, `raw_line` and
`tail` fields (Discord free text from an untrusted third party) are wrapped as
`{"untrusted": true, "text": "..."}`.

---

## Port-forward

The BFF is a ClusterIP-only service (no ingress). Access it from the workstation via
`kubectl port-forward`.

```bash
kubectl -n copytrade port-forward svc/tenant-dashboard-bff 18080:8083
```

**Verified 2026-11-13 on live cluster** using the commands below (read-only; no token was
minted or changed during this check):

```bash
# Health check — proves the forward is reachable
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/actuator/health
# → 200

# Valid token → whoami returns tenant
curl -s \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/prod_real.token)" \
  http://127.0.0.1:18080/api/mcp/whoami
# → {"tenant_id":"prod_real","token_id":"<12 hex>"}

# Bad token → 401 (filter is reachable through the forward)
curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer not-a-token" \
  http://127.0.0.1:18080/api/mcp/whoami
# → 401

# MCP token cannot reach non-allowlisted routes
curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/prod_real.token)" \
  -H "X-Tenant-Id: prod_real" \
  http://127.0.0.1:18080/api/strategy-config
# → 403

# Forged X-Tenant-Id on a different tenant → 403
curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/prod_real.token)" \
  -H "X-Tenant-Id: other_tenant" \
  http://127.0.0.1:18080/api/mcp/whoami
# → 403

# POST not allowed by MCP token
curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/prod_real.token)" \
  http://127.0.0.1:18080/api/account-killswitch/force-close
# → 403
```

The BFF NetworkPolicy admits traffic from the dashboard pod, the api-gateway pod and Prometheus.
Port-forward traffic arrives from the node and the above verification confirms kube-router
permits it. If your cluster differs and the forward is blocked, see `docs/ops/mcp-read-token-runbook.md`.

---

## Client setup

Mint a token first: see `docs/ops/mcp-read-token-runbook.md`.

The token file is at `~/.config/omt-mcp/<tenant>.token` (mode 600). **Never paste the token
into a config file you commit to git.** The snippets below read it from the file.

### Claude Code

Verified against `claude mcp add --help` (Claude Code 2.1.325).

```bash
claude mcp add omt-mcp \
  --scope user \
  -e OMT_TENANT=prod_real \
  -e "OMT_MCP_TOKEN=$(cat ~/.config/omt-mcp/prod_real.token)" \
  -e OMT_BFF_URL=http://127.0.0.1:18080 \
  -- uv --directory /home/ridopark/src/oh-my-tradeagent/services/mcp-server run omt-mcp
```

This stores the env vars (including the resolved token value) in `~/.claude.json` under the
`user` scope. If you rotate the token, re-run the command with the new value. Use `--scope local`
to restrict to the current project instead.

The port-forward must be running in a separate terminal whenever you start a Claude Code session
that uses `omt-mcp`.

Docs: `claude mcp add --help` (run locally; Claude Code docs at
https://docs.anthropic.com/en/docs/claude-code/mcp).

### Gemini CLI

Verified against the Gemini CLI MCP docs at https://www.geminicli.com/docs/tools/mcp-server
(accessed 2026-11-13).

> **Note:** Gemini CLI was superseded by Antigravity CLI on 2026-06-18 for free-tier and
> Google AI Pro/Ultra users. Code Assist Standard/Enterprise users continue on Gemini CLI.
> Confirm which tool your account uses before applying this config.

Gemini CLI's `mcpServers` block in `~/.gemini/settings.json` supports `$VAR` references to host
environment variables. Export the token from your shell profile so Gemini CLI can pass it
through:

```bash
# Add to ~/.bashrc or ~/.zshrc
export OMT_MCP_TOKEN=$(cat ~/.config/omt-mcp/prod_real.token)
```

Then add to `~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "omt-mcp": {
      "command": "uv",
      "args": [
        "--directory",
        "/home/ridopark/src/oh-my-tradeagent/services/mcp-server",
        "run",
        "omt-mcp"
      ],
      "env": {
        "OMT_TENANT": "prod_real",
        "OMT_MCP_TOKEN": "$OMT_MCP_TOKEN",
        "OMT_BFF_URL": "http://127.0.0.1:18080"
      }
    }
  }
}
```

The Gemini CLI docs note that host variables matching patterns `*TOKEN*`, `*SECRET*`,
`*PASSWORD*`, `*KEY*` are stripped from the environment passed to MCP servers unless you list
them explicitly in `env` — the snippet above lists `OMT_MCP_TOKEN` explicitly, so it is passed
through.

Alternatively, `gemini mcp add` writes the same block from the command line:

```bash
gemini mcp add omt-mcp \
  -e OMT_TENANT=prod_real \
  -e "OMT_MCP_TOKEN=$OMT_MCP_TOKEN" \
  -e OMT_BFF_URL=http://127.0.0.1:18080 \
  uv -- --directory /home/ridopark/src/oh-my-tradeagent/services/mcp-server run omt-mcp
```

### Codex CLI

Verified against the Codex CLI config reference at
https://developers.openai.com/codex/config-reference (redirects to
https://learn.chatgpt.com/docs/config-file/config-reference, accessed 2026-11-13).

User-level config lives in `~/.codex/config.toml`. The MCP section uses `[mcp_servers.<id>]`
with `command`, `args`, `env` (literal key/value pairs) and `env_vars` (host variable names to
whitelist). Export the token from your shell profile first (same as the Gemini step above), then
add:

```toml
[mcp_servers.omt-mcp]
command = "uv"
args = [
  "--directory",
  "/home/ridopark/src/oh-my-tradeagent/services/mcp-server",
  "run",
  "omt-mcp"
]
env = { OMT_TENANT = "prod_real", OMT_BFF_URL = "http://127.0.0.1:18080" }
env_vars = ["OMT_MCP_TOKEN"]
```

`env_vars` whitelists `OMT_MCP_TOKEN` from the host environment and passes it to the server
process. The token value is never stored in the TOML file.

---

## Data retention

**What leaves the workstation via each AI provider:**

Each tool call sends BFF response data to the AI model: open positions (OCC symbols, quantities,
premiums, unrealized P&L), portfolio equity and P&L figures, cash, options-flow and dark-pool
summaries, and author text from Discord (labelled untrusted). The redaction layer removes account
numbers, broker order IDs and operator email addresses before they leave the process.

**What never leaves the workstation:**

Broker account numbers, broker order IDs and operator IDs (`operator_id`) are removed by the
sanitiser before every tool result is returned. Your Alpaca account number and internal order
tracking IDs do not reach any AI provider.

**Provider data-use policies:**

Providers differ in whether they retain or train on inputs. API access, Team plans and Enterprise
plans typically exclude prompt data from training; free-tier and consumer chat plans typically do
not. Review the current terms for each tool you use before relying on it in a session that
contains position or P&L data:

- Anthropic (Claude): https://www.anthropic.com/legal/privacy
- Google (Gemini / Antigravity): https://policies.google.com/privacy
- OpenAI (Codex): https://openai.com/policies/privacy-policy

---

## Running tests

```bash
cd services/mcp-server
uv run ruff check .
uv run pytest -q
```
