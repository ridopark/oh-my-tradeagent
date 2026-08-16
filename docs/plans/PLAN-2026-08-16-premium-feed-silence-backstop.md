# PLAN — 2026-08-16 premium-feed silence backstop

An armed trailing stop that stops receiving ticks is **inert and looks healthy**. Nothing detects it.
The only existing backstop, `exitFeedStaleFired`, is watchlist-only **and** one-shot: it is armed once
at `armWatchlistExit` and fires only if **no tick has ever arrived** since arm. A feed that dies
*after* ticking is unprotected on both paths, and the copytrade chandelier path has no staleness
backstop at all.

This became more pressing on 2026-08-16 for two independent reasons:

1. **#689** shipped a `/live` Stop-loss button, making arming an operator habit rather than something
   that happens automatically post-target.
2. **#690** made a withdrawn bid produce **no tick at all** (`acceptPremiumQuote` refuses a no-bid
   quote, correctly — the mid is an arithmetic artifact). Silence is now a *normal market outcome*,
   not only a feed failure. That is the right call for pricing and it widens this gap.

Source: adversarial review thread on #689/#690, 2026-08-16.

## P0 — Immediate operational (no code; operator)

- **None.** Verified 2026-08-16: `trailingState()` on the live prod_real position returns
  `armed: false, ticksReceived: 0`, and no armed trail exists on any tenant. There is nothing
  currently exposed. The gap becomes reachable the first time an operator arms a trail and leaves it
  armed — plausibly Monday.

## Current behaviour (verified anchors)

| anchor | what it does |
|---|---|
| `PositionWorkflowImpl.java:900` | `exitFeedStaleFired` — set by the one-shot timer |
| `:901` | `exitTickSeen` — set once, **never reset** |
| `:2407-2416` | the timer, armed inside `if (exitArmed)` → **watchlist path only**, window = `resolveExitFillTtlSecs()` |
| `:2459` | `processExitTick` sets `exitTickSeen = true` unconditionally |
| `:1298` | main-loop condition consuming `exitFeedStaleFired` → flatten with reason `time_stop` |
| `:1305-1316` | audits `KIND_WATCHLIST_EXIT_FEED_STALE` (`:244`) when `exitArmed && !exitTickSeen` |
| `:1353-1359` | the tick drain and route fork — the only place **both** paths pass through |
| `:2009-2015` | `processTick` early-returns on `!trailingArmed`, so **`lastTickAt` is stamped only while the trail is armed** |

**The design crux is `:2009-2015`.** `lastTickAt` (`:922`) cannot be the staleness reference: it is
not stamped for the watchlist pre-target window, and it is stamped from `tick.getRetrievedAt()` —
*quote* time, not observation time — so it does not measure "how long since we heard anything."
A rolling check needs **one unconditional workflow-clock stamp at the drain point**, which is what
Phase 1 adds.

---

## Phase 1 — observe silence before acting on it (orchestrator)

**Goal:** make feed silence *visible* with zero behaviour change and no version gate.

**Changes** (anchors) — *corrected during implementation; the two strikethroughs were defects in this
plan, recorded rather than quietly fixed*:
- `PositionWorkflowImpl.java:1353-1359` — stamp a new field `lastTickObservedAt = workflowNow()`
  immediately after `pendingTicks.poll()`, **before** the route fork, so it covers watchlist and
  copytrade identically and is independent of `trailingArmed`.
- `PositionWorkflowImpl.java` `trailingState()` — expose the **raw stamp**, ~~`secondsSinceLastTick`~~.
  **Do NOT derive the age inside the workflow.** The workflow clock only advances on workflow tasks,
  so a genuinely silent workflow's own "now" freezes and a derived age would report a constant —
  failing at precisely the job. The caller holds real time; let it subtract.
- ~~`contract/schemas/trailing-state.json`~~ — **no such file.** `TrailingState` is a hand-written
  Java record at
  `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/TrailingState.java`,
  not a generated contract POJO. **No schema change, no pydantic regeneration, no drift check** —
  Phase 1 is more isolated than this plan assumed. Verified: no other `new TrailingState(...)` call
  sites outside the impl and `PositionWorkflowImplLegacyReplayTest:553` (a stub override that needs
  the extra arg), and no dashboard/BFF consumer.

**Version gate:** **NONE REQUIRED.** Assigning a field and returning it from a query issues no
command, so recorded histories replay byte-identically. Confirm with `PositionWorkflowImplLegacyReplayTest`.

**Tests (TDD):** as shipped —
- `trailingState_lastTickObservedAt_isNullBeforeAnyTick`
- `tickDrain_stampsObservedAt_evenWhenTheTrailIsNotArmed` — the case `lastTickAt` misses today; it
  also pins that `lastTickAt` stays null, so the pre-existing behaviour is asserted unchanged.
- `tickDrain_observedAtAdvancesWithEachTick_whileLastTickAtTracksQuoteTime` — proves the two stamps
  are different quantities and are not conflated.

**Note for the implementer (cost a debug cycle):** a tick is delivered as a SIGNAL, so it is enqueued
and drained on a later workflow task. A query issued immediately after the signal legitimately races
the drain — no existing test queried `trailingState()` after a tick, so nothing in the suite had hit
this. Use the polling helper `waitForTickObserved`, which mirrors `waitForPlaceOrderCount` and
returns the still-null state on timeout so a genuine miss still fails the caller's assertion rather
than the wait. Mutation-verified: deleting the stamp fails both tests.

**Verify / success criteria:** `mvn -pl services/orchestrator -am spotless:apply` then
`mvn -pl services/orchestrator test`. Behavioural assertion: a position that receives a tick while
**unarmed** reports a non-null `lastTickObservedAt` while `lastTickAt` stays null, and nothing acts
on either.

---

## Phase 2 — detect silence and audit it (orchestrator)

**Goal:** emit an audit event when an **armed** position goes quiet past a window. Still no flatten,
no disarm.

**Changes** (anchors):
- `PositionWorkflowImpl.java` main loop (near `:1226` await) — add a periodic timer that, while
  `trailingArmed || exitArmed`, checks `Workflow.currentTimeMillis() - lastTickObservedAtMillis`
  against the window and latches `feedSilentFired`.
  **Version gate: `premium-feed-silence-backstop`** — a new timer is a new command, so this MUST be
  `Workflow.getVersion("premium-feed-silence-backstop", DEFAULT_VERSION, 1)` read once at stable
  scope. In-flight real-money positions exist today and must replay unchanged.
- New audit kind `PremiumFeedSilent`, registered in
  `services/audit/src/main/java/com/ohmytradeagent/audit/AuditEventKinds.java` `ALL_KINDS` — the
  pre-push `KindRegistryGuardTest` blocks the push otherwise.
- Subject should carry `contract_symbol`, `seconds_since_last_tick`, `armed_path`
  (`watchlist`/`copytrade`), `peak_premium`, `threshold_premium` — enough to judge severity without
  a second query.

**Window:** reuse `resolveExitFillTtlSecs()` initially rather than adding a knob. It is the window
the existing backstop already uses, so Phase 2 introduces no new tunable. A dedicated knob belongs in
Phase 3 **only if** Phase 2's audit volume shows the shared value is wrong.

**Tests (TDD):**
- `armedCopytradeTrail_goesQuiet_emitsPremiumFeedSilent` — the case with **no** coverage today.
- `armedWatchlistExit_quietAfterFirstTick_emitsPremiumFeedSilent` — the one-shot gap; must fail
  before this phase.
- `unarmedPosition_quiet_emitsNothing` — silence only matters when something depends on ticks.
- `tickResumes_beforeWindow_emitsNothing`.
- `legacyReplay` — a recorded pre-gate history replays byte-identically at `DEFAULT_VERSION`.
  **Defeat the gate and confirm the fixture fails**, per the toothless-fixture lesson.

**Verify / success criteria:** module build + tests; audit row present with the right kind and
subject; `KindRegistryGuardTest` green.

---

## Phase 3 — decide what silence MEANS (orchestrator + config)

**Goal:** act on silence. **This phase contains a decision that is the operator's, not the
implementer's, and it must not be picked silently.**

### The fork

| option | behaviour | argument |
|---|---|---|
| **A. Alert only** | audit + Discord; trail stays armed | Zero risk of a wrong exit. But the operator still holds an inert stop they believe is live — it only converts a silent failure into a loud one. |
| **B. Disarm + alert** | clear `trailingArmed`, tell the operator protection is gone | Honest: stops the UI implying protection that cannot fire. Removes protection at the moment it may be most needed. |
| **C. Probe, then decide** | call `getOptionQuote`; bid present ⇒ feed/guard problem; bid absent ⇒ market condition | Distinguishes the two causes, which A and B conflate. Costs one activity call per silence event, and reads the **unfiltered** `snapshotQuote`. |
| **D. Flatten** | what the watchlist backstop does today (`:1298`) | **Wrong as a default post-#690.** The dominant new cause of silence is a *withdrawn bid* — flattening then means selling into a market with no bid. |

**Recommendation: C gated to fall back to A.** Probe on silence; if the probe shows a live two-sided
book, the tick path is broken (alert loudly, this is a real defect); if the probe shows no bid, the
contract is genuinely untradeable and the correct action is to alert and **not** attempt an exit.
Never flatten on silence alone.

**Note for whoever takes this:** the existing watchlist backstop already implements D, and #690
changed its trigger surface — a no-bid contract now yields no tick where it previously yielded a tick
with a bogus mid. Its "never ticked since arm" flatten is still defensible as a fail-safe, but it
should be re-read in light of D's argument, and it is **out of scope here** because changing it alters
a live watchlist exit path.

**Changes** (anchors):
- `contract/schemas/strategy-config.json` — `premium_feed_silence_action` (enum, optional, OUT of
  `required`; null ⇒ alert-only) and optionally `premium_feed_silence_secs`. Regenerates the Java
  POJO + pydantic model.
- `OrderFailureAlerter` `DEFAULT_FAILURE_KINDS` + `application.yml` **image default** (not env — env
  is unset on homelab and not applied by deploy) so `PremiumFeedSilent` pages.
- Version gate: reuse `premium-feed-silence-backstop` if Phase 2 and 3 ship close together;
  otherwise a second gate for the probe activity call (an activity **is** a command).

**Tests (TDD):** one per fork branch, plus `nullAction_defaultsToAlertOnly` proving the config-absent
path is inert.

**Verify / success criteria:** module build + tests; `scripts/gen-config-field-manifest.py`
regenerated and the CI drift job green; if `tenants/dev/strategies/*.yaml` gains the field, re-sync
`infra/k8s/40-tenants-config.yaml` and run `scripts/check-tenants-configmap-drift.py`.

---

## Ship order & gating

1. **Phase 1** (no version gate, query-only, isolated) → observe real silence in production first.
2. **Phase 2** (version-gated, audit-only) → quantify how often silence actually happens before
   attaching behaviour to it.
3. **Phase 3** (behaviour + config) → only after Phase 2 data, and only after the fork is decided by
   the operator.

Each phase: TDD, `spotless:apply` on every touched module, its own PR, operator merge gate
(trading-critical).

**Do not compress this.** Phases 1–2 are close to risk-free and produce the data that tells you
whether Phase 3 is worth its risk. Attaching an exit behaviour to a signal nobody has measured is how
the 2026-07-21 fail-closed outage happened.

## Operator follow-ups (not code phases)

- `staging_paper` / `prod_real` / `prod-kipark` / `prod-jinchul` strategy YAMLs are **not in this
  repo** — any Phase 3 config enablement for those tenants is an out-of-band DB/config edit.
- `KillSwitchWorkflowImplTest` is a known flake — instruct a re-run, do not "fix" it.

## Related

- `PositionWorkflow` still has **no continue-as-new**; measured 2026-08-16 at ~42 events/day
  unarmed (decades of runway) but ~11,700/day armed **pre-throttle**. The armed post-throttle rate is
  still unmeasured — measure it at the first armed session before deciding that work is needed.
- The blown-ask anchor gap in `resolveTrailAnchor` is documented-unguarded and shares a root cause
  with this plan: `snapshotQuote` is unfiltered while `pollOnce` is filtered.
