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

## Order of operations

The steps below are in the order to run them. Nothing is reachable from the internet until
Step 7. Stopping after any step is safe: Steps 1–6 change nothing that is reachable from outside, and the gateway
answers 503 on everything except `/healthz` until Step 4.

## Prerequisites

- [ ] PRs 1–4 of #1060 merged and rolled (BFF credential class, V17/V18 on the dashboard DB,
      gateway code, Connected AI apps page). Check: `mcp_gateway` exists and is `NOLOGIN`:
      `select rolcanlogin from pg_roles where rolname = 'mcp_gateway'` → `f`.
- [ ] The workflow PR (deploy.yml / build-images.yml) merged, **and its build has pushed
      `ghcr.io/ridopark/oh-my-tradeagent-mcp-server:latest`**. Without the image the first apply
      sits in `ImagePullBackOff`. This is the first time the image is built.
- [ ] Outside regular trading hours: Step 3 restarts the BFF and the dashboard, which briefly
      removes the `/live` operator buttons.

---

## Step 1 — Keys and secrets

Run this whole block **on the homelab** (`ssh ridopark@192.168.10.123`): it needs `kubectl`,
`openssl` and `jq`, which are all there (the workstation has no `kubectl`). It generates the
Ed25519 key pair and the DB password and adds them to `dashboard-secrets` **from files** (a
multi-line PEM cannot be pasted into a JSON patch):

```bash
umask 077; mkdir -p ~/omt-mcp-gateway-keys && cd ~/omt-mcp-gateway-keys

openssl genpkey -algorithm ed25519 -out gateway.pem
# Public key for the BFF: base64 of the SPKI DER (GNU base64; on macOS use `base64 | tr -d '\n'`)
openssl pkey -in gateway.pem -pubout -outform DER | base64 -w0 > gateway.pub.b64
# Password for the mcp_gateway DB role
openssl rand -base64 32 | tr -d '\n' > db-password.txt

# Build the patch with kubectl itself, so the PEM's newlines are encoded correctly:
kubectl create secret generic dashboard-secrets --dry-run=client -o json \
  --from-file=MCP_GATEWAY_SIGNING_KEY=gateway.pem \
  --from-file=MCP_GATEWAY_DB_PASSWORD=db-password.txt \
  --from-file=BFF_MCP_DELEGATED_PUBLIC_KEY=gateway.pub.b64 \
  | jq '{data: .data}' > patch.json
kubectl -n copytrade patch secret dashboard-secrets --type merge --patch-file patch.json

# Confirm the three keys exist (names only):
kubectl -n copytrade get secret dashboard-secrets -o json | jq -r '.data | keys[]' | grep -E 'MCP_GATEWAY|BFF_MCP_DELEGATED'
```

`--type merge` with only `.data` adds the three keys and leaves every existing key untouched.
Keep an offline copy of the three files somewhere that is **not** in the repo
(`infra/secrets-template/secrets.local.yaml` is NOT git-ignored; only `infra/k8s/*.local.yaml`
is), then delete `~/omt-mcp-gateway-keys`.

---

## Step 2 — First apply (gateway stays dark)

```bash
kubectl -n copytrade apply -f infra/k8s/68-mcp-server.yaml
kubectl -n copytrade rollout status deployment/mcp-server --timeout=120s
kubectl -n copytrade get pods -l app=mcp-server
```

The pod boots with `OMT_GATEWAY_DISABLED=true`: no DB connection, every route except `/healthz`
answers 503, and the readiness probe (`/healthz`) passes. The signing key is required even in
this mode:

- `CreateContainerConfigError` → `MCP_GATEWAY_SIGNING_KEY` is not in `dashboard-secrets` (Step 1).
- `CrashLoopBackOff` with `error: invalid signing key: ...` in
  `kubectl -n copytrade logs deployment/mcp-server --previous` → the PEM is malformed.
- `ImagePullBackOff` → the image has not been built yet (Prerequisites).

---

## Step 3 — Enable the BFF and the dashboard page

Both manifests are `RESTART_ONLY` in `deploy.yml` (CI never applies them), so these are live
`kubectl set env` steps. Run outside RTH.

```bash
# BFF: accept the gateway's signed assertions and read dashboard memberships.
# All three in ONE command, so the BFF restarts once. The public key is referenced from the
# secret (secretKeyRef), not copied in as a literal.
kubectl -n copytrade set env deployment/tenant-dashboard-bff \
  --from=secret/dashboard-secrets --keys=BFF_MCP_DELEGATED_PUBLIC_KEY \
  BFF_MCP_DELEGATED_ENABLED=true \
  DASHBOARD_READONLY_URL=jdbc:postgresql://postgres:5432/dashboard
# Check the key really landed (--keys silently adds nothing if the secret lacks the key):
kubectl -n copytrade get deployment tenant-dashboard-bff -o json \
  | jq -r '.spec.template.spec.containers[0].env[].name' | grep BFF_MCP_DELEGATED
kubectl -n copytrade rollout status deployment/tenant-dashboard-bff --timeout=180s
kubectl -n copytrade exec deployment/tenant-dashboard-bff -c tenant-dashboard-bff -- \
  curl -sf http://localhost:8083/actuator/health | grep -o '"status":"UP"'
```

`DASHBOARD_READONLY_URL` is **required**: without it the membership check connects to
`localhost`, fails closed, and every MCP tool call is refused with 403 as if the user were not a
member. `DASHBOARD_READONLY_PASSWORD` and `DASHBOARD_WRITER_ENABLED=true` are already on the
live BFF (the grant revoke route needs the writer flag).

```bash
# Dashboard: show the "Connected AI apps" page and Nav link.
kubectl -n copytrade set env deployment/dashboard MCP_CONNECTED_APPS_ENABLED=true
kubectl -n copytrade rollout status deployment/dashboard --timeout=180s
```

Members who signed in before #1065 see "sign out and back in" on that page once; their session
then carries the identity the page needs.

---

## Step 4 — Enable the gateway

**4a — give the DB role its password** (`mcp_gateway` is `NOLOGIN` since V18). `\password`
prompts for it and sends only the SCRAM hash, so the plaintext is never in a SQL statement, the
server log or a shell history:

```bash
kubectl -n copytrade get secret dashboard-secrets -o jsonpath='{.data.MCP_GATEWAY_DB_PASSWORD}' | base64 -d; echo
# copy the value, then:
kubectl -n copytrade exec -it statefulset/postgres -- sh -c 'psql -U "$POSTGRES_USER" -d dashboard'
#   \password mcp_gateway        (paste the value twice)
#   ALTER ROLE mcp_gateway LOGIN;
#   \q
```

**4b — flip the kill switch.** Only this one variable: the password and the other secrets are
already wired from `dashboard-secrets` by the manifest. Never pass a secret to `kubectl set env`
as a literal — it would be stored in the Deployment spec, readable by anything that can read
Deployments, and echoed by the k8s drift check.

```bash
kubectl -n copytrade set env deployment/mcp-server OMT_GATEWAY_DISABLED=false
kubectl -n copytrade rollout status deployment/mcp-server --timeout=120s
```

If the pod exits with `error: DB connection failed …`, the role password (4a) and the secret
disagree, or the role is still `NOLOGIN`.

**4c — make it permanent.** `mcp-server` is applied by CI, so the next roll would re-darken it.
Change `OMT_GATEWAY_DISABLED` to `"false"` in `infra/k8s/68-mcp-server.yaml` and merge that PR.

---

## Step 5 — Verify in-cluster (before anything is public)

```bash
kubectl -n copytrade port-forward svc/mcp-server 18000:8000 &
H='Host: tradeagent-mcp.ridopark.com'

curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18000/healthz          # 200
curl -s -w ' %{http_code}\n' http://127.0.0.1:18000/readyz                       # {"status":"ok"} 200
curl -s -H "$H" http://127.0.0.1:18000/.well-known/oauth-authorization-server | jq \
  '.issuer, .token_endpoint_auth_methods_supported, .code_challenge_methods_supported, .client_id_metadata_document_supported'
#   "https://tradeagent-mcp.ridopark.com/"  ["none"]  ["S256"]  true
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -X POST http://127.0.0.1:18000/mcp \
  -H 'content-type: application/json' -d '{}'                                    # 401
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18000/mcp -X POST -d '{}'   # 421 (wrong Host)
kill %1
```

`/readyz` 503 `store_unhealthy` means the gateway cannot reach Postgres as `mcp_gateway`.

---

## Step 6 — Google

Add the gateway's callback URI to the **existing** Google OAuth client (the one the dashboard
uses; `AUTH_GOOGLE_ID` / `AUTH_GOOGLE_SECRET` in `dashboard-secrets`):

1. Open [Google Cloud Console → APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials).
2. Open the OAuth 2.0 client the dashboard uses.
3. Under **Authorised redirect URIs** add
   `https://tradeagent-mcp.ridopark.com/oauth/google/callback`.
4. Save. Google says changes can take from a few minutes to a few hours to apply.

Users see the same app name and consent screen as for the dashboard.

**Rollback:** remove the URI. New sign-ins fail; existing grants keep working until revoked.

---

## Step 7 — Cloudflare (this makes the endpoint public)

Do this LAST, after Step 5 verified the gateway in-cluster and Step 6 is saved. Until the DNS record and the tunnel
ingress rule both exist, nothing is reachable from the internet.

All Cloudflare steps are out-of-band via the Cloudflare API or dashboard.
The homelab-dashboard tunnel already exists (`60-cloudflared.yaml`). Do NOT add the MCP gateway
to the dashboard's Cloudflare Access application — it manages its own OAuth.

### WAF and rate-limit rules

Add the rate-limit rules FIRST, before the DNS record and ingress rule below, so the hostname is
never public without them (Cloudflare dashboard → Security → WAF). Available periods and block durations
depend on the Cloudflare plan; use the closest your plan allows:

**Rate-limit rule — OAuth endpoints** (complement the per-IP gateway limit of 30 req/min):
- Expression: `(http.host eq "tradeagent-mcp.ridopark.com") and (http.request.uri.path in {"/authorize" "/token" "/register" "/revoke" "/oauth/google/callback"})`
- Action: Rate limit — 120 requests per minute per IP, block for 60 seconds.

**Rate-limit rule — /mcp** (complement the per-subject/per-client gateway limits):
- Expression: `(http.host eq "tradeagent-mcp.ridopark.com") and (http.request.uri.path eq "/mcp")`
- Action: Rate limit — 600 requests per minute per IP, block for 10 seconds.

No IP allowlist — AI cloud providers use dynamic egress IPs.

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

Replace `<tunnel-id>` with the UUID of the `homelab-dashboard` tunnel (listed on the Cloudflare
Tunnels page; the tunnel token itself lives in the `cloudflared-token` Secret, not in the repo).

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

---

## Step 8 — Verify from the internet

```bash
curl -sf https://tradeagent-mcp.ridopark.com/healthz                              # {"status":"ok"}
curl -sf https://tradeagent-mcp.ridopark.com/readyz                               # {"status":"ok"}
curl -sf https://tradeagent-mcp.ridopark.com/.well-known/oauth-protected-resource/mcp | jq .authorization_servers
#   ["https://tradeagent-mcp.ridopark.com/"]   (byte-for-byte the AS metadata "issuer")
curl -s -o /dev/null -D - -X POST https://tradeagent-mcp.ridopark.com/mcp \
  -H 'content-type: application/json' -d '{}' | grep -iE '^HTTP|www-authenticate'
#   HTTP/2 401 … www-authenticate: Bearer … resource_metadata="…/.well-known/oauth-protected-resource/mcp"
```

Then connect one real client and run a tool; the per-client procedure is in the client
compatibility runbook (PR 6 of #1060).

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
   Also hide the dashboard page, which would otherwise show errors:
   `kubectl -n copytrade set env deployment/dashboard MCP_CONNECTED_APPS_ENABLED=false`.
3. **Remove Cloudflare ingress rule** (§Kill switch level 2).
4. **Revoke existing grants** (optional, if re-enabling is not planned):
   Existing `mcp_oauth_grant` rows with `revoked_at IS NULL` remain in the DB. They cannot be
   used because the gateway is disabled; active access tokens expire within 1 hour. Soft-revoke
   all grants via psql if a clean slate is needed:
   ```sql
   UPDATE mcp_oauth_grant SET revoked_at = now() WHERE revoked_at IS NULL;
   ```
