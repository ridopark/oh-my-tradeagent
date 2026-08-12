# PLAN — 2026-08-12 Clear auto daily-loss trips at trading-day rollover

**Goal.** Make a **daily** loss breaker actually daily. Today both kill switches carry `tripped`
across the trading-day rollover, so one bad day halts the tenant on day N, N+1, N+2 … until a human
resets. Clear `tripped` at rollover **only** for the auto daily-loss actors; every operator and
config/data fail-closed trip must survive untouched.

Source of finding: live diagnosis 2026-08-12 06:30 ET. All `file:line` anchors below were re-read at
authoring time.

---

## Incident summary (confirmed from live state)

On 2026-08-10 three live tenants entered `GOOGL 260821C00370000`. It was flattened 2026-08-11
14:46 ET @ `0.81` (`order_intent_journal`, `exec_alpaca_live` — prod-kipark 36, prod-jinchul 18,
prod_real 15). All GOOGL `PositionWorkflow`s are gone; the book is flat.

The next morning (2026-08-12, pre-market) `killswitch_state` still reported:

| Workflow | State | Tripped since |
|---|---|---|
| `t-prod-kipark/s-copytrade-v1/killswitch` | `tripped=true`, `auto:daily_loss`, value `-8784.0` | 2026-08-11 14:46 ET |
| `t-staging_paper/s-copytrade-v1/killswitch` | `tripped=true`, `auto:daily_loss` | 2026-08-03 |

Both report `trading_day: 2026-08-12` — the day advanced, `tripped` did not. prod-kipark is real
money and would have refused every BTO at the 09:30 ET open. staging_paper had silently taken no
copytrade entry for **nine days**.

kipark's number is exactly the cross-day GOOGL round trip: `(0.81 − 3.25) × 36 × 100 = −$8,784`
against its `daily_loss_threshold = 2500`. It is also why kipark missed the `SPY 260817C00775000`
entry prod_real took at 15:59 ET the same day.

### The two rollover branches

**Strategy switch** — `KillSwitchWorkflowImpl.java:248-251`:

```java
if (!today.equals(tradingDay)) {
  // Day rollover — reset day-scoped state. tripped/coolingDownUntil persist across days.
  this.tradingDay = today;
}
```

**Account switch** — `AccountKillSwitchWorkflowImpl.java:739-746`: resets `tradingDay`,
`sodEquity`, and `consecutiveMtmUnavailableTicks`. Never `tripped`.

The comment is explicit, so this is documented behaviour rather than an oversight — but it is wrong
for a cap whose threshold is defined per day. Only the `reset_killswitch` /
`reset_account_killswitch` Updates clear `tripped`, and (per
`memory/project_account_cap_crossday_and_reset_enabled.md`) a manual reset while the book is still
underwater just re-trips 60s later. The 2026-08-11 audit trail shows prod-jinchul resetting three
times before the position was closed.

---

## The material design decision (read before Phase 1)

**Not every trip is day-scoped.** Clearing `tripped` unconditionally at rollover would silently
re-arm a strategy an operator deliberately halted. That is a real configuration in production:
`memory/project_prod_real_watchlist_deactivated.md` records `prod_real/watchlist-trigger-v1` as
INTENTIONALLY deactivated with *"do NOT re-activate"*.

The trip actors in play:

| Actor | Source | Clear at rollover? |
|---|---|---|
| `auto:daily_loss` | `KillSwitchWorkflowImpl` heartbeat | **YES** — day-scoped by definition |
| `auto:account_daily_loss` | `AccountKillSwitchWorkflowImpl` heartbeat | **YES** — day-scoped by definition |
| `operator:<id>` | `KillSwitchController.trip` (`:70`), and `LiveActivationGateActivitiesImpl.tripKillSwitch` (`:60`) with reason `live_deactivation:one_click` (`LiveActivationWorkflowImpl.java:219`) | **NO** — a deliberate halt |
| `auto:missing_loss_threshold` | `KillSwitchWorkflowImpl.java:288` | **NO** — a config fault, not a day event |
| `auto:account_mtm_unavailable` | account heartbeat debounce | **NO** — see non-goals |

**Discriminate on `actor`, not `reason`.** The operator trip path takes `reason` as free text from
the request body (`KillSwitchController.ResetPayload` / `TripPayload`), so `reason` is
attacker-shaped and unreliable; `actor` is always set by the workflow or prefixed `operator:` by the
controller. The rule is an exact match on the single day-scoped actor constant per workflow —
anything unrecognised persists (fail-closed).

**Non-goal: `auto:account_mtm_unavailable`.** It re-trips within one 60s heartbeat if the data
source is still broken, so clearing it would be near-harmless — but it is a data-quality
fail-closed, not a daily event, and carrying it is the conservative read. Out of scope; note it in
the PR body so the decision is on the record rather than accidental.

**Non-goal: the cross-day unrealized charge.** The account cap values the open book as
`(liveBid − entryPremium) × remainingQty × 100`, so an overnight loser charges *lifetime* unrealized
to *today's* cap (`AccountKillSwitchWorkflowImpl` ~`:946`). That is a separate defect with a
separate fix (baseline from prior close, not entry). This plan only stops the trip from being
*sticky*; it does not change what the cap charges. Without that second fix a tenant holding a deep
overnight loser will clear at rollover and re-trip within a minute — correct-but-noisy, and strictly
better than today's silent multi-day halt.

---

## Phase 1 — Strategy switch clears `auto:daily_loss` at rollover

**File:** `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/KillSwitchWorkflowImpl.java`

Add a change-id constant beside the existing three:

```java
static final String VERSION_KILLSWITCH_CLEAR_DAILY_LOSS_ON_ROLLOVER =
    "killswitch-clear-daily-loss-trip-on-rollover-v1";
```

In `heartbeat()`, read the gate **once, early**, at the same stable scope as the existing markers —
and critically **before** the `if (tripped) return;` early-return at `:252`, which today short-
circuits the whole tick. Inside the rollover branch at `:248`, at `v >= 1` only, clear
`tripped`/`reason`/`actor`/`trippedAt` when `TRIP_ACTOR_DAILY_LOSS.equals(actor)`. Emit a new audit
kind `KillSwitchClearedOnRollover` carrying the prior `reason`, `actor`, `tripped_at`, and both
trading days, so the clear is visible in `audit_log` rather than a silent state flip.

Leave `coolingDownUntil` alone — it is a post-reset debounce, not day-scoped state, and it is
already in the past by any rollover.

**Replay safety.** At `Workflow.DEFAULT_VERSION` every pre-Phase-1 in-flight history records no
marker and the rollover branch stays byte-identical (no new audit command). Per
`memory/reference_temporal_replay_activity_input.md` only command type/ordering is checked, but the
new `auditLog` call *is* a new command — hence the gate is load-bearing, not ceremony.

**Ordering constraint.** `Workflow.getVersion` markers must not be reordered on replay. Read this
gate **after** the three existing gates are read in `heartbeat()`, or — since the rollover branch
runs before them — read it as the *first* gate and confirm `KillSwitchWorkflowImplLegacyReplayTest`
passes against the checked-in histories. The replay test is the arbiter; do not reason about it
from first principles.

**Tests** (`KillSwitchWorkflowImplTest`):
1. tripped `auto:daily_loss` + day rollover → `tripped=false`, one `KillSwitchClearedOnRollover`
   audit.
2. tripped `operator:someone` / reason `live_deactivation:one_click` + day rollover → still
   `tripped=true`, no audit. **This is the regression guard that matters.**
3. tripped `auto:missing_loss_threshold` + rollover → still `tripped=true`.
4. Cleared switch re-trips the same day if today's realized crosses the threshold again.
5. `KillSwitchWorkflowImplLegacyReplayTest` green (unchanged histories).

---

## Phase 2 — Account switch clears `auto:account_daily_loss` at rollover

**File:** `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/AccountKillSwitchWorkflowImpl.java`

Same shape, own change-id (`account-killswitch-clear-daily-loss-trip-on-rollover-v1` — independent
history, so it must be a distinct string). Extend the rollover branch at `:739-746`, gated, matching
only `auto:account_daily_loss`; leave `auto:account_mtm_unavailable` tripped per the non-goal above.

Watch the interaction with the Phase-2b re-page block at `:747-755`: a rollover that clears the trip
must fall through to normal evaluation, not into `maybeRepageWhileHolding()`. Clear **before** the
`if (tripped)` check.

`sodEquity` is already nulled at rollover and re-captured lazily, so the freshly-cleared cap
evaluates against the new day's start-of-day equity — which is the whole point.

**Tests** (`AccountKillSwitchWorkflowImplTest`): mirror Phase 1's five, plus one asserting
`auto:account_mtm_unavailable` still persists. Plus
`AccountKillSwitchWorkflowImplLegacyReplayTest` green.

---

## Ship order and constraints

1. **Phase 1 and Phase 2 are separate PRs.** Different workflows, different histories, different
   blast radius — and Phase 2 touches the real-money account cap, so it deserves its own review.
2. `mvn -pl services/orchestrator spotless:apply` before committing
   (`memory/feedback_spotless_precommit.md`). `KillSwitchWorkflowImplTest` is a known timing flake —
   re-run before believing a red.
3. No schema change, no ConfigMap change, no tenant-YAML change. Nothing to regenerate.
4. Deploy is a plain orchestrator roll. Running kill-switch workflows continue-as-new roughly daily
   (`historyLengthWatermark = 10_000`), so the gate goes live for each tenant on its next CAN or
   worker pickup — expect the behaviour to arrive staggered, not all at once.

---

## Verification

**Unit.** `mvn -pl services/orchestrator test -Dtest='KillSwitch*Test,AccountKillSwitch*Test'` —
including both legacy replay tests.

**Live, post-deploy.** No synthetic trip on a real-money tenant. Instead let the next natural
`auto:daily_loss` trip roll over, then the morning after:

```bash
ssh ridopark@192.168.10.123 'kubectl exec -n temporal deploy/temporal-admintools -- \
  temporal --address temporal-frontend.temporal.svc.cluster.local:7233 \
  workflow query --namespace copytrade \
  -w "t-<tenant>/s-copytrade-v1/killswitch" --type killswitch_state'
```

Expect `tripped: false` with `trading_day` = today, and a matching row:

```sql
SELECT occurred_at, tenant_id, strategy_id, kind, subject
FROM audit_log
WHERE kind = 'KillSwitchClearedOnRollover'
ORDER BY occurred_at DESC LIMIT 10;
```

**Regression guard, live.** `prod_real/watchlist-trigger-v1` is intentionally deactivated. After
deploy it must STILL read `tripped: true` every morning. If that one ever clears, revert — the actor
discriminator leaked.

---

## Addendum 2026-08-12 — corrections from the pre-implementation consults

`java-architect` and `risk-manager` both reviewed this plan before implementation. Two claims above
are **wrong as written** and are corrected here rather than edited in place, so the original
reasoning stays auditable. `risk-manager`'s verdict was GO WITH CHANGES.

**Correction 1 — the version gate does more than the audit (supersedes "Replay safety" above).**
That paragraph justifies the gate solely because `auditLog` emits a new command. The *state
mutation* is equally load-bearing: clearing `tripped` at `DEFAULT_VERSION` makes the replaying tick
fall through `if (tripped) return;` into `isMarketOpen()`, `strategy.get(...)`, the three existing
`getVersion` markers and possibly `computeRealizedPnl` — a command stream the recorded history does
not contain → `NonDeterministicException` on a real-money kill switch. **Both the mutation and the
audit must sit inside the `v >= 1` guard.** In Phase 2 the mirror-image divergence is skipping
`maybeRepageWhileHolding()`, whose commands *are* in recorded `v>=1` histories.

**Correction 2 — the ordering constraint was a non-issue (supersedes "Ordering constraint" above).**
Temporal 1.27 resolves version markers by `changeId` through a `Map<String, VersionStateMachine>`,
preloads markers per workflow task, and yields to `DEFAULT_VERSION` emitting **no command** for a
changeId absent from history. A brand-new changeId therefore cannot reorder or displace existing
markers at any position. Phase 1's gate goes at the very top of `heartbeat()` — it *must* precede
`if (tripped) return;`, which short-circuits all three existing gates on exactly the ticks this
feature exists for. Phase 2's goes last in the existing five-gate block at `:735`, preserving
recorded order.

**Correction 3 — "next CAN or worker pickup" (Ship order item 4) is wrong on the second half.** A
new change-id resolves to `DEFAULT_VERSION` for the entire lifetime of an already-running workflow.
The fix activates **only after continue-as-new** — roughly a day for a tripped switch. Do not read
"still tripped the first morning after deploy" as failure.

**Additions to each phase:**
- Extract `TRIP_ACTOR_DAILY_LOSS` / `TRIP_ACTOR_ACCOUNT_DAILY_LOSS` (currently bare literals at
  `KillSwitchWorkflowImpl:319` and `AccountKillSwitchWorkflowImpl:905`). **Exact match only** —
  `doTrip`'s `startsWith("auto:")` precedent at `AccountKillSwitchWorkflowImpl:1459` must NOT be
  followed here; a prefix match would sweep in `auto:missing_loss_threshold` and
  `auto:account_mtm_unavailable`.
- Register `KillSwitchClearedOnRollover` in `services/audit/.../AuditEventKinds.java` `ALL_KINDS`
  or `KindRegistryGuardTest` fails the audit build. (`audit-event.json` types `kind` as an open
  string, so there is no contract change.)
- Phase 2 must also zero `stillHoldingRepageTicks`, mirroring `reset()` at `:1377`; otherwise a
  re-trip inherits yesterday's window and pages early.
- **The replay fixtures in this repo cannot currently detect an ungated clear.** Every existing
  fixture stubs `calendar.todayEt()` to a single fixed date, so none crosses the rollover branch —
  delete the gate and they all stay green. Each phase must add a rollover-crossing *tripped*
  fixture, and the implementer must confirm by experiment that removing the gate makes it fail.
- Add a same-day-no-clear test per phase. Without it, a clear accidentally hoisted out of the
  rollover branch disables the breaker entirely and every other test still passes.

**Residual risks accepted for this plan, each needing its own follow-up:**
1. **The `already_tripped` swallow.** `LiveActivationGateActivitiesImpl:65-78` swallows the trip
   rejection from `tripValidator:371-374`, so a one-click Deactivate landing on a switch already
   tripped by `auto:daily_loss` leaves `actor` at the auto value — the deactivation intent never
   reaches the switch and the rollover would clear a deliberately-halted strategy. Verified NOT to
   affect `prod_real/watchlist-trigger-v1` today (its actor is `operator:*`). Aggravating factor:
   the watchlist path never calls `checkLivePromotion` (only `CopytradeSignalWorkflowImpl:800`
   does), so for a watchlist strategy the kill switch is the *only* gate — copytrade has a
   `DEACTIVATED` backstop, watchlist has none.
2. **Open-of-day window on the account cap.** `sodEquity` is nulled at rollover; if re-capture
   fails, `resolveEffectiveThreshold` falls back to a null `absolute` and the cap cannot arm at
   all, where today's sticky trip would have protected. Mitigating: that state feeds
   `recordInactivityOutcome(false)` and pages `AccountKillSwitchCapInactive` rather than going
   silent. Phase 1 is unaffected (static threshold, realized starts at 0).
3. **A tripped-and-flat switch is 100% silent** — the true cause of staging_paper's nine-day halt,
   and unaddressed here for every non-daily-loss actor. The proposed fix is a bounded once-per-day
   still-tripped page on the first market-open tick, actor-agnostic, on both workflows.

---

## Config drift found alongside (separate work, not in this plan)

`prod-kipark/copytrade-v1` and `staging_paper/copytrade-v1` still carry the legacy per-strategy
`daily_loss_threshold = 2500` that the single-account-loss-rule epic
(`memory/project_single_account_loss_rule_epic.md`) retired — `prod_real/copytrade-v1` and
`prod-jinchul/copytrade-v1` have it null and run on the account cap alone. That legacy rule is what
fired on kipark. Nulling it on those two tenants is a DB CAS, not code, and is deliberately **not**
bundled here: it changes which breaker protects a real-money tenant and should be an explicit
operator decision reviewed on its own.
