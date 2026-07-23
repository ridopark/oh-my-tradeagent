# PLAN-2026-07-23 — Expiry-day adoption creates an immortal PositionWorkflow that fail-closes the account cap

`staging_paper`'s account kill switch tripped `auto:account_mtm_unavailable` at 2026-07-22 13:30:47Z,
47 seconds after the open, and again on 2026-06-29 — each time halting the tenant for the whole
session. The cause was not a market-data outage: two of the three running `PositionWorkflow`s held
contracts that had **expired days earlier** (`NVDA 260720C00210000` exp 07-20, `TSLA 260717C00420000`
exp 07-17). Expired contracts are delisted and permanently unpriceable, so every heartbeat counted
2 of 3 positions as quote failures and fail-closed. Both workflows were created by **recon adoption
at 14:45 ET on the contract's own expiry day** — one to two seconds after that contract's expiry
flatten had fired and failed — and both then blocked forever with no terminal path.

Source: live forensics 2026-07-23 (Temporal histories + `audit_log` + `order_intent_journal`,
homelab). Both zombies terminated and the kill switch reset by the operator on 2026-07-23 (see P0).

## 1. Root cause — a three-link chain, each link verified

**Link 1 — recon adopts into a window where the new workflow cannot manage itself.**
Phase 3 of [PLAN-2026-07-12](PLAN-2026-07-12-watchlist-flatten-floor-and-expired-readoption.md)
**shipped**: `ReconciliationWorkflowImpl.java:116` defines `recon-refuse-expired-sameday-v1` and the
gate at `:700` refuses a same-day expiry adoption — but only `&& pastEtClose()` (16:00 ET), the
deliberate Fork-2B choice to keep managing a still-tradeable intraday 0DTE orphan. Both adoptions
happened at **14:45 ET**, before the close, so the gate correctly did not refuse. Fork 2B is not
wrong; it is simply blind to the fact that *this* lot's own terminal instants had already elapsed.

**Link 2 — a PositionWorkflow started after all its terminal instants arms no timer at all.**
`PositionWorkflowImpl.java:1019-1062` arms the eod, expiry-close, and expiry-lead timers each behind
`if (!X.isZero() && !X.isNegative())`. For a workflow starting at 14:45:01 ET on expiry day with
`eod_force_flatten=false` (staging_paper copytrade) and the expiry-close instant at 14:45:00:
- eod timer — not armed (opt-out),
- expiry-close timer — duration ≤ 0, not armed,
- expiry-lead timer — already past, not armed.

The workflow therefore holds **no terminal timer**, and `eodFired` / `expiryFired` /
`expiryLeadFired` can never become true. Confirmed in both histories: exactly one `TIMER_STARTED`
(the 90s TTL), no others.

**Link 3 — the worthless-close is unreachable from that state.**
`maybeCloseWorthlessAtExpiry(...)` (`PositionWorkflowImpl.java:2682`) is correct and *did* ship
(Phase 2 of PLAN-2026-07-12 — `expire-worthless-scheduled-v1`). But it has exactly **one** call
site, `:1345`, inside the `if (eodFired || expiryFired || expiryLeadFired)` block at `:1330`. With
no timer armed, that block is dead code for the lifetime of the workflow. Neither history contains
an `expire-worthless-*` version marker — the method was never reached.

**Amplifier — the account cap treats "expired" as "unknown" rather than "worth zero."**
`AccountKillSwitchWorkflowImpl.java:897-905`: `valueOpenBook` counts any `bid == null` as a
`quoteFailure`. An expired contract's value is *known* (zero), not unknown. With
`failsClosed(listed=3, failures=2)` → `2 × 2 > 3` → immediate trip; the small-book protections
(in-tick refetch, 2-tick debounce, defer page) are gated on `listed <= SMALL_BOOK_MAX_POSITIONS`
(`:255`, value 2) and so did not apply to a 3-position book.

**Observed end state:** both workflows ran 43 events — start, timers computed, `PositionEntered`,
one 90s timer, `TIMER_FIRED`, then a `WORKFLOW_TASK_COMPLETED` issuing **no commands** — and sat
idle for 3 and 6 days respectively until terminated by hand.

## 2. P0 — Immediate operational (no code; operator)

- **DONE 2026-07-23 11:15Z** — terminated both zombie workflows (broker confirmed flat on both;
  recon saw only `NVDA 260807C00220000` ×1).
- **DONE 2026-07-23 11:16Z** — reset `t-staging_paper/account/killswitch`
  (`KillSwitchResetApproved`, root cause in the note).
- **PENDING** — confirm no re-trip after the 13:30Z open on 2026-07-23.
- **CLEAN, no action** — `prod_real` and `prod-kipark` have zero running `PositionWorkflow`s, so
  neither carries a zombie today. This is luck, not immunity: both run the same code path.
- No ConfigMap / tenant-YAML change in any phase below, so no manual `kubectl apply` is needed.
  The orchestrator deploys automatically on merge to main.

## 3. Phases (one concern per phase = one PR; ship order = risk order)

### Phase 1 — Value a physically-expired contract at $0 instead of counting it as a quote failure (orchestrator)

**Goal:** an expired lot can never fail-close the account cap, regardless of whether a zombie exists.
This is the defense-in-depth layer and alone would have prevented both halts.

**Changes** (anchors):
- `services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java:902-905` — in
  `valueOpenBook`, when `bid == null`, first check physical expiry via
  `OccSymbol.expiryOf(pos.contractSymbol())` (`domain/OccSymbol.java:50`) against the workflow's ET
  date. If the OCC has expired on or before today, contribute
  `(0 - entryPremium) * remainingQty * 100` to `openMtm` — the real, known loss of a worthless lot —
  and do **not** increment `quoteFailures`. Otherwise the existing failure path is unchanged.
- Version gate: `killswitch-expired-worth-zero-v1`, read once at stable scope alongside the existing
  `mtmDebounceVersion` read (`:712`). Required: the account kill switch is a long-running heartbeat
  workflow whose history records past trips; changing the trip decision would diverge replay of any
  history that tripped. At `DEFAULT_VERSION` keep the current count-as-failure behavior.

Note this makes the cap *stricter*, not looser: a worthless expired lot now books its full loss into
open MTM instead of being invisible. That is the correct direction for a safety mechanism.

**Tests (TDD):**
- `AccountKillSwitchWorkflowImplTest.expiredNoBid_valuedAtZero_noMtmUnavailableTrip` — **reproduces
  the incident**: 3-position book, 2 expired + unpriceable, 1 live and quotable → asserts NO
  `auto:account_mtm_unavailable` trip and that open MTM includes the expired legs at `-entryPremium`.
- `AccountKillSwitchWorkflowImplTest.unexpiredNoBid_stillCountsAsQuoteFailure` — a genuine
  market-data outage on a live contract still fail-closes (no regression of #325).
- `AccountKillSwitchWorkflowImplTest.expiredNoBid_atDefaultVersion_stillTrips` — replay determinism
  at `DEFAULT_VERSION`.

**Verify / success criteria:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test -Dtest=AccountKillSwitchWorkflowImplTest
```
Behavioral assertion: the exact 2026-07-22 book (2 expired + 1 live) evaluates without a trip.
No new audit kinds. `KillSwitchWorkflowImplTest` is a known timing flake — re-run, do not fix.

### Phase 2 — A PositionWorkflow with no terminal timer must not be immortal (orchestrator)

**Goal:** close the gap that makes the zombie possible at all — the core fix. Independent of how the
workflow was created, so it also covers any future path that starts a position late.

**Changes** (anchors):
- `services/orchestrator/.../workflows/PositionWorkflowImpl.java:1019-1062` — after the three timer
  arms, add a terminal-path guard: if the contract has **physically expired**
  (`expiryDate != null && !expiryDate.isAfter(currentEtDate())`, the same test
  `maybeCloseWorthlessAtExpiry` already uses at `:2691`) **and** no expiry/expiry-lead timer was
  armed, then take the worthless-close immediately rather than proceeding into the signal-only
  await. Reuse `maybeCloseWorthlessAtExpiry("expiry")` (`:2682`) so the audit shape
  (`PositionExpired` / `reason=worthless_expiry`) and the P&L-neutral semantics stay identical, then
  return from `run()` as the existing `:1345` path does.
- Version gate: `expire-worthless-no-timer-v1`, read once before the guard. Mandatory — this adds an
  audit activity + early return to a workflow whose in-flight histories must replay byte-identically.

Scope deliberately stays at *physically expired*. A workflow started after its eod instant on a
non-expiry day still has its expiry timers days out, so it retains a terminal path and is untouched.

**Tests (TDD):**
- `PositionWorkflowImplTest.startedAfterExpiryInstantsOnExpiryDay_closesWorthlessImmediately` —
  **reproduces the incident**: start at 14:45:01 ET on the OCC's expiry date with
  `eod_force_flatten=false` and the expiry-close instant at 14:45:00 → asserts `PositionExpired`
  with `reason=worthless_expiry`, `remainingQty → 0`, `run()` completes, and NO indefinite await.
- `PositionWorkflowImplTest.startedBeforeExpiryInstants_armsTimersUnchanged` — a normal same-day
  start still arms the expiry/lead timers and does not close early.
- `PositionWorkflowImplTest.startedAfterEodOnNonExpiryDay_staysAliveWithExpiryTimers` — the
  non-expired case is explicitly unaffected.
- Replay: a pre-change history that blocked forever replays byte-identically at `DEFAULT_VERSION`.

**Verify / success criteria:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test -Dtest=PositionWorkflowImplTest
```
Behavioral assertion: replaying the 2026-07-20 adoption inputs (`NVDA 260720C00210000`, qty 35,
entry 2.19, `eod_force_flatten=false`, start 18:45:01Z) yields a completed workflow emitting
`PositionExpired`, not a 43-event history ending in silence. `KIND_POSITION_EXPIRED` is already
registered — no `AuditEventKinds` change.

### Phase 3 — Refuse same-day adoption once the lot's own terminal instants have passed (orchestrator) — SHIP LAST, needs the Fork decision below

**Goal:** stop creating a position the system knows it cannot manage. Narrows the shipped Fork-2B
window from "before 16:00 ET" to "before this contract's own expiry-flatten instant."

**Changes** (anchors):
- `services/orchestrator/.../workflows/ReconciliationWorkflowImpl.java:700` — replace the
  `pastEtClose()` term in the same-day branch with a test against the contract's own expiry-flatten
  instant (expiry close − `flatten_lead_minutes`), the same instant `PositionWorkflowImpl:1052`
  computes via `calendar.durationUntilExpiryFlattenEt(...)`. Past that instant on the expiry date →
  refuse. Reuses `KIND_AUTO_ADOPT_REFUSED_EXPIRED` and the `refused_expired` metric — no new kind.
- Version gate: extend behind a **new** marker `recon-refuse-expired-past-flatten-v1` rather than
  widening `recon-refuse-expired-sameday-v1` (`:116`), so already-recorded same-day refusals replay
  unchanged.

**Tests (TDD):**
- `ReconciliationWorkflowImplTest.expiredOcc_onExpiryDayPastFlattenInstant_refusesAdopt` —
  **reproduces the incident**: a broker remnant seen at 14:45 ET on its expiry date with the flatten
  instant at 14:45 → asserts `AutoAdoptRefusedExpired`, no child start.
- `ReconciliationWorkflowImplTest.expiredOcc_onExpiryDayBeforeFlattenInstant_stillAdopts` — the
  Fork-2B intent survives: a 10:00 ET 0DTE orphan is still adopted and managed.
- Replay determinism at `DEFAULT_VERSION` and at `recon-refuse-expired-sameday-v1` v1.

**Verify / success criteria:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test -Dtest=ReconciliationWorkflowImplTest
```
Behavioral assertion: the 2026-07-20 14:45 ET remnant is refused instead of adopted.

## 4. Forks — DECIDED 2026-07-23 (operator)

**Fork 1 — is Phase 3 wanted at all? → SHIP IT.** Operator decision 2026-07-23: keep Phase 3 for
defense in depth, overriding the plan's original "skip it, Phase 2 makes it redundant"
recommendation. Rationale: Phases 1 + 2 both close the hole *after* a position the system cannot
manage has already been created; Phase 3 stops it being created at all, and the three layers fail
independently. All three phases ship.

**Fork 2 — should Phase 1 book the expired lot's loss, or just skip it? → BOOK THE LOSS.**
Phase 1 contributes `(0 - entryPremium) × qty × 100` to open MTM for a physically-expired lot rather
than skipping the leg. This makes the account cap *stricter* (a worthless lot's loss becomes visible
to the cap instead of invisible), which is the correct direction for a safety mechanism.

## 5. Ship order & gating

1. **Phase 1** (kill switch — stops the halt class immediately, smallest blast radius)
2. **Phase 2** (position lifecycle — the core fix; version-gated workflow change)
3. **Phase 3** (adoption policy — retained per Fork 1; riskiest history change, ships last)

Each phase: TDD-first, `spotless:apply` on `services/orchestrator` before commit, its own PR,
operator merge gate (trading-critical path). No `.github/workflows/*.yml` edits. Set the PR body at
create time (`gh pr edit --body` is broken in this repo). Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
