# k8s drift check — operator setup

One-time setup the operator must complete on the homelab before the
`.github/workflows/k8s-drift.yml` workflow produces a visible signal on
PRs. Until step 4 is done, the workflow exits early with a "skipped"
notice — it does not fail.

Scope: implements **Option 1 (advisory PR comment)** from issue #133.
The workflow runs `kubectl diff -f infra/k8s/` against the live homelab
cluster from a self-hosted runner and posts the diff as a sticky PR
comment. Advisory only — never blocks merge.

## Prerequisites

- Homelab k3s cluster reachable on the LAN (`ssh ridopark@192.168.10.123`).
- `kubectl` configured with cluster-admin on the homelab (one-shot setup only).
- A LAN host that can host a long-running self-hosted GitHub runner
  (the homelab host itself is fine).

## Steps

### 1. Apply the read-only ServiceAccount

```sh
kubectl apply -f infra/k8s/56-ci-readonly-sa.yaml
```

Creates `ServiceAccount/ci-drift-checker` in namespace `copytrade`, a
`ClusterRole` with `[get, list, watch]` verbs, and the binding. The SA
cannot mutate cluster state — `kubectl diff` only needs read.

Verify:

```sh
kubectl -n copytrade get sa ci-drift-checker
kubectl get clusterrole ci-drift-checker-readonly
kubectl get clusterrolebinding ci-drift-checker-readonly
```

### 2. Generate a long-lived kubeconfig for the SA

k3s ships with the legacy `ServiceAccount` token controller disabled by
default in newer versions; create a manually-managed token Secret bound
to the SA so the kubeconfig does not depend on a short-lived
projected token.

```sh
# Create a long-lived token secret bound to the SA.
cat <<'EOF' | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: ci-drift-checker-token
  namespace: copytrade
  annotations:
    kubernetes.io/service-account.name: ci-drift-checker
type: kubernetes.io/service-account-token
EOF

# Pull the token + CA bundle out and build the kubeconfig.
TOKEN=$(kubectl -n copytrade get secret ci-drift-checker-token -o jsonpath='{.data.token}' | base64 -d)
CA=$(kubectl -n copytrade get secret ci-drift-checker-token -o jsonpath='{.data.ca\.crt}')
SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')

cat > /tmp/kubeconfig-ci-drift-checker.yaml <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: homelab
    cluster:
      server: ${SERVER}
      certificate-authority-data: ${CA}
contexts:
  - name: ci-drift-checker@homelab
    context:
      cluster: homelab
      user: ci-drift-checker
      namespace: copytrade
current-context: ci-drift-checker@homelab
users:
  - name: ci-drift-checker
    user:
      token: ${TOKEN}
EOF

# Smoke-test.
KUBECONFIG=/tmp/kubeconfig-ci-drift-checker.yaml kubectl auth can-i get configmaps -n copytrade
# Expected: yes
KUBECONFIG=/tmp/kubeconfig-ci-drift-checker.yaml kubectl auth can-i delete configmaps -n copytrade
# Expected: no
```

### 3. Register a self-hosted runner labeled `homelab`

GitHub Actions cannot reach the homelab from a hosted runner — the
homelab k3s API is LAN-only. Register a self-hosted runner on a LAN
host so the workflow can run `kubectl diff` against the live cluster.

1. In GitHub: **Settings → Actions → Runners → New self-hosted runner**.
2. Pick **Linux x64**. Follow the registration script GitHub shows.
3. When prompted for labels, add `homelab` (in addition to the defaults).
4. Run the runner as a systemd service (`./svc.sh install && ./svc.sh start`).
5. Install `kubectl` on the runner host:
   ```sh
   curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
   sudo install -m 0755 kubectl /usr/local/bin/kubectl
   ```

Verify the runner appears as **Idle** under Settings → Actions → Runners.

### 4. Add the kubeconfig as a repo secret

```sh
gh secret set KUBECONFIG_READONLY < /tmp/kubeconfig-ci-drift-checker.yaml
rm /tmp/kubeconfig-ci-drift-checker.yaml
```

### 5. Flip the enablement variable

The workflow's preflight job gates on `vars.K8S_DRIFT_CHECK_ENABLED ==
'true'`. Until this var is `true`, every run skips early with a friendly
notice. Flip it only after steps 1-4 are confirmed.

```sh
gh variable set K8S_DRIFT_CHECK_ENABLED --body "true"
```

### 6. Confirm on the next `infra/k8s/**` PR

The next PR that touches `infra/k8s/**` should:

- Trigger the **k8s drift check (advisory)** workflow.
- Run the preflight on GitHub-hosted infra, then dispatch the diff job
  to the self-hosted runner.
- Post a comment starting with `### k8s drift check (advisory)` (or
  update it if the PR is pushed again).

If no comment appears, check the workflow run log under the **Actions**
tab — the preflight job's "Check K8S_DRIFT_CHECK_ENABLED" step prints a
GitHub `::notice` annotation explaining which gate fired.

## Rollback / disable

To turn the workflow back off without removing files:

```sh
gh variable set K8S_DRIFT_CHECK_ENABLED --body "false"
```

To fully remove:

```sh
gh secret delete KUBECONFIG_READONLY
gh variable delete K8S_DRIFT_CHECK_ENABLED
kubectl delete -f infra/k8s/56-ci-readonly-sa.yaml
kubectl -n copytrade delete secret ci-drift-checker-token
# Deregister the self-hosted runner from Settings → Actions → Runners.
```

## Why advisory-only

Issue #133 explicitly chose Option 1 (advisory comment) over Option 2
(blocking check) and Option 3 (GitOps) for v0:

- Option 2 would block merges on transient cluster unreachability — the
  homelab is a single-node k3s host, so any reboot mid-PR would stall
  merges. Defer until multi-node or until reachability is more robust.
- Option 3 (GitOps via Flux/ArgoCD) inverts the deploy flow and is a
  much bigger change. Track as a separate issue when the operator is
  ready to give up the `kubectl apply` workflow.
