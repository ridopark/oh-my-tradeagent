# k3s deploy — homelab

Manifests for the single-node k3s production target at `ssh ridopark@192.168.10.123`.

## Layout

```
infra/k8s/
├── 00-namespace.yaml                # `copytrade` namespace
├── 10-postgres.yaml                 # StatefulSet + init SQL ConfigMap
├── 20-redis.yaml                    # Deployment + Service
├── 30-temporal.yaml                 # auto-setup Deployment + Web UI
├── 31-temporal-bootstrap.yaml       # Job: registers TenantStrategy + ContractSymbol SAs
├── 40-tenants-config.yaml           # ConfigMap mounted into orchestrator
├── 50-audit.yaml                    # audit-svc
├── 51-orchestrator.yaml             # orchestrator-svc (+ tenants ConfigMap mount)
├── 52-exec-tradier-paper.yaml       # broker worker (stub broker until 5b.D)
├── 53-market-data.yaml              # market-data-svc
├── 54-api-gateway.yaml              # api-gateway-svc + Traefik Ingress
├── 55-signal-source-discord.yaml    # Python sidecar (+ PVC for storage_state.json)
├── secrets.template.yaml            # Secret templates (NOT applied as-is)
└── README.md                        # this file
```

## First-time deploy

1. **Make GHCR images pullable.** The first push to `main` triggers `build-images.yml` and creates packages at `ghcr.io/ridopark/oh-my-tradeagent-*`. Packages are private by default; make each one **public** in the GitHub UI (Packages → package → Settings → Change visibility) so the homelab pulls without an imagePullSecret. Alternative: create a github-pat secret and add `imagePullSecrets:` to each Deployment (deferred).

2. **Copy + fill secret templates.**
   ```sh
   cp infra/k8s/secrets.template.yaml infra/k8s/secrets.local.yaml
   # edit infra/k8s/secrets.local.yaml — change the postgres password if
   # the cluster is reachable beyond the LAN; the Tradier + sidecar
   # blocks can stay placeholder until 5b.D wires the real cutover.
   ```

3. **Apply, in order:**
   ```sh
   # From the workstation (or copy infra/k8s/ to the node first):
   scp -r infra/k8s ridopark@192.168.10.123:~/copytrade-k8s
   ssh ridopark@192.168.10.123 'kubectl apply -f copytrade-k8s/secrets.local.yaml \
                                && kubectl apply -f copytrade-k8s/'
   ```
   `kubectl apply -f <dir>` applies files in alphabetical order, which matches
   the `00-` / `10-` / ... prefixes.

4. **Verify rollout.**
   ```sh
   kubectl -n copytrade get pods
   kubectl -n copytrade logs job/temporal-bootstrap
   kubectl -n copytrade exec deploy/orchestrator -- curl -sf http://localhost:8080/actuator/health
   ```

5. **Discord one-time login** (5b.D will document this more carefully):
   ```sh
   kubectl -n copytrade exec -it deploy/signal-source-discord -- \
     python -m ohmytradeagent_sidecar.bootstrap
   ```
   This walks the Playwright bootstrap flow once; cookies land in
   `/app/state/storage_state.json` (PVC-backed).

## Dry-run validation (PR-time)

Open a tunnel or run from a workstation with the homelab's kubeconfig copied
(`~/.kube/config-homelab`):

```sh
KUBECONFIG=~/.kube/config-homelab kubectl apply --dry-run=server -f infra/k8s/
```

`--dry-run=server` round-trips through the API server, which validates schemas,
admission, and references (e.g. Secret/ConfigMap names) without mutating state.

## What's NOT in 5b.B (deferred)

- **OTel Collector + Prometheus** manifests — observability comes after 5b.D
  validates the green-redeploy gate.
- **Image digest pinning** for base images and Action versions — supply-chain
  hardening pre-Phase 6.
- **NetworkPolicy** default-deny — needs more thought on inter-service flow
  before locking down (orchestrator → exec → audit → temporal grpc, etc.).
- **HorizontalPodAutoscaler / PodDisruptionBudget** — single-node k3s; not
  meaningful until a multi-node setup lands.

## What's NOT credible state-of-the-world yet

- Postgres has 10Gi PVC against the local-path provisioner. A node OS reinstall
  loses it. Acceptable for v0; the audit log + journal can be replayed from
  Temporal if/when this hurts.
- The Postgres password defaults to `temporal/temporal`. Change before exposing
  beyond the LAN.

## Rollback

Manifests are declarative — `kubectl delete -f infra/k8s/` tears the whole
namespace down. For a faster targeted rollback of an app image only:

```sh
kubectl -n copytrade set image deployment/orchestrator orchestrator=ghcr.io/ridopark/oh-my-tradeagent-orchestrator:<prior-sha>
kubectl -n copytrade rollout status deployment/orchestrator --timeout=120s
```

The Temporal `Workflow.getVersion` checkpoints in long-lived workflows
(`PositionWorkflow`) handle the rollback case so in-flight positions complete
on the version they started with.
