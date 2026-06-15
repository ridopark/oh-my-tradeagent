# Activating the `staging_paper` second tenant (P4-b-3)

Operator runbook for onboarding a second tenant (`staging_paper`, an Alpaca **paper**
account) onto the shared exec worker, using the per-tenant file-mounted credential
source. This is a **gated manual step** — none of it is applied by `deploy.yml`.

## What shipped (the safety stack this depends on)

All dark/fail-closed and live-path byte-identical until the flips below:

- **P4-b-1 (#384)** `FileMountedBrokerCredentialSource` — per-tenant scoped secrets, selectable via `broker.creds.source=file`.
- **P4-b-2 (#385)** broker-client cache invalidation on credential rotation.
- **P4-c-a (#386)** `broker_account_id` contract field + `(broker_target, account)` boot invariant (gated `multitenant.broker-accounts.enabled`).
- **P4-c-b-1 (#388)** per-tenant `AccountSnapshot` (each tenant's cap reads its own account).
- **P4-c-b-2 (#389)** creds-vs-config account cross-check (`AccountMismatchError` if config `broker_account_id` ≠ the account the creds authenticate).

## Homelab topology note (READ FIRST)

On homelab, `dev/copytrade-v1` is overridden to **`alpaca-live`** (account 847309116) — the
repo default `alpaca-paper` is a per-cluster override (see `40-tenants-config.yaml`). So
`staging_paper` on **`alpaca-paper`** sits on a **different `broker_target`** than `dev`, on a
**different exec pod** (`exec-alpaca-paper`, queue `broker-alpaca-paper`). Consequences:

- The `(broker_target, account)` cross-tenant invariant does **not** trigger (different
  targets), so **`multitenant.broker-accounts.enabled` is NOT needed** on homelab. Leave it off.
- Only the **`exec-alpaca-paper`** pod changes (→ `file` creds). `exec-alpaca-live` (account
  847309116) is **untouched** — it keeps env creds. The live account is unaffected throughout.

> If you instead intend `staging_paper` AND `dev` to share `alpaca-paper` (both paper on one
> pod), then `dev` ALSO needs a distinct non-blank `broker_account_id` and you must flip
> `multitenant.broker-accounts.enabled=true`. That is NOT the homelab path below.

## Step 1 — create the scoped credential Secret (real keys, never committed)

The `FileMountedBrokerCredentialSource` reads a directory of per-field files at
`/etc/broker-creds/<tenant>-<provider>/`. Create the Secret so each data key becomes a file:

```bash
kubectl create secret generic broker-creds-staging-paper -n copytrade \
  --from-literal=api-key-id='<PAPER_API_KEY>' \
  --from-literal=api-secret-key='<PAPER_API_SECRET>' \
  --from-literal=base-url='https://paper-api.alpaca.markets' \
  --from-literal=expected-account-id='PA3FKGPFYPLH'
```

`expected-account-id` MUST equal the tenant config's `broker_account_id` (`PA3FKGPFYPLH`) or
every order fails closed with `AccountMismatchError` (P4-c-b-2). It also drives the P2 boot
assertion (the pod crashloops if the keys authenticate a different account).

## Step 2 — add the tenant config (do NOT commit to the repo `tenants/` tree)

Committing this to the repo `tenants/` tree would make the **default** tree invalid (two
alpaca-paper tenants under strict mode → boot fails for anyone applying it). Keep it in the
homelab tenants ConfigMap only. Add `staging_paper/strategies/copytrade-v1.yaml`:

```yaml
schema_version: 1
tenant_id: staging_paper
strategy_id: copytrade-v1
broker_target: alpaca-paper
broker_account_id: PA3FKGPFYPLH   # P4-c-a/-b-2: declared account, cross-checked vs the creds
author_whitelist:
  - <discord_author_id>
max_signal_age_bto_secs: 30
max_signal_age_stc_secs: 60
max_positions: 5
capital_weight: 0.2
min_contracts: 1
max_contracts: 5
# ...plus whatever risk gates you want for the paper tenant
```

plus a `staging_paper/tenant.yaml` mirroring `dev/tenant.yaml`. Regenerate + apply the tenants
ConfigMap (`40-tenants-config.yaml`) — it is NOT applied by a deploy:

```bash
kubectl apply -f infra/k8s/40-tenants-config.yaml   # after adding staging_paper to its source
```

## Step 3 — flip `exec-alpaca-paper` to file creds + mount the secret

Patch the `exec-alpaca-paper` Deployment (`infra/k8s/52-exec-alpaca-paper.yaml`) — apply by
hand, do NOT change the committed default (a deploy re-applying the repo manifest reverts it):

```yaml
spec:
  template:
    spec:
      containers:
        - name: exec-alpaca-paper
          env:
            - name: BROKER_CREDS_SOURCE          # broker.creds.source
              value: file
            - name: EXEC_BOOTSTRAP_TENANT_ID     # warms the boot probe for this tenant AND
              value: staging_paper               # defaults broker.creds.account-level-tenant
            # (broker.creds.file.root defaults to /etc/broker-creds)
          volumeMounts:
            - name: broker-creds-staging-paper
              mountPath: /etc/broker-creds/staging_paper-alpaca
              readOnly: true
      volumes:
        - name: broker-creds-staging-paper
          secret:
            secretName: broker-creds-staging-paper
```

`EXEC_BOOTSTRAP_TENANT_ID=staging_paper` is required: under `file` creds the boot probe resolves
*that* tenant's directory, and it also becomes the `ACCOUNT_LEVEL` tenant for the account-wide
reads (snapshot / pre-trade / reconciliation) on this single-paper-account pod.

## Step 4 — verify (before relying on it)

1. **Boot**: `exec-alpaca-paper` starts (no crashloop). Logs show
   `broker account identity verified at boot via registry warm-up (tenant=staging_paper)` — the
   keys authenticate `PA3FKGPFYPLH`. A crashloop here means the secret is missing/mismounted or
   the keys authenticate a different account (fail-closed, correct).
2. **Mount**: `kubectl exec deploy/exec-alpaca-paper -n copytrade -- ls /etc/broker-creds/staging_paper-alpaca`
   lists `api-key-id api-secret-key base-url expected-account-id`.
3. **Order path**: send a paper BTO for `staging_paper`; confirm it places on account
   `PA3FKGPFYPLH` (the dashboard `/status` page shows the broker/account). A mismatched
   `broker_account_id` vs the secret would reject with `AccountMismatchError` — that is the
   guard working.
4. **Live account untouched**: `exec-alpaca-live` / account 847309116 unchanged throughout.

## Rollback

Revert `exec-alpaca-paper` to env creds (remove `BROKER_CREDS_SOURCE`, the mount, and
`EXEC_BOOTSTRAP_TENANT_ID`; re-apply the repo manifest) and remove `staging_paper` from the
tenants ConfigMap. The live pod was never touched, so there is nothing to roll back there.
