# Runbook — public demo: bring up, verify, publish, take down

**Audience:** operator (homelab k3s). **Access:** `ssh ridopark@192.168.10.123`.
**Plan:** `docs/plans/PLAN-2026-11-26-public-staging-preview.md`.
**Manifest:** `infra/k8s/69-demo.yaml` (applied by hand; `deploy.yml` does not touch it).

The demo is a login-free, read-only view of the **paper** tenant `staging_paper` at
`https://tradeagent-demo.ridopark.com`. One pod: `web` (unprivileged nginx, static files, no
credentials) and `poller` (holds the preview token, calls the BFF, writes the files `web` serves).

> **Golden rules**
> 1. The preview token is bound to `staging_paper` and must be a **freshly minted** secret. Never
>    reuse the BFF shared token, the ingest token, or an MCP token: the BFF refuses to boot if a
>    read-token hash matches one of them, and a hash listed twice also fails boot.
> 2. Store the **hash** in `dashboard-secrets`; the token itself lives only in the
>    `demo-preview-token` secret.
> 3. Steps that restart the BFF happen **outside market hours**.
> 4. Nothing is public until the Cloudflare step. Every step before it can be undone quietly.

---

## Off switch (memorise this one)

```bash
kubectl -n copytrade scale deploy/demo --replicas=0     # site answers 502/1033 at Cloudflare
```

To cut the data without taking the site down (pages go "stale" within 30 s and stay up):

```bash
kubectl -n copytrade delete networkpolicy tenant-dashboard-bff-allow-demo
```

To revoke the credential (needs a BFF restart, so outside market hours): remove the preview entry
from `BFF_PREVIEW_READ_TOKENS` in `dashboard-secrets` and `kubectl -n copytrade rollout restart
deploy/tenant-dashboard-bff`.

---

## 1. Prerequisites

- [ ] Images exist: `ghcr.io/ridopark/oh-my-tradeagent-demo-web:latest` and `-demo-poller:latest`
      (built by "Build and push images" on `main`).
- [ ] The BFF runs the build that contains `/api/preview/*` **and** the second security review
      fixes (#1081). Check: `kubectl -n copytrade get pod -l app=tenant-dashboard-bff` is newer
      than that merge's deploy.
- [ ] The BFF's unauthenticated `/actuator/prometheus` no longer exposes per-route request
      counters to pods that can reach port 8083 (see the plan, "must be settled before"). Check
      from the dashboard pod: the output must contain no `uri="/api/positions` line.
      ```bash
      kubectl -n copytrade exec deploy/dashboard -- \
        wget -q -O- http://tenant-dashboard-bff:8083/actuator/prometheus | grep -c 'uri="/api/'
      ```

## 2. Mint the preview token

```bash
token=$(openssl rand -hex 32)
hash=$(printf '%s' "$token" | sha256sum | awk '{print $1}')

# The token: only in this secret.
kubectl -n copytrade create secret generic demo-preview-token --from-literal=token="$token"

# The hash: appended to the BFF's preview list (same format as BFF_MCP_READ_TOKENS).
kubectl -n copytrade patch secret dashboard-secrets --type merge \
  -p "{\"stringData\":{\"BFF_PREVIEW_READ_TOKENS\":\"${hash}:staging_paper\"}}"
unset token hash
```

Mirror the `BFF_PREVIEW_READ_TOKENS` line into your local `infra/k8s/secrets.local.yaml` (never
committed), as for the MCP token.

## 3. Wire the hash into the BFF (first time only; restarts the BFF)

The BFF manifest is `RESTART_ONLY` in `deploy.yml`, so the env entry is added to the **live**
Deployment with a patch, exactly as `BFF_MCP_READ_TOKENS` was (see `mcp-read-token-runbook.md`
§Wire):

```bash
kubectl -n copytrade patch deploy tenant-dashboard-bff --type json -p '[
  {"op":"add","path":"/spec/template/spec/containers/0/env/-","value":
    {"name":"BFF_PREVIEW_READ_TOKENS","valueFrom":{"secretKeyRef":
      {"name":"dashboard-secrets","key":"BFF_PREVIEW_READ_TOKENS","optional":true}}}}]'
kubectl -n copytrade rollout status deploy/tenant-dashboard-bff
```

If the pod does not become Ready, read its log first: a malformed entry, a duplicate hash, or a
hash equal to another credential's is a deliberate boot failure with a one-line reason. Roll back
with `kubectl -n copytrade rollout undo deploy/tenant-dashboard-bff`.

## 4. Start the pod (still dark)

```bash
kubectl apply -f infra/k8s/69-demo.yaml
kubectl -n copytrade rollout status deploy/demo
kubectl -n copytrade logs deploy/demo -c poller --tail=20
```

Expected poller log at this point: every feed `not published (fetch-...)`. The pod cannot reach
the BFF yet; every page says "waiting for data". That is correct.

## 5. Let the poller reach the BFF

Uncomment the `tenant-dashboard-bff-allow-demo` NetworkPolicy at the bottom of
`infra/k8s/69-demo.yaml` and apply the file again. Within a few seconds:

```
status: publishing
fills: publishing
orders: publishing
marks: publishing
portfolio: publishing
```

A line like `orders: not published (unknown-key)` means the poller's own gate refused a response.
**Do not work around it.** The rule name says which check failed; the BFF projection and
`demo/poller/check.mjs` have diverged and one of them is wrong.

## 6. Verify before anything is public

```bash
kubectl -n copytrade port-forward deploy/demo 8080:8080 &
DEMO_FORBIDDEN='<paper account number>,<each whitelisted author name>' \
  node scripts/ops/demo_verify.mjs http://127.0.0.1:8080
```

It must end with `ALL CHECKS PASSED`. It checks, from the outside: the five pages and their
security headers; that every non-GET method is refused; that 28 paths outside the site (traversal,
dotfiles, the BFF's own routes) are not served; that each published file passes the publish gate
and is fresh; and that no response byte — pages, scripts, data — contains a Discord id, a workflow
id, an account number, the hidden strategy's name, or any `DEMO_FORBIDDEN` string.

Then confirm the pod's isolation (each must fail or time out):

```bash
P="kubectl -n copytrade exec deploy/demo -c poller --"
$P node -e 'fetch("http://api-gateway:8082/").then(r=>console.log("REACHED",r.status),e=>console.log("blocked"))'
$P node -e 'fetch("http://orchestrator:8080/").then(r=>console.log("REACHED",r.status),e=>console.log("blocked"))'
$P node -e 'fetch("https://example.com/").then(r=>console.log("REACHED",r.status),e=>console.log("blocked"))'
$P node -e 'require("net").connect(5432,"postgres").on("connect",()=>console.log("REACHED")).on("error",()=>console.log("blocked"))'
```

## 7. Measure one market session before publishing

- **Temporal load.** Count workflows started in the session and compare with the same window on a
  day without the poller; the plan's gate is under +50%.
  ```bash
  kubectl -n temporal exec deploy/temporal-admintools -- temporal workflow count -n copytrade \
    --query 'StartTime > "<session start, UTC>"'
  ```
- **Operators' live prices.** With `/live` open on a live tenant, the Option-prices light stays
  green and mark ages stay under 5 s while the demo runs.

## 8. Publish (operator, in Cloudflare)

Tunnel `homelab-dashboard` → add a published application: `tradeagent-demo.ridopark.com` →
`http://demo:8080`. **Do not** create an Access application for it.

From a network that is not logged in to anything:

```bash
DEMO_FORBIDDEN='...' node scripts/ops/demo_verify.mjs https://tradeagent-demo.ridopark.com
curl -sI https://tradeagent-demo.ridopark.com/data/marks.json | grep -i cf-cache-status
curl -s -o /dev/null -w '%{http_code}\n' https://tradeagent.ridopark.com/status   # still 302 to Access
```

If `cf-cache-status` is `DYNAMIC`, add a Cache Rule for `/data/*.json` that respects the origin's
`max-age=3`; without it every viewer's poll reaches the homelab.

## Rotate the token

Repeat §2 with a new token (replace the secret: `kubectl -n copytrade delete secret
demo-preview-token` first), restart the BFF outside market hours, then
`kubectl -n copytrade rollout restart deploy/demo`.
