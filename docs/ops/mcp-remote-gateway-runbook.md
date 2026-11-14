# Runbook — Remote MCP gateway: enable, verify, kill switch, rollback

**Audience:** operator (homelab k3s). **Risk:** public read-only endpoint; no trading path is
touched. **Access:** `ssh ridopark@192.168.10.123`.
**Related:** `docs/plans/PLAN-2026-11-13-mcp-remote-oauth.md`, `services/mcp-server/README.md`,
`infra/k8s/68-mcp-server.yaml`.

This runbook enables the remote MCP gateway for a tenant member who has added
`https://tradeagent-mcp.ridopark.com/mcp` to their AI app. The endpoint is read-only. The gateway
issues OAuth 2.1 access tokens after a Google sign-in, and every tool call becomes a BFF read
carried by a 60-second delegated EdDSA assertion. Nothing here can place, change, or cancel orders.

> **Blast radius:** a gateway pod restart has no effect on the trading path. The BFF restarts
> (needed to enable the delegated-assertion credential class) briefly remove the `/live` Trim and
> Force-exit buttons. Schedule BFF restarts outside RTH when open positions exist.

> **Known gaps (as of PR 5):**
> - The gateway image has never been built from CI — the first build triggers when the workflow
>   PR (the sibling of this PR) merges and its build job runs green.
> - Expired `pending_auth`, `code`, and token rows are not swept automatically yet (they expire
>   on use; a periodic sweep is a follow-up).
> - The live client-compat matrix (which AI clients tested end-to-end) is in PR 6 (`docs/ops/mcp-client-compat.md`).

---

## Prerequisites

Before starting, confirm all of the following are merged and deployed:

- [ ] PR 1 (BFF delegated-assertion credential class) merged.
- [ ] PR 2 (dashboard-DB migrations, `mcp_gateway` role) merged and BFF rolled (Flyway V17/V18 applied).
- [ ] PR 3 (gateway Python package + Dockerfile) merged and image built in CI.
- [ ] PR 4 (Connected AI apps dashboard page + BFF grants routes) merged.
- [ ] PR 5 (this manifest) applied by hand (see §First apply below).
- [ ] Workflow PR (deploy.yml / build-images.yml additions) merged — CI will now build and roll
      `mcp-server` automatically on future merges.
- [ ] `MCP_GATEWAY_DB_PASSWORD` is NOT yet required pre-merge (D1): `mcp_gateway` is created
      `NOLOGIN` in V18; you set the password at enablement below (§Enable gateway).

---

## Keys

Generate the Ed25519 key pair once per cluster. The private key signs BFF assertions; the public
key is verified by the BFF.

```bash
# On the workstation — not on the homelab
openssl genpkey -algorithm ed25519 -out gateway.pem
chmod 600 gateway.pem

# Derive the public key (base64 SPKI DER):
openssl pkey -in gateway.pem -pubout -outform DER | base64 -w0
# → paste as BFF_MCP_DELEGATED_PUBLIC_KEY

# Full private key PEM (multi-line; paste the whole block):
cat gateway.pem
# → paste as MCP_GATEWAY_SIGNING_KEY

# Strong random password for the mcp_gateway DB role:
openssl rand -base64 32
# → paste as MCP_GATEWAY_DB_PASSWORD
```

Add all three values to `dashboard-secrets`:

```bash
# On the homelab (or via ssh)
kubectl -n copytrade patch secret dashboard-secrets --type merge -p "$(cat <<'EOF'
{
  "stringData": {
    "MCP_GATEWAY_SIGNING_KEY": "<paste full PEM including headers>",
    "MCP_GATEWAY_DB_PASSWORD": "<paste random password>",
    "BFF_MCP_DELEGATED_PUBLIC_KEY": "<paste base64 SPKI DER>"
  }
}
EOF
)"
```

Mirror all three values to `infra/secrets-template/secrets.local.yaml` (git-ignored; your offline
backup). Delete `gateway.pem` from the workstation once both values are stored.

---

## Google

Add the gateway's callback URI to the **existing** Google OAuth client (the one the dashboard
already uses; `AUTH_GOOGLE_ID` / `AUTH_GOOGLE_SECRET` in `dashboard-secrets`):

1. Open [Google Cloud Console → APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials).
2. Click the OAuth 2.0 client the dashboard uses.
3. Under **Authorised redirect URIs** add:
   ```
   https://tradeagent-mcp.ridopark.com/oauth/google/callback
   ```
4. Save. Changes take effect immediately (no propagation delay for redirect URIs).

The gateway shares the dashboard's Google app name and consent screen. Users will see the same
app name when they authorise the MCP gateway as when they sign in to the dashboard.

**Rollback:** remove the URI from the Google console. Existing tokens remain valid until they
expire or are revoked; new sign-ins are blocked.

---

## Cloudflare

All Cloudflare steps are out-of-band via the Cloudflare API or dashboard.
The homelab-dashboard tunnel already exists (`60-cloudflared.yaml`). Do NOT add the MCP gateway
to the dashboard's Cloudflare Access application — it manages its own OAuth.

### DNS

Create a proxied CNAME under the same apex as the dashboard. Universal SSL covers one additional
level, so `tradeagent-mcp.ridopark.com` is valid without an Advanced certificate:

```bash
# Using the CF API (replace ZONE_ID and CF_TOKEN):
curl -s -X POST "https://api.cloudflare.com/client/v4/zones/${ZONE_ID}/dns_records" \
  -H "Authorization: Bearer ${CF_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "CNAME",
    "name": "tradeagent-mcp",
    "content": "<tunnel-id>.cfargotunnel.com",
    "ttl": 1,
    "proxied": true
  }'
```

Replace `<tunnel-id>` with the UUID of the `homelab-dashboard` tunnel (shown in
`60-cloudflared.yaml`'s TUNNEL_TOKEN value or the CF Tunnels page).

**Rollback:** delete the DNS record. The gateway becomes unreachable immediately.

### Tunnel ingress rule

Add an ingress rule to the existing `homelab-dashboard` tunnel so the gateway's hostname routes
to the in-cluster service:

```bash
# Via the CF API (Tunnel Config PATCH):
# GET current config first, then PATCH with the new ingress rule prepended:
#   {
#     "hostname": "tradeagent-mcp.ridopark.com",
#     "service": "http://mcp-server.copytrade.svc.cluster.local:8000"
#   }
# Place it BEFORE the catch-all rule that routes the dashboard.
```

Or use the Cloudflare dashboard: Zero Trust → Networks → Tunnels → homelab-dashboard →
Configure → Public Hostname → Add a public hostname:
- Subdomain: `tradeagent-mcp`  
- Domain: `ridopark.com`  
- Service: `HTTP`, `mcp-server.copytrade.svc.cluster.local:8000`

**Rollback:** remove the ingress rule. Existing active connections drop; new connections return a
CF error page (not a 503 from the gateway).

### WAF and rate-limit rules

Add two rules to the zone (Cloudflare dashboard → Security → WAF):

**Rate-limit rule — OAuth endpoints** (complement the per-IP gateway limit of 30 req/min):
- Expression: `(http.host eq "tradeagent-mcp.ridopark.com") and (http.request.uri.path in {"/authorize" "/token" "/register" "/revoke" "/oauth/google/callback"})`
- Action: Rate limit — 120 requests per minute per IP, block for 60 seconds.

**Rate-limit rule — /mcp** (complement the per-subject/per-client gateway limits):
- Expression: `(http.host eq "tradeagent-mcp.ridopark.com") and (http.request.uri.path eq "/mcp")`
- Action: Rate limit — 600 requests per minute per IP, block for 10 seconds.

No IP allowlist — AI cloud providers use dynamic egress IPs.

---

## First apply

After provisioning the keys (§Keys) and before Cloudflare is wired:

```bash
# From the homelab or with KUBECONFIG set:
kubectl -n copytrade apply -f infra/k8s/68-mcp-server.yaml
kubectl -n copytrade rollout status deployment/mcp-server --timeout=60s
kubectl -n copytrade get pods -l app=mcp-server
```

The pod boots with `OMT_GATEWAY_DISABLED=true`. All routes except `/healthz` return 503. The
readiness probe (`/healthz`) passes as soon as the process is up — the pod becomes Ready.

If the pod is in CrashLoopBackOff, the most likely cause is a missing or malformed
`MCP_GATEWAY_SIGNING_KEY` in `dashboard-secrets`. Check:

```bash
kubectl -n copytrade logs deployment/mcp-server --previous 2>&1 | tail -5
# Expected output: "error: Required env var(s) missing: MCP_GATEWAY_SIGNING_KEY"
# or: "error: invalid signing key: ..."
```

---

## Enable BFF

Enable the BFF's delegated-assertion credential class. The BFF manifest is in `RESTART_ONLY` in
`deploy.yml` — this is a manual `kubectl set env` step (live-only override).

```bash
# Set both env vars in one call; the BFF restarts once.
kubectl -n copytrade set env deployment/tenant-dashboard-bff \
  BFF_MCP_DELEGATED_ENABLED=true \
  BFF_MCP_DELEGATED_PUBLIC_KEY="$(kubectl -n copytrade get secret dashboard-secrets \
    -o jsonpath='{.data.BFF_MCP_DELEGATED_PUBLIC_KEY}' | base64 -d)"

# Wait for rollout:
kubectl -n copytrade rollout status deployment/tenant-dashboard-bff --timeout=120s

# Verify:
kubectl -n copytrade exec deployment/tenant-dashboard-bff -- \
  curl -sf http://localhost:8083/actuator/health | grep -o '"status":"UP"'
```

**Outside RTH only** (the restart briefly removes the `/live` operator buttons).

---

## Enable gateway

The gateway ships with `OMT_GATEWAY_DISABLED=true`. To enable:

**Step 1 — set the DB role password** (mcp_gateway was created NOLOGIN in V18):

```bash
# Interactive psql — avoids quoting the password in a shell command:
kubectl -n copytrade exec -it statefulset/postgres -- \
  sh -c 'psql -U "$POSTGRES_USER" -d dashboard'
# At the psql prompt:
#   ALTER ROLE mcp_gateway LOGIN PASSWORD '<MCP_GATEWAY_DB_PASSWORD value>';
#   \q
```

**Step 2 — flip the kill switch** (and edit the manifest so the next CI roll keeps it enabled):

```bash
kubectl -n copytrade set env deployment/mcp-server \
  OMT_GATEWAY_DISABLED=false \
  MCP_GATEWAY_DB_PASSWORD="$(kubectl -n copytrade get secret dashboard-secrets \
    -o jsonpath='{.data.MCP_GATEWAY_DB_PASSWORD}' | base64 -d)"

kubectl -n copytrade rollout status deployment/mcp-server --timeout=60s
```

**Step 3 — update the manifest** so a future CI roll does not re-darken:

```bash
# In infra/k8s/68-mcp-server.yaml, change:
#   - name: OMT_GATEWAY_DISABLED
#     value: "true"
# to:
#   - name: OMT_GATEWAY_DISABLED
#     value: "false"
# Commit and merge (the workflow PR must have landed first).
```

---

## Verify

Run after both Enable steps above and after Cloudflare is wired.

```bash
# 1. Health and readiness (from the homelab or port-forward):
curl -sf https://tradeagent-mcp.ridopark.com/healthz
# Expected: {"status":"ok"}

curl -sf https://tradeagent-mcp.ridopark.com/readyz
# Expected: {"status":"ok"}  (503 if DB is unreachable)

# 2. Protected-resource metadata (MCP clients discover this on 401):
curl -sf https://tradeagent-mcp.ridopark.com/.well-known/oauth-protected-resource/mcp | python3 -m json.tool
# Expected: "authorization_servers": ["https://tradeagent-mcp.ridopark.com"]

# 3. AS metadata (clients discover this next):
curl -sf https://tradeagent-mcp.ridopark.com/.well-known/oauth-authorization-server | python3 -m json.tool
# Expected fields: "issuer", "authorization_endpoint", "token_endpoint",
#                  "registration_endpoint", "revocation_endpoint",
#                  "code_challenge_methods_supported": ["S256"],
#                  "client_id_metadata_document_supported": true,
#                  "token_endpoint_auth_methods_supported": ["none"]

# 4. /mcp without a bearer — expect 401 with WWW-Authenticate:
curl -si https://tradeagent-mcp.ridopark.com/mcp \
  -H "Content-Type: application/json" -d '{}' | head -10
# Expected: HTTP/2 401
#           www-authenticate: Bearer resource_metadata="https://tradeagent-mcp.ridopark.com/.well-known/oauth-protected-resource/mcp"

# 5. First sign-in (manual): add https://tradeagent-mcp.ridopark.com/mcp to Claude Code
#    (see docs/ops/mcp-client-compat.md when available), complete the OAuth flow, and
#    confirm the BFF log shows:
#    mcp read tenant=<tenant> token_id=dlg-<12hex> client_id=<...> method=POST path=/mcp
```

---

## Kill switch

### Level 1 — fast (503 within one pod restart, ~5 s):

```bash
kubectl -n copytrade set env deployment/mcp-server OMT_GATEWAY_DISABLED=true
kubectl -n copytrade rollout status deployment/mcp-server --timeout=60s
# Verify:
curl -sf https://tradeagent-mcp.ridopark.com/healthz   # still 200 (process up)
curl -si https://tradeagent-mcp.ridopark.com/mcp -H "Content-Type: application/json" -d '{}' | head -3
# Expected: HTTP/2 503
```

Then edit `infra/k8s/68-mcp-server.yaml` and commit to prevent a future CI roll from re-enabling.

### Level 2 — hard cut (immediate, no 503 — CF returns an error page):

Remove the tunnel ingress rule for `tradeagent-mcp.ridopark.com` from the Cloudflare tunnel
configuration (Cloudflare dashboard → Zero Trust → Networks → Tunnels → homelab-dashboard →
Configure → delete the hostname rule).

---

## Rollback

To fully roll back after enablement:

1. **Kill switch level 1** (§Kill switch).
2. **Disable BFF delegated-assertion class**:
   ```bash
   kubectl -n copytrade set env deployment/tenant-dashboard-bff BFF_MCP_DELEGATED_ENABLED=false
   kubectl -n copytrade rollout status deployment/tenant-dashboard-bff --timeout=120s
   ```
   (Outside RTH — brief removal of `/live` operator buttons.)
3. **Remove Cloudflare ingress rule** (§Kill switch level 2).
4. **Revoke existing grants** (optional, if re-enabling is not planned):
   Existing `mcp_oauth_grant` rows with `revoked_at IS NULL` remain in the DB. They cannot be
   used because the gateway is disabled; active access tokens expire within 1 hour. Soft-revoke
   all grants via psql if a clean slate is needed:
   ```sql
   UPDATE mcp_oauth_grant SET revoked_at = now() WHERE revoked_at IS NULL;
   ```
