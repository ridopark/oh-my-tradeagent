# Plan — Alert on an STC that has no live position to close (OrphanSTC)

## Context / incident

An STC ("sell to close") copytrade signal arrived for `QQQ 260605C00742000`, whose
`PositionWorkflow` had **Failed** ~8h earlier (the over-sell incident; fix in #357).
The signal produced only:
```
SignalReceived
ExitRequested {fraction:0.5}
```
…and then **nothing** — no `OrphanSTC`, no `SignalRejected`, no fill, **no failure alert**.
The operator only saw the info-level "Signal received" Discord message and had no idea the
STC couldn't be applied. Meanwhile reconciliation logged `PositionOrphan/JournalOrphan` every
~30 min, but that is a delayed, broker-side audit record — not an STC-outcome alert.

## Root cause (verified in code)

`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/CopytradeSignalWorkflowImpl.java`, `handleStc()` (~lines 487-580):

1. It resolves the target position via `positionLookup.findPositionWorkflowId(tenant, strategy, occ)` (~line 499), which reads a **Redis cache with a 24h TTL** (`PositionLookupActivitiesImpl.java` `CACHE_TTL`, ~line 31). When a `PositionWorkflow` **Failed/closed**, the cache is **not** invalidated, so the lookup returns the **dead workflow id** instead of `null`.
2. Because a (stale) id came back, the handler emits `ExitRequested` (~lines 546-553) — assuming success — and dispatches:
   ```java
   ExternalWorkflowStub stub = Workflow.newUntypedExternalWorkflowStub(positionId);
   stub.signal("partialExit", req);   // ~line 555-556 — NO try/catch
   ```
3. Signalling a non-running/Failed workflow fails; the exception is **uncaught**, so the whole `CopytradeSignalWorkflow` **crashes (FAILED)** and never emits an outcome audit.
4. The existing `OrphanSTC` path (~lines 514-523) only fires when the lookup returns **null** (cache miss). The stale-cache-returns-dead-id case slips past it.
5. `OrderFailureAlerter` already alerts on `OrphanSTC` (its default failure-kinds = `"OrphanSTC,EntryExpired"`, `STC_KINDS = {"OrphanSTC"}`) — so emitting `OrphanSTC` here would produce the missing Discord alert. The kind exists; it's just never emitted for this case.

## The fix (version-gated)

In `handleStc`, behind ONE new version gate (CopytradeSignalWorkflow is determinism-sensitive; the new check adds a command, so in-flight handleStc executions must replay unchanged):

1. **Preventive — verify the position is actually running before committing to the exit.** After a non-null `positionId` comes back from the cache and BEFORE emitting `ExitRequested`, call the existing `positionLookup.isPositionWorkflowRunning(positionId)` activity (already used by reconciliation, `PositionLookupActivitiesImpl` ~line 82). If it is NOT running:
   - Emit `OrphanSTC` with a `reason` field distinguishing this case (e.g. `reason:"position_workflow_not_running"`), carrying `signal_id`, `option_symbol`, `position_workflow_id`.
   - `return` gracefully (no `ExitRequested`, no dispatch, no crash).
2. **Defense-in-depth — never crash on a dispatch race.** Wrap `stub.signal("partialExit", req)` in try/catch. If it still throws (a TOCTOU race: the workflow died between the running-check and the dispatch), emit `OrphanSTC` with `reason:"signal_dispatch_failed"` (+ the error message) and return gracefully instead of failing the workflow.
3. **Reuse the existing `OrphanSTC` audit kind** (already in `OrderFailureAlerter`'s allowlist → produces the Discord alert). Do NOT invent a new kind. Keep the existing cache-miss `OrphanSTC` path intact; the new emissions just add a `reason` so the three orphan causes (cache-miss vs not-running vs dispatch-failed) are greppable.

### Constraints / invariants
- Determinism: all logic in the workflow; the new `isPositionWorkflowRunning` call is an activity (a command) — gate it so v=0 in-flight `handleStc` replays are byte-identical. No `Instant.now`/`UUID`/`Math.random`.
- The `CopytradeSignalWorkflow` must **complete gracefully** (not FAILED) on an orphaned STC — a failed signal workflow is itself an observability/retry hazard.
- Keep the happy path (live position → `ExitRequested` → `partialExit` signal) unchanged.
- Idempotency: emitting `OrphanSTC` then returning must not double-process; ensure only one terminal outcome per STC.

## Tests (TDD)

Add to `services/orchestrator/src/test/java/.../workflows/CopytradeSignalWorkflowImplTest.java` (existing style: `TestWorkflowEnvironment`, mocked orchestrator-core activities incl. `positionLookup`, real child workflows where needed):

1. **`stcAction_cachedPositionWorkflowNotRunning_emitsOrphanStc` (headline):** mock `findPositionWorkflowId` to return a non-null id, and `isPositionWorkflowRunning(id)` → `false`. Assert: an `OrphanSTC` audit is emitted (with `reason` = not-running); **no** `ExitRequested`; **no** `partialExit` signal dispatched; the workflow **completes** (does not fail).
2. **`stcAction_signalDispatchThrows_emitsOrphanStcAndCompletes` (defense-in-depth):** `isPositionWorkflowRunning` → true, but the `partialExit` dispatch throws (simulate the workflow dying mid-dispatch). Assert: caught → `OrphanSTC` (`reason` = dispatch-failed) → workflow completes (not FAILED).
3. **Keep green:** `stcAction_cacheHit_dispatchesExitRequestedAudit` (live position still routes `ExitRequested` + signal) and `stcAction_cacheMissAndBufferExpires_emitsOrphanStc` (the existing null-lookup path) — unchanged behavior under the new gate.
4. If a `CopytradeSignalWorkflow` legacy-replay test exists, confirm it passes (v=0 unchanged); else verify replay-safety by construction (new command only under the gate).

## Success criteria (must all hold)
1. `mvn -B -ntp -pl services/orchestrator -am test` → BUILD SUCCESS, 0 failures (KillSwitchWorkflowImplTest is known-flaky: re-run once).
2. Test 1 passes and FAILS without the fix (genuinely reproduces the silent-STC gap → it asserts `OrphanSTC` emitted + no crash).
3. An STC against a non-running/Failed position emits exactly one `OrphanSTC` (no `ExitRequested`), and the workflow completes — verified by the new tests.
4. The existing happy-path and cache-miss OrphanSTC tests stay green (no behavior change for live positions or true cache-misses).
5. `OrderFailureAlerter` would post a Discord alert for the new `OrphanSTC` (it's in the failure-kind allowlist — confirm the kind/string matches, no alerter change needed).
6. `mvn -B -ntp -pl services/orchestrator spotless:apply` then `spotless:check` clean.

## Halt conditions
- If the fix would require changing v=0 `handleStc` behavior or removing an existing version gate → stop and surface (replay-safety risk).
- If emitting `OrphanSTC` here double-counts in any audit/ledger group (it should not — OrphanSTC is a failure/observability kind, not a fill kind; verify against `AuditEventKinds` groupings) → stop and reconsider the kind/reason.

## Verification commands
```
mvn -B -ntp -pl services/orchestrator -am -Dtest=CopytradeSignalWorkflowImplTest test
mvn -B -ntp -pl services/orchestrator -am test
mvn -B -ntp -pl services/orchestrator spotless:apply && mvn -B -ntp -pl services/orchestrator spotless:check
```

## Out of scope
- Reducing the Redis position-cache TTL / invalidating it on workflow failure (a separate preventive tuning; the running-check makes a stale cache harmless for STC routing regardless).
- Surfacing reconciliation `PositionOrphan`/`JournalOrphan` to the Discord alert channel (a separate observability improvement).
- The over-sell root cause that caused the workflow to fail (already fixed in #357).
- Any change to `SignalFeedAlerter`'s received/accepted/rejected feed.
