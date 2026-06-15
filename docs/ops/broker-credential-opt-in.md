# Broker-credential self-service: opt-in runbook

The multi-tenant broker-credential write path (epic Fork B: P6-a..d + UI-P2-a/-b/-c)
ships **dark**. This runbook is the ordered, auditable procedure to enable it on a
single cluster, and the hard gates that must be satisfied **before** it touches
real money or a public/untrusted network.

> **Default state is dark.** No repo manifest sets `broker.creds.source=db` or
> `broker.credentials.write.enabled=true`. Re-applying the repo manifests/ConfigMaps
> **reverts a cluster to dark** — the opt-in is a per-cluster manual override that does
> not survive a plain redeploy (same pattern as the alpaca-live override). Treat that as
> the rollback, not a bug.

## What "dark" protects today

- `broker.creds.source` defaults to `env`/`file`, so `DbBrokerCredentialSource`,
  `BrokerCredentialWriter`, the exec `POST /internal/broker-credentials` controller,
  and `ExecAdminTokenFilter` **do not exist as beans** on a default cluster (P6-a..c
  `@ConditionalOnProperty`).
- `broker.credentials.write.enabled` defaults to `false`, so the api-gateway
  `POST /broker-credentials` controller, its `RestClient` to exec, the
  `ServiceTokenFilter`, and the `CredentialWriteLimiter` **do not exist** (UI-P2-a/-c);
  the route 404s.
- The dashboard `/settings` form is hidden unless `BROKER_CREDENTIALS_WRITE_ENABLED=true`
  is set on the dashboard deployment (UI-P2-b); the non-secret status read is harmless.
- DB-sourced credentials **refuse `*-live` by construction** (P6-a) — this path is
  paper-only until a deliberate, separately-gated change (see "Before LIVE").

## Preconditions — verify ALL before flipping any flag

1. **DB grant (UI-P2-b).** The BFF read-only role must be able to read the *non-secret*
   credential status columns:
   ```sql
   -- on the exec_alpaca_paper DB
   GRANT SELECT ON broker_credentials TO bff_readonly;
   ```
   This is **not** applied by any Flyway migration or by `deploy.yml` (role provisioning;
   matches the existing `order_intent_journal` grant). Until granted, the dashboard status
   panel shows "not configured" (the read 500s) — harmless while dark, but the panel is
   useless without it. The grant is SELECT-only; the app-layer allowlist (never selecting
   `ciphertext/iv/wrapped_dek/dek_iv/kek_version`) is what keeps the read non-secret.

2. **Token parity (two independent hops).** Both shared service tokens must be set and
   **match on both ends of their hop**:
   - `API_GATEWAY_SHARED_TOKEN` — set on the **dashboard** deployment (caller) and the
     **api-gateway** deployment (verifier, `ServiceTokenFilter`). Must be identical.
   - `EXEC_ADMIN_SHARED_TOKEN` — set on the **api-gateway** deployment (caller) and the
     **exec** deployment (verifier, `ExecAdminTokenFilter`). Must be identical.
   Under the `prod` Spring profile, api-gateway fails fast if `API_GATEWAY_SHARED_TOKEN`
   is the well-known default and `ExecClientConfig` fails fast if `EXEC_ADMIN_SHARED_TOKEN`
   is blank — so a misconfigured pod refuses to boot rather than forward under a bad token.

3. **KEK (P6-a/-b).** The envelope KEK must be mounted to exec (`broker.creds.db.kek-path`,
   base64 32 bytes) and validated at boot. Without it, encryption fails closed.

4. **NetworkPolicy applied + enforcing (UI-P2-c).** The exec ingress lockdown
   (`exec-alpaca-paper-allow-api-gateway-internal`, inside `52-exec-alpaca-paper.yaml`)
   restricts `exec:8080` ingress to `app: api-gateway` only.
   - It rides the exec per-service glob, so a `deploy` of exec applies it; if you are not
     deploying exec, apply it manually: `kubectl apply -f infra/k8s/52-exec-alpaca-paper.yaml`.
   - **Confirm the CNI enforces it** (stock k3s uses kube-router, which does):
     ```sh
     kubectl get pods -n kube-system | grep kube-router    # present ⇒ NetworkPolicy enforced
     ```
     If no policy controller is present, the policy is intent-only — do **not** treat the
     network as a control; rely on the service token and (before public/live) mTLS.
   - Verify denial: a pod that is not `app: api-gateway` cannot open `exec:8080`.

5. **mTLS posture (documented gate, not built).** There is no cert-manager / service mesh;
   the api-gateway→exec hop is **plaintext HTTP inside the pod network**, protected by the
   `EXEC_ADMIN_SHARED_TOKEN` bearer + the NetworkPolicy + the redacted request `toString`
   (the secret never logs). For **paper on a single trusted homelab node** this is the
   accepted dark-state posture. For any **public, multi-node, or untrusted-segment**
   deployment, real mTLS on this hop is **required before opt-in** (see "Before LIVE").

## Flip (ordered)

The order matters: make exec able to *serve* DB creds before opening the *write* route, so
the route never points at a path that cannot persist.

1. **exec:** set `broker.creds.source=db` (per-cluster override; not in the repo ConfigMap).
   Restart/roll exec. The read path + write controller + admin-token filter now exist.
2. **api-gateway:** set `broker.credentials.write.enabled=true` (per-cluster override).
   Roll api-gateway. The `POST /broker-credentials` route, the exec `RestClient`, the
   `ServiceTokenFilter`, and the `CredentialWriteLimiter` now exist.
3. **dashboard (optional UI):** set `BROKER_CREDENTIALS_WRITE_ENABLED=true` to reveal the
   `/settings` entry form. The status panel works as soon as the grant (precondition 1) is
   in place, independent of this flag.

The `CredentialWriteLimiter` (UI-P2-c) now bounds the reachable exec `/v2/account`
validation probe: a per-tenant rate cap (`broker.credentials.write.rate-per-minute`, default
10/min) plus a lockout that arms after `lockout-threshold` (default 5) validation
rejections within `lockout-window` (default 10m), holding for `lockout-duration` (default
15m). Only validation (422) rejections drive lockout — a legitimate rotation (SAVED) resets
the streak; server-side persist errors never lock a tenant out.

## Verify after flip

- `POST /broker-credentials` returns 404 only if a flag is still off; a well-formed write
  for a real paper key returns 200 `{version}` and a `BrokerCredentialWritten` audit row
  appears on the `<tenant>/_broker` chain (orchestrator `BrokerCredentialAuditWorkflow`).
- A bad key returns a coarse 422 (no oracle detail); repeated bad keys for one tenant
  eventually return 429 (lockout).
- No api-key/secret appears in any log, the Temporal history, or the audit subject (MF-7).
- `*-live` is refused by construction — confirm a live-provider write is rejected.

## Rollback

- Unset `broker.credentials.write.enabled` (api-gateway) and/or `broker.creds.source`
  (exec) — or simply **re-apply the repo manifests/ConfigMaps**, which reverts the cluster
  to dark. Roll the affected deployments.
- If a tenant's credential is suspect, trip the kill switch for the affected strategy and
  rotate the key out-of-band; the DB row carries `updated_by/version/updated_at` for
  forensics.

## Before LIVE (hard gates — separate from the paper opt-in)

These are **not** satisfied by this runbook's paper flip and MUST land first:

1. **Dual-control (HARD HALT #4).** Credential writes must become two-approver, mirroring
   `/promotion/approve` (api-gateway → Temporal Update → orchestrator activity → audit),
   before any live credential is ever accepted. The current path is single-actor.
2. **Real mTLS** on the api-gateway↔exec hop (precondition 5), or an equivalently trusted
   transport, for any non-single-trusted-node deployment.
3. **Relax the `*-live` construction refusal** — a deliberate, separately-reviewed change
   (P6-a refuses live by construction today). This is not implied by the paper opt-in.
4. **api-gateway-side ingress tightening** — UI-P2-c shipped only the exec-side policy
   (the secret sink). Restricting api-gateway ingress (e.g. to the ingress namespace /
   dashboard) is a documented follow-up; map the existing orchestrator→api-gateway flows
   before adding it so you don't break operator routes.
