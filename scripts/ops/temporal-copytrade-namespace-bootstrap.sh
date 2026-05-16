#!/usr/bin/env bash
# Phase 5b.E — Bootstrap the `copytrade` Temporal namespace on the shared
# `temporal/temporal-frontend` cluster.
#
# Runs the temporal admin CLI inside an ephemeral pod in the `temporal` k8s
# namespace (so it can resolve `temporal-frontend` over cluster-local DNS),
# registers the Temporal-level namespace `copytrade`, and registers the two
# custom Search Attributes the workflows depend on (`TenantStrategy` and
# `ContractSymbol`, both Keyword).
#
# Idempotent: re-running is a no-op once everything exists. The script
# branches on exit code (not output text) so it survives admin-tools CLI
# version skews.
#
# Usage (from a workstation with kubectl pointing at the homelab):
#   ./scripts/ops/temporal-copytrade-namespace-bootstrap.sh
#
# Env overrides (rarely needed):
#   K8S_NAMESPACE     k8s namespace where temporal-frontend runs (default: temporal)
#   TEMPORAL_NS       Temporal-level namespace to create (default: copytrade)
#   RETENTION         workflow execution retention (default: 72h)
#   IMAGE             admin-tools image (default: temporalio/admin-tools:1.29)
#
# This must be run BEFORE applying the Phase 5b.E manifest changes that
# repoint copy-trade services at the shared cluster, otherwise the workers
# will fail to register on a non-existent Temporal namespace.

set -euo pipefail

K8S_NAMESPACE="${K8S_NAMESPACE:-temporal}"
TEMPORAL_NS="${TEMPORAL_NS:-copytrade}"
RETENTION="${RETENTION:-72h}"
IMAGE="${IMAGE:-temporalio/admin-tools:1.29}"
# The Service name in the `temporal` k8s namespace is `temporal-frontend`
# (the shared cluster). When the pod runs inside `temporal` ns, the short
# hostname resolves; the FQDN is used here so the script also works from a
# pod in any other ns.
TARGET="${TARGET:-temporal-frontend.${K8S_NAMESPACE}.svc.cluster.local:7233}"

pod_name="copytrade-temporal-bootstrap-$$"

echo "Bootstrapping Temporal namespace '${TEMPORAL_NS}' on ${TARGET}"
echo "  (using ephemeral pod ${pod_name} in k8s namespace ${K8S_NAMESPACE})"

kubectl -n "${K8S_NAMESPACE}" run "${pod_name}" \
  --rm -i --restart=Never \
  --image="${IMAGE}" \
  --command -- /bin/sh -c "$(cat <<EOF
set -eu
TARGET="${TARGET}"
TEMPORAL_NS="${TEMPORAL_NS}"
RETENTION="${RETENTION}"

echo "Waiting for Temporal frontend at \${TARGET}..."
for i in \$(seq 1 60); do
  if temporal operator cluster health --address "\${TARGET}" >/dev/null 2>&1; then
    echo "  Temporal reachable."
    break
  fi
  sleep 2
done

# Register the copytrade Temporal namespace. Swallow AlreadyExists on the
# exit-code path so re-runs are no-ops.
if out=\$(temporal operator namespace create --address "\${TARGET}" --namespace "\${TEMPORAL_NS}" --retention "\${RETENTION}" 2>&1); then
  echo "namespace \${TEMPORAL_NS}: created (retention=\${RETENTION})"
elif echo "\$out" | grep -qi 'AlreadyExists\|already exists'; then
  echo "namespace \${TEMPORAL_NS}: already exists"
else
  echo "\$out"
  exit 1
fi

# Wait until the namespace is queryable — namespace replication is async on
# fresh-cluster bootstraps. Without this, the search-attribute create calls
# below race and intermittently fail with NamespaceNotFound.
echo "Waiting for namespace \${TEMPORAL_NS} to become queryable..."
for i in \$(seq 1 60); do
  if temporal operator namespace describe --address "\${TARGET}" --namespace "\${TEMPORAL_NS}" >/dev/null 2>&1; then
    echo "  \${TEMPORAL_NS} ready."
    break
  fi
  sleep 2
done

register_sa() {
  name="\$1"
  if out=\$(temporal operator search-attribute create --address "\${TARGET}" --namespace "\${TEMPORAL_NS}" --name "\$name" --type Keyword 2>&1); then
    echo "  \$name: registered"
  elif echo "\$out" | grep -qi 'AlreadyExists'; then
    echo "  \$name: already registered"
  else
    echo "\$out"
    exit 1
  fi
}
register_sa TenantStrategy
register_sa ContractSymbol

echo "bootstrap complete."
EOF
)"
