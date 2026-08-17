# PLAN — 2026-08-17 retire the orchestrator tenants ConfigMap

`kubectl apply -f infra/k8s/51-orchestrator.yaml` would today **stop the orchestrator pod from starting**: the manifest's `items:` projection names ConfigMap key `watchlist-trigger-v1.yaml`, which does not exist in the live `tenants-config` ConfigMap, and a missing `items` key fails the volume mount. `orchestrator` sits on `deploy.yml`'s `RESTART_ONLY` list for exactly this reason — that list is a quarantine for manifests that are unsafe to apply, not a preference.

Investigation (2026-08-17, live cluster + boot logs, read-only) found the ConfigMap is **vestigial**: it has never written a configuration value, and everything it feeds is either already DB-backed or validating a stale partial subset of the truth. Rather than sync a dead artifact into git forever, this plan removes it — after closing the two things it still genuinely buys.

## Evidence (all verified against the live cluster, 2026-08-17 07:34 boot)

| observation | source |
|---|---|
| Mount holds 3 tenant dirs / 6 files → **4** of the 8 live `(tenant, strategy)` pairs | `kubectl exec deploy/orchestrator -- find /etc/copytrade/tenants` |
| `strategy_config` has **8 rows / 5 tenants**; `updated_by` is `api-gateway:/strategy-config`, `operator:…`, `claude-derisk-canary` — **zero `seed:boot`** | `SELECT tenant_id, strategy_id, updated_by FROM strategy_config` |
| Both seeders seeded **0** at boot | `tenant_config seed reconciler: seeded 0 tenants, 3 already present` / `strategy_config seed reconciler: seeded 0 strategies, 4 already present` |
| Seeders **cannot** overwrite | `StrategyConfigSeedReconciler.java` — `INSERT … ON CONFLICT (tenant_id, strategy_id) DO NOTHING` |
| Account-cap resolver is DB-backed in prod | `AccountKillSwitchConfig.java` — `@ConditionalOnProperty(name = "strategy.config.source", havingValue = "db")` → `DbTenantStrategies`; the `ScannerTenantStrategies` bean is not constructed |
| `TenantReconcileLoop` ensured **all 8 pairs** from the DB registry at boot+60s — a strict superset of the ConfigMap's 4 | `tenant reconcile: ensured 8 new (tenant, strategy) pair(s) this tick` @ 07:35:19 |
| Every consumer tolerates a missing mount (`Files.exists` → `log.warn` → skip) | `TenantConfigBootstrapper:60`, `TenantConfigSeedReconciler:73`, `StrategyConfigSeedReconciler:74`, `LiveRequiredGateValidator:61`, `KillSwitchBootstrapper:53` |

**What the mount still buys, and what this plan must therefore replace:**

1. Kill-switch + reconciliation-schedule start **at boot** for 4 of 8 pairs — roughly 60 s earlier than `TenantReconcileLoop` would (Phase 1).
2. Three boot-time validators — tenant-config invariant, live-required-gate, cross-tenant `broker_target` — which currently inspect the YAML tree, i.e. a stale 4-of-8 subset, not what the live read path serves (Phase 2).

**Incidental defect found:** `TenantConfigBootstrapper` logged `validated for 5 tenant(s)` against a directory holding 3. `Files.list(...).filter(Files::isDirectory)` counts Kubernetes' atomic-write artifacts — `..data` (a symlink, which `isDirectory` follows) and `..2026_08_17_07_34_10.3332253454` — as tenants, then calls `tenantRegistry.get("..data")`. It does not throw today only because `DbTenantRegistry.get` tolerates an unknown tenant; if that ever fails closed, boot breaks on a Kubernetes filename. Same pattern at `TenantStrategyScanner.java:43-44`. Fixed for free by Phase 2 (no more `Files.list`).

## Replay safety — applies to the whole plan

**No `Workflow.getVersion` marker is needed anywhere in this plan.** Nothing here changes the command shape of a running workflow:

- `TenantReconcileLoop` is a Spring `@Scheduled` bean and explicitly *not* workflow code — see its own class javadoc: "A Spring `@Scheduled` bean lives entirely outside Temporal workflow history, so there is NO `Workflow.getVersion` marker here."
- `KillSwitchBootstrapper` **starts** workflows (`REJECT_DUPLICATE`); starting is not replay-sensitive.
- The validators and seeders are `ApplicationRunner`s that touch Postgres and the filesystem, never workflow history.

State this in each PR body so a reviewer does not ask for a gate that would be noise.

## P0 — Immediate operational (no code; operator)

- **Do not run `kubectl apply -f infra/k8s/51-orchestrator.yaml` (or a directory-wide apply that includes it) until Phase 4 has merged and deployed.** It will leave the orchestrator unable to start. `deploy.yml` will not do this on its own — `orchestrator` is on `RESTART_ONLY`.
- The k8s drift check will keep reporting `51-orchestrator.yaml` and `40-tenants-config.yaml` as drifted until Phase 4. That is correct and expected; it is not a new finding.
- **No cleanup, broker action, or tenant change is required.** Nothing is broken right now; this is a latent trap plus dead weight.

## Decision required before Phase 3 — fresh-cluster seeding

Today the ConfigMap is what populates `strategy_config` / `tenant_config` on a **brand-new cluster with an empty DB**. Retiring it removes that path. Two options, materially different, so the operator picks:

- **(a) Accept API-only onboarding.** A fresh cluster is seeded through the api-gateway `/strategy-config` write path or the `/config` UI — the same route every live row already came from (`updated_by` shows this). Simplest; makes the DB unambiguously authoritative. Cost: a fresh cluster boots with zero tenants until someone onboards one.
- **(b) Keep a seed path.** Retain the seeders reading `tenants/dev/**` **from the image** (not a ConfigMap), so a fresh cluster comes up with the `dev` tenant only. Costs one retained code path; keeps local/dev parity with prod boot.

Phase 3 is written for **(a)** with the (b) delta noted inline. Do not start Phase 3 until this is answered.

---

## Phase 1 — Close the 60 s boot window (services/orchestrator)

**Goal:** every `(tenant, strategy)` in the DB registry has its kill switch and reconciliation schedule ensured *at startup*, so removing the mount in Phase 4 does not leave 4 pairs unprotected for the first tick.

**Changes** (anchors):
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/bootstrap/TenantReconcileLoop.java:75-77` — the `@Scheduled` currently sets `initialDelayString` equal to `fixedDelayString` (default 60000 ms), so the first pass runs a full minute after boot. Add an initial pass that runs once at startup: an `ApplicationRunner` (or `@EventListener(ApplicationReadyEvent)`) invoking the same `reconcileTick()`. Leave the schedule itself unchanged.
- **Add explicit serialization — there is none today, and this is the trap in this phase.** `TenantReconcileLoop.java:50-58` documents that ticks are serialized only because "default Spring `fixedDelay` scheduling already serializes ticks on a single thread"; the `seen` set is a `ConcurrentHashMap.newKeySet()` purely so a future multi-threaded `TaskScheduler` cannot corrupt it. An initial pass invoked from an `ApplicationRunner` runs on the **main** thread and therefore sits entirely outside that implicit guarantee — it can interleave with the first scheduled tick. Introduce an explicit lock (or `AtomicBoolean` guard) around the body of `reconcileTick()` and have both entry points take it. Do not rely on the existing comment's guarantee; it does not cover this new caller.
- Ordering: run **after** the existing boot `ApplicationRunner`s so a warm boot still short-circuits on `REJECT_DUPLICATE` rather than racing them. `StrategyConfigSeedReconciler` is `@Order(Ordered.HIGHEST_PRECEDENCE)`; the initial pass must be lowest precedence.

**Version gate:** none — see "Replay safety" above.

**Tests (TDD):**
- `TenantReconcileLoopTest#initialPassEnsuresEveryRegistryPairAtStartup` — registry returns 8 pairs; assert `ensureForTenantStrategy` called for all 8 **before** any scheduled tick fires.
- `TenantReconcileLoopTest#initialPassDoesNotRunConcurrentlyWithAScheduledTick` — the lock is held; reproduce by driving both entry points.
- `TenantReconcileLoopTest#initialPassFailureIsRetriedOnTheNextTick` — a pair that fails is NOT added to `seen` (mirrors the existing per-pair contract at `:93-100`).
- **Incident reproduction:** `TenantReconcileLoopTest#coversPairsAbsentFromTheTenantsDir` — with the tenants dir empty/absent, all 8 registry pairs are still ensured. This is the assertion that makes Phase 4 safe.

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test`
- **Behavioral:** boot the orchestrator with `orchestrator.tenants-dir` pointing at a nonexistent path → log shows kill-switch + schedule ensured for all registry pairs, and the `tenant reconcile: ensured N new` line appears **before** boot completes rather than 60 s later.
- `KillSwitchWorkflowImplTest` is a known flake — **re-run it, do not fix it**.

---

## Phase 2 — Validate against the DB, not the YAML tree (services/orchestrator)

**Goal:** the three boot invariants check what the live read path actually serves, and stop depending on a mounted directory. Also fixes the `..data` miscount.

**Changes** (anchors):
- `services/orchestrator/.../bootstrap/TenantConfigBootstrapper.java:59-77` — replace `Files.list(tenantsDir).filter(Files::isDirectory)` (`:64-66`) with an enumeration from the tenant registry. Keeps the fail-closed intent at `:70-73` (the loop calls `tenantRegistry.get(...)`, so a bad `account_daily_loss_pct` still throws and boot fails closed). The `Files.exists` early-return at `:60-63` becomes dead — remove it too.
- `services/orchestrator/.../bootstrap/LiveRequiredGateValidator.java:59-72` — `validate` takes `tenantsDir` and calls `TenantStrategyScanner.scan(tenantsDir)` at `:68`; source the pairs from `registry.list()` instead. Its own javadoc at `:25` already says the goal is to validate "exactly the config the live read path will serve" — today it does not.
- `services/orchestrator/.../bootstrap/CrossTenantBrokerTargetBootstrapper.java:53` — same substitution inside `CrossTenantBrokerTargetValidator.validate(...)`.
- **Keep yaml-mode working for dev/tests.** Mirror the existing pattern at `AccountKillSwitchConfig` (`@ConditionalOnProperty(name = "strategy.config.source", havingValue = "db")` with a `matchIfMissing = true` yaml counterpart) rather than inventing a new flag — that comment block explicitly warns an independent flag is "one more ConfigMap value a live cluster could forget to set, silently reverting the cap to the tree scan (the exact 2026-07-21 prod-kipark silent-unprotect)."

**Version gate:** none.

**Tests (TDD):**
- `TenantConfigBootstrapperTest#ignoresKubernetesAtomicWriteArtifacts` — **incident reproduction**: a tenants dir containing `..data` (symlink) and `..2026_08_17_07_34_10.3332253454` alongside 3 real tenants must validate **3**, not 5. Assert this fails against the current implementation before fixing.
- `LiveRequiredGateValidatorTest#validatesEveryDbPairIncludingThoseAbsentFromTheTree` — a pair present in the registry but not on disk (`prod-jinchul`, `paper_jinchiul` today) is validated.
- `CrossTenantBrokerTargetValidatorTest#detectsCollisionOnlyVisibleInDb` — two tenants sharing a `broker_target` where one exists solely in the DB. This is a real coverage gain: today that collision is invisible.
- Yaml-mode regression: existing tests must still pass unchanged under `strategy.config.source` unset.

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test`
- **Behavioral:** with the mount absent, boot logs `tenant-config invariant validated for 5 tenant(s)` — 5 being the DB tenant count, no longer coincidentally equal to a directory-entry count.

---

## Phase 3 — Retire the ConfigMap-fed seeders (services/orchestrator)

**Blocked on the fresh-cluster decision above. Written for option (a).**

**Goal:** stop reading the tenants tree on the prod boot path, without a per-boot `log.warn` once the mount is gone.

**Changes** (anchors):
- `services/orchestrator/.../bootstrap/StrategyConfigSeedReconciler.java` — gate the bean on yaml-mode (same `@ConditionalOnProperty` pattern as Phase 2) so db-mode never constructs it. Its `TODO(P0c)` at the constructor already anticipates this: "when seeder/validators move onto the DB path, split a standalone YAML reader component so this transitional self-construct can go away."
- `services/orchestrator/.../bootstrap/TenantConfigSeedReconciler.java:73-74` — same treatment.
- **Do NOT delete `tenants/dev/**`.** `YamlStrategyRegistry` still reads it for local dev and tests under `strategy.config.source` unset.
- *If option (b) is chosen instead:* leave both beans enabled, and point `orchestrator.tenants-dir` at an image-baked `tenants/` path rather than `/etc/copytrade/tenants`. Phase 4 then removes only the mount, not the property.

**Version gate:** none.

**Tests (TDD):**
- `StrategyConfigSeedReconcilerTest#beanAbsentInDbMode` — Spring context in db-mode has no such bean. **Note the trap:** a context test that never asserts bean absence passes vacuously; assert on `ApplicationContext#getBeanNamesForType` being empty, and confirm the assertion fails when the condition is removed.
- `#seedsInYamlMode` — unchanged behaviour with the property unset.

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test`
- **Behavioral:** db-mode boot logs contain **no** `seed reconciler` line and **no** `tenants dir … not found` warning.

---

## Phase 4 — Drop the volume and retire the ConfigMap (infra/k8s)

**Goal:** make `51-orchestrator.yaml` safe to apply, and delete the dead manifest.

**Changes** (anchors):
- `infra/k8s/51-orchestrator.yaml:143-145` — remove the `tenants` entry from `volumeMounts` (`mountPath: /etc/copytrade/tenants`). **Keep** `tenants-snapshot` at `:146-147` — that is an unrelated `emptyDir` for `TenantConfigChangedEmitter` (Issue #88).
- `infra/k8s/51-orchestrator.yaml:163-175` — remove the `tenants` volume, including the `items:` projection at `:169-175` that is the actual trap. **Keep** the `tenants-snapshot` volume at `:181-182`.
- `infra/k8s/51-orchestrator.yaml:83-84` — remove `ORCHESTRATOR_TENANTS_DIR: /etc/copytrade/tenants`; it names the mount being deleted. Code keeps its `${orchestrator.tenants-dir:tenants}` default for dev, so nothing else needs to change.
- **Delete** `infra/k8s/40-tenants-config.yaml` (160 lines).
- **Delete** `scripts/check-tenants-configmap-drift.py` — it exists solely to compare `40-tenants-config.yaml` against `tenants/dev/*` (see its `CONFIGMAP` constant at `:25` and the key map at `:30-32`). Removing the ConfigMap without removing this script breaks CI.

**Tests:** none — manifest change. Correctness rests on the operator verification below plus Phases 1–3 having made the mount unnecessary.

**Verify / success criteria:**
- `kubeconform -strict` passes (the `k8s (kubeconform)` CI job).
- **The k8s drift check must report `51-orchestrator.yaml` CLEAN** on the PR. That is the executable proof the manifest now matches the cluster — and this file has been drifted since before the check first ran.
- **Behavioral (operator, pre-open only):**
  ```sh
  kubectl -n copytrade apply -f infra/k8s/51-orchestrator.yaml
  kubectl -n copytrade rollout status deploy/orchestrator
  ```
  The pod must reach `1/1 Running`. Before this phase, this exact command leaves it stuck in `ContainerCreating`.
  Then confirm the reconcile coverage Phase 1 added:
  ```sh
  kubectl -n copytrade logs deploy/orchestrator | grep 'tenant reconcile: ensured'
  ```
  All 8 `(tenant, strategy)` pairs, at boot rather than boot+60 s.

**Operator follow-ups (not code):**
- `kubectl -n copytrade delete configmap tenants-config` — **only after** the rollout above is confirmed healthy. Do this last; it is the irreversible step.
- Per `deploy.yml`'s apply scope, shared manifests are never applied by CI; `40-tenants-config.yaml` was already manual, so nothing in the pipeline needs changing for its deletion.
- **Timing: pre-open with no armed trails.** This rolls the orchestrator pod. It does not roll market-data, but a pod roll during the session is not free.

---

## Phase 5 — Un-quarantine orchestrator (.github/workflows) — SEPARATE PR

**Goal:** take `orchestrator` off `RESTART_ONLY` now that its manifest is applicable, and remove the CI step for the deleted drift script.

**Changes** (anchors):
- `.github/workflows/deploy.yml` — the `RESTART_ONLY="orchestrator dashboard api-gateway signal-source-discord tenant-dashboard-bff"` line (`:335` after #707; `:309` before). Remove `orchestrator`.
- `.github/workflows/ci.yml:118-126` — remove the `tenants ConfigMap drift vs tenants/dev/*` step, whose script Phase 4 deleted.

**Why this is its own PR:** a PR touching `.github/workflows/*.yml` cannot run its own Claude review (known GitHub guardrail) — plain CI is the gate. Bundling it with Phase 4 would forfeit review on the manifest change. Both workflow edits belong together in this single PR.

**Verify / success criteria:**
- CI green; the `k8s (kubeconform)` job no longer runs the deleted script.
- **Behavioral:** the next merge touching `infra/k8s/51-orchestrator.yaml` shows `deploy.yml` **applying** it rather than logging `has live-only overrides — skipping apply`.
- Must merge **after** Phase 4 has deployed and the orchestrator is confirmed healthy. Merging it earlier would let CI apply the still-trapped manifest.

---

## Ship order & gating

| # | Phase | Module | Risk | Gate |
|---|---|---|---|---|
| 1 | Close the boot window | orchestrator | low — additive, no removal | must merge + deploy before Phase 4 |
| 2 | DB-based validators | orchestrator | low — behaviour-preserving in yaml-mode | |
| 3 | Retire the seeders | orchestrator | low | **blocked on the fresh-cluster decision** |
| 4 | Drop volume, delete ConfigMap | infra/k8s | medium — pod roll | **pre-open only**; operator applies by hand; drift check must read CLEAN |
| 5 | Un-quarantine + CI cleanup | .github/workflows | low | **after** Phase 4 is deployed and healthy |

Each phase: TDD-first, `spotless:apply` on every touched module, its own PR, operator merge gate. Phases 1–3 are ordinary deploys. Phase 4 is the only one requiring a market-hours decision.

**Rollback:** Phases 1–3 are additive and independently revertible. Phase 4's rollback is `git revert` plus re-applying `40-tenants-config.yaml` and `51-orchestrator.yaml` — which is why the `kubectl delete configmap tenants-config` step is deliberately last and separate from the merge.

## What this plan does NOT do

- It does not change how tenant or strategy configuration is **read**. That has been the DB (`TENANT_CONFIG_SOURCE=db`, `STRATEGY_CONFIG_SOURCE=db`) since before this plan; nothing here alters the live read path.
- It does not address the other manifests the drift check flagged — `10-postgres.yaml` (live init ConfigMap missing `create_db_if_missing dashboard`, latent until a Postgres rebuild) or `57-audit-completeness-check-cron.yaml` (a CronJob that has never existed in the cluster, and whose manifest hardcodes `--tenant=dev`). Both are separate work.
- It does not remove the other four services from `RESTART_ONLY`. Each needs its own manifest made applicable first, on the same pattern.
