# PLAN — 2026-10-30 preinit-stc-drain-dedupe

Issues: #1023 (production fix, version-gated) + #1024 (forced test). Parent #1016. One PR.
Branch `fix/1023-preinit-stc-drain-dedupe`, base `b6ad6321` (= origin/main at planning time).

## Goal

When two or more copies of the same STC signal id are all buffered before `run()` assigns `input`
(all land in the first workflow task), `PositionWorkflowImpl` places the exit ONCE and records each
later copy as an `ExitDuplicateSuppressed` audit instead of placing it again, and every in-flight
PositionWorkflow history recorded before this change still replays deterministically.

**Done when:**
- `mvn -pl services/orchestrator -am test -Dtest=PositionWorkflowImplContinueAsNewTest#freshRun_allCopiesInFirstTask_drainSuppressesDuplicates_placeOnce -Dsurefire.failIfNoSpecifiedTests=false`
  passes on the branch, and the Step 1 log shows the same test failing on unmodified production code
  with `[three copies, one placement] expected: 1L but was: 2L`.
- `PositionWorkflowImplLegacyReplayTest#preInitDrainDoublePlaceLegacyHistoryReplaysWithoutNonDeterminism`
  passes against a fixture that contains TWO `:exit:sig-y` placements and NO
  `preinit-stc-drain-dedupe-v1` marker; with the gate defeated the same test throws
  `NonDeterministicException` (exception text recorded verbatim in the test javadoc).
- The three pre-existing PositionWorkflow replay fixtures (`position-pre-276-legacy-history.json`,
  `position-carried-run-history.json`, `position-preinit-redelivery-legacy-history.json`) are
  byte-identical to `b6ad6321` (`git diff b6ad6321 --stat -- services/orchestrator/src/test/resources/temporal/replay/` lists only the new file) and their tests pass.
- `mvn -pl services/orchestrator -am test` is green (full module, not `surefire:test`) and
  `mvn -pl services/orchestrator -am spotless:check` is clean.
- `services/audit/.../AuditEventKinds.java` is unchanged (`ExitDuplicateSuppressed` is already in
  `ALL_KINDS` line 288 and `PARTIAL_EXIT_FILL_KINDS` line 60; no alerter under
  `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/alert/` references it, so it
  does not page).
- Merged and deployed outside regular trading hours; after the roll,
  `kubectl -n <ns> logs deploy/orchestrator --since=30m | grep -c NonDeterministic` prints `0`.

## Non-goals

- No change to the exec side (`ExecActivitiesImpl.placeOrder` 422 handling, `resolveDuplicateByClientOrderId`, reprice keys). The issue documents those consequences; this PR removes the cause.
- No change to `VERSION_PREINIT_STC_DEDUPE` or `VERSION_CARRIED_PREINIT_DEDUPE` semantics or literals.
- No new audit kind, no `AuditEventKinds` edit, no dashboard/BFF change.
- No dedupe inside the `partialExit` handler's `input == null` branch (handler-side `getVersion` is order-sensitive; the repo's rule is to gate in the main loop — see the `VERSION_BUFFERED_OPERATOR_AUDIT` javadoc at `PositionWorkflowImpl.java:855-867`).
- No re-recording of any existing replay fixture.
- No general flake sweep of `PositionWorkflowImplContinueAsNewTest`; only the two tests named below are touched.

## Spec

### Problem Statement

A copytrade author's STC can be delivered to a position workflow more than once (redelivery, pod roll, continue-as-new backlog). If two or more copies all arrive before the workflow's first task runs `run()`, every copy is buffered as new and the main-loop drain places each one: the second placement reuses the intent key, exec resolves it to the already-filled order, the workflow waits 90 s for a fill that never comes, then re-prices under NEW keys — real extra SELL orders. A 50% STC on a 6-lot can leave 1 instead of 3. Capped at held quantity (never naked), but over-selling against the author's instruction on live accounts. 0 instances in 60 days of live/paper journals; forced reproduction 20/20; loaded CI reproduction 2/100.

### Solution

At the main-loop drain, a pre-init-buffered request whose signal id has already been recorded as processed is suppressed with an `ExitDuplicateSuppressed` audit instead of being placed. The suppression is behind a new Temporal version marker read only on that branch, so histories that never hit it carry no marker and histories recorded before the fix replay through their recorded double placement. The test that was supposed to pin this case is rewritten to force the ordering deterministically; the early-poll ordering is kept as a second test.

### User Stories

1. As a live copytrade tenant, I want a redelivered STC never to sell more than the author's fraction, so that my position matches the author's.
2. As the operator, I want an in-flight PositionWorkflow to survive the deploy of this fix without a non-deterministic replay error, so that no live position wedges or flattens on the roll.
3. As the operator, I want each suppressed duplicate to leave an `ExitDuplicateSuppressed` row carrying the signal id, so that the audit trail explains why one STC produced one order.
4. As the operator, I want the suppression audit to look like the existing duplicate audits, so that the audit verifier and dashboards need no change.
5. As a maintainer, I want the test for the all-copies-pre-init case to fail every time on the old code and pass every time on the new code, so that CI stops reporting it as a flake.
6. As a maintainer, I want a replay fixture that actually exercises the gated branch, so that a future un-gated edit to the drain fails CI.
7. As a maintainer, I want the misleading "measured SDK ordering" comment corrected, so that the next reader does not conclude the drain needs no dedupe.
8. As a maintainer, I want the new version constant pinned by name and acknowledged in the carry-forward classification test, so that a rename or an unclassified gate fails the build.

### Implementation Decisions

- Module: `services/orchestrator`, workflow `PositionWorkflowImpl`, the `pendingExits` drain in the main loop (today lines 2182-2203). The `partialExit` handler's pre-init buffer (lines 2449-2463) is NOT changed.
- New constant `VERSION_PREINIT_STC_DRAIN_DEDUPE = "preinit-stc-drain-dedupe-v1"`, declared beside `VERSION_PREINIT_STC_DEDUPE` (line 893) with a javadoc in the same style (what, why gated, why read in the main loop only on the duplicate branch). The name must start with `VERSION_` — `PositionWorkflowCarryForwardClassificationTest.everyGetVersionCallIsKeyedOnAVersionConstant` enforces it.
- Drain restructure (decision-rich, so the shape is spelled out; ordering of the two version reads is the point):
  ```
  req = pendingExits.poll()
  if (preInitCarriedDuplicates.remove(req) && getVersion(CARRIED_PREINIT_DEDUPE) >= 1) { audit }   // unchanged
  else {
    boolean preInit = preInitBuffered.remove(req);
    if (preInit && processedSignalIds.contains(req.signalId)
        && getVersion(PREINIT_STC_DRAIN_DEDUPE, DEFAULT, 1) >= 1) {          // NEW: read only here
      auditLog(ExitDuplicateSuppressed, signal_id, note, deferred_until_init=true)
    } else {
      if (preInit && getVersion(PREINIT_STC_DEDUPE, DEFAULT, 1) >= 1) processedSignalIds.add(id);  // unchanged
      processOne(req);
    }
  }
  ```
  `preInitBuffered.remove` must still run exactly once per drained request (it did before; the set is identity-keyed). The `contains` check comes BEFORE the new `getVersion`, so a history that never had a duplicate never records the marker. On a legacy history that did hit the duplicate, the new read resolves `DEFAULT_VERSION` (no marker present), execution falls into the else, the second `getVersion(PREINIT_STC_DEDUPE)` returns the already-resolved value without a new command, and `processOne` re-issues the recorded `placeOrder` — byte-identical.
- Audit subject: use `note=preinit_drain_duplicate` (distinct from the carried-duplicate's `note=duplicate_signal_id`) plus `signal_id` and `deferred_until_init=true`. No new kind, no new vocabulary beyond the note value (the kind `ExitDuplicateSuppressed` is already classified as a fill-fulfilling kind in `AuditEventKinds`). Decided by operator 2026-10-30 so SQL can distinguish this race from the carried-preinit-duplicate path.
- Temporal SDK is 1.27.0 (root `pom.xml:27`): replay compares command type/order and activity id, not activity inputs; a suppression replaces a `placeOrder` ScheduleActivityTask with an `audit.log` ScheduleActivityTask at the same position, which the SDK catches (see Repo gates for how).
- Acknowledge the new constant in `PositionWorkflowCarryForwardClassificationTest.VERSION_GATES_ACKNOWLEDGED` (line 162ff., next to `VERSION_PREINIT_STC_DEDUPE`). It resolves to max on a carried run like its siblings; it tightens (suppresses an order), never loosens a stop, so the set's HARD RULE is satisfied.
- Pin the literal in `PositionWorkflowImplLegacyReplayTest` with a `versionPreinitStcDrainDedupeConstantNameIsStable` test, following the existing `version*ConstantNameIsStable` pattern.

### Testing Decisions

- A good test here observes only public seams: the `PositionWorkflow` stub (signals, `positionState()` query), the mocked `ExecActivities.placeOrder` / `AuditActivities.log` activity boundaries, the workflow history fetched through `WorkflowClient`, and `WorkflowReplayer`. No reflection into `pendingExits`/`preInitBuffered`.
- Forced ordering: the workers must not be polling until the start and all three `partialExit` signals are in history. The existing `suspendPolling()` approach is insufficient (a long poll already waiting still takes the first task). Build the `TestWorkflowEnvironment` and register workers/mocks, send `signalWithStart` + two `partialExit` through `env.getWorkflowClient()`, assert the fetched history has three `WorkflowExecutionSignaled` events and zero `WorkflowTaskStarted`, THEN `env.start()`. The simplest way inside this class is to split `setUp()` into "build" and "start" (e.g. `buildEnv()` + `env.start()`), and have the forced test `env.close()` and rebuild without starting. Keep the split minimal; do not restructure other tests.
- Prior art: `freshRun_preInitStc_redeliveries_areDeduped` (same file, lines 1100-1117) for the assertion style; `AccountKillSwitchWorkflowImplLegacyReplayTest.markerVersions` (lines 1473-1485) for scanning `MarkerRecorded` events by `changeId`; `carriedRunHistoryReplaysAgainstCurrentImplWithoutNonDeterminism` (LegacyReplayTest lines 431-460) for asserting fixture preconditions before replaying; `regenerateCarriedRunFixture` for an `@EnabledIfSystemProperty`-gated recorder that drives the REAL impl to completion and writes `fetchHistory(...).toJson(true)`.
- Fixture arithmetic (from `processOne`, line 3876ff., `ceil`): 6 lots, three 0.5 copies on old code → copy 1 places 3 (6→3), copy 2 places `ceil(1.5)=2` (3→1), copy 3 hits the runner-quantum SKIP (`PartialExitSkippedMinQty`, no order). The recorder fills 3, fills 2, then sends a full-close `sig-full` (fraction 1.0), fills 1 and waits for `getResult` so the history is terminal. Expected fixture content: exactly two `ActivityTaskScheduled` events whose input contains `:exit:sig-y`, one whose input contains `:exit:sig-full`, zero `MarkerRecorded` with `changeId=preinit-stc-drain-dedupe-v1`.
- Marker-absence check (gate read ONLY on the duplicate branch) goes on a test whose ordering is deterministic and never hits the branch: `freshRun_preInitStc_redeliveries_areDeduped` (bundled copy pre-init, later copies in-handler). It must NOT go on the early-poll test, whose ordering is the 2/100 non-deterministic one.
- Early-poll test (`freshRun_copiesQueuedInFirstTask_onlyBundledOneIsPreInit_placeOnce`, lines 1119-1157): keep it, but make it ordering-agnostic (under the fix both orderings yield one placement, two audits, remaining 3), rewrite its javadoc and the `.as("both later copies suppressed in the handler...")` description, which are false in the all-pre-init case.
- Mutation checks run with `mvn test`, never `surefire:test` (stale classes make a mutant "survive").

### Out of Scope

See Non-goals. Also: measuring the SDK's handler-vs-run dispatch order in general; any change to continue-as-new carry-forward; re-examining the exec 422 path.

### Further Notes

- Step 1 deliberately commits a RED test on top of unchanged production code. It is one PR; only the final head must be green. The red run is the "fails on main today" evidence #1024 asks for, and recording the legacy fixture on the pre-fix code is only guaranteed if production code is untouched when the recorder runs.
- The recorder relies on `env.getWorkflowClient()` working before `env.start()` (the in-memory service starts in `newInstance()`, `start()` only starts the worker factory). The history precondition assertion (3 signals, 0 task started) is what proves this; if it fails, stop (Failure checklist).
- A rolling deploy replays every in-flight PositionWorkflow on the new image. Several tenants hold multi-day copytrade positions, so the replay fixtures are the only pre-deploy evidence; that is why the toothiness proof is a hard requirement, not a nicety.

## Steps

### Step 1 — Force the all-copies-pre-init case red, and mint the legacy double-place fixture (tests only)

**Goal:** On unmodified production code, a deterministic test demonstrates the double placement, and a replay fixture recorded from that same ordering captures it for Step 2.
**Blocked by:** none.
**Seams:**
- `PositionWorkflow` stub + mocked `ExecActivities.placeOrder` / `AuditActivities.log` under `TestWorkflowEnvironment` — catches placement count, intent keys, audit kinds/subjects, remaining qty; misses the real exec journal/Alpaca 422 behaviour.
- `WorkflowClient.fetchHistory` — catches the ordering precondition (all signals before any task) and marker presence/absence; misses nothing else about behaviour.

Work:
1. In `PositionWorkflowImplContinueAsNewTest`, split `setUp()` into build + `env.start()` (minimal). Add
   `freshRun_allCopiesInFirstTask_drainSuppressesDuplicates_placeOnce`: rebuild env without starting, `signalWithStart(partialExit sig-y 0.5, futureInput(6))` + two more `partialExit(sig-y 0.5)`, assert history precondition, `env.start()`, `confirmEntry(6)`, `waitForPlaceOrderAtLeast(1)`, `onFill(brk-exit, 3, 2.60)`, bounded wait, then assert: intent keys of all `placeOrder` calls `containsExactly(wfId + ":exit:sig-y")`; `auditKinds("ExitDuplicateSuppressed")` filtered on `signal_id=sig-y` has size 2; `positionState().remainingQty() == 3`. Javadoc states the SDK ordering as the issue describes it (all copies reach the handler before `input` is set when they share the first task).
2. Rename/rewrite the existing early-poll test's javadoc and assertion description to be ordering-agnostic (keep the test; same three assertions as above; drop the "suppressed in the handler" claim).
3. In `PositionWorkflowImplLegacyReplayTest`, add `regeneratePreInitDrainDoublePlaceFixture` gated by `-Dgenerate.preinit.drain.fixture=true`, driving the REAL `PositionWorkflowImpl` with the forced ordering through fill 3 → fill 2 → `sig-full` 1.0 → fill 1 → `getResult`, writing `src/test/resources/temporal/replay/position-preinit-drain-double-place-legacy-history.json`. Run it ONCE now, on unmodified production code. Add `preInitDrainDoublePlaceLegacyHistoryReplaysWithoutNonDeterminism` asserting the fixture preconditions (two `:exit:sig-y` scheduled placements, zero `preinit-stc-drain-dedupe-v1` markers) and replaying against `PositionWorkflowImpl.class`. Javadoc: "recorded once on b6ad6321, NEVER re-record".
4. `mvn -pl services/orchestrator -am spotless:apply`; commit tests + fixture.

**Success checklist**
- [ ] Forced test fails on unmodified production code with the double placement — evidence: `mvn -pl services/orchestrator -am test -Dtest=PositionWorkflowImplContinueAsNewTest#freshRun_allCopiesInFirstTask_drainSuppressesDuplicates_placeOnce -Dsurefire.failIfNoSpecifiedTests=false` output containing `expected: 1L but was: 2L` (or the `containsExactly` equivalent showing two `:exit:sig-y` keys), 3 of 3 consecutive runs.
- [ ] The ordering precondition holds — evidence: the test's own assertion on fetched history (3 `WorkflowExecutionSignaled`, 0 `WorkflowTaskStarted` before `env.start()`) passes in those runs (the failure is at the placement assertion, not the precondition).
- [ ] Fixture exists and has the double place — evidence: `python3 -c` or `jq` over the JSON counting `activityTaskScheduledEventAttributes` whose input contains `:exit:sig-y` = 2, `:exit:sig-full` = 1, and `markerRecordedEventAttributes` with changeId `preinit-stc-drain-dedupe-v1` = 0; last event is `WorkflowExecutionCompleted`.
- [ ] New replay test passes on unmodified production code — evidence: `-Dtest=PositionWorkflowImplLegacyReplayTest#preInitDrainDoublePlaceLegacyHistoryReplaysWithoutNonDeterminism` green.
- [ ] Early-poll test still passes and no longer claims handler-only suppression — evidence: test green; `grep -n "suppressed in the handler" PositionWorkflowImplContinueAsNewTest.java` returns nothing for that test.
- [ ] Only test files and the one new fixture changed — evidence: `git diff --stat b6ad6321` lists `PositionWorkflowImplContinueAsNewTest.java`, `PositionWorkflowImplLegacyReplayTest.java`, the new JSON, nothing else.
- [ ] Spotless clean — evidence: `mvn -pl services/orchestrator -am spotless:check` exit 0.

**Failure checklist** (any one: STOP and report)
- [ ] The forced test passes on unmodified production code in any of 3 runs (the ordering is not forced; do not loosen the assertion).
- [ ] The history precondition cannot be met (the client cannot be used before `env.start()`, or a task starts before the third signal) — do not fall back to `suspendPolling()`.
- [ ] The recorded fixture contains fewer than two `:exit:sig-y` placements or already contains a `preinit-stc-drain-dedupe-v1` marker.
- [ ] Any existing fixture under `temporal/replay/` changes.
- [ ] Any file under `src/main` changes.

### Step 2 — Drain dedupe behind `preinit-stc-drain-dedupe-v1`, replay-proven

**Goal:** The forced test is green, every replay fixture (old and new) is green, and the fixture is proven to have teeth.
**Blocked by:** Step 1.
**Seams:** same two as Step 1, plus
- `WorkflowReplayer.replayWorkflowExecution(history, PositionWorkflowImpl.class)` — catches a command-shape divergence under `DEFAULT_VERSION`; misses activity input payloads (SDK 1.27 does not compare them) and any branch the fixture does not exercise (which is why the defeat check below is mandatory).

Work:
1. `PositionWorkflowImpl`: add `VERSION_PREINIT_STC_DRAIN_DEDUPE` with javadoc; restructure the drain exactly as in Implementation Decisions.
2. `PositionWorkflowCarryForwardClassificationTest.VERSION_GATES_ACKNOWLEDGED`: add `"VERSION_PREINIT_STC_DRAIN_DEDUPE"`.
3. `PositionWorkflowImplLegacyReplayTest`: add `versionPreinitStcDrainDedupeConstantNameIsStable`.
4. `freshRun_preInitStc_redeliveries_areDeduped`: append an assertion that the fetched history has NO `preinit-stc-drain-dedupe-v1` marker. Forced test: append an assertion that its history HAS that marker with version 1.
5. Mutants, each run with `mvn -pl services/orchestrator -am test -Dtest=<class>#<method> -Dsurefire.failIfNoSpecifiedTests=false`, then restored:
   - M1 remove the suppression (always `processOne`) → forced test must fail.
   - M2 defeat the gate (`>= 1` → `>= Workflow.DEFAULT_VERSION`) → new replay test must fail; copy the exception text verbatim into that test's javadoc.
   - M3 hoist `getVersion(VERSION_PREINIT_STC_DRAIN_DEDUPE, ...)` above the `contains` check → the marker-absence assertion in `freshRun_preInitStc_redeliveries_areDeduped` must fail.
6. `mvn -pl services/orchestrator -am spotless:apply`; full `mvn -pl services/orchestrator -am test`; commit.

**Success checklist**
- [ ] Forced test green — evidence: 5 of 5 consecutive runs of `-Dtest=PositionWorkflowImplContinueAsNewTest#freshRun_allCopiesInFirstTask_drainSuppressesDuplicates_placeOnce` pass, log shows one `placeOrder`, two `ExitDuplicateSuppressed(sig-y)`, remaining 3, marker `preinit-stc-drain-dedupe-v1` = [1].
- [ ] Early-poll test green and the other pre-init tests green — evidence: `-Dtest=PositionWorkflowImplContinueAsNewTest` passes.
- [ ] All PositionWorkflow replay tests green — evidence: `-Dtest=PositionWorkflowImplLegacyReplayTest` passes (pre-276, carried-run, preinit-redelivery, new double-place, all `*ConstantNameIsStable`).
- [ ] Marker read only on the duplicate branch — evidence: `freshRun_preInitStc_redeliveries_areDeduped` asserts zero `preinit-stc-drain-dedupe-v1` markers and passes.
- [ ] M1 killed — evidence: the forced test's failure output with the mutant applied (`expected: 1L but was: 2L` or two `:exit:sig-y` keys).
- [ ] M2 killed — evidence: `NonDeterministicException` (or the SDK's wording) from `preInitDrainDoublePlaceLegacyHistoryReplaysWithoutNonDeterminism`, pasted verbatim into its javadoc; the three pre-existing fixtures still pass with the mutant applied (proves the new fixture, not an old one, is the tripwire). **Amended at review (2026-10-30):** the pre-#964 `position-preinit-redelivery-legacy-history.json` also fails under M2 (event 71) because it reaches the duplicate branch too; today's code (post-#964) does not double-place there. A second tripwire, not a defect.
- [ ] M3 killed — evidence: the marker-absence assertion fails with the mutant applied.
- [ ] Mutants restored — evidence: `git diff` shows the intended production change only; `grep -c "preinit-stc-drain-dedupe-v1" PositionWorkflowImpl.java` = 1 (the constant) and `grep -c "VERSION_PREINIT_STC_DRAIN_DEDUPE" PositionWorkflowImpl.java` = 2 (declaration + one read).
- [ ] Classification and literal pins green — evidence: `-Dtest=PositionWorkflowCarryForwardClassificationTest` passes.
- [ ] Full module green — evidence: `mvn -pl services/orchestrator -am test` exit 0 (not `surefire:test`); `mvn -pl services/orchestrator -am spotless:check` exit 0.
- [ ] `AuditEventKinds.java` untouched — evidence: `git diff --stat b6ad6321 -- services/audit` is empty.
- [ ] Production diff confined to the drain + constant/javadoc — evidence: `git diff b6ad6321 -- services/orchestrator/src/main` touches only `PositionWorkflowImpl.java`, hunks only at the constant block (~line 893) and the drain (~lines 2182-2203).

**Failure checklist** (any one: STOP and report)
- [ ] Any mutant survives after a clean `mvn test` compile (first suspect: stale classes; re-run with `mvn test`; if it still survives the test is toothless).
- [ ] Any pre-existing replay fixture fails, or the fix only passes by editing/re-recording a fixture.
- [ ] The `partialExit` handler needs a change, or a `getVersion` read is needed inside a handler.
- [ ] `VERSION_PREINIT_STC_DEDUPE`'s literal, read sites, or semantics need to change.
- [ ] The forced test needs a sleep lengthened past existing helpers' 50 s deadlines, or an assertion weakened, to pass.
- [ ] A new audit kind or an `AuditEventKinds` edit turns out to be needed.
- [ ] Any file outside `PositionWorkflowImpl.java` and the three test classes named above changes.

## Repo gates

- **Spotless:** `mvn -pl services/orchestrator -am spotless:apply` before every commit; `spotless:check` in the success checklist. CI fails on it otherwise.
- **Mutants and verification runs use `mvn test`, never `surefire:test`** (plugin goals skip compilation; the mutant never runs).
- **Temporal replay (SDK 1.27.0):** every behaviour change on a recorded command path is behind `Workflow.getVersion`, read in the MAIN LOOP only on the changed branch. The new fixture must be proven to exercise the branch by defeating the gate and watching it fail (memory: two fixtures here were toothless before; one recorded at a config value that disabled the branch). Activity input payloads are not compared on replay, so the replay test proves command shape, not values — the round-trip tests cover values.
- **Never re-record** `position-pre-276-legacy-history.json`, `position-carried-run-history.json`, `position-preinit-redelivery-legacy-history.json`, and after merge the new `position-preinit-drain-double-place-legacy-history.json`.
- **`PositionWorkflowCarryForwardClassificationTest`:** a new `VERSION_*` constant must be acknowledged in `VERSION_GATES_ACKNOWLEDGED`, and every `getVersion` changeId must be a `VERSION_*` constant.
- **Update-before-run() race:** the fix stays in the main loop where `input` is assigned; no handler change.
- **Audit kinds:** `ExitDuplicateSuppressed` is already registered and classified; no `ALL_KINDS` edit, nothing pages on it.
- **Schema / ConfigMap / kubectl:** not touched (no `strategy-config.json`, no shared manifest, no tenant YAML).
- **Deploy:** orchestrator is a trading-path service. Merge and deploy OUTSIDE regular trading hours. The local PR/deploy agent holds an orchestrator deploy while the market is open and any PositionWorkflow is running; when closed, only non-terminal orders block. After the roll, grep orchestrator logs for `NonDeterministic` (expect 0) and confirm the open PositionWorkflows are still Running in Temporal namespace `copytrade`.
- **PR body:** `Closes #1023` and `Closes #1024` (without the keyword the issues stay open and the drain re-picks them).

## Open questions

1. **Audit subject for the drain-suppressed duplicate.** Planned on reusing the carried-duplicate shape (`note=duplicate_signal_id`, `deferred_until_init=true`) so no new vocabulary reaches the verifier or dashboard. If the operator wants the two drain paths distinguishable in SQL, a distinct note such as `duplicate_preinit_drain` is a one-line change inside the same gated branch and needs no test rework beyond the subject assertion.
   **Decided by operator 2026-10-30:** (b) the drain-suppressed duplicate uses a DISTINCT audit note, `note=preinit_drain_duplicate` (keep the existing `ExitDuplicateSuppressed` kind), so SQL can tell this race apart. Step 2 implements it; Step 2's success checklist subject assertion updated accordingly.
2. **Split of `setUp()` in `PositionWorkflowImplContinueAsNewTest`.** Planned on the minimal build/start split with the forced test rebuilding its own env. If the builder finds a smaller way to keep workers from polling until the signals are in history that still satisfies the history precondition assertion, that is acceptable; `suspendPolling()` is not (the issue shows it does not cancel the waiting long poll).
   **Decided by operator 2026-10-30:** Accepted — the setUp() build/start split with the history precondition asserted.
3. **Red commit mid-PR.** Planned on committing Step 1 red (test + fixture) and Step 2 green in the same PR. If the operator prefers a single squashed commit, squash at merge; the Step 1 log is still the "fails on main" evidence.
   **Decided by operator 2026-10-30:** Accepted — Step 1 may commit the red test; the PR will be squash-merged.
