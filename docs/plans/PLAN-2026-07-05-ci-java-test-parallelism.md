# PLAN — 2026-07-05 Speed up CI by parallelizing the Java test job

**Type:** CI/build-infra change, its own PR. **No production code, no Temporal replay surface, no
contract/schema/tenant-YAML change.** Touches `.github/workflows/ci.yml` and Maven surefire config only.

> ⚠️ CI-guardrail note: a PR that edits `.github/workflows/*.yml` cannot run its own Claude review/QA
> (see `feedback_claude_pr_workflow_edits`). Plain CI is the real gate — the change must be validated
> by the CI run itself (job wall-clock before/after), not by the bot.

## Problem

A full CI run takes ~10 min. Measured on run `28745441911` (2026-07-05):

| job | wall-clock |
|---|---|
| **Java (mvn verify + spotless)** | **469s** ← the entire bottleneck |
| Python (pydantic round-trip) | 16s |
| Python sidecar | 14s |
| k8s (kubeconform) | 8s |

The three non-Java jobs already run **in parallel** and finish in <20s. "Make CI faster" = "make the
Java job faster." Nothing else moves the needle.

## Are we parallelizing tests today? No.

- The Java job runs `mvn -B -ntp verify` — the **7 Maven modules build sequentially** (default reactor,
  no `-T`).
- **Surefire/failsafe have zero parallelism config** (grep of every `pom.xml`: no `parallel`,
  `forkCount`, `threadCount`, or `reuseForks`). Default = one forked JVM, tests run serially within it.
- So all 231 test files across all modules run one after another on a single runner, including 35
  Testcontainers ITs that each spin up their own Postgres.

Test-file distribution (the long pole is orchestrator, which is also the Temporal-heavy module):

| module | test files | notes |
|---|---|---|
| services/orchestrator | 100 | Temporal `TestWorkflowEnvironment` (auto-time-skip) — the slow, contention-sensitive suite |
| services/exec | 43 | |
| services/tenant-dashboard-bff | 43 | |
| services/api-gateway | 29 | |
| services/market-data | 9 | |
| contract/java | 4 | upstream dep of every service module |
| services/audit | 3 | |

**Contention caveat (important):** the flake this same day
(`CopytradeSignalWorkflowImplPreTradeDispatchTest`, see sibling plan
`PLAN-2026-07-05-deflake-pretrade-retry-budget-test.md`) was caused by **thread contention in the
single aggregate job** over-advancing Temporal's virtual clock. Lesson: naive *intra-JVM thread*
parallelism (`threadCount`) risks **more** time-skip flakes. Parallelism that gives each test suite
**process/runner isolation** is safer than shared-JVM threading. That steers the phase ordering below.

## Approach — parallelize across runners first (safest + biggest win)

### Phase 1 — Split the Java job into a per-module CI matrix *(primary win)*

Replace the single `java` job with a `strategy.matrix` over modules, each on its own runner:

```yaml
  java:
    name: Java ${{ matrix.module }}
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        module:
          - contract/java
          - services/orchestrator
          - services/exec
          - services/market-data
          - services/api-gateway
          - services/audit
          - services/tenant-dashboard-bff
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - name: mvn verify (${{ matrix.module }})
        env:
          RUN_DB_ITS: "true"
        run: mvn -B -ntp -pl ${{ matrix.module }} -am verify
```

- **Wall-clock → slowest single module (orchestrator), not the sum.** GitHub schedules the 7 matrix
  legs on separate runners concurrently.
- **Docker/Testcontainers is isolated per runner** → directly reduces the cross-suite contention that
  produced today's flake. Strict improvement on flake-rate, not just speed.
- `-am` rebuilds the upstream `contract/java` in each leg (duplicated *compile*, but parallel
  wall-clock and cheap ~10s). Acceptable; keeps each leg self-contained.
- `fail-fast: false` so one module's failure still lets the others report — better signal per PR.
- **spotless:** currently `verify` runs it in every module. Two options — pick during implementation:
  (a) keep it in each leg (simplest, redundant but parallel), or (b) hoist a dedicated fast
  `spotless-check` job (`mvn -B spotless:check`) for fail-fast formatting feedback and pass
  `-Dspotless.check.skip=true` in the matrix. Recommend (a) for minimal blast radius unless spotless
  proves slow.
- **Branch protection:** the required status-check name changes from `Java (mvn verify + spotless)` to
  per-matrix contexts (`Java services/orchestrator`, …). Update required checks in the same change or
  the merge gate silently stops enforcing. **Call this out in the PR description.**

**Verify:** compare the new max-matrix-leg wall-clock against the 469s baseline on the PR's own CI run.
Expectation: total Java wall-clock drops to roughly orchestrator-alone time.

### Phase 2 — Intra-orchestrator fork parallelism — IMPLEMENTED

> **Status (2026-07-05):** implemented alongside Phase 1 (`forkCount=2`, `reuseForks=true` in
> `services/orchestrator/pom.xml`). Local validation on the full orchestrator surefire suite (1022
> tests, `-DskipITs`, includes `-am` compile of exec+contract):
> `forkCount=1` → **2m19s**, `forkCount=2` → **1m45s** (~24% wall-clock, more on the test-only
> portion). 0 failures across repeated runs. CI is the final flake gate; if any of the 11 wall-clock
> `currentTimeMillis` orchestrator tests flake under runner contention, back off to `forkCount=1`
> (revert is one line, Phase 1 stands alone).

Orchestrator is the module long pole. Uses **process-level** (not thread-level) surefire parallelism
in `services/orchestrator/pom.xml`:

```xml
<configuration>
  <forkCount>2</forkCount>          <!-- or 1C; separate JVMs, not shared threads -->
  <reuseForks>true</reuseForks>
</configuration>
```

- `forkCount` gives each fork its **own JVM** → no shared static `TestWorkflowEnvironment` state, so it
  does **not** reintroduce the virtual-clock contention class. `threadCount`/JUnit-parallel would — do
  not use those for the Temporal suite.
- Must be validated with a **loop run (e.g. 20×)** of the orchestrator suite for flake regressions
  before merge, especially the known-flaky `KillSwitchWorkflowImplTest` and the just-fixed pre-trade
  test.
- Runner sizing: GitHub `ubuntu-latest` is 4 vCPU; `forkCount=2` fits. Higher forks + 35 Postgres
  containers can exhaust memory — measure, don't assume.

### Phase 3 — Testcontainers reuse *(optional, if IT startup dominates)*

35 IT classes each start a fresh Postgres. A shared/singleton container (or
`testcontainers.reuse.enable=true` in CI) cuts N cold-starts to ~1. Higher risk (cross-test state
bleed), so only pursue if Phases 1–2 leave IT container startup as the measured tail. Out of scope
unless data justifies it.

## Rejected / not now

- **`mvn -T 1C` on a single runner** (reactor thread-parallelism, one job): one-line change, but a
  4-vCPU runner caps the speedup, and concurrent Testcontainers on one Docker daemon + memory pressure
  reintroduces contention. Phase 1's per-runner isolation strictly dominates it.
- **Sharding a module's tests across matrix legs by class name:** more complex than Phase 1 for no
  benefit until a *single* module (orchestrator) is the proven floor — that's what Phase 2 addresses
  more simply.

## Success criteria

- CI wall-clock for the Java stage, measured on the PR's own run, drops materially from the 469s
  baseline (target: ≈ orchestrator-leg time after Phase 1).
- No new test flakes: the orchestrator suite (incl. `KillSwitchWorkflowImplTest`, the pre-trade
  retry-budget test) stays green across repeated runs.
- Branch-protection required checks updated to the new matrix contexts (no silent gate loss).

## Ship

- Own branch off `origin/main`, single-concern PR, separate from the deflake PR.
- CI-config only → no `getVersion`, no ConfigMap/tenant-YAML, no migration.
- Editing `ci.yml` disables the Claude PR bot on this PR — rely on the CI run itself as the gate and
  say so in the PR body.
