# PLAN — 2026-10-30 floor-breach-correlation-id

**Accepted by operator 2026-10-30. Open-question assumptions accepted as written.**

Issue: #1029 — Floor-breach alerts silently lost for positions with long workflow IDs
(`audit_log.correlation_id varchar(96)` overflow).

## Goal

Every `FloorBreachAlerted` firing is persisted to `audit_log` and paged to Discord regardless of the
position's workflow-id length, and no other audit writer can hit the same `varchar(96)` wall.

**Done when:**
- `audit_log.correlation_id` is `VARCHAR(256)` (same width as `workflow_id`) via a new orchestrator
  Flyway migration `V12__audit_log_widen_correlation_id.sql`; on the homelab after deploy
  `SELECT character_maximum_length FROM information_schema.columns WHERE table_name='audit_log' AND column_name='correlation_id'`
  returns `256`.
- A Testcontainers IT (`RUN_DB_ITS=true`, real Flyway schema) inserts a `FloorBreachAlerted` event
  whose `workflow_id` and `correlation_id` are both the real 111-char production id
  `t-staging_paper/s-copytrade-v1/pos/SMCI  261120C00050000/chat-messages-769797179992571914-1541869380974157876:0`
  and reads both back intact with a non-null `row_hash`; the same IT drives that event through the
  real `AuditActivitiesImpl.log()` → after-commit → `OrderFailureAlerter` funnel and observes one
  `WebhookClient.postEmbedToUrl` call whose footer contains the full 111-char workflow id.
- A Docker-free surefire test fails whenever `correlation_id` is narrower than `workflow_id` in the
  orchestrator migrations, or whenever the `services/audit` IT mirror schema drifts from the
  orchestrator's widths.
- A `WorkflowIdsTest` case proves the worst-case `WorkflowIds.position(...)` id (64-char tenant,
  64-char strategy, 21-char OCC, 59-char signal id) is ≤ 256 chars.
- `mvn -pl services/orchestrator,services/audit,contract/java test` green; spotless clean on each.
- The orchestrator pod on the homelab, after the post-deploy roll, logs zero
  `value too long for type character varying(96)` lines over a full RTH session, and a new
  `FloorBreachAlerted` row with `length(correlation_id) > 96` exists once a long-id position breaches.
- The PR body states the lost-alert window and that it cannot be backfilled.

## Non-goals

- No change to what the floor-breach loop puts in `correlation_id` (it stays `= workflow_id`); the
  119 stored rows keep their convention.
- No truncation, hashing, or defensive shortening in `AuditActivitiesImpl` or any writer.
- No change to the audit hash-chain canonical form (`docs/ops/audit-retention.md §2`).
- No server-side length validation on the api-gateway `correlation_id` request fields (see Open
  questions).
- No change to the `persist()`-swallows-then-publishes ordering in `AuditActivitiesImpl.log()` (see
  Further Notes).
- No backfill of lost alerts (impossible: the rows were never written).
- No change to `FloorBreachAlertLoop` behaviour, `OrderFailureAlerter` rendering, the BFF
  `FloorBreachController`, or the dashboard.

## Spec

### Problem Statement

An operator with a copytrade position whose Temporal workflow id exceeds 96 characters (every
position whose entry signal id is a Discord `chat-messages-<channel>-<message>` id on a tenant /
strategy pair of ordinary length — 111 chars for `staging_paper/copytrade-v1`) never receives the
−50%-of-entry floor-breach page. The `FloorBreachAlertLoop` copies the workflow id into
`correlation_id`, the `audit_log` INSERT fails with `value too long for type character varying(96)`,
`AuditActivitiesImpl.persist()` swallows the exception, and the Discord page — rendered from the
committed audit row — is lost. The loop retries each cycle (72 failures in the current pod's last
24 h; 119 stored `FloorBreachAlerted` rows all have `length(correlation_id) <= 96`). A live safety
signal is silently missing for exactly the positions that are most common on the copytrade tenants.

### Solution

Widen `audit_log.correlation_id` to `VARCHAR(256)` — the width `workflow_id` already has — so any
value that fits `workflow_id` fits `correlation_id`. Guard the class of bug with (1) a schema-parity
test that fails if the two widths ever diverge or the audit-svc IT mirror schema drifts, (2) a
contract test bounding the longest id `WorkflowIds` can mint, and (3) a real-schema IT that writes
and pages the 111-char production id through the production funnel.

**Why (a) widen, not (b) a short key — evidence:**

- Hash chain is safe: `AuditLogChainWriter.canonicalBytes` hashes `correlation_id` as
  length-prefixed UTF-8 bytes; the column's declared width is not part of the pre-image, so widening
  changes no stored `row_hash` and no verifier output. `docs/ops/audit-retention.md` names no width.
- No generated model: `audit_log` has no jOOQ codegen (`grep AUDIT_LOG` → nothing); every reader
  (`JooqAuditEventSource`, `AuditController`, `TradesReader`, `PositionLifecycleReader`,
  `LedgerRederiver`) uses `field(name("correlation_id"), String.class)` or raw SQL; `dashboard/lib/bff.ts`
  types it `string | null`; `contract/schemas/audit-event.json` has no `maxLength`. Nothing to regenerate.
- Lock/size: homelab `audit_log` is 644 MB total / 401 MB heap / 848,260 rows; indexes are
  `audit_log_pkey`, `audit_log_event_id_key`, and the two V2 composite indexes — none on
  `correlation_id`; no views, no CHECK constraints reference it. PostgreSQL 16.14: widening a
  `varchar(n)` is a catalog-only change (no table rewrite, no index rebuild) that takes an
  ACCESS EXCLUSIVE lock for the catalog update only. Orchestrator Flyway runs at pod start as the
  table owner `temporal`; the deployment is `replicas=1, strategy=Recreate`, so no sibling pod is
  writing when the ALTER runs and the trading write path cannot be blocked by it.
- Precedent: `V11__watchlist_daily_widen_message_id.sql` is the same fix shape for the same
  Discord-id cause.
- (b) would have to touch four writers (below), change the stored convention for the 119 existing
  rows, and leave the wall in place for the next writer that copies a workflow id. Nobody reads
  `correlation_id` for `FloorBreachAlerted` specifically: `FloorBreachController` (BFF) and the
  embed footer use `workflow_id`. There is no consumer to preserve, so the only argument for (b) is
  avoiding a migration, and the migration is trivial.

**Writer audit (every `setCorrelationId` into an `AuditEvent` in orchestrator main):**

| Writer | Value | Theoretical max | Status |
|---|---|---|---|
| `alert/floorbreach/FloorBreachAlertLoop` | PositionWorkflow id | 219 (2+64+3+64+5+21+1+59) | **FAILING** in prod (119 rows capped at 96; 72 errors/24 h) |
| `alert/overnighttrail/OvernightTrailNoticeLoop` | PositionWorkflow id | 219 | Latent, identical class; 0 rows in prod so far |
| `bootstrap/KillSwitchBootstrapper` | killswitch wf id (`t-…/s-…/killswitch`, `t-…/account/killswitch`) | 144 | Latent on long tenant/strategy ids; ~40 in practice |
| `alert/CondorTaskFailureWatchdog` | CondorSession / CondorHold wf id | ~151 | Latent; ~50 in practice |
| `workflows/AuditEmitWorkflowImpl` (api-gateway `AuditEmitRequest`) | client-supplied `correlation_id` (dashboard sends a UUID, 36) | unbounded | Operator-only routes; see Open questions |
| `KillSwitchWorkflowImpl`, `ReconciliationWorkflowImpl`, `TenantDeleteWorkflowImpl`, `LivePromotionActivitiesImpl`, `TenantConfigChangedEvents`, `AccountLossCapChangedEvents`, `BrokerCredentialChangedEvents`, `StrategyConfigWriter`, `AccountKillSwitchWorkflowImpl` | `tenantId + "/" + strategyId` (or `/account…`) | 129–147 | Latent at the 64-char column bounds; ≤ 59 observed |
| `CopytradeSignalWorkflowImpl`, `CopytradeDeriskWorkflowImpl`, `PositionWorkflowImpl`, `AdoptionWorkflowImpl`, `WatchlistTrigger*WorkflowImpl` | signal id / source message id | ~59 | Fits today (max observed 59) |

`CondorSessionWorkflowImpl:442` sets `correlationId` on an exec `AccountSnapshotRequest`, not an
audit event — not in scope. Every audit value above is composed of parts bounded by other
`audit_log` columns (tenant 64, strategy 64) plus fixed segments, so 256 is a hard ceiling for the
class, and the contract test in Step 1 pins the largest composition (`position`).

### User Stories

1. As an operator, I want the −50% floor-breach page for every open position, so that a position
   with a long Discord-derived id is not silently unprotected.
2. As an operator, I want the `FloorBreachAlerted` audit row persisted, so that the audit trail and
   `/live` badge agree with what was paged.
3. As an operator, I want the overnight-trail-held notice, kill-switch-down page, and condor
   task-failure page to survive long ids too, so the same bug does not resurface on another kind.
4. As a maintainer, I want a build-time test that fails when `correlation_id` is narrower than
   `workflow_id`, so a future schema edit cannot reintroduce the wall.
5. As a maintainer, I want the `services/audit` IT mirror schema held equal to the orchestrator's,
   so the completeness verifier's fixtures stay faithful.
6. As a maintainer, I want the longest id `WorkflowIds` can build bounded by a test, so a new id
   segment cannot outgrow both columns unnoticed.
7. As an auditor, I want the hash chain untouched by the fix, so previously verified rows remain
   verifiable.
8. As an operator, I want the PR to state the window of lost alerts and that they cannot be
   recovered, so the gap is on record.
9. As an operator, I want the orchestrator rolled outside market hours, so the migration never
   coincides with a live order.

### Implementation Decisions

- New orchestrator Flyway migration `V12__audit_log_widen_correlation_id.sql`:
  `ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(256);` with a header comment
  naming #1029, the 111-char cause, and "256 = workflow_id's width so anything storable there is
  storable here". No grant changes: V3/V4 grants are table-level and unaffected by a type change.
- Hand-align `services/audit/src/test/resources/db/migration/audit-it/V1__audit_log_for_it.sql`
  (`correlation_id VARCHAR(256)`); its header says it is kept hand-aligned with V2/V3 — do that.
- Schema-parity guard: a Docker-free test in `services/audit` (which already walks to the monorepo
  root in `KindRegistryGuardTest`) that parses the orchestrator's `V2` plus every later
  `ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(n)` and the IT mirror `V1`, and
  asserts `correlation_id width == workflow_id width` and mirror widths == orchestrator effective
  widths for `tenant_id, strategy_id, kind, actor, workflow_id, correlation_id`. `assumeTrue` when the
  orchestrator tree is absent (partial checkout), as `KindRegistryGuardTest` does.
- Contract guard: a new case in `contract/java` `WorkflowIdsTest` building `position()` with 64-char
  tenant, 64-char strategy, a 21-char OCC with embedded spaces, and a 59-char signal id, asserting
  `length() <= 256` and documenting that 256 is `audit_log.workflow_id`/`correlation_id`.
- Real-schema IT: extend `AuditLogChainWriterIT` (RUN_DB_ITS, runs Flyway from
  `classpath:db/migration`, connects as `orchestrator_runtime`) with a test that logs a
  `FloorBreachAlerted` event with the literal 111-char id in both `workflow_id` and `correlation_id`
  and reads both back by `event_id` with `row_hash IS NOT NULL`.
- Funnel IT: in the same IT's `AuditITConfig`, replace `noopEventPublisher()` with the context's
  `ApplicationEventPublisher` and add an `OrderFailureAlerter` bean built from a Mockito
  `WebhookClient` and a Mockito `TenantWebhookResolver` (resolve → a fixed URL) using the 5-arg
  constructor with `OrderFailureAlerter.DEFAULT_FAILURE_KINDS`; assert `postEmbedToUrl` was called
  once after `log()` with an embed whose footer contains the full workflow id. If the publisher swap
  disturbs the two existing IT tests, add a second `@Configuration` + IT class instead of widening
  the first — never weaken the existing tests.
- No other production code changes.

### Testing Decisions

- Tests observe external behaviour only: what is in the table, what reached the webhook client, what
  the migration SQL declares. No test reaches into `AuditActivitiesImpl` internals or mocks
  `DSLContext` for the new behaviour.
- Modules under test: `services/orchestrator` (migration + IT), `services/audit` (parity guard + IT
  mirror), `contract/java` (`WorkflowIdsTest`).
- Prior art: `AuditLogChainWriterIT` (Spring + Testcontainers + real Flyway, runtime role),
  `DashboardMigrationSqlStructureTest` (Docker-free SQL-shape lock),
  `KindRegistryGuardTest` (cross-module source scan from the audit module), `WorkflowIdsTest`.
- The RUN_DB_ITS tests run only in CI (`ci.yml` Java matrix, `RUN_DB_ITS: "true"`); local WSL has no
  working Testcontainers. The Docker-free tests must be shown RED then GREEN locally; the IT's RED
  state is argued from the schema (a 111-char value cannot be inserted into `varchar(96)`) and
  confirmed GREEN in CI.

### Out of Scope

Listed under Non-goals. Also: alert dedupe/retry semantics of the loop; the dashboard `/live` badge;
`OvernightTrailNoticeLoop` changes (it is fixed by the same migration, no code change).

### Further Notes

- `AuditActivitiesImpl.log()` catches the INSERT failure inside `persist()` and still publishes
  `AuditEventCommitted`; the page was nonetheless lost in production (issue evidence), consistent
  with the aborted transaction's commit not running AFTER_COMMIT synchronizations. The funnel IT
  must not depend on this: its RED proof is the missing row, its GREEN proof is row + page.
- The lost-alert window: at least 2026-10-30 12:45Z–20:45Z on the current pod (33 failures observed
  then, 72 in the trailing 24 h at plan time); earlier pods' logs are recycled, so the true start is
  the first long-id breach after #779 shipped and is unknowable. The PR must say this and that no
  backfill is possible.

## Steps

### Step 1 — Widen the column and pin the class with Docker-free guards

**Goal:** `correlation_id` is 256 wide in the orchestrator schema and the audit IT mirror, and two
surefire tests fail if either width or the `WorkflowIds.position` bound regresses.

**Blocked by:** none.

**Seams:**
- Migration SQL text (`services/orchestrator/src/main/resources/db/migration/V*.sql` and
  `services/audit/src/test/resources/db/migration/audit-it/V1__audit_log_for_it.sql`) read as files
  by the parity test — catches a width mismatch or a mirror drift at `mvn test` without Docker;
  misses whether Postgres actually applies it (Step 2's IT covers that).
- `WorkflowIds.position(tenant, strategy, occ, signalId)` return value — catches an id format that
  outgrows both columns; misses ids built elsewhere by string concatenation.

**Success checklist:**
- [ ] `V12__audit_log_widen_correlation_id.sql` exists with exactly one statement
      `ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(256);` and a comment naming
      #1029 — evidence: `cat` of the file; `ls services/orchestrator/src/main/resources/db/migration`
      shows V12 as the only new file.
- [ ] The audit IT mirror declares `correlation_id VARCHAR(256)` — evidence:
      `grep -n correlation_id services/audit/src/test/resources/db/migration/audit-it/V1__audit_log_for_it.sql`.
- [ ] The parity test was RED before V12 (run it with V12 stashed or not yet written) and is GREEN
      after — evidence: two `mvn -pl services/audit test -Dtest=<ParityTestName>` outputs, one
      failing with the width message, one passing.
- [ ] The parity test checks all six varchar columns against the mirror, not only `correlation_id`
      — evidence: test source + a one-line deliberate mirror edit (not committed) turning it red.
- [ ] `WorkflowIdsTest` has a case asserting the worst-case `position()` id length ≤ 256 and
      printing/asserting the actual length (expected 219) — evidence:
      `mvn -pl contract/java test -Dtest=WorkflowIdsTest` output.
- [ ] `KindRegistryGuardTest` still passes (no new `KIND_` constants) — evidence: included in the
      `services/audit` test run.
- [ ] `mvn -pl contract/java,services/orchestrator,services/audit spotless:check` passes — evidence:
      command output.
- [ ] `git diff --stat` touches only: the V12 file, the audit-it V1 file, the new parity test, and
      `WorkflowIdsTest` — evidence: the stat.

**Failure checklist (any one means STOP and report):**
- [ ] The parity test cannot be made to go red without V12 (it is not testing the width).
- [ ] Making the test pass requires editing V2 or V3 (migrations already applied in production are
      immutable; Flyway checksums would break the homelab boot).
- [ ] A grant, index, or any statement beyond the single ALTER seems necessary.
- [ ] `WorkflowIds.position` worst case exceeds 256 (then 256 is the wrong number and the plan must
      be revisited, not the test).
- [ ] Any existing test must be weakened, deleted, or `@Disabled` to pass.
- [ ] A change outside `services/orchestrator/src/main/resources/db/migration`,
      `services/audit/src/test`, or `contract/java/src/test` is needed.

### Step 2 — Prove the 111-char id inserts and pages through the real funnel

**Goal:** against the real Flyway schema (V1–V12) and the `orchestrator_runtime` role, the literal
111-char production id round-trips in both columns with a hash, and the production after-commit
funnel posts exactly one Discord embed for it.

**Blocked by:** Step 1.

**Seams:**
- `AuditActivities.log(AuditEvent)` (Spring-proxied `AuditActivitiesImpl`) → `audit_log` row read
  back via the admin connection — catches the overflow (RED without V12), the runtime-role grant
  path, and the chain hash; misses the scheduler/Visibility side of `FloorBreachAlertLoop`
  (already covered by `FloorBreachAlertLoopTest`).
- `AuditActivities.log(AuditEvent)` → `WebhookClient.postEmbedToUrl(url, embed)` via
  `@TransactionalEventListener(AFTER_COMMIT)` — catches a page lost at the commit boundary; misses
  the Discord transport (the `WebhookClient` is a Mockito mock by design: it is the public
  outbound interface).

**Success checklist:**
- [ ] New IT test logs a `FloorBreachAlerted` event with `workflow_id == correlation_id ==` the
      111-char literal from #1029 and the loop's real subject keys (`contract_symbol, qty,
      entry_premium, current_bid, loss_pct, step, threshold`) — evidence: test source.
- [ ] Read-back by `event_id` returns `length(correlation_id) = 111`, `length(workflow_id) = 111`,
      `row_hash IS NOT NULL` — evidence: the assertions and the CI `Java (orchestrator)` log lines
      for the test.
- [ ] `postEmbedToUrl` called exactly once; captured embed footer contains the full 111-char id and
      the title starts with `:rotating_light: FLOOR BREACH` — evidence: ArgumentCaptor assertions.
- [ ] The two pre-existing IT tests (`writesGoldenVectorChainAndRoundTrips`,
      `runtimeRoleStillCannotUpdateOrDeleteAfterChainPopulated`) are unchanged in assertion content
      and still pass — evidence: `git diff` of the IT shows only additions to config/tests; CI log.
- [ ] The RED argument is written in the test's Javadoc: without V12 the INSERT fails with
      `value too long for type character varying(96)` and the read-back finds no row — evidence:
      the Javadoc; if Docker is available locally, an actual run with V12 removed showing the
      failure.
- [ ] `FloorBreachAlertLoopTest` and `OrderFailureAlerterTest` unchanged and green — evidence:
      `mvn -pl services/orchestrator test -Dtest='FloorBreachAlertLoopTest,OrderFailureAlerterTest'`.
- [ ] CI `Java (orchestrator)` green with `RUN_DB_ITS=true` — evidence: the check-run URL.
- [ ] `mvn -pl services/orchestrator spotless:check` passes — evidence: output.
- [ ] PR body states: fix = V12 widen to 256; writer audit summary (FloorBreach failing,
      OvernightTrailHeld latent, others latent at column bounds); the lost-alert window
      "≥ 2026-10-30 12:45Z–20:45Z on the current pod, true start unknowable, cannot be backfilled";
      deploy note "orchestrator is `trading-path` in `scripts/ops/pr-deploy-agent/deploy_gate.py`,
      roll outside market hours; Flyway applies V12 at boot as `temporal`; no manual kubectl";
      `Closes #1029` — evidence: `gh pr view --json body`.

**Failure checklist (any one means STOP and report):**
- [ ] Wiring `OrderFailureAlerter` into the IT needs a Temporal, Redis, or `TenantWebhookResolver`
      DB dependency that cannot be satisfied by a Mockito mock (then the funnel assertion moves to a
      follow-up and the row assertion ships alone — report, do not fake it).
- [ ] The funnel test passes WITHOUT V12 (the page fires off a failed insert) AND the row assertion
      is removed to make the suite consistent — the row assertion is the RED carrier and must stay.
- [ ] Any production class (`AuditActivitiesImpl`, `OrderFailureAlerter`, `FloorBreachAlertLoop`)
      needs editing to make the IT pass.
- [ ] A mock of `DSLContext`, `AuditLogChainWriter`, or `ApplicationEventPublisher` is needed in the
      IT (it must stay a real-schema, real-transaction test).
- [ ] An existing IT assertion must be loosened.

## Repo gates

- `spotless:apply` on every touched module before commit: `contract/java`, `services/orchestrator`,
  `services/audit` (memory: impl env skips spotless → CI fails).
- Flyway: new file `V12__…` only; V1–V11 are applied on the homelab (`flyway_schema_history` top =
  11, 2026-10-15) and must not be edited. Flyway runs at orchestrator boot as `temporal` (table
  owner), `replicas=1, strategy=Recreate` — no manual `kubectl apply`, no shared-manifest change.
- Deploy timing: `orchestrator` is classified `trading-path` in
  `scripts/ops/pr-deploy-agent/deploy_gate.py`; the local deploy agent waits while the market is
  open with running PositionWorkflows or any non-terminal order. Do not override it.
- `KindRegistryGuardTest`: no new `KIND_` constant is introduced; it must stay green.
- No `contract/schemas/*.json` edit → no jsonschema2pojo / datamodel-codegen / config-field-manifest
  regeneration; no `RiskRelevant` change.
- No Temporal workflow code changes → no version gate / replay fixture.
- DB ITs are `RUN_DB_ITS`-gated and run only in CI (`ci.yml` Java matrix); do not claim a local
  IT pass.
- Memory rule: confirm the new test actually fails before the fix (Temporal fixtures here have been
  toothless twice; the parity test is the local RED carrier, the IT's RED is argued and CI-verified).
- Lost alerts cannot be backfilled; the PR must say so (operator instruction).

## Open questions

1. Should the api-gateway `correlation_id` request fields (`TenantCreateRequest`,
   `StrategyConfigWriteRequest`, `TenantConfigWriteRequest`, `StrategyEnableRequest`,
   `BrokerCredentialForwardRequest`) get a server-side `@Size(max=256)`? They are operator-only
   routes and the dashboard always sends a UUID. **Assumption:** out of scope; note in the PR as a
   follow-up candidate rather than widening this fix.
2. Is the funnel assertion (page through `OrderFailureAlerter` inside the IT) worth the Spring
   wiring, given the row assertion alone proves the fix and `OrderFailureAlerterTest` already proves
   rendering? **Assumption:** yes — the operator asked for "inserts and pages through the real
   alerter path at its seam", and the Step 2 failure checklist lets the builder drop it with a
   report if the wiring is not mock-satisfiable.
3. Why did the page not fire when `persist()` swallowed the INSERT error yet `publishEvent` still ran?
   The production evidence says it did not; the mechanism (commit of an aborted PG transaction and
   AFTER_COMMIT synchronizations) is not verified here. **Assumption:** leave the ordering alone;
   the IT records the observed behaviour either way and the fix does not depend on it.
4. Should a `NOT NULL`/length `CHECK` or a comment be added on `workflow_id` to stop someone
   re-narrowing it below 256 later? **Assumption:** no — the parity test enforces
   `correlation_id == workflow_id` width and the contract test pins the largest id at 219 ≤ 256.
