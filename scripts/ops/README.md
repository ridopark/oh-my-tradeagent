# Operator scripts

Out-of-band operator tooling — things done by hand against the live homelab,
*not* applied by `deploy.yml` and (for the Cloudflare edge) not in git at all.

```
scripts/ops/
├── cf_access_add_email.sh                    # add an email to the Cloudflare Access edge gate
├── temporal-copytrade-namespace-bootstrap.sh # register the `copytrade` Temporal namespace + SAs
├── check_drill_freshness.py                  # Phase 7 promotion-gate: kill-switch/rollback drill freshness
└── tests/
    └── test_check_drill_freshness.py
```

---

## `cf_access_add_email.sh` — the edge gate for dashboard onboarding

Granting someone access to https://tradeagent.ridopark.com takes **two
independent allowlists**, and the dashboard UI only does the second one:

| | Gate | Where | Managed by |
|---|---|---|---|
| **Gate 1** | Cloudflare Access | the edge, before the request reaches the homelab | this script (Cloudflare API) — **not in git, not deployed** |
| **Gate 2** | dashboard invite | binds the person to a tenant on first Google sign-in | admin UI ("Invite user" / onboard step 4) |

A user who has gate 2 but not gate 1 is blocked at the edge with *"that account
doesn't have access"* and never reaches the login page — the single most common
onboarding failure, and it looks like a broken invite. The dashboard surfaces
this with `CloudflareGateNote` next to the submit button on **both** invite
surfaces, pointing at this script.

There is **no verification email from the app** — login is OAuth only. The only
email in the flow is Cloudflare Access's One-Time PIN, which is not sent until
the address is on this allowlist.

### Why a script and not a curl

The Cloudflare policy API is a `PUT` that **replaces the whole include list**, so
a hand-rolled curl that forgets an existing entry silently *revokes* that
person's access. The script makes that structurally impossible:

- builds the `PUT` body from a live `GET`, **appending** rather than re-typing;
- **refuses to write** if the policy holds any non-email include rule (e.g. an
  Access Group) instead of clobbering it;
- **verifies against a fresh read** afterwards and fails loudly if the new
  address is missing *or* any pre-existing address was dropped;
- **idempotent** — re-adding an existing address is a no-op that points you at
  gate 2.

### Usage

```sh
export CF_ACCESS_TOKEN='...'                          # short-lived — see below
scripts/ops/cf_access_add_email.sh --list             # show current allowlist
scripts/ops/cf_access_add_email.sh person@example.com # append one address
scripts/ops/cf_access_add_email.sh --revoke-token     # delete the token when done
```

Then create the dashboard invite (gate 2) and tell the person to sign in with
Google. `--help` prints the full banner.

### The token

Mint a **short-lived, single-use** token every time — it can grant access to a
real-money trading dashboard, so never leave it in a dotfile.

1. https://dash.cloudflare.com/profile/api-tokens → *Create Custom Token*
2. Permission (account scope, no zone perms): **Account → Access: Apps and
   Policies → Edit**
3. Set a **short TTL** (today only).
4. `export CF_ACCESS_TOKEN='...'`, use it, then `--revoke-token`.

`--revoke-token` is best-effort: an Access-scoped token cannot delete *itself*
(that needs *User → API Tokens → Edit*), so rather than widen the token's blast
radius the script prints the token id + console URL for a one-click manual
delete.

### Configuration

The account / app / policy IDs are baked in as defaults and can be overridden
via env vars if the Cloudflare setup changes:

| Env var | Default | Meaning |
|---|---|---|
| `CF_ACCOUNT_ID` | `2f62bd9e…` | Cloudflare account |
| `CF_ACCESS_APP_ID` | `254ba4d3…` | Access app ("Homelab Trade Dashboard") |
| `CF_ACCESS_POLICY_ID` | `416eabe6…` | policy ("Allow operators") |

---

## `temporal-copytrade-namespace-bootstrap.sh`

Phase 5b.E one-shot: registers the Temporal-level `copytrade` namespace and the
two custom Search Attributes the workflows depend on (`TenantStrategy`,
`ContractSymbol`, both Keyword) by running the temporal admin CLI inside an
ephemeral pod in the `temporal` k8s namespace. Idempotent — re-running is a
no-op once everything exists; it branches on exit code (not output text) so it
survives admin-tools CLI version skew.

```sh
# from a workstation with kubectl pointing at the homelab
./scripts/ops/temporal-copytrade-namespace-bootstrap.sh
```

Env overrides (rarely needed): `K8S_NAMESPACE` (default `temporal`),
`TEMPORAL_NS` (default `copytrade`).

---

## `check_drill_freshness.py`

Phase 7 promotion-gate precondition. Parses `docs/ops/drill-log.md` and asserts
that both required drill types — `kill-switch` and `rollback` — have a `pass`
entry within the last 30 days for the target `<provider>-live` adapter, per gate
criteria (f)/(h) in `docs/plans/PLAN.md`. Meant to run as a hard precondition
before issuing the dual-control `LivePromotionApproved`.

Exit `0` when both drills are fresh; `1` when one or both are stale/missing
(stderr names each failing drill type). Unit tests live in
`tests/test_check_drill_freshness.py`.
