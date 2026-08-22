# Manifest drift log

Append-only record of drift events between `infra/k8s/` (the source of
truth) and the live homelab k3s cluster. Each entry documents the
symptom, the reconcile command applied, and the verification evidence —
so future audits can answer "when did the cluster get this manifest?"
without spelunking shell history.

See `docs/ops/k8s-drift-check.md` for the standing detection workflow
(`kubectl diff` advisory comments on PRs).

## 2026-05-26 — orchestrator snapshot env var + volume (issue #132)

- **Symptom:** Every orchestrator boot logged
  `WARN c.o.o.a.TenantConfigChangedEmitter ... failed to persist TenantConfig snapshot ... /etc/copytrade/tenants/.snapshot: Read-only file system`.
  Functional impact: `TenantConfigChangedEmitter`'s diff-on-boot
  guarantee never held — every restart emitted a spurious
  `TenantConfigChanged` audit row.
- **Root cause:** Manifest drift, not a code defect.
  `infra/k8s/51-orchestrator.yaml` (committed in `e84bbe8`, same commit
  as the `TenantConfigChangedEmitter` feature in PR #123) already
  declared `ORCHESTRATOR_SNAPSHOT_DIR=/var/lib/copytrade/snapshot`, the
  `tenants-snapshot` `emptyDir` volume, and the
  `/var/lib/copytrade/snapshot` mount. The live homelab Deployment had
  simply never received the apply, so the Spring default
  `${orchestrator.snapshot-dir:${orchestrator.tenants-dir:tenants}/.snapshot}`
  resolved inside the read-only ConfigMap mount and writes failed.
- **Reconcile:**
  ```sh
  scp infra/k8s/51-orchestrator.yaml ridopark@192.168.10.123:/tmp/51-orchestrator.yaml
  ssh ridopark@192.168.10.123 "kubectl apply -f /tmp/51-orchestrator.yaml"
  ssh ridopark@192.168.10.123 "kubectl -n copytrade rollout status deploy/orchestrator --timeout=180s"
  ```
  Result: `deployment.apps/orchestrator configured` →
  `deployment "orchestrator" successfully rolled out`. New pod:
  `orchestrator-76f5946f49-tjxwr` (created `2026-05-26T03:21:29Z`).
- **Verification (new pod):**
  - `env | grep ORCHESTRATOR_SNAPSHOT_DIR` →
    `ORCHESTRATOR_SNAPSHOT_DIR=/var/lib/copytrade/snapshot`.
  - `.spec.volumes[*].name` includes `tenants-snapshot`.
  - `.spec.containers[0].volumeMounts[*].mountPath` includes
    `/var/lib/copytrade/snapshot`.
  - `kubectl logs ... | grep -c 'failed to persist TenantConfig snapshot'`
    → `0` for the new boot.
  - `ls /var/lib/copytrade/snapshot/dev/copytrade-v1.json` →
    `-rw------- 1 app app 680 May 26 03:21 ...` (snapshot landed at the
    documented path).
- **Follow-up filed?** Out of scope for this entry — the issue body
  defers the "how did the cluster drift / add `kubectl diff` to CI"
  question to a separate issue. `docs/ops/k8s-drift-check.md` already
  documents the operator setup for the `k8s-drift` workflow.

## 2026-08-22 — strategy_config intentionally diverges from tenants ConfigMap (issue #780)

- **What:** The `strategy_config` DB rows for `copytrade-v1` on `prod-kipark` /
  `prod-jinchul` / `prod_real` now carry `capital_source=static` with equity-encoded
  `capital_weight` (0.052 / 0.016 / 0.065), written by operator DB CAS
  (`updated_by='static-sizing-cutover-780'`, versions 18/14/28). The tenants
  ConfigMap still shows the old `account_cash` + 0.2/0.3 values.
- **Why this is NOT drift to reconcile:** intentional, per issue #780 and
  `docs/ops/copytrade-static-sizing-cutover.md`. Orchestrator boot seeding is
  `INSERT ... ON CONFLICT DO NOTHING`, so a restart never reverts these rows; both
  directions are explicit operator writes. Do NOT "fix" the ConfigMap mismatch by
  re-applying it into the DB.
- **Verification:** re-Activation post-write returned `ACTIVATED` ×3 through the
  #789 equity-ceiling gate; fresh `LivePromotionApproved` audit rows 15:41:39-40Z.
