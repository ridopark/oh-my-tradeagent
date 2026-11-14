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
| `OMT_MCP_TOKEN` | one of | The raw MCP token value; set exactly one of this or `OMT_MCP_TOKEN_FILE`. |
| `OMT_MCP_TOKEN_FILE` | one of | Absolute path to a file containing the token (e.g. `~/.config/omt-mcp/prod_real.token`); preferred for client configs — the file path is stored in config, not the token. |
| `OMT_BFF_URL` | yes | Base URL of the tenant-dashboard-bff (e.g. `http://127.0.0.1:18080`). |

Exactly one of `OMT_MCP_TOKEN` or `OMT_MCP_TOKEN_FILE` must be set; both or neither causes exit
code 2. Any other missing variable also causes exit code 2 with a one-line error. A token that the
BFF rejects (401) or that is bound to a different tenant (mismatch) causes exit code 1 with a
one-line message.

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

### Keep the tunnel up (optional, systemd user service)

Instead of a port-forward in a spare terminal, a user service reconnects on its own. This one
SSHes to the homelab and runs the port-forward there, forwarding local `127.0.0.1:18080`:

```ini
# ~/.config/systemd/user/omt-bff-tunnel.service
[Unit]
Description=SSH tunnel + kubectl port-forward to tenant-dashboard-bff (omt-mcp)
After=network-online.target

[Service]
ExecStart=/usr/bin/ssh -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o BatchMode=yes \
  -L 127.0.0.1:18080:127.0.0.1:18080 <user>@<homelab-host> \
  kubectl -n copytrade port-forward --address 127.0.0.1 svc/tenant-dashboard-bff 18080:8083
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

`systemctl --user daemon-reload && systemctl --user enable --now omt-bff-tunnel.service`, then
`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/actuator/health` → `200`.
Do **not** add `ssh -N`: it skips the remote command, so the port-forward never starts and every
request fails with an empty reply.

### Claude Code

Verified against `claude mcp add --help` (Claude Code 2.1.325).

```bash
claude mcp add omt-mcp \
  --scope user \
  -e OMT_TENANT=prod_real \
  -e OMT_MCP_TOKEN_FILE="$HOME/.config/omt-mcp/prod_real.token" \
  -e OMT_BFF_URL=http://127.0.0.1:18080 \
  -- uv --directory <repo>/services/mcp-server run omt-mcp
```

Replace `<repo>` with the absolute path to the repository root. This stores the env vars in
`~/.claude.json` under the `user` scope. Because the env var holds the **file path**, not the
token value, `~/.claude.json` never contains the raw token — the file is read at startup each
time. Rotation means overwriting `~/.config/omt-mcp/prod_real.token`; no config change needed.
Use `--scope local` to restrict to the current project instead.

The port-forward must be running in a separate terminal whenever you start a Claude Code session
that uses `omt-mcp`.

Docs: `claude mcp add --help` (run locally; Claude Code docs at
https://docs.anthropic.com/en/docs/claude-code/mcp).

### Gemini CLI

Verified against the Gemini CLI MCP docs at https://www.geminicli.com/docs/tools/mcp-server
(accessed 2026-11-13).

Gemini CLI's `mcpServers` block in `~/.gemini/settings.json` passes `env` entries directly to
the server process. Use `OMT_MCP_TOKEN_FILE` so the token stays in the file, not in the config:

```json
{
  "mcpServers": {
    "omt-mcp": {
      "command": "uv",
      "args": [
        "--directory",
        "/absolute/path/to/repo/services/mcp-server",
        "run",
        "omt-mcp"
      ],
      "env": {
        "OMT_TENANT": "prod_real",
        "OMT_MCP_TOKEN_FILE": "/home/YOUR_USER/.config/omt-mcp/prod_real.token",
        "OMT_BFF_URL": "http://127.0.0.1:18080"
      }
    }
  }
}
```

Replace `/absolute/path/to/repo` with the repository root and `/home/YOUR_USER` with your home
directory. JSON does not expand `~` or `$HOME`, so the full path is required.

Rotation means overwriting the token file; no config change needed.

### Codex CLI

Verified against the Codex CLI config reference at
https://developers.openai.com/codex/config-reference (redirects to
https://learn.chatgpt.com/docs/config-file/config-reference, accessed 2026-11-13).

User-level config lives in `~/.codex/config.toml`. The MCP section uses `[mcp_servers.<id>]`
with `command`, `args`, and `env` (literal key/value pairs). Set `OMT_MCP_TOKEN_FILE` so the
token stays in the file, not in the config:

```toml
[mcp_servers.omt-mcp]
command = "uv"
args = ["--directory", "/absolute/path/to/repo/services/mcp-server", "run", "omt-mcp"]

[mcp_servers.omt-mcp.env]
OMT_TENANT = "prod_real"
OMT_MCP_TOKEN_FILE = "/home/YOUR_USER/.config/omt-mcp/prod_real.token"
OMT_BFF_URL = "http://127.0.0.1:18080"
```

Use a `[mcp_servers.omt-mcp.env]` table, not a multi-line inline `env = { … }`: TOML 1.0 inline
tables must be on one line, and a multi-line one fails to parse.

Replace `/absolute/path/to/repo` with the repository root and `/home/YOUR_USER` with your home
directory. TOML does not expand `~`, so the full path is required.

Rotation means overwriting the token file; no config change needed.

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

---

## Remote (OAuth) mode

The gateway exposes the same read-only MCP tools over Streamable HTTP so that
hosted AI clients (Claude.ai, Gemini web app, ChatGPT) can connect without a
local CLI.  Members sign in with Google; the gateway federates to Google OIDC
and issues short-lived MCP access tokens that are scoped to the member's tenant
set.

> **Status:** Step 3c (implemented in PR 3).  Full setup docs, k8s manifests,
> and a Cloudflare runbook are delivered in PR 5.

### Environment variables

| Variable | Required | Description |
|---|---|---|
| `OMT_PUBLIC_URL` | yes | HTTPS URL of the gateway (e.g. `https://tradeagent-mcp.ridopark.com`) |
| `OMT_BFF_URL` | yes | Internal URL of the tenant-dashboard-bff |
| `MCP_GATEWAY_SIGNING_KEY` | yes | Ed25519 private key PEM for delegated assertions |
| `OMT_GATEWAY_DB_URL` | yes* | PostgreSQL DSN for the OAuth store, without a password (e.g. `postgresql://mcp_gateway@pg/dashboard`) |
| `OMT_GOOGLE_CLIENT_ID` | yes* | Google OAuth 2.0 client ID |
| `OMT_GOOGLE_CLIENT_SECRET` | yes* | Google OAuth 2.0 client secret |
| `MCP_GATEWAY_DB_PASSWORD` | yes* | Password of the `mcp_gateway` role. Passed to the driver as a separate argument, never spliced into the DSN |
| `OMT_GATEWAY_DISABLED` | no | `true`, `1` or `yes`: disabled mode (kill switch) — `/healthz` only, everything else 503 |
| `OMT_TRUSTED_PROXY_CIDRS` | no | CIDRs whose `CF-Connecting-IP` header is trusted for per-IP rate limits. Default empty: the header is ignored and the socket peer is used |
| `OMT_RATE_TOOLS_PER_MIN_SUBJECT` / `_CLIENT` | no | Tool-call limits per signed-in user / per client (default 60 / 300 per minute) |
| `OMT_RATE_AUTH_PER_MIN_IP` | no | Per-IP limit on the OAuth endpoints (default 30 per minute) |
| `OMT_ALLOWED_HOSTS` | no | Comma-separated host allow-list (defaults to host of `OMT_PUBLIC_URL`) |
| `OMT_LISTEN` | no | `host:port` to bind (default `0.0.0.0:8000`) |

\* Required unless `OMT_GATEWAY_DISABLED` is set. A missing or invalid value exits 2 with one line; so does an unreachable database.

### Generating the signing key

```bash
openssl genpkey -algorithm ed25519 -out mcp-signing-key.pem
# Store in k8s Secret; pass as MCP_GATEWAY_SIGNING_KEY (the PEM text)
```

### Local smoke test (disabled mode)

```bash
OMT_PUBLIC_URL=https://tradeagent-mcp.ridopark.com \
OMT_BFF_URL=http://127.0.0.1:18080 \
MCP_GATEWAY_SIGNING_KEY="$(openssl genpkey -algorithm ed25519 2>/dev/null)" \
OMT_GATEWAY_DISABLED=1 \
  uv run python -m ohmytradeagent_mcp.gateway
```

The server starts, serves `GET /healthz` → `{"status":"ok"}`, and
rejects all `/mcp` requests with 503.
