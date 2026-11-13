# Runbook — MCP read-only token: mint, wire, verify, revoke

**Audience:** operator (homelab k3s). **Risk:** read-only; a BFF restart briefly removes the
`/live` Trim and Force-exit buttons. **Access:** `ssh ridopark@192.168.10.123`.
**Related:** `services/mcp-server/README.md`, `docs/plans/PLAN-2026-11-13-mcp-server.md`.

This runbook provisions a per-tenant read-only token that lets the MCP server on your workstation
call the BFF's allowlisted routes (`/api/portfolio`, `/api/notional-cap`, `/api/trades`,
`/api/orders`, `/api/floor-breach`, `/api/live/marks`, `/api/signals`, `/api/entries/*`,
`/api/flow/*`, `/api/darkpool/*`, `/api/account-killswitch`). The token cannot trade and cannot
read a different tenant's data.

> **Golden rules**
> 1. The token lives only in `~/.config/omt-mcp/<tenant>.token` (mode 600) on the workstation.
>    Client configs hold the **file path** (`OMT_MCP_TOKEN_FILE=~/.config/omt-mcp/<tenant>.token`),
>    never the raw token value. Rotation = overwrite the file; no config change needed.
> 2. Store the **hash**, not the token, in `dashboard-secrets` (read by the BFF).
> 3. Never commit either value to git. `~/.config/omt-mcp/` is on the workstation only.
> 4. Restart the BFF **outside RTH** when open positions exist — the restart is fast but it
>    momentarily removes the operator buttons (`/live` Trim, Force-exit). Read-only paths
>    (portfolio, positions, etc.) remain up during the roll.

---

## Current state (2026-11-13)

> **Port-forward VERIFIED on live cluster 2026-11-13.** `kubectl -n copytrade port-forward
> svc/tenant-dashboard-bff 18080:8083` → `/api/mcp/whoami` with a valid token returns 200 as
> `prod_real`; forged `X-Tenant-Id` returns 403; POST force-close returns 403;
> `/api/admin/tenants` returns 403; `/api/strategy-config` returns 403; bad token returns 401.
> BFF logs `mcp read tenant=... token_id=<12 hex> method=... path=...` for each call.

> **`BFF_MCP_READ_TOKENS` env present on live Deployment since 2026-11-13.** The env var was
> absent from the manifest (deploy.yml lists the BFF under `RESTART_ONLY` — see §Wire) and was
> added with a JSON patch that appended a `secretKeyRef` entry to the BFF container's env. Any
> future token change needs only a secret patch + rollout restart (the second branch in §Wire).

---

## Mint

Generate a 32-byte hex token and derive its SHA-256 hash.

```bash
# On the workstation — not on the homelab
mkdir -p ~/.config/omt-mcp
chmod 700 ~/.config/omt-mcp

token=$(openssl rand -hex 32)
printf '%s' "$token" > ~/.config/omt-mcp/<tenant>.token
chmod 600 ~/.config/omt-mcp/<tenant>.token

# Derive the hash (no trailing newline — printf, not echo)
hash=$(printf '%s' "$token" | sha256sum | awk '{print $1}')
echo "Hash (store in Kubernetes Secret): $hash"
```

Replace `<tenant>` with the tenant ID (e.g. `prod_real`). The hash value printed here goes into
the next step. The token file stays on the workstation only.

---

## Store

The hash is stored in `dashboard-secrets` under key `BFF_MCP_READ_TOKENS` as a comma-separated
list of `<sha256-hex>:<tenant_id>` entries.

**Patch the secret** (add or update; multiple tokens are comma-separated):

```bash
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p '{"stringData":{"BFF_MCP_READ_TOKENS":"<hash>:prod_real"}}'

# Two tokens (e.g. prod_real + staging_paper):
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p '{"stringData":{"BFF_MCP_READ_TOKENS":"<hash1>:prod_real,<hash2>:staging_paper"}}'
```

**Mirror to `infra/k8s/secrets.local.yaml`** (per `infra/k8s/README.md`): add or update the
`BFF_MCP_READ_TOKENS` line in the `dashboard-secrets` block of your local copy. Files matching
`infra/k8s/*.local.yaml` are git-ignored; that is the correct location for operator secrets
backups. (`infra/secrets-template/secrets.local.yaml` is NOT git-ignored — do not use it.)

---

## Wire

The BFF reads `BFF_MCP_READ_TOKENS` from its container environment. There are two branches:

### Branch 1 — env var already present on the Deployment (the current state as of 2026-11-13)

A secret edit alone does **not** restart the pod, so the BFF must be restarted to pick up the
new hash:

```bash
kubectl -n copytrade rollout restart deployment/tenant-dashboard-bff
kubectl -n copytrade rollout status deployment/tenant-dashboard-bff
```

### Branch 2 — env var absent (first-time wiring on a fresh cluster)

This was done on 2026-11-13 by JSON-patching the Deployment to add a `secretKeyRef` entry to
the first container's env array, then rolling the pod. The equivalent kubectl idiom that creates
a `secretKeyRef` and rolls the pod:

```bash
kubectl -n copytrade set env deployment/tenant-dashboard-bff \
  --from=secret/dashboard-secrets \
  --keys=BFF_MCP_READ_TOKENS
kubectl -n copytrade rollout status deployment/tenant-dashboard-bff
```

Confirm the env is now present before moving on:

```bash
kubectl -n copytrade get deploy tenant-dashboard-bff \
  -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}{"\n"}{end}'
# BFF_MCP_READ_TOKENS should appear in the output
```

### Why deploy.yml does not apply the BFF manifest

`deploy.yml` lists `tenant-dashboard-bff` under `RESTART_ONLY`:

```
RESTART_ONLY="orchestrator dashboard api-gateway signal-source-discord tenant-dashboard-bff"
```

When a PR changes `services/tenant-dashboard-bff/**` or `infra/k8s/58-tenant-dashboard-bff.yaml`,
the pipeline **skips** `kubectl apply -f 58-tenant-dashboard-bff.yaml` and runs only
`kubectl rollout restart`. Applying the manifest would wipe live-only operator overrides
(`OPERATOR_ADMIN_READ_ENABLED`, `OPERATOR_ALLOWLIST`), so the manifest documents *intent* and is
never applied by the pipeline. Token wiring is therefore a manual operator step per this runbook.

---

## Verify

**Check the BFF log** for a clean load (no parse error):

```bash
kubectl -n copytrade logs deploy/tenant-dashboard-bff | grep -E 'mcp-read-tokens|Malformed'
# No "Malformed bff.mcp-read-tokens" line = loaded correctly.
# A successful call looks like:
#   mcp read tenant=prod_real token_id=<12 hex> method=GET path=/api/portfolio
```

**Verify the token reaches the BFF** via port-forward:

```bash
kubectl -n copytrade port-forward svc/tenant-dashboard-bff 18080:8083 &
PF_PID=$!

curl -s \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/prod_real.token)" \
  http://127.0.0.1:18080/api/mcp/whoami
# Expected: {"tenant_id":"prod_real","token_id":"<12 hex>"}

kill $PF_PID
```

**Negative checks** (verify the boundary):

```bash
# Bad token → 401
curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer not-a-real-token" \
  http://127.0.0.1:18080/api/mcp/whoami
# Expected: 401

# MCP token cannot read strategy-config → 403
curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/prod_real.token)" \
  -H "X-Tenant-Id: prod_real" \
  http://127.0.0.1:18080/api/strategy-config
# Expected: 403
```

---

## Revoke

Remove **only** the target tenant's hash from `BFF_MCP_READ_TOKENS`, then restart.

**If this is the only entry** (the value becomes empty):

```bash
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p '{"stringData":{"BFF_MCP_READ_TOKENS":""}}'
```

**If other tenant entries remain** (replace the value with everything except the revoked entry;
comma-separated, no trailing comma):

```bash
# Example: BFF_MCP_READ_TOKENS was "<hash1>:prod_real,<hash2>:staging_paper"
# After revoking prod_real, set it to just the remaining entries:
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p '{"stringData":{"BFF_MCP_READ_TOKENS":"<hash2>:staging_paper"}}'
```

Then restart to pick up the change:

```bash
kubectl -n copytrade rollout restart deployment/tenant-dashboard-bff
kubectl -n copytrade rollout status deployment/tenant-dashboard-bff
```

Confirm revocation: `curl … /api/mcp/whoami` with the old token → `401`.

Delete the token file from the workstation:

```bash
rm ~/.config/omt-mcp/<tenant>.token
```

---

## Rotate

Add the new hash first (old + new both valid during the transition), verify the new token works,
then remove the old hash and restart.

```bash
# 1. Mint new token
new_token=$(openssl rand -hex 32)
printf '%s' "$new_token" > ~/.config/omt-mcp/<tenant>.token.new
chmod 600 ~/.config/omt-mcp/<tenant>.token.new
new_hash=$(printf '%s' "$new_token" | sha256sum | awk '{print $1}')

# 2. Patch secret with both hashes (old_hash:tenant,new_hash:tenant)
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p "{\"stringData\":{\"BFF_MCP_READ_TOKENS\":\"<old_hash>:<tenant>,${new_hash}:<tenant>\"}}"
kubectl -n copytrade rollout restart deployment/tenant-dashboard-bff
kubectl -n copytrade rollout status deployment/tenant-dashboard-bff

# 3. Verify new token
curl -s \
  -H "Authorization: Bearer $(cat ~/.config/omt-mcp/<tenant>.token.new)" \
  http://127.0.0.1:18080/api/mcp/whoami

# 4. Replace old token file, remove the OLD entry from the BFF secret (keep only the new hash)
mv ~/.config/omt-mcp/<tenant>.token.new ~/.config/omt-mcp/<tenant>.token
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p "{\"stringData\":{\"BFF_MCP_READ_TOKENS\":\"${new_hash}:<tenant>\"}}"
kubectl -n copytrade rollout restart deployment/tenant-dashboard-bff
```

---

## Blast radius

A BFF restart:

- **Does not affect trading.** The BFF is read-only. The orchestrator, exec-alpaca-live/paper,
  and the fill listener are unaffected.
- **Briefly removes the `/live` operator buttons** (Trim, Force-exit, Arm-trail, Deactivate).
  The dashboard shows stale data for a few seconds while the pod rolls. Do this outside RTH when
  you have open positions, or accept the brief loss of the manual controls.
- **Does not roll any other pod.** Only `deployment/tenant-dashboard-bff` is restarted.
