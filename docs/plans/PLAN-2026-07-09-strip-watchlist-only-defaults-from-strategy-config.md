# PLAN 2026-07-09 — Strip watchlist-only defaults from the shared StrategyConfig schema

## Incident / finding summary

The `/config` editor renders three WATCHLIST-only fields on COPYTRADE strategies
(e.g. `copytrade-v1`): `entry_mode`, `watchlist_expiry_rule`, `gap_tolerance_pct`.
copytrade never reads them — only the watchlist-trigger path does.

Root cause: `StrategyConfig` is ONE union schema for every strategy type
(`contract/schemas/strategy-config.json`). These three fields declare a **non-null
`default`**:

- `entry_mode: "BREAKOUT"` — `contract/schemas/strategy-config.json:320-324` (VERIFIED)
- `watchlist_expiry_rule: "NEAREST_WEEKLY"` — `:326-330` (VERIFIED)
- `gap_tolerance_pct: 0.005` — `:332-336` (VERIFIED)

When a copytrade config (whose YAML sets none of them —
`tenants/dev/strategies/copytrade-v1.yaml`, VERIFIED: no `entry_mode` /
`watchlist_expiry_rule` / `gap_tolerance_pct` keys) is deserialized and
canonicalized, jsonschema2pojo / pydantic fill the defaults, so copytrade's
canonical config carries the three inert keys and the editor renders them. The
already-opt-in watchlist fields (`sl_pct`, `tp_ratio`, `force_close_eod_et`, …)
are null-default and are correctly absent from copytrade. This surfaced when a
recent `/config` PR (#580/#582) turned `entry_mode` into a labeled dropdown.

### Fix (behavior-neutral)

Remove the `default` from those three fields so they become opt-in (null when
absent), exactly like `sl_pct`/`tp_ratio`. copytrade's canonical config then no
longer carries them → they stop rendering; watchlist behavior is preserved
because every consumer already falls back **in code** (verified below).

### Consumer verification (why this is behavior-neutral)

- `entry_mode` — `WatchlistTriggerWorkflowImpl.entryMode()`
  `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImpl.java:1049-1052`
  returns `BREAKOUT` when `getEntryMode()` is null. VERIFIED. Only caller of
  `getEntryMode()` is this method.
- `gap_tolerance_pct` — `WatchlistTriggerWorkflowImpl.gapTolerance()` `:1055-1058`
  returns `new BigDecimal("0.005")` when null. VERIFIED. Only caller of
  `getGapTolerancePct()`.
- `watchlist_expiry_rule` — **has NO consumer at all.** A repo-wide grep for
  `getWatchlistExpiryRule` finds zero call sites. The expiry is resolved
  unconditionally by `ExpirySelector.resolveNearestWeekly(...)` at
  `WatchlistTriggerWorkflowImpl.java:976`; the config field is spec-only and
  never read. So it is trivially null-safe — no code-side fallback needed. VERIFIED.

**Conclusion: no code-side null-default hardening phase is required.** All three
fields are already null-safe today, so the schema change ships as a single PR.

### Which strategies/fields actually change

- **copytrade-v1** (`tenants/dev/strategies/copytrade-v1.yaml`): sets NONE of the
  three → all three drop out of its canonical config. This is the whole fix.
- **watchlist-trigger-v1** (`tenants/dev/strategies/watchlist-trigger-v1.yaml:27-29`):
  explicitly SETS all three (`entry_mode: BREAKOUT`,
  `watchlist_expiry_rule: NEAREST_WEEKLY`, `gap_tolerance_pct: 0.005`) → they STAY
  in its canonical config. Watchlist behavior is unchanged (value read from YAML,
  not from the schema default). VERIFIED.
- `dev` is the only tenant in-repo (`tenants/dev` only). No other strategy YAMLs.

### Replay-safety verdict: NO `getVersion` needed

- The config values are read at the **activity boundary** (config → activity
  input inside `WatchlistTriggerWorkflowImpl`), not baked into a command's shape.
- The only workflow that branches on `entry_mode` (BREAKOUT vs RETEST, via
  `EntryStateMachine`) is `WatchlistTriggerWorkflowImpl`. Its input value is
  **unchanged** because `watchlist-trigger-v1.yaml` sets `entry_mode` explicitly.
- copytrade workflows never run the watchlist path and never read these fields,
  so copytrade's command stream is untouched.
- The coerced VALUE is identical either way: null→BREAKOUT == prior BREAKOUT,
  null→0.005 == prior 0.005, and `watchlist_expiry_rule` is not read at all.
- Per repo memory (Temporal 1.27 replay checks only command type/ordering;
  activity-input payload divergence is fail-closed, not history-wedging) — and
  here there is not even a value divergence. **No command shape depends on these
  defaults. No `getVersion`.**

---

## P0 / operator follow-ups (NOT code phases)

1. **One-time `TenantConfigChanged` audit diff on next orchestrator boot.**
   `TenantConfigChangedEmitter` diffs the freshly canonicalized copytrade config
   against the prior on-disk snapshot
   (`${orchestrator.snapshot-dir:${orchestrator.tenants-dir:tenants}/.snapshot}`,
   see `TenantConfigChangedEmitter.java:86-93`). After deploy, copytrade's
   canonical config loses three keys, so the first boot that diffs against a
   pre-change snapshot emits ONE benign `TenantConfigChanged` event (keys removed:
   `entry_mode`, `watchlist_expiry_rule`, `gap_tolerance_pct`), then re-stores.
   If the homelab snapshot dir is an ephemeral `emptyDir`, every restart is
   "first boot" (records-current, no event ever). If it's PVC-persisted, the
   event fires exactly once. Either way it is benign — expect it, do not alarm on
   it. No operator action beyond awareness.
2. **No `kubectl apply 40-tenants-config.yaml`.** No `tenants/dev/*.yaml` file
   changes (the defaults live in the SCHEMA, not the YAML), so the tenants
   ConfigMap drift guard is NOT triggered and no ConfigMap re-apply is needed.
   VERIFIED against both dev YAMLs.
3. **No live-tenant YAML edits, no broker toggles.** This is a contract/schema +
   test change only.

---

## Phase 1 (ONE PR) — Make the three watchlist-only defaults opt-in

**Concern:** remove the non-null `default` from `entry_mode`,
`watchlist_expiry_rule`, `gap_tolerance_pct` so they are null-when-absent.
All edits below are the same atomic concern — a schema change without the regen +
test + snapshot updates leaves CI red between commits, so they MUST ship together
in one PR.

### Changes (with anchors)

1. **Schema** — `contract/schemas/strategy-config.json`
   - Delete the `"default": "BREAKOUT"` line from `entry_mode` (`:323`).
   - Delete the `"default": "NEAREST_WEEKLY"` line from `watchlist_expiry_rule` (`:329`).
   - Delete the `"default": 0.005` line from `gap_tolerance_pct` (`:335`).
   - Leave `enum`/`type`/`minimum`/`description` intact. Trim the "DEFAULT" wording
     in each `description` to "null/absent = not set; consumers apply the code
     default" (behavioral doc only). All three are already absent from the
     schema's `required` list — no `required` edit needed. Do NOT touch
     `equity_emit_delta_pct` or `enabled` (see Out-of-scope fork below).

2. **Python model regen** — `contract/python/ohmytradeagent_contract/models/strategy_config.py`
   - Run `contract/python/regen.sh`. The three fields flip from
     `= EntryMode.breakout` / `= WatchlistExpiryRule.nearest_weekly` / `= 0.005`
     to `... | None = None`. Commit the regenerated model (the CI Python job runs
     `regen.sh` and fails on any uncommitted model drift — `.github/workflows/ci.yml:210-213`).

3. **Committed snapshot** — `tenants/.snapshot/dev/copytrade-v1.json` (git-tracked)
   - Remove the three keys `"entry_mode":"BREAKOUT"`, `"watchlist_expiry_rule":"NEAREST_WEEKLY"`,
     `"gap_tolerance_pct":0.005` to match the new canonicalize output. KEEP
     `"equity_emit_delta_pct":5.0E-4` and `"enabled":true` (their schema defaults
     remain). `tenants/.snapshot/dev/watchlist-trigger-v1.json` is UNCHANGED (its
     source YAML sets all three explicitly). This keeps the committed prior-diff
     baseline honest; it is runtime-regenerated so it is low-risk either way.
     (Minor fork — see below — if the team would rather gitignore this dir.)

4. **Test repairs (assert new opt-in behavior):**
   - Python `contract/python/tests/test_round_trip.py::test_strategy_config_new_fields_default_when_absent`
     (`:542-549`): change the three assertions to
     `model.entry_mode is None`, `model.watchlist_expiry_rule is None`,
     `model.gap_tolerance_pct is None`. KEEP `equity_emit_delta_pct == 0.0005`
     and `enabled is True`.
   - Java `contract/java/.../WatchlistTriggerContractsTest.java::strategyConfig_absentNewFields_appliesDefaults`
     (`:98-116`): change the three assertions to
     `assertThat(deserialized.getEntryMode()).isNull()`,
     `getWatchlistExpiryRule()).isNull()`, `getGapTolerancePct()).isNull()`.
     KEEP the `equity_emit_delta_pct == 0.0005` and `enabled == true` assertions.
     Rename the method to `...leavesNewFieldsNull` for accuracy.
   - Fixture-based tests stay GREEN and are NOT edited (the fixture
     `contract/fixtures/strategy-config-copytrade-v1.json` still carries the three
     values explicitly, so `WatchlistTriggerContractsTest.strategyConfig_copytradeV1Fixture_*`
     `:118-134`, `RoundTripTest.strategyConfig_roundTrips_*` `:277-297`, and Python
     `test_strategy_config_round_trips` `:191-211` all still pass). See fixture
     decision below.

5. **Behavioral-proof test (new):** the fix's positive proof.
   - Add an orchestrator unit test (co-locate with the canonicalize logic, e.g.
     `services/orchestrator/.../activities/TenantConfigSnapshotTest.java` or the
     existing snapshot test class) that deserializes a copytrade `StrategyConfig`
     which sets none of the three fields, calls
     `TenantConfigSnapshot.canonicalize(objectMapper, config)`
     (`services/orchestrator/.../activities/TenantConfigSnapshot.java:117`), and
     asserts the resulting map does NOT contain keys `entry_mode`,
     `watchlist_expiry_rule`, `gap_tolerance_pct` (and DOES still contain
     `enabled`). This is the direct regression guard for the incident.
   - Add/confirm a watchlist behavior-preserved assertion: a watchlist
     `StrategyConfig` with `entry_mode` UNSET still resolves to `BREAKOUT` via
     `WatchlistTriggerWorkflowImpl`'s `entryMode()` fallback. If no unit exercises
     the private `entryMode()` fallback directly, add a minimal test that
     constructs a `StrategyConfig` with null `entry_mode` and asserts the entry
     path treats it as BREAKOUT (or promote `entryMode()` coverage via the
     existing `WatchlistTriggerWorkflowImplTest`).

### Replay / CI hazards to name in this phase

- **Replay:** none. No command shape depends on these defaults (rationale above).
  Do NOT add a `getVersion` — it would be dead over-engineering here.
- **Python regen drift gate:** you MUST run `regen.sh` and commit the model diff,
  or the CI Python job fails (`ci.yml:210-213`).
- **spotless:** run `spotless:apply` on every touched Java module — `contract/java`
  (test edits) and `services/orchestrator` (new canonicalize test). The impl env
  skips spotless, so CI fails on it otherwise.
- **Cross-module:** removing the POJO default does NOT change the getter signature
  (`getEntryMode()` still returns `EntryMode`), so `orchestrator` (which depends on
  `contract-java`) still compiles; the only consumers are already null-safe.
- **ConfigMap drift:** not triggered (no `tenants/dev/*.yaml` change).
- **Snapshot drift:** there is NO snapshot-content CI gate (the only drift check,
  `scripts/check-tenants-configmap-drift.py` in `ci.yml:118-126`, covers
  `tenants/dev/*` vs the ConfigMap, not `.snapshot`). Updating the committed
  copytrade snapshot is a correctness/hygiene step, not a gate.
- **Flaky test:** if `KillSwitchWorkflowImplTest` flakes in the orchestrator leg,
  re-run — do not "fix" it.

### Success criteria

- copytrade canonical config (via `TenantConfigSnapshot.canonicalize`) no longer
  contains `entry_mode` / `watchlist_expiry_rule` / `gap_tolerance_pct` — asserted
  by the new orchestrator test.
- A watchlist config with unset `entry_mode` still resolves BREAKOUT — asserted.
- Python + Java "absent → default" tests updated to assert null, and all
  fixture-based round-trip tests remain green.
- `regen.sh` leaves the working tree clean after the model is committed.
- All touched Java modules pass spotless.
- `/config` for copytrade no longer lists the three fields (render is purely
  config-driven — confirmed in the finding; no dashboard change).

### Verify commands

```bash
# Python: regen drift + round-trip (the required Python round-trip check)
cd /home/ridopark/src/oh-my-tradeagent/contract/python
./regen.sh && git diff --exit-code ohmytradeagent_contract/models   # commit the expected strategy_config.py diff, then this passes
uv run pytest -q tests/test_round_trip.py
uv run pytest -q                                                     # full python contract suite

# Contract Java: regenerates the POJO from the schema + runs the contract tests
cd /home/ridopark/src/oh-my-tradeagent
mvn -B -ntp -pl contract/java spotless:apply
mvn -B -ntp -pl contract/java verify

# Orchestrator: build upstream deps without re-testing them, then verify the new canonicalize test
mvn -B -ntp -pl services/orchestrator -am -DskipTests -Dspotless.check.skip=true install
mvn -B -ntp -pl services/orchestrator spotless:apply
mvn -B -ntp -pl services/orchestrator verify
```

---

## Ship order

Single PR (one concern). No pre-phase code-side hardening is needed — all three
consumers are already null-safe. No deploy gating beyond a normal orchestrator +
contract redeploy; the one-time `TenantConfigChanged` diff is expected and benign
(P0 note 1).

---

## Forks to surface (need a lead/user decision)

1. **`equity_emit_delta_pct` — identical bug, excluded by the finding's scope.**
   It is also watchlist-only, also has a non-null default (`0.005`... actually
   `0.0005`, `strategy-config.json:338-342`), also leaks into copytrade's canonical
   config/snapshot, and its consumer `WatchlistTriggerWorkflowImpl.emitDeltaPct()`
   (`:1061-1064`) is already null-safe — so it is a one-line, zero-risk addition to
   this same phase IF the intent is to stop copytrade from carrying inert watchlist
   knobs. The finding explicitly scoped to three fields (the `/config` page shows
   three), so the **default here is to leave `equity_emit_delta_pct` alone**. Flag:
   confirm whether `/config` renders `equity_emit_delta_pct` for copytrade; if it
   does, fold it in (same schema line removal + same two test updates + same
   snapshot key drop). `enabled` is genuinely universal (per-strategy on/off) and
   correctly keeps its default.
2. **Committed `tenants/.snapshot/` — source artifact or runtime leak?** The files
   are git-tracked (`git ls-files tenants/.snapshot/` lists both), yet they are
   runtime-regenerated by the orchestrator. This plan updates the copytrade snapshot
   to stay consistent. If the team would rather treat `.snapshot/` as a
   runtime-only artifact, the alternative is to gitignore/untrack it and skip the
   edit (the one-time audit diff is benign either way). Low cost to guess wrong;
   default chosen = keep it tracked and update it. Flag for confirmation.
