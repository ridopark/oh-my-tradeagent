# k3s deploy — homelab

Manifests for the single-node k3s production target at `ssh ridopark@192.168.10.123`.

## Layout

```
infra/k8s/
├── 00-namespace.yaml                # `copytrade` namespace
├── 10-postgres.yaml                 # StatefulSet + init SQL ConfigMap
├── 20-redis.yaml                    # Deployment + Service
├── 30-temporal.yaml                 # auto-setup Deployment + Web UI (deprecated — see 5b.E)
├── 31-temporal-bootstrap.yaml       # Job: registers SAs on the local Temporal (deprecated — see 5b.E)
├── 40-tenants-config.yaml           # ConfigMap mounted into orchestrator
├── 51-orchestrator.yaml             # orchestrator-svc (+ tenants ConfigMap mount; AuditActivitiesImpl lives here)
├── 52-exec-tradier-paper.yaml       # broker worker (stub broker until 5b.D)
├── 53-market-data.yaml              # market-data-svc
├── 54-api-gateway.yaml              # api-gateway-svc + Traefik Ingress
├── 55-signal-source-discord.yaml    # Python sidecar (+ PVC for storage_state.json)
└── README.md                        # this file

# (Secret templates live one level up at infra/secrets-template/ so a
# `kubectl apply -f infra/k8s/` glob cannot accidentally clobber live secrets.)
```

## Temporal cluster topology (Phase 5b.E)

Copy-trade services connect to the shared homelab Temporal cluster at
`temporal-frontend.temporal.svc.cluster.local:7233`, Temporal-level namespace
`copytrade`. The in-`copytrade` Temporal Deployment (`30-temporal.yaml`) and
bootstrap Job (`31-temporal-bootstrap.yaml`) are retained in-repo until the
operator runs the teardown runbook
([docs/ops/temporal-consolidation-teardown.md](../../docs/ops/temporal-consolidation-teardown.md));
after that they should be deleted from the repo in a follow-up PR.

**Before applying the manifests for the first time on a fresh cluster**, run:

```sh
./scripts/ops/temporal-copytrade-namespace-bootstrap.sh
```

This creates the `copytrade` Temporal namespace on the shared cluster and
registers the `TenantStrategy` + `ContractSymbol` Search Attributes. Idempotent.

## First-time deploy

1. **Make GHCR images pullable.** The first push to `main` triggers `build-images.yml` and creates packages at `ghcr.io/ridopark/oh-my-tradeagent-*`. Packages are private by default; make each one **public** in the GitHub UI (Packages → package → Settings → Change visibility) so the homelab pulls without an imagePullSecret. Alternative: create a github-pat secret and add `imagePullSecrets:` to each Deployment (deferred).

2. **Copy + fill secret templates.**
   ```sh
   cp infra/secrets-template/secrets.template.yaml infra/secrets-template/secrets.local.yaml
   # edit infra/secrets-template/secrets.local.yaml — change the postgres password if
   # the cluster is reachable beyond the LAN; the Tradier + sidecar
   # blocks can stay placeholder until 5b.D wires the real cutover.
   ```
   The template lives outside `infra/k8s/` on purpose — a `kubectl apply -f infra/k8s/` glob must never overwrite live secrets with REPLACE_ME placeholders.

3. **Bootstrap the `copytrade` Temporal namespace on the shared cluster** (5b.E):
   ```sh
   ./scripts/ops/temporal-copytrade-namespace-bootstrap.sh
   ```
   Required before any copy-trade worker starts — the workers fail to register
   if the Temporal namespace doesn't exist or the Search Attributes are missing.

4. **Apply, in order:**
   ```sh
   # Secrets first (from infra/secrets-template/, NOT from infra/k8s/):
   scp infra/secrets-template/secrets.local.yaml ridopark@192.168.10.123:/tmp/secrets.local.yaml
   ssh ridopark@192.168.10.123 'kubectl apply -f /tmp/secrets.local.yaml && rm /tmp/secrets.local.yaml'

   # Then the rest of the manifests:
   scp -r infra/k8s ridopark@192.168.10.123:~/copytrade-k8s
   ssh ridopark@192.168.10.123 'kubectl apply -f copytrade-k8s/'
   ```
   `kubectl apply -f <dir>` applies files in alphabetical order, which matches
   the `00-` / `10-` / ... prefixes. The secret template lives outside `infra/k8s/`
   so this glob-apply cannot clobber live secrets with placeholders.

5. **Verify rollout.**
   ```sh
   kubectl -n copytrade get pods
   # Phase 5b.E: the in-`copytrade` temporal-bootstrap Job is deprecated; the
   # equivalent namespace + SA registration on the shared cluster is done by
   # ./scripts/ops/temporal-copytrade-namespace-bootstrap.sh (run in step 3).
   kubectl -n copytrade exec deploy/orchestrator -- curl -sf http://localhost:8080/actuator/health
   ```

6. **Discord one-time login** (5b.D will document this more carefully):
   ```sh
   kubectl -n copytrade exec -it deploy/signal-source-discord -- \
     python -m ohmytradeagent_sidecar.bootstrap
   ```
   This walks the Playwright bootstrap flow once; cookies land in
   `/app/state/storage_state.json` (PVC-backed).

## Dry-run validation (PR-time)

Two layers, both run before merge:

1. **CI client-side gate** (automatic, GitHub Actions). The `k8s-dryrun` job in
   `.github/workflows/ci.yml` runs `kubectl apply --dry-run=client -f infra/k8s/`
   on any PR whose diff touches `infra/k8s/**` (kubectl pinned to k3s 1.35.4
   to match the homelab). Catches schema regressions and basic resource
   structure without contacting a cluster. Does NOT exercise admission
   webhooks or namespace-bound references (Secrets/ConfigMaps) — that's what
   layer 2 is for.
2. **Operator server-side gate** (manual, before merge of a non-trivial
   manifest change). Open a tunnel or run from a workstation with the
   homelab's kubeconfig copied (`~/.kube/config-homelab`):

   ```sh
   KUBECONFIG=~/.kube/config-homelab kubectl apply --dry-run=server -f infra/k8s/
   ```

   `--dry-run=server` round-trips through the API server, which validates
   schemas, admission, and references (e.g. Secret/ConfigMap names) without
   mutating state.

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
