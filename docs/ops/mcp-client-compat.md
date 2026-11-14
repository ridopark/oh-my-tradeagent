# MCP gateway — client compatibility and setup

> **This endpoint is not live yet.** The operator must follow
> `docs/ops/mcp-remote-gateway-runbook.md` before any client can connect. All
> setup steps in this document assume the runbook has been completed and
> `https://tradeagent-mcp.ridopark.com/mcp` is reachable.

**Audience:** members who want to connect an AI app to their tenant's read-only
data, and the operator who tests each client after enabling.

**What members can do:** query open positions, portfolio summary, risk state,
market context (7 read-only tools + one `analyze_positions` prompt). **No
trading.** The gateway cannot place, change or cancel orders.

**Who can connect:** Google accounts that are `dashboard_user` members of at
least one tenant. Members sign in with the same Google account they use for the
dashboard. Non-members receive `access_denied` and cannot proceed past sign-in.

---

## Gateway technical summary

Sources: `gateway/settings.py`, `gateway/provider.py`, `gateway/app.py`,
`gateway/redirects.py` — all in `services/mcp-server/ohmytradeagent_mcp/`
(PR 3, merged).

| Property | Value |
|---|---|
| Endpoint | `https://tradeagent-mcp.ridopark.com/mcp` |
| Transport | Streamable HTTP · stateless · JSON responses |
| AS issuer | `https://tradeagent-mcp.ridopark.com` |
| AS metadata | `/.well-known/oauth-authorization-server` |
| Protected-resource doc | `/.well-known/oauth-protected-resource/mcp` (and `/.well-known/oauth-protected-resource`) |
| Registration | DCR at `/register` (JSON body) · CIMD supported |
| Client type | Public PKCE only · no client secrets accepted |
| PKCE method | S256 only |
| Access token lifetime | 1 hour (`_ACCESS_TOKEN_LIFETIME = 3600` in `gateway/provider.py`) |
| Refresh token lifetime | 30 days · rotating · reuse detection (`_REFRESH_TOKEN_LIFETIME = 30 * 86400`) |
| Sign-in provider | Google only |
| Tenant selection | one tenant → implicit · multiple → `tenant=` argument |

---

## OAuth 2.1 registration paths

**Dynamic Client Registration (DCR)** — `POST /register` with a JSON body.
Clients that register at runtime use this path. Required: `redirect_uris`,
`grant_types: ["authorization_code"]`. The body must omit
`token_endpoint_auth_method` or set it to `"none"`; any secret-based method
returns `400 {"error":"invalid_client_metadata"}`.
Source: `gateway/app.py` `_patch_register_route`.

**Client ID Metadata Document (CIMD)** — when the `client_id` passed to
`/authorize` is an `https://` URL with a non-root path, the gateway fetches
that URL as the client's self-description. The document's `client_id` must
equal the URL. Preferred when the AS metadata advertises both
`client_id_metadata_document_supported: true` and `"none"` in
`token_endpoint_auth_methods_supported` — both are true for this gateway.
Source: `gateway/app.py` `_patch_as_metadata`, `gateway/provider.py`
`get_client`, `gateway/cimd.py`.

---

## Redirect-URI policy

Source: `gateway/redirects.py`.

Accepted redirect URIs:
- `https://<hostname>/...` — any hostname that is not an IP literal.
- `http://127.0.0.1[:<port>]/...` — loopback IPv4; port is matched
  port-agnostically per RFC 8252 §7.3 (a registered URI with no port matches
  a request URI with any port on the same host/path).
- `http://localhost[:<port>]/...` — same port-agnostic rule.
- `http://[::1][:<port>]/...` — loopback IPv6.

Rejected: fragments (`#`), userinfo (`user@host`), wildcards, bare IP literals
in HTTPS URIs, non-loopback `http://`.

---

## OAuth hop sequence (all clients)

1. Client sends a request to `/mcp` without a bearer.
2. Gateway returns `401` with
   `WWW-Authenticate: Bearer resource_metadata="https://tradeagent-mcp.ridopark.com/.well-known/oauth-protected-resource/mcp"`.
3. Client fetches the protected-resource document, finds
   `authorization_servers[0]`, fetches the AS metadata at
   `/.well-known/oauth-authorization-server`.
4. Client registers (DCR) or uses its pre-known CIMD URL as `client_id`.
5. Client redirects the user's browser to `/authorize` with PKCE
   (`code_challenge_method=S256`).
6. Gateway stores a pending auth entry and redirects to Google
   (`openid email`, `prompt=select_account`).
7. User signs in with their dashboard Google account.
8. If not a `dashboard_user` member: gateway redirects back to the client with
   `error=access_denied&error_description=not_a_member&state=…`. **End.**
9. If member: gateway renders a consent page showing the app name, the sentence
   "Read-only. This app cannot place, change or cancel orders.", and checkboxes
   for each tenant the member belongs to. Source: `gateway/consent.py`.
10. Member approves (selects at least one tenant) and submits.
11. Gateway issues an authorization code (5-minute lifetime); redirects to the
    client's registered `redirect_uri`.
12. Client exchanges the code at `/token` (body must be
    `application/x-www-form-urlencoded`; a JSON body returns `400`) for an
    access token (1 h) and a refresh token (30 d).
13. Client stores the tokens and starts calling `/mcp`.

Source: `gateway/app.py` (Google callback handler, consent handler),
`gateway/provider.py` (`authorize`, `exchange_authorization_code`).

---

## Multi-tenant members

If the member belongs to more than one tenant, the consent page shows a
checkbox for each. All checked tenants are included in the grant. Every tool
call accepts an optional `tenant=<id>` argument:

- Omit it when the grant covers exactly one tenant (the single tenant is used
  implicitly).
- Include it when the grant covers multiple tenants; omitting it returns a
  `ToolError("tenant required: this token covers multiple tenants — pass
  tenant= to identify which one")`.
- Passing a tenant not in the grant returns
  `ToolError("not authorised for tenant '<id>'")` (never reveals the other
  tenants).

Source: `gateway/resolver.py` `OAuthIdentityResolver.__call__`.

---

## Revoking access

Members revoke from the dashboard: **Connected AI apps** (behind
`MCP_CONNECTED_APPS_ENABLED`). The row shows the app name, tenants, created
date and last-used date. Clicking **Revoke** invalidates the grant; all
outstanding access and refresh tokens for that grant die immediately on the
next use. Source: PR 4 (Connected AI apps page).

Clients also offer their own disconnect: see the per-client sections below.

---

## Error reference

Source: `gateway/app.py` (all HTTP status codes confirmed by grep, 2026-11-14).

| Situation | Response |
|---|---|
| No bearer on `/mcp` | `401` + `WWW-Authenticate: Bearer resource_metadata="…/oauth-protected-resource/mcp"` |
| Unknown/expired/revoked token on `/mcp` | `401` |
| `/register` body with `token_endpoint_auth_method` other than `"none"` | `400 {"error":"invalid_client_metadata"}` |
| `/register` body with an invalid redirect URI | `400 {"error":"invalid_redirect_uri"}` |
| Non-member or zero-tenant member after Google sign-in | `302` to `redirect_uri?error=access_denied&error_description=not_a_member&state=…` |
| Wrong `Host` header on any request | `421 Invalid Host header` |
| IP rate limit exceeded on OAuth endpoints | `429` + `Retry-After` |
| Kill switch on (`OMT_GATEWAY_DISABLED=true`) | `503 {"error":"disabled"}` on all paths except `/healthz` |
| Transient store/DB failure | `503 {"error":"temporarily_unavailable"}` |
| Tool rate limit exceeded | `ToolError` with text `rate_limited: retry after <n> s` (not an HTTP 429; the MCP layer already accepted the request) |

---

## Results table (operator fills after enabling)

The gateway has never been enabled on the live cluster. The operator runs the
compatibility matrix after completing the runbook and fills this table.

| Client | Version | Date | Result | Notes |
|---|---|---|---|---|
| Claude web (claude.ai) | | | | |
| Claude Desktop | | | | |
| Claude mobile (custom connector) | | | | |
| Claude Code (HTTP transport) | | | | |
| Gemini CLI | | | | |
| Gemini app (connected apps) | | | | |
| ChatGPT connector | | | | |

---

## Per-client setup

### Claude web (claude.ai), Claude Desktop, Claude mobile

These three clients share the same OAuth flow for HTTP MCP servers: they all
connect from **Anthropic's cloud infrastructure** (not from the user's local
device), so the server must be publicly reachable on port 443.
Source: https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp
(fetched 2026-11-14).

**Registration path:** CIMD (preferred). The AS metadata advertises both
`client_id_metadata_document_supported: true` and `"none"` in
`token_endpoint_auth_methods_supported`, which causes Claude to prefer the CIMD
path over DCR. Claude.ai fetches its own metadata document and presents it as
the `client_id`. DCR and manual `client_id` are also available via the "OAuth
client" selector.
Source: https://claude.com/docs/connectors/building/authentication
(fetched 2026-11-14).

**Callback used:** `https://claude.ai/api/mcp/auth_callback` (all three clients).
Source: https://claude.com/docs/connectors/building/authentication (verbatim):
"For the hosted Claude apps, which are claude.ai on the web, Desktop, mobile,
and Cowork, register exactly this redirect URI:
`https://claude.ai/api/mcp/auth_callback`". Fetched 2026-11-14. The gateway's
redirect policy accepts this URI (`https://` with a valid hostname).

**Setup steps (claude.ai web and Desktop):**
Source: https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp
(fetched 2026-11-14).

For **Pro/Max plans**:
1. Claude.ai → **Customize** → **Connectors** → **+ Add** → **Add custom
   connector**.
2. Enter a name and the endpoint URL:
   `https://tradeagent-mcp.ridopark.com/mcp`
3. Click **Continue**. Claude detects the authentication settings from the AS
   metadata.
4. Under **Authentication**, leave **Sign in now** selected (or choose **Sign
   in when needed** to defer until first use).
5. Under **OAuth client**, leave **Use Claude's published identity
   (Recommended)** selected. Click **Add**.
6. Claude opens a browser tab to `https://tradeagent-mcp.ridopark.com/authorize`.
7. Sign in with the Google account used for the dashboard.
8. The consent page appears: it shows the app name, the warning "Read-only.
   This app cannot place, change or cancel orders.", and checkboxes for the
   tenant(s) the account belongs to.
9. Select the tenant(s) to share and click **Approve**.
10. The browser closes and the connector is active. The 7 tools and the
    `analyze_positions` prompt are available in new conversations.

For **Team/Enterprise plans**: an owner first adds the connector under
**Organization settings** → **Connectors** → **Add** → **Custom** → **Web**,
then members connect via **Customize** → **Connectors** → **Connect**.

**Editing a connector** requires removing and re-adding it.

**Tenant selection:** in a conversation, pass `tenant=<id>` to any tool if the
grant covers more than one tenant.

**Revoke:** from the gateway — dashboard → Connected AI apps → Revoke. From
Claude.ai — Customize → Connectors → remove the connector.

**Data retention:** see [Data retention — Anthropic](#data-retention--anthropic).

---

### Claude Code (`--transport http`)

**Registration path:** CIMD. Claude Code presents the CIMD document at
`https://claude.ai/oauth/claude-code-client-metadata` as its `client_id`. That
document registers the port-less loopback URIs `http://localhost/callback` and
`http://127.0.0.1/callback`; the gateway matches any port on these hosts
port-agnostically (RFC 8252 §7.3).
Source: `gateway/redirects.py` `loopback_port_agnostic_match`;
`tests/gateway/test_loopback_redirects.py`; plan spec.

The connection runs **from the user's local machine** (stdio MCP model), unlike
the hosted clients above.

**Setup command** (verified against `claude mcp add --help`, Claude Code
2.1.325, 2026-11-14):

```bash
# Add to the current project (scope: local)
claude mcp add --transport http omt-mcp \
  https://tradeagent-mcp.ridopark.com/mcp

# Add for all projects on this machine (scope: user)
claude mcp add --transport http --scope user omt-mcp \
  https://tradeagent-mcp.ridopark.com/mcp
```

After adding, trigger the OAuth flow in one of two ways:
- In a Claude Code session: type `/mcp` and select `omt-mcp` → **Sign in**.
- In the shell: `claude mcp login omt-mcp`.

Source: https://code.claude.com/docs/en/mcp (fetched 2026-11-14 from
redirect of https://docs.anthropic.com/en/docs/claude-code/mcp).

By default, Claude Code picks a random available loopback port for the OAuth
callback. Use `--callback-port <port>` to fix the port if your firewall requires
a specific port. The gateway matches any port port-agnostically, so no
pre-registration is needed.
Source: https://code.claude.com/docs/en/mcp (fetched 2026-11-14).

**Do not pass `--client-secret`** — this gateway accepts only public PKCE
clients. Any secret-based `token_endpoint_auth_method` returns
`400 invalid_client_metadata`. Source: `gateway/app.py` `_patch_register_route`.

**What the user sees:** a browser window opens to Google sign-in, then the
gateway's consent page. After approval, the browser shows a "you can close this
window" message; Claude Code resumes with the tokens stored locally.

**Tenant selection:** pass `tenant=<id>` as a tool argument. Claude Code's
schema display shows `tenant` as optional on every tool.

**Revoke:** `claude mcp remove omt-mcp` removes the client configuration.
To invalidate the grant server-side: dashboard → Connected AI apps → Revoke.

**Data retention:** see [Data retention — Anthropic](#data-retention--anthropic).

---

### Gemini CLI

**Registration path:** DCR. Gemini CLI registers itself via `/register` when
it first connects to a new HTTP MCP server. It binds an OS-assigned random
loopback port for the OAuth callback (`http://localhost:<random>/oauth/callback`),
which the gateway matches port-agnostically.
Source: https://raw.githubusercontent.com/google-gemini/gemini-cli/main/docs/tools/mcp-server.md
(fetched 2026-11-14; original path `docs/mcp-servers.md` was 404).

**Setup:** add to `~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "omt-mcp": {
      "httpUrl": "https://tradeagent-mcp.ridopark.com/mcp"
    }
  }
}
```

On the next `gemini` invocation that uses `omt-mcp`, the CLI detects the `401`,
performs DCR, and opens a browser for the OAuth flow.

**Re-authenticating:** run `gemini /mcp auth omt-mcp` (or the equivalent
`--auth` flag; confirm in `gemini --help`).

**Token storage:** Gemini CLI stores OAuth tokens in
`~/.gemini/mcp-oauth-tokens.json`.
Source: https://raw.githubusercontent.com/google-gemini/gemini-cli/main/docs/tools/mcp-server.md
(fetched 2026-11-14).

**Headless limitation:** Gemini CLI's OAuth flow opens a browser. It does not
work over SSH without X11 forwarding or in a container without a browser. If
the system is headless, copy the authorization URL and open it in a browser on
another machine.

**Tenant selection:** pass `tenant=<id>` as a tool argument in the prompt, e.g.
"call `get_portfolio_summary` with `tenant=prod_real`".

**Revoke:** dashboard → Connected AI apps → Revoke.

**Data retention:** see [Data retention — Google](#data-retention--google).

---

### Gemini app (consumer connected apps)

**Registration path:** DCR (preferred; the app falls back to manual credentials
if DCR is not supported).
Source: https://support.google.com/gemini/answer/17209137?hl=en&co=GENIE.Platform%3DDesktop
(fetched 2026-11-14).

**Eligibility requirements (all mandatory):**
- Age 18 or older.
- Personal Google account (not a Workspace / work / school account).
- Located in the United States.
- **Keep Activity** enabled in Google account settings.
- English only (UI and responses).

Source: https://support.google.com/gemini/answer/17209137 (fetched 2026-11-14).

**Setup:**
1. Go to `gemini.google.com` → **Settings** → **Connected Apps**.
2. Under **Custom apps**, choose **Add a custom app**.
3. Enter the endpoint URL: `https://tradeagent-mcp.ridopark.com/mcp`
4. Click **Next**.
5. If the app asks for credentials instead of using DCR: click **Show more**
   next to "Advanced features" and enter the OAuth details manually
   (use the values from the AS metadata document at
   `https://tradeagent-mcp.ridopark.com/.well-known/oauth-authorization-server`).
6. Complete the Google sign-in and consent flow.

Adding is web only; the connected app is then usable in the mobile Gemini app.
Source: https://support.google.com/gemini/answer/17209137 (fetched 2026-11-14).

**Keep Activity caveat:** if Keep Activity is turned off, Google may silently
remove the connected app. If the connection stops working, re-check the Keep
Activity setting and reconnect.
Source: https://support.google.com/gemini/answer/17209137 (fetched 2026-11-14).

**Callback used:** the redirect URI that the Gemini app registers via DCR.
The specific URI is not stated in the Google support page (fetched 2026-11-14);
the operator must verify after enabling. Any HTTPS redirect URI the app
registers will be accepted by the gateway.

**Tenant selection:** pass `tenant=<id>` as a tool argument in the prompt.

**Revoke:** from the Gemini app — Settings → Connected Apps → remove the app.
From the gateway — dashboard → Connected AI apps → Revoke.

**Note on Gemini Enterprise:** Gemini Enterprise uses a completely different
authentication model (static client ID/secret, specific redirect URI
`https://vertexaisearch.cloud.google.com/oauth-redirect`). That path is not
tested against this gateway; the gateway requires public PKCE clients and will
reject any attempt to register a confidential client.
Source: https://docs.cloud.google.com/gemini/enterprise/docs/connectors/custom-mcp-server/set-up-custom-mcp-server
(fetched 2026-11-14).

**Data retention:** see [Data retention — Google](#data-retention--google).

---

### ChatGPT connector

**Registration path:** CIMD (preferred when the AS advertises
`client_id_metadata_document_supported: true`, which this gateway does). ChatGPT
presents its own metadata document as the `client_id`; the typical CIMD URL is
`https://chatgpt.com/oauth/client.json`.
Source: https://developers.openai.com/api/docs/guides/tools-connectors-mcp.md
(fetched 2026-11-14).

**Setup:**
1. Go to `chatgpt.com/plugins` → click the **+** button → **Add custom MCP
   server**.
2. Enter a name and the endpoint URL (including the `/mcp` path):
   `https://tradeagent-mcp.ridopark.com/mcp`
3. Configure authentication. ChatGPT detects OAuth from the AS metadata;
   static credentials are not needed.
4. Accept the terms and **Create as a plugin**.
5. Install the plugin.
6. The first time you invoke a tool, ChatGPT opens a browser for the OAuth
   flow (Google sign-in → consent page).

Source: https://developers.openai.com/api/docs/guides/tools-connectors-mcp.md
(fetched 2026-11-14).

**Callback used:**
- `https://chatgpt.com/connector_platform_oauth_redirect` (when the AS meets
  RFC 9207 issuer requirements).
- `https://chatgpt.com/connector/oauth/{callback_id}` (otherwise).

Both are `https://` URIs with valid hostnames; the gateway's redirect policy
accepts both. Copy the exact URI shown on ChatGPT's connector management page
into your records. Source: https://developers.openai.com/api/docs/guides/tools-connectors-mcp.md
(fetched 2026-11-14).

**`resource` parameter:** ChatGPT appends `resource=<mcp url>` to `/authorize`
and `/token` requests. The gateway accepts this parameter and ignores it
(plan decision D6: the gateway does not store `resource` in V17; tokens are
bound to this server by being opaque lookups). Source: `gateway/app.py`
(`validate_token_resource=False` in `AuthSettings`); plan D6.

**Known limitation:** if ChatGPT insists on a confidential client registration
(sends `token_endpoint_auth_method: client_secret_post` or similar), the gateway
returns `400 {"error":"invalid_client_metadata"}`. This gateway only accepts
public PKCE clients. Report this if it occurs — a code change would be required.
Source: `gateway/app.py` `_patch_register_route`; plan D5.

**Tenant selection:** pass `tenant=<id>` as a tool argument in the prompt.

**Revoke:** from ChatGPT — manage the plugin/connector in settings. From the
gateway — dashboard → Connected AI apps → Revoke.

**Data retention:** see [Data retention — OpenAI](#data-retention--openai).

---

## Data retention

### What leaves the cluster

Every tool call sends the BFF's response payload to the AI model's inference
API. That payload may contain:

- Open positions: OCC contract symbols, quantities, entry and current premiums,
  unrealized P&L, trail/floor status.
- Portfolio summary: equity, cash, open exposure, notional-cap headroom,
  realized P&L, kill-switch state.
- Options-flow and dark-pool summaries for underlyings queried.
- Author text from Discord (labelled `{"untrusted": true, "text": "..."}`) —
  unstructured third-party text.

The gateway's redaction layer removes the following fields before any payload
reaches the AI provider:

- `account_number` — Alpaca account number.
- `broker_order_id` — internal broker order tracking ID.
- `operator_id` — operator email address (removed at all nesting depths,
  including inside JSON strings).

Source: `services/mcp-server/ohmytradeagent_mcp/sanitize.py`; `services/mcp-server/README.md`
§"Redacted from every tool result".

### Data retention — Anthropic

Applies to: Claude web, Claude Desktop, Claude mobile, Claude Code.

Tool results sent to Anthropic's inference API are subject to Anthropic's
privacy policy. API access, Team and Enterprise plans typically exclude prompt
data from training; consumer claude.ai plans may not. Review the current terms
before relying on the connection in a session that contains position or P&L data.

Policy: https://www.anthropic.com/legal/privacy
(source: `services/mcp-server/README.md` §"Data retention").

### Data retention — Google

Applies to: Gemini CLI, Gemini app.

Tool results sent to Google's Gemini inference API are subject to Google's
privacy policy. The applicable terms depend on whether access is through the
Gemini API (Workspace), Gemini Advanced, or the free Gemini app. Review the
current terms for the account type in use.

Policy: https://policies.google.com/privacy
(source: `services/mcp-server/README.md` §"Data retention").

### Data retention — OpenAI

Applies to: ChatGPT connector.

Tool results sent to OpenAI's inference API are subject to OpenAI's privacy
policy. API access and Team/Enterprise plans typically exclude prompt data from
training; consumer ChatGPT plans may differ. Review the current terms.

Policy: https://openai.com/policies/privacy-policy
(source: `services/mcp-server/README.md` §"Data retention").

---

## Troubleshooting summary

| Symptom | Likely cause | Action |
|---|---|---|
| `503 {"error":"disabled"}` on any request | Kill switch on | Operator: `kubectl set env deploy/mcp-server OMT_GATEWAY_DISABLED=false` and update manifest; see runbook |
| `401` with `WWW-Authenticate: Bearer resource_metadata=…` | No bearer; normal for first request | Follow OAuth flow; client should handle automatically |
| `400 {"error":"invalid_client_metadata"}` on registration | Client sent `token_endpoint_auth_method` other than `"none"` | Use a public PKCE client; do not pass a client secret; report as known limitation |
| `302` to `redirect_uri?error=access_denied&error_description=not_a_member` | Google account is not a `dashboard_user` member | Add the Google account to a tenant's `dashboard_user` |
| `421 Invalid Host header` | Request reached the gateway with wrong `Host` | Client should use the endpoint URL exactly as given; check for proxies |
| `429` + `Retry-After` on `/authorize`, `/token`, `/register` | Per-IP rate limit (30 req/min) exceeded | Wait the number of seconds in `Retry-After`, then retry |
| `ToolError: rate_limited: retry after <n> s` | Per-user (60/min) or per-client (300/min) tool-call rate exceeded | Wait `<n>` seconds |
| Token works then stops with `401` | Refresh token reuse detected: entire grant revoked | Reconnect from scratch (the grant is gone server-side) |
| Tools return no data for a tenant | Membership check failed at request time | Confirm the Google account still has `dashboard_user` membership for that tenant |
