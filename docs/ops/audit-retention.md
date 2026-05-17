# Audit log retention and tamper-evidence policy

> **Status:** policy of record as of 2026-05-17. Issue #22.
> **Owner:** risk-manager (policy), java-architect (schema), ops (runbook).
> **Schema migration:** `services/orchestrator/src/main/resources/db/migration/V3__audit_immutability.sql`.

This doc is the written retention and tamper-evidence policy for the orchestrator
`audit_log` table. It covers (1) the retention period, (2) the daily Merkle root
job design that gives append-only enforcement actual teeth, (3) the WORM pin
target, and (4) the DB role posture that backs the policy at the engine layer.

The schema and role grants land in `V3__audit_immutability.sql`. The runtime
hash-chain writer and the daily Merkle root job are staged follow-ups behind
that schema — this doc is the design they will implement against. The policy binding is established today at the schema layer. Full engine-level enforcement (the role grant) requires a dedicated non-superuser login role — tracked as an open follow-up in §6.

## 1. Retention period

| Class | Window | Mode |
|---|---|---|
| Financial events (`EntryFilled`, `PartialExitFilled`, `OrderIntent*`, fills, exits, kill-switch trips) | 7 years from `occurred_at` | Years 0-2 immutable (WORM); years 3-7 immutable-by-policy + grant (no UPDATE/DELETE from `orchestrator_app`) |
| Operational events (signal parse, risk decision, reconciliation, audit-query) | 7 years from `occurred_at` | Same — single retention window simplifies the disposal runbook |
| Daily Merkle root pins | Indefinite (forever) | WORM, never disposed |

Rationale for 7 years: the SEC 17a-4 books-and-records expectation for
broker-dealers is the closest published reference and lands at six years.
We pick 7 to give a one-year cushion and to align with common state-level
financial recordkeeping floors. We are not currently a registered
broker-dealer; this policy is defensible recordkeeping discipline, not a
direct regulatory obligation. Revisit if/when the firm registers (see
§5).

The first two years are **WORM** — write-once-read-many, no override, no
disposal even via dual control. This bounds the window during which the
freshest events (where the legal and forensic value is highest) cannot be
silently mutated.

## 2. Daily Merkle root job (design)

### Per-row hash chain

The `V3` migration adds two nullable columns:

- `prev_hash BYTEA` — SHA-256 of the previous row's `row_hash`, scoped to
  the `(tenant_id, strategy_id)` chain.
- `row_hash BYTEA` — SHA-256 of a canonical serialization of this row,
  including `prev_hash`. Canonical form:

      sha256(
        prev_hash
        || tenant_id           (utf-8)
        || strategy_id         (utf-8)
        || event_id            (16 bytes)
        || occurred_at         (epoch micros, big-endian 8 bytes)
        || kind                (utf-8)
        || actor               (utf-8, "" if null)
        || workflow_id         (utf-8, "" if null)
        || correlation_id      (utf-8, "" if null)
        || subject_canonical   (RFC 8785 JCS-canonicalized JSON, utf-8)
      )

When `prev_hash IS NULL` (the first row in a chain), substitute 32 zero bytes (`\x00 × 32`) in the concatenation before hashing. This pins the canonical byte form for chain-head rows so independent verifiers compute identical `row_hash` values; the choice cannot be revisited later without invalidating every chain head ever written.

The chain writer runs **inside the same transaction that inserts the
audit row**. It reads the previous `row_hash` for that
`(tenant_id, strategy_id)` chain `FOR UPDATE`, hashes the new row, and
writes both columns in one statement. This serializes writes per chain,
which is acceptable because audit insert volume is bounded by signal
volume (orders of magnitude under per-row hashing cost).

Nullable columns are the bridge: until the writer is enabled, new rows
carry `NULL` and the chain is dormant. The writer flip is a code change,
not a schema change.

### Daily Merkle root

A scheduled job runs once per UTC day, ~02:00 UTC (after the prior NY
trading day has fully settled into audit). For each `(tenant_id,
strategy_id)` chain, it:

1. Selects all rows where `occurred_at` is in the prior UTC day,
   ordered by `id ASC`.
2. Builds a Merkle tree over the rows' `row_hash` values (SHA-256,
   duplicate-last-on-odd standard).
3. Emits the root as a `merkle_root` row in a new sibling table
   (`audit_merkle_root`, schema TBD in the follow-up migration — covers
   `tenant_id, strategy_id, period_date, root_hash, first_row_id,
   last_row_id, row_count, pinned_at, pin_receipt`).
4. **Pins** the root to a tamper-evident store (see §3) and stores the
   pin receipt in the same row.

Verifying the chain at any point: walk forward from any anchored
`row_hash`, recompute each subsequent `row_hash` from the row's
serialization + the previous `row_hash`, compare against the stored
`row_hash`. Verifying a day's Merkle root: rebuild the tree from the
day's rows, compare against the pinned root.

## 3. WORM pin target

The daily Merkle root is the externally-anchored witness that the audit
chain has not been silently rewritten. The pin target must be
write-once: once the root is pinned for day D, it cannot be replaced
even by an attacker with full Postgres + orchestrator credentials.

**Primary target: S3 Object Lock (Compliance mode), 7-year retention.**

- Bucket: `oh-my-tradeagent-audit-merkle-{env}` (per-env, isolated
  credentials).
- Object key: `{tenant_id}/{strategy_id}/{YYYY}/{MM}/{DD}.json` —
  contains `{period_date, root_hash, first_row_id, last_row_id,
  row_count, computed_at}`.
- Object Lock mode: **Compliance** (NOT Governance — Governance allows
  override by a sufficiently privileged IAM principal, which defeats
  the purpose). Compliance mode cannot be lifted by any user including
  the AWS account root, only by closing the AWS account itself.
- Lock period: 7 years (matches §1 retention window).
- Pin receipt stored in `audit_merkle_root.pin_receipt`: S3 VersionId
  + ETag + Object Lock retention timestamp.

**Fallback / secondary anchor: external RFC 3161 timestamping.**

- Submit the daily root hash to a public RFC 3161 time-stamping
  authority (e.g. FreeTSA, DigiCert TSA). Store the timestamp token
  alongside the S3 receipt.
- Defends against the case where the S3 account itself is the
  adversary (insider, root-credential compromise). The TSA receipt
  cryptographically binds the root to a wall-clock time outside our
  blast radius.

A daily root is considered **pinned** only when both targets have
returned a receipt. If either fails, the job retries with backoff and
pages on N consecutive failures (alerting design lives with the
implementation follow-up).

## 4. DB role posture

`V3__audit_immutability.sql` creates an `orchestrator_app` Postgres
role (NOLOGIN) and:

- `REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM orchestrator_app;`
- `GRANT  SELECT, INSERT             ON audit_log TO   orchestrator_app;`

In deployment, the actual login role used by the orchestrator service
(currently `temporal`, per
`services/orchestrator/src/main/resources/application.yml` —
`ORCHESTRATOR_DB_USER:temporal`) must be GRANTed membership in
`orchestrator_app`:

    GRANT orchestrator_app TO temporal;

After that grant, a compromised orchestrator service cannot mutate or
delete audit rows even with full DB credentials, because the role it
inherits lacks those grants. The Postgres engine refuses the operation.

**What this does NOT cover:**

- The Postgres superuser, the table owner, and any DBA-class role
  retain full privileges. End-of-retention disposal (§5) must run as
  one of those roles.
- This is engine-layer enforcement, not encryption-layer. Anyone with
  direct disk / WAL access to the underlying volume can in principle
  rewrite bytes. Defense in depth lives at the volume encryption and
  cluster access-control layers (out of scope for this doc).

## 5. End-of-retention disposal (dual control)

Years 0-2: **no disposal path exists.** The WORM Object Lock cannot be
lifted; the rows stay in Postgres until they age into year 3+.

Years 3-7: rows older than 2 years may, in principle, be archived to
cold storage to reduce hot-DB footprint, but they remain in the
immutability envelope (WORM-pinned Merkle roots still cover them).
Live deletes from `audit_log` itself are not part of the steady-state
runbook.

Year 7+: rows past the retention window may be disposed. The disposal
procedure requires **dual control** (two distinct human operators must
co-sign the disposal ticket) and is executed as the Postgres
superuser, not via the orchestrator service. The disposal runbook
lives in `docs/ops/audit-retention-disposal.md` (to be authored when
the first cohort approaches year 7; the firm is several years out
from that today).

The S3-Object-Locked Merkle root for any disposed day is **retained**
— that is the surviving proof that the audit row set was internally
consistent at the time of pinning, even if the rows themselves are no
longer in the hot store.

## 6. Open follow-ups (tracked in #22 lineage)

- Implement the per-row hash-chain writer (transactional, FOR UPDATE
  on the prior chain head, populates `prev_hash` and `row_hash`).
- Implement the daily Merkle root job + `audit_merkle_root` table.
- Wire the S3 Object Lock bucket (+ IAM, + retention configuration).
- Wire the RFC 3161 timestamping fallback.
- Author the dual-control disposal runbook
  (`docs/ops/audit-retention-disposal.md`) when the firm crosses
  year 5 of the first audit cohort.
- Promote this policy to a registered-firm posture if/when the entity
  registers as a broker-dealer (SEC 17a-4 then becomes a direct
  obligation, not a reference).
