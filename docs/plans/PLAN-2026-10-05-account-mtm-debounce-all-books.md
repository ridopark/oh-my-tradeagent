# PLAN — 2026-10-05 account-mtm-debounce-all-books

At 09:34:55 ET on 2026-10-05, prod_real's account kill switch (live acct 847309116) fail-closed
with `auto:account_mtm_unavailable` on a day with no real loss. A homelab upstream DNS outage
(09:34:50–09:35:32 ET; CoreDNS i/o timeouts to 1.1.1.1/1.0.0.1) made at least 2 of the 3 position
quotes fail on one 60s heartbeat. PLAN-2026-07-22 added an in-tick refetch and a 2-consecutive-tick
debounce, but scoped both to books of 2 positions or fewer (`SMALL_BOOK_MAX_POSITIONS = 2`). A
3-position book goes down the relative `>50%` path, which trips on the first failing tick. The next
heartbeat (~09:35:55) came after connectivity returned at 09:35:32, so the existing 2-tick debounce
would have prevented this halt. No orders were blocked and nothing was flattened (`flatten: manual`).

**Source:** `_workspace/01_forensics_2026-10-05_mtm_unavailable_halt.md` (F1 infra root cause, F2
design gap, F3 transport-vs-data observation).
**Scope (operator-facing lead, closed):** R1 is the only code phase. R2 is a deferred issue. R3 is operator work.

---

## P0 — Immediate operational (no code; operator)

1. **Reset the prod_real account kill switch** from the dashboard once the cooldown has passed (already
   communicated). Before resetting, find the 3rd broker position on /live or in the broker UI (the trip
   payload says 3, recon names only GOOGL + SMCI; forensics "Unverified"). The trip is sticky by
   design: `auto:account_mtm_unavailable` does not clear on rollover (`AccountKillSwitchWorkflowImpl.java:122`,
   exact-match on `auto:account_daily_loss`).
2. **R3: homelab DNS hardening + dead-man alert.** Two upstream blips in one morning (05:00 and 09:34
   ET). Options: give CoreDNS a secondary upstream (router plus a second public resolver), and/or use
   NodeLocal DNSCache or CoreDNS `cache` with `serve_stale`, so a 60s ISP blip doesn't take in-cluster
   resolution down with it. Revive the dead-man alert that was deferred on 2026-10-03 with the mobile epic.
   This is infra work outside the repo's deploy path; it needs no code PR.
3. **After the Phase 1 PR merges:** the operator runs `deploy.yml`, which rolls the orchestrator. Check
   the image digest, not the timestamp. No ConfigMap is involved (the tenants ConfigMap is retired),
   and there is no `kubectl apply`, tenant YAML or schema step.
4. **Effectiveness lag (important):** a running execution keeps the marker value it already recorded
   (`account-mtm-debounce-v1` = 2) until its next continue-as-new. Continue-as-new triggers only on
   `getHistoryLength() > historyLengthWatermark (10_000)` (`:609-610`). My estimate is about one
   continue-as-new per day; I have not measured it. **Until prod_real's account execution
   continues-as-new, a repeat blip can still trip it.** To verify: find the prod_real account
   kill-switch workflow with `temporal workflow list -n copytrade` (id pattern
   `t-<tenant>/account/killswitch` per the tests; confirm it). Then check that the new run's history
   has an `account-mtm-debounce-v1` MarkerRecorded at version **3**. Do the same for prod-kipark and
   staging_paper.

---

## Phase 1 — Debounce the MTM-unavailable fail-close on ALL book sizes (orchestrator; VERSION-GATED)

**Goal:** a large-book (`listed > 2`) relative `>50%` MTM-unavailable condition must persist for
`MTM_UNAVAILABLE_TRIP_TICKS` (2) consecutive heartbeats before it trips. This is the same rule small
books already follow. The cap stays fail-closed: a sustained outage still trips, one tick later.

### Anchors (verified by reading at HEAD `add61deb`)
`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/AccountKillSwitchWorkflowImpl.java`
- `:96` `KIND_ACCOUNT_MTM_DEFERRED = "AccountKillSwitchMtmDeferred"`. It is already registered at
  `services/audit/.../AuditEventKinds.java:398`. The renderer `AccountKillSwitchCapAlerter.buildMtmDeferredEmbed`
  (`:173-195`) works for any book size. **No new kind, no alerter change.**
- `:206-220` `VERSION_ACCOUNT_MTM_DEBOUNCE = "account-mtm-debounce-v1"` and its javadoc (says "A LARGE
  book's relative >50% failure still fail-CLOSES immediately", which becomes stale).
- `:318` `HEARTBEAT_INTERVAL = 60s`; `:344` `SMALL_BOOK_MAX_POSITIONS = 2`.
- `:346-355` `static int MTM_UNAVAILABLE_TRIP_TICKS = 2`, with a javadoc that says the large book is
  "unaffected", now stale. **Not config-driven:** it is a package-private non-final static so tests can
  override it, and there is no tenant-config or schema field for it. The only schema mention is the
  carry description in `contract/schemas/account-kill-switch-workflow-input.json:59`. **Leave that
  untouched** (editing the schema regenerates 3 artifacts).
- `:504-523` field `consecutiveMtmUnavailableTicks` (javadoc says "small-book"). It is already carried
  across continue-as-new (`:569`, `:757-791`), reset on new day (`:876`), on a clean tick (`:1115`) and on
  reset (`:1609`). **No carry or reset change is needed.** The same counter now also counts large-book ticks.
- `:840-852` the single stable-scope read
  `int mtmDebounceVersion = Workflow.getVersion(VERSION_ACCOUNT_MTM_DEBOUNCE, DEFAULT_VERSION, 2);`.
- `:1034-1111` the fail-close block. The gate is at `:1041`
  (`if (mtmDebounceVersion >= 1 && book.listed() <= SMALL_BOOK_MAX_POSITIONS)`), the refetch call at
  `:1044`, the exposure re-cache at `:1053`, the counter/defer at `:1059-1095`, the deferred audit at
  `:1077-1093` (`>= 2`) and the trip at `:1102-1110` (`doTrip("auto:account_mtm_unavailable", ...)` at `:1103-1108`).
- `:1224-1244` `refetchSmallBookQuotes`. **Unchanged.**
- `:1435-1439` `failsClosed`. **Unchanged.** Do not loosen the `>50%` bound or the small-book floor.

### Change (surgical)
1. **Widen the existing gate** from `:852` `maxSupported 2 → 3`, at the same position, so marker order
   is unchanged. Add a `v>=3` note to the `:840-850` comment block.
2. **`:1041` gate:** at `v>=3`, enter the debounce block for any book size. Keep the in-tick refetch
   and its re-cache for small books only:
   ```java
   boolean smallBook = book.listed() <= SMALL_BOOK_MAX_POSITIONS;
   if (mtmDebounceVersion >= 1 && (smallBook || mtmDebounceVersion >= 3)) {
     if (smallBook) {            // :1044-1053 unchanged, just nested
       valued = refetchSmallBookQuotes(book, valued);
       combinedFailures = book.valueFailures() + valued.quoteFailures();
       cacheOpenBookExposure(book, valued);
     }
     // :1059-1095 counter / WARN / deferred-audit / return false — UNCHANGED
   }
   ```
   At `v>=3`, a large book's first failing tick increments the counter and emits the existing
   `AccountKillSwitchMtmDeferred` audit (`v>=3` implies `>=2`), which pages YELLOW. Then it returns
   `false`. The second consecutive failing tick falls through to the existing `doTrip` at `:1102`.
   The subject already carries `listed`, so a large-book defer can be told apart with no new field.
3. **Javadoc and comments only for your own stale text:** `:206-219`, `:346-354`, `:504-511`,
   `:1035-1040`, `:1097`. Replace "large book trips immediately" with "large book debounced at v>=3;
   in-tick refetch remains small-book only". Do **not** edit `AuditEventKinds.java` or
   `AccountKillSwitchCapAlerter.java` comments that say "small-book". They are stale but harmless, and
   editing them would pull in another module. Mention them in the PR body.

### Decision: refetch stays small-book (justification)
- The debounce alone prevents this incident: the next tick (~09:35:55) came after recovery at 09:35:32.
- The refetch could not have helped here. It does 2 attempts at a 2s delay, so it covers about 4s of a
  40–90s DNS outage. Extending it would add `2 × listed` quote activities plus 2 timers per failing
  large-book tick (more history, faster continue-as-new) and buy nothing for transport outages.
- It keeps the diff smaller: one boolean and a nesting change, versus a refetch path for a new book size.
- Cost: a sub-4s quote blip on a large book now costs one deferred tick and a YELLOW page instead of
  being absorbed within the tick. The cap still does not trip. R2 is the better fix for that case.

### Version gate / replay safety (MUST)
- This is a command-shape change. At `v>=3` a large-book failing tick emits an audit command plus
  `return false` where it used to emit `doTrip`'s commands, and the trip moves to a later tick.
- `v ∈ {DEFAULT, 1, 2}` histories take the old `listed <= 2` branch and stay byte-identical. The
  counter is pure workflow state and adds no commands.
- **Gate fork (flagged for the lead, low cost either way):** the brief proposed a **new** change-id
  (`account-mtm-debounce-large-book-v1`). This plan **widens the existing id instead**, because:
  - The repo already did exactly this for this same feature. The `:846-850` comment says "Widening the
    existing change-id (not a second marker) is the correct pattern". The test javadoc at
    `AccountKillSwitchWorkflowImplLegacyReplayTest.java:355-363` says the same.
  - It means no new constant, no new marker read, and no marker-order concern.
  - Both options are replay-safe, and the same fixture (below) proves either one.
  - If the lead prefers a new id: add `VERSION_ACCOUNT_MTM_DEBOUNCE_LARGE_BOOK` and read it **last**,
    after `:867-868` (`clearOnlyWhenArmable`), at maxSupported 1. Gate on
    `mtmDebounceVersion >= 1 && (smallBook || largeBookVersion >= 1)`, keep `:852` at 2, and add a
    constant-name-stability test.
- **Rolling deploy:** an old pod (max 2) that gets a task for a fresh execution that recorded v3 fails
  that workflow task and retries until a new pod takes it. This is normal for a widen (the v2 widen
  shipped the same way). The workflow does not fail.

### Tests (TDD — write first, watch them fail, then implement)
In `services/orchestrator/src/test/java/.../workflows/AccountKillSwitchWorkflowImplTest.java`:
1. **Incident reproduction.** `heartbeat_largeBookAllQuotesFail_debouncesThenFailsClosedOnSecondTick`:
   3-position book, all 3 quotes `UNAVAILABLE` (the provider returns UNAVAILABLE for DNS errors, see R2),
   `MTM_UNAVAILABLE_TRIP_TICKS=2`, refetches left at their default.
   - Tick 1 (`env.sleep(75s)`): not tripped, `KillSwitchTripped` count is 0, one `AccountKillSwitchMtmDeferred`
     with `listed=3, failures=3, consecutive_ticks=1, trip_ticks=2`, and
     `verify(optionQuote, times(3)).getOptionQuote(any())`. That last check pins "no refetch on large books".
   - Tick 2 (`+60s`): tripped, reason `auto:account_mtm_unavailable`, `flatten=manual`, `open_positions=3`,
     still exactly one deferred audit.
   - **Replaces** `heartbeat_largeBookRelativeFailure_failsClosedImmediately_unchanged` (`:577-607`),
     which asserts the behavior being fixed. Delete it and say so in the commit.
2. **Recovery counterfactual (2026-10-05).** `heartbeat_largeBookOneFailingTickThenPriced_doesNotTrip`:
   tick 1 has 2 of 3 failing, then the stub is switched to all-OK. Tick 2 does not trip, the counter
   resets, and a 3rd tick that fails again only defers (proves the reset). There is one deferred audit
   per episode, so 2 in total.
3. **Small book unchanged.** All existing `heartbeat_smallBook*` tests (`:326-575`, `:676-897`) and the
   `killswitchState_*Refetch*` tests (`:1262-1320`) pass **unmodified**. Add
   `heartbeat_smallBookStillRefetchesInTick` only if no existing test already asserts the refetch count;
   `heartbeat_smallBookBlipClearsViaInTickRefetch_doesNotDeferOrTrip` at `:553` likely does.
4. **Sustained outage still fails closed.** Covered by test 1's tick 2. Also update
   `heartbeat_unexpiredContractsUnpriceable_stillFailsClosed` (`:1024-1053`): it currently trips after one
   tick. Change it to two ticks (75s, then +60s) and assert a trip after tick 2, keeping its "unexpired
   contracts still fail closed" intent. Fix its `:1026` comment.
   `heartbeat_lossBreachWithPartialMiss_dailyLossWinsImmediately` (`:642`, 1 of 3, below the bound) and
   `heartbeat_genuineDailyLoss_tripsFirstTick_debounceNotConsulted` (`:614`) must pass unmodified:
   a real loss still trips immediately.

In `.../workflows/AccountKillSwitchWorkflowImplLegacyReplayTest.java`:

5. **Legacy replay sentinel.** `legacyLargeBookImmediateTripV2HistoryReplaysCleanly` plus fixture
   `src/test/resources/temporal/replay/account-killswitch-largebook-trip-v2-legacy-history.json`.
   - **Fixture recipe (preferred):** add `regenerateLargeBookTripV2Fixture`
     (`@EnabledIfSystemProperty(named="generate.legacy.fixture", matches="true")`). It runs the
     **production `AccountKillSwitchWorkflowImpl` as it is before this change**, with the same mock
     wiring as `AccountKillSwitchWorkflowImplTest#setUp`: 3-position book, 2 of 3 `UNAVAILABLE`,
     unexpired OCCs. It captures the first tick (immediate trip) plus one tripped tick, while the
     workflow is still running, via `fetchHistory`. Every marker is then at its real current value
     (debounce = 2), which is exactly the shape of prod_real's in-flight history.
   - Commit the fixture in the PR's **first commit, before** the impl change.
   - Make the generator `assertThat` that the history contains a `MarkerRecorded` for
     `account-mtm-debounce-v1`, so a post-change regeneration is caught in review.
   - Javadoc the generator as "one-shot, pre-change only".
   - Fallback if the full mock wiring is impractical: copy `LegacyMtmDeferEmulatorWorkflowImpl`
     (`:1319-1450`) with `getVersion(..., 2)`, a 3-position book, and the trip path reusing the
     `legacyDoTrip` of the no-flatten emulator (`:1116`).
   - **Replay:** pin `MTM_UNAVAILABLE_TRIP_TICKS = 2` in try/finally, as `:379-389` does. Leave the
     refetch count at its default 2, so a refetch leaking into large books also diverges.
   - **Toothless check (MANDATORY; past fixtures here were toothless twice):** temporarily mutate
     `:1041` to ungate the large book (`mtmDebounceVersion >= 1 && (smallBook || mtmDebounceVersion >= 1)`).
     Run the replay test with `mvn ... test`, **not** `surefire:test`, or the mutant never compiles. It
     **must fail** with `NonDeterministicException`. Revert. Record the failing output in the PR body.
6. **Existing sentinels stay green unmodified.** `legacyMtmDeferV1HistoryReplaysCleanlyNoEmit` (v1
   small-book defer under max 3), `versionAccountMtmDebounceConstantNameIsStable` (`:234-240`, string
   unchanged), and all other `legacy*` replays.

### Verify / success criteria
```bash
cd <worktree>
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test \
  -Dtest='AccountKillSwitchWorkflowImplTest,AccountKillSwitchWorkflowImplLegacyReplayTest,AccountKillSwitchCapAlerterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl services/orchestrator -am test          # full module, then spotless:check
mvn -pl services/orchestrator -am spotless:check
```
- **Behavioral:** the 2026-10-05 shape (3 of 3, or 2 of 3, unpriceable on one tick, then priced) produces
  **no** `KillSwitchTripped` and exactly one YELLOW `AccountKillSwitchMtmDeferred`. Two consecutive
  unpriceable ticks **do** trip `auto:account_mtm_unavailable`. A real computed loss still trips on the
  first tick.
- **Replay:** the v2 large-book-trip fixture replays cleanly, and the gate-removal mutant fails it.
- **Known noise:** `KillSwitchWorkflowImplTest` is a known flake, so re-run it rather than fix it.
  `PositionWorkflowImplTest` flakes are unrelated.
- No contract/schema, no new audit kind, no `tenants/*`, no `.github/workflows/*`, no ConfigMap.

---

## Ship order & gating
1. **P0 #1** (reset switch). Independent and immediate.
2. **Phase 1:** one PR, single concern, TDD, spotless on `services/orchestrator`, **operator merge gate**
   (real-money kill-switch workflow).
   - Suggested title: `fix(orchestrator): debounce the account cap's mtm-unavailable fail-close on large books`.
   - Set the PR body at create time (`gh pr edit --body` is broken; use
     `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`).
   - Add `Closes #<n>` only if the lead files an issue for R1 first.
   - Read the Claude review comment itself; a green check is not enough.
3. **P0 #3/#4:** operator deploy via `deploy.yml`, then verify marker v3 on each tenant's account
   execution after its next continue-as-new.
4. **P0 #2 (R3)** and **R2**: independent, any time.

---

## Deferred follow-up — R2 (GitHub-issue-ready)

**Title:** Account cap: distinguish transport failures from data-level quote misses in the MTM fail-close

**Problem.** The account cap's MTM fail-close counts every unpriceable position the same way. During a
transport outage (DNS, connect timeout), tripping protects nothing: flattening is equally impossible
and entries cannot price. An immediate fail-close only makes sense for data-level misses, where the
host is reachable and the quote is absent (forensics F3, 2026-10-05). Phase 1 debounces both
equally, which is sufficient but blunt.

**Finding: the discriminator exists but never fires for Alpaca.**
- `contract/schemas/option-quote-result.json:45` already defines `status ∈ {OK, UNAVAILABLE, FAILED}`, with
  FAILED meaning "the snapshot call threw".
- `GetOptionQuoteActivityImpl.getOptionQuote` (`services/market-data/.../activities/GetOptionQuoteActivityImpl.java:44-67`)
  maps a thrown provider exception to `FAILED`.
- But `AlpacaMarketData.snapshotQuote` (`.../provider/alpaca/AlpacaMarketData.java:270-280`) catches
  **every** `RuntimeException`, including `ResourceAccessException` from UnknownHost or connect timeout,
  and returns `Optional.empty()`. So a DNS outage surfaces as `UNAVAILABLE`, never `FAILED`.
- The workflow's `liveBid` (`AccountKillSwitchWorkflowImpl.java:~1420-1433`) also turns any non-OK
  status into `null`.

**Proposal (to be planned separately).**
1. Market-data: let transport exceptions (`ResourceAccessException`) propagate out of `snapshotQuote`,
   or return a typed result, so the activity reports `FAILED`. **High blast radius:** `snapshotQuote` also
   feeds the premium poll, flatten anchors, re-peg and the arm-time trail anchor. Audit every caller
   for its handling of empty versus thrown first. An activity-only distinction might be safer.
2. Workflow: count `FAILED` separately from `UNAVAILABLE`. Debounce transport failures, possibly with a
   longer tolerance. Consider restoring an immediate trip for a data-level `>50%` miss on large books.
   This is a version-gated command-shape change; widen `account-mtm-debounce-v1` again.

**Acceptance.** A test where a DNS-style exception yields `FAILED` end to end; a cap test where a
transport-only failing book defers and a data-level failing large book follows the chosen policy; a
legacy replay at v3.

**Open fork for the operator:** after Phase 1, is an immediate trip on a data-level `>50%` miss still
wanted? Phase 1 already gives 60s of tolerance to both kinds.
