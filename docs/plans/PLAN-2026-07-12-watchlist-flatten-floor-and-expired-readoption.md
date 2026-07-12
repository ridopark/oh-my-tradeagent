# PLAN-2026-07-12 — Watchlist flatten-floor gap + expired-contract re-adoption loop

Status: DRAFT (plan only — not implemented)
Author: remediation-architect
Incident date: 2026-07-10 (staging_paper / watchlist-trigger-v1, paper money)
Blast radius if promoted: prod_real watchlist (real money, currently armed-but-dark)

---

## 1. Incident summary

On 2026-07-10 a `staging_paper` / `watchlist-trigger-v1` paper position **AMZN 260710C00252500**
(expiry 2026-07-10 = 0DTE that day; strike 252.5 vs AMZN underlying ~$244 → deep-OTM, effectively
worthless) got stuck in an **orphan → auto-adopt → fail-to-flatten** loop and never closed. Its
`PositionWorkflow` lingered as a phantom for ~2 days until manual termination on 2026-07-12.

Verified audit lifecycle (all 2026-07-10):

- 13:30 OrderSubmitted (BUY 5) → 13:31 TriggerEntryUnfilled (90s entry TTL) — but the broker HAD
  filled → 13:35 PositionOrphan → ReconAutoAdoptionInitiated → PositionAdopted → PositionEntered
  (recovery=adopted, entry_premium 0.85).
- 19:00 ExpiryLeadFlattenRequested (reason=expiry_lead) → **FlattenFloorConfigError**
  (note=`no_resolvable_floor_marketable_fallback`) → PositionOrphan → re-adopted.
- 19:30 ExpiryForceFlattenRequested (reason=expiry) → re-adopted.
- 19:55 EodForceFlattenRequested (reason=eod) → **FlattenFloorConfigError** again →
  PositionOrphanOngoing (age 3298s) → re-adopted.
- Re-adopted ~4× through the day; every flatten failed to fill; last re-adopt 19:55 then went quiet
  (broker stopped reporting the expired lot) but the PositionWorkflow stayed Running until manual
  termination.

### Root cause — two distinct concerns (both verified against code)

**Concern A — flatten-floor config gap (secondary / noise, but real).**
`computeBoundedFlattenLimit` → `resolveExitFloor(...)` returns null when no floor field is
configured, which emits the loud `FlattenFloorConfigError` and falls back to a marketable sell.
- `PositionWorkflowImpl.java:3071-3084` (the null-floor → `KIND_FLATTEN_FLOOR_CONFIG_ERROR`
  `note=no_resolvable_floor_marketable_fallback` → `return null`).
- Mirror on the stepped-reprice path: `PositionWorkflowImpl.java:3159`
  (`resolveExitFloor(anchor, false)`).
- `resolveExitFloor` body: `PositionWorkflowImpl.java:3203-3219` — expiry session reads
  `expiry_day_floor`; non-expiry reads `max(exit_floor_abs, anchor*exit_floor_pct)`.
- The three floor fields exist in the shared schema as **opt-in / not-required**:
  `contract/schemas/strategy-config.json:285-306` (and are NOT in the `required` list at
  `strategy-config.json:9-20`); the Java POJO getters already exist (`input.getExitFloorAbs()`
  etc.). copytrade-v1 sets all three; **watchlist-trigger-v1 sets none** (verified — the YAML has
  sl_pct/tp_ratio/tp_partial_fraction/trail_giveback_pct/no_progress_time_stop_secs/force_close_eod_et/
  eod_force_flatten but NOT exit_floor_abs/exit_floor_pct/expiry_day_floor). So EVERY watchlist
  flatten hits `FlattenFloorConfigError` and loses bounded-floor protection (recurred on
  staging_paper watchlist on 2026-07-06 as well).

**Concern B — the actual stuck loop: a physically-expired / no-bid contract can neither flatten nor
be dropped, and recon re-adopts it indefinitely.** Two independent code holes:

- **B1 (linger).** `maybeCloseWorthlessAtExpiry` (`PositionWorkflowImpl.java:2656-2681`) is gated
  `if (!"expiry".equals(reason)) return false;` — it fires ONLY for a `reason=="expiry"` flatten.
  It is called from the run-tail at `PositionWorkflowImpl.java:1326`
  (`if (!flat && !maybeCloseWorthlessAtExpiry(reason))`). The lot that finally lingered was the
  **19:55 eod-adopted** workflow: it flattened with `reason=eod`, got no fill (no bid → marketable
  sell can't fill on a worthless contract — `PositionWorkflowImpl.java:3056-3058` returns null =
  marketable when bid ≤ 0 in the expiry session), and because `reason != "expiry"` the worthless-
  close was skipped → it fell through to the stay-ALIVE block and lingered forever. The physical-
  expiry date check inside the method (`expiryDate.isAfter(currentEtDate())`) would have passed on
  2026-07-10 — the ONLY thing blocking the close was the `reason=="expiry"`-only restriction.
- **B2 (re-adoption churn).** Recon's refuse-expired gate is strict `isBefore`:
  `ReconciliationWorkflowImpl.java:658` →
  `if (occExpiry != null && occExpiry.isBefore(workflowEtDate())) { ...refuse... }`
  (`maybeAutoAdopt`, `ReconciliationWorkflowImpl.java:642-674`). On the expiry DAY (0DTE),
  `occExpiry (2026-07-10).isBefore(workflowEtDate() 2026-07-10)` is **false**, so recon does NOT
  refuse and happily re-adopts the worthless lot every ~25-30 min all day. The existing #434 gate
  only refuses on the day AFTER expiry — it has a same-day (0DTE) hole.

The existing expire-worthless mechanism (#434/#435, marker `expire-worthless-v1`,
`PositionWorkflowImpl.java:539`) did NOT fire here because (B1) it is `reason=="expiry"`-only and the
lingering lot's terminal flatten was `reason=eod`, and (B2) recon kept spawning fresh
PositionWorkflows on the expiry day faster than any single one could terminate.

---

## 2. P0 / operator follow-ups (NOT code phases)

| # | Action | State |
|---|--------|-------|
| O1 | Terminate the stuck AMZN 260710C00252500 phantom PositionWorkflow | **DONE 2026-07-12** (manual) |
| O2 | Seed the three exit-floor fields into the **staging_paper** watchlist-trigger config (DB — `STRATEGY_CONFIG_SOURCE=db`, live-cluster/DbStrategyRegistry; NOT in repo) | Pending — after Phase 1 merges. Values to mirror copytrade: `exit_floor_abs=0.05`, `exit_floor_pct=0.5`, `expiry_day_floor=0.01` (confirm with risk before applying) |
| O3 | Seed the same three fields into the **prod_real** watchlist-trigger config (DB, live-cluster-only) BEFORE any prod_real watchlist go-live | Pending — gate on prod_real promotion |
| O4 | `kubectl apply -f infra/k8s/40-tenants-config.yaml` on homelab after Phase 1 (deploy.yml does NOT apply the shared tenants ConfigMap — reference_deploy_yml_apply_scope) | Pending — after Phase 1 |
| O5 | Redeploy orchestrator (carries all three code phases) to homelab | Pending — after each phase merges, in ship order |

Note: the dev-seed YAML edits in Phase 1 are the repo default/seed only; staging_paper &
prod_real watchlist configs are DB/live-cluster-only, so O2/O3 are operator DB edits, not repo
phases.

---

## 3. Phases (one concern per phase = one PR; ship order = risk order)

Ship order: **Phase 1 → Phase 2 → Phase 3.** Phase 1 is isolated config (no replay gate). Phases 2
and 3 are Temporal workflow-history changes and MUST ship last, each behind its own new
`Workflow.getVersion` marker. Phase 3 is last of all (recon governs every strategy/account → widest
blast radius). All three are independently mergeable and independently deployable.

Per-phase gates that always apply:
- `mvn -pl <touched module> -am spotless:apply` on EVERY touched module before commit (impl env
  skips spotless → CI fails otherwise; cross-module: touching orchestrator only here, but run it on
  every module you edit).
- PR body must carry `Closes #<issue>` if issue-linked (else /issues-drain re-picks it).
- `gh pr edit --body` is broken here — set the body via `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`
  and verify it persisted.
- `KillSwitchWorkflowImplTest` and `PositionWorkflowImplTest.partialExit_placeRetry_boundedAndExhausts_pagesTerminal`
  are known flaky — re-run, do NOT "fix".

---

### Phase 1 — Give watchlist-trigger the exit-floor config (config-only; no replay gate)

**Concern:** watchlist flattens always hit `FlattenFloorConfigError` and lose bounded-floor
protection because the three exit-floor fields are unset for watchlist-trigger.

**Recommended approach (see Fork 1 below):** add the three opt-in fields to the watchlist-trigger
seed config, mirroring copytrade's values. This is pure config — no workflow-history change, no
getVersion — hence it ships FIRST.

**Changes (with anchors):**
1. `tenants/dev/strategies/watchlist-trigger-v1.yaml` — add, in the exit-policy block:
   ```yaml
   exit_floor_abs: 0.05
   exit_floor_pct: 0.5
   expiry_day_floor: 0.01
   ```
   (values chosen to match copytrade-v1.yaml; confirm with risk. These are opt-in fields already in
   the schema — `contract/schemas/strategy-config.json:285-306`, NOT in the `required` list — so no
   schema edit and no POJO/pydantic regen is needed.)
2. `infra/k8s/40-tenants-config.yaml` — add the same three lines to the **embedded**
   `watchlist-trigger-v1.yaml` block (starts at `40-tenants-config.yaml:122`). The copytrade block
   already carries them at `40-tenants-config.yaml:104-106`. REQUIRED by the drift guard
   (`scripts/check-tenants-configmap-drift.py`) — a mismatch fails CI.

**Replay/CI hazard:** none in the workflow sense (config value read at the activity boundary, not a
command). The ONLY CI gate is the ConfigMap drift check — both files must move together.

**TDD / tests:**
- Add/extend a drift-guard or config-load unit test asserting watchlist-trigger-v1 resolves
  non-null `exit_floor_abs`/`exit_floor_pct`/`expiry_day_floor` (mirror any existing copytrade
  config-load assertion).
- If a `resolveExitFloor` unit test fixture exists, add a case: watchlist-trigger config on the
  expiry session → floor resolves to `expiry_day_floor` (0.01), NOT null → no
  `FlattenFloorConfigError`.

**Verify command:**
```
python3 scripts/check-tenants-configmap-drift.py
mvn -pl services/orchestrator -am test
```
**Behavioral assertion (ties to finding):** a watchlist-trigger scheduled flatten with a live bid no
longer emits `KIND_FLATTEN_FLOOR_CONFIG_ERROR`; `resolveExitFloor` returns a bounded floor instead
of null.

> NOTE: Phase 1 does NOT fix the stuck loop (a worthless no-bid contract still can't fill and still
> gets re-adopted). It removes the recurring config-error noise and restores bounded-floor
> protection for NON-worthless watchlist exits. The loop is Phases 2+3.

---

### Phase 2 — Close a physically-expired, unfillable lot as worthless on eod/expiry_lead flattens too (replay-gated)

**Concern (B1 — the linger):** an adopted PositionWorkflow whose terminal flatten is `reason=eod`
(or `expiry_lead`) on a physically-expired contract never closes — the worthless-close only fires
for `reason=="expiry"`. Broaden it so ANY scheduled flatten on a physically-expired contract that
rests unfilled terminates as expire-worthless.

**Changes (with anchors):**
- `PositionWorkflowImpl.java` — `maybeCloseWorthlessAtExpiry` (`2656-2681`). Relax the
  `if (!"expiry".equals(reason)) return false;` gate to ALSO accept `eod` and `expiry_lead`. The
  existing physical-expiry date check (`expiryDate == null || expiryDate.isAfter(currentEtDate())
  → return false`) stays and remains the real guard — a non-expiry-day eod flatten still returns
  false because the contract has not physically expired, so no behavior change off the expiry date.
- Called unchanged from the run-tail at `PositionWorkflowImpl.java:1326`.

**Replay safety (mandatory):** the current code returns `false` at the FIRST line for
`reason=eod`/`expiry_lead` and NEVER reads a getVersion marker on that path. Broadening the reason
set makes those paths reach a worthless-close (zero `remainingQty`, clear the late-fill flag, emit
`KIND_POSITION_EXPIRED`) — a NEW command stream for in-flight histories. Gate the broadened branch
behind a **new** marker read once at a stable scope:
```java
Workflow.getVersion("expire-worthless-scheduled-v1", Workflow.DEFAULT_VERSION, 1)
```
Leave the existing `VERSION_EXPIRE_WORTHLESS` ("expire-worthless-v1", `:539`) UNTOUCHED for the
`reason=="expiry"` path. Under `DEFAULT_VERSION`, `eod`/`expiry_lead` keep returning false (legacy
stay-ALIVE) so recorded histories replay byte-identically; the only new command on v=0 is the
appended marker. Read the new marker deterministically for the broadened reasons (do not read it
conditionally on state that diverges across replay). This is a long-lived multi-day workflow —
in-flight executions replay across the redeploy, so the gate is mandatory.

**Audit kinds:** reuses `KIND_POSITION_EXPIRED` (already registered —
`AuditEventKinds.java:87,282`). No new kind → no `ALL_KINDS` edit / no KindRegistryGuardTest risk.

**TDD / tests (name them):**
- `PositionWorkflowImplTest.expiredContract_eodFlatten_noFill_closesWorthless_notLinger` —
  **reproduces THIS incident**: a watchlist 0DTE deep-OTM no-bid lot reaches an `eod` flatten that
  rests unfilled on/after its OCC expiry date → asserts `remainingQty==0`, a `PositionExpired`
  (`reason=worthless_expiry`) audit, and `run()` completes (NOT stuck in the alive-block).
- `PositionWorkflowImplTest.expiredContract_expiryLeadFlatten_noFill_closesWorthless`.
- `PositionWorkflowImplTest.notExpiredContract_eodFlatten_noFill_staysAliveUnchanged` — regression:
  off the expiry date, `eod` no-fill still stays ALIVE (no worthless-close).
- Replay determinism test at `DEFAULT_VERSION`: an eod-flatten-no-fill history from before this
  change replays byte-identically (stays ALIVE).

**Verify command:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test -Dtest=PositionWorkflowImplTest
```
**Behavioral assertion (ties to finding):** the 19:55-style eod-adopted worthless lot terminates as
expire-worthless at its first post-expiry flatten instead of lingering until manual termination.

> Interaction with Phase 3: shipping Phase 2 alone fixes the linger and makes each re-adopted PW
> self-terminate worthless (so the loop becomes self-healing and bounded), but recon may still
> re-adopt once or twice intraday. Phase 3 removes that residual churn. Phase 2 carries most of the
> value and is the mandatory core.

---

### Phase 3 — Stop recon from re-adopting a physically-expired contract on/after its expiry date (replay-gated) — SHIP LAST

**Concern (B2 — re-adoption churn):** recon's refuse-expired gate uses strict `isBefore`, so on the
expiry DAY (0DTE) it does not refuse and re-adopts a worthless post-close lot every recon cycle.

**Changes (with anchors):**
- `ReconciliationWorkflowImpl.java` — `maybeAutoAdopt`, the expiry gate at `:642-674`, specifically
  the condition at `:658` (`occExpiry.isBefore(workflowEtDate())`). Tighten it so a lot whose OCC
  has physically expired is refused ON its expiry date too (see Fork 2 for the exact guard). Reuses
  `KIND_AUTO_ADOPT_REFUSED_EXPIRED` (`AuditEventKinds.java:352`, registered) and the existing
  `refused_expired` metric outcome — no new audit kind.

**Replay safety (mandatory):** the current gate is a plain `isBefore` branch that reads no
getVersion marker. Changing the branch condition changes which commands run (refuse → early
`return`, i.e. NO child-start commands, vs adopt → `AdoptionWorkflow` child-start + audits). Recon
histories must replay byte-identically. Gate the tightened condition behind a **new** marker read
once at a stable scope in `maybeAutoAdopt`, before the expiry branch:
```java
Workflow.getVersion("recon-refuse-expired-sameday-v1", Workflow.DEFAULT_VERSION, 1)
```
Precedent: recon already gates history changes behind `VERSION_MISSING_VISIBILITY_FALLBACK`
("recon-missing-visibility-fallback-v1"). Under `DEFAULT_VERSION`, keep the strict `isBefore`
behavior so in-flight recon histories replay identically.

**TDD / tests (name them):**
- `ReconciliationWorkflowImplTest.expiredOcc_onExpiryDayAfterClose_refusesAdopt_notReadopt` —
  **reproduces THIS incident**: a broker remnant for a 0DTE OCC seen after the expiry-session close
  on its expiry date → asserts `AutoAdoptRefusedExpired` + `refused_expired` metric + NO
  `ReconAutoAdoptionInitiated` / NO child-start.
- `ReconciliationWorkflowImplTest.expiredOcc_dayAfter_stillRefuses` — regression on the existing
  #434 next-day path.
- The still-tradeable-orphan guard test dictated by Fork 2 (see below) — asserts a legitimately
  orphaned lot on its expiry day that is NOT yet worthless/post-close is STILL adopted.
- Replay determinism test at `DEFAULT_VERSION`: a pre-change recon history that adopted a same-day
  expiry OCC replays byte-identically.

**Verify command:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test -Dtest=ReconciliationWorkflowImplTest
```
**Behavioral assertion (ties to finding):** a physically-expired worthless OCC is dropped by recon
instead of being re-adopted every ~25-30 min; the loop cannot restart.

---

## 4. Forks requiring a user/lead decision

**Fork 1 (Phase 1) — how to close the floor gap.**
- (A, RECOMMENDED, chosen as default) **Add the three floor fields to the watchlist config.**
  Restores real bounded-floor protection for non-worthless watchlist exits and removes the noise.
  Pure config → no replay gate → ships first. Low cost if "wrong" (values are tunable). Requires
  operator DB seeds O2/O3 for staging_paper/prod_real (their configs are not in the repo).
- (B) **Make `resolveExitFloor`/`computeBoundedFlattenLimit` tolerate an absent floor quietly**
  (downgrade `FlattenFloorConfigError` to a benign note when no floor is configured, treating "no
  floor configured" as intentional rather than an error). This is a CODE change in
  `PositionWorkflowImpl` that alters the audit command stream → it would itself need a getVersion
  gate and would NOT be an isolated first phase. It also leaves watchlist with zero floor
  protection (always marketable). **Chose A** because the cost of guessing wrong on config values
  is low and A keeps the risk-first ship order intact; B is strictly heavier. Confirm A's values
  with risk before O2/O3.

**Fork 2 (Phase 3) — exact recon refuse guard, and whether Phase 3 is needed at all.**
- (2A, minimal) **Phase 2 only; leave recon as-is.** Each re-adopted PW self-terminates worthless
  (Phase 2), so the loop is self-healing; recon churn is bounded, not infinite. Avoids the delicate
  recon change entirely. Residual: a transient false dashboard position + a little recon noise
  between re-adopt and self-terminate on the expiry day.
- (2B, RECOMMENDED per the finding) **Ship Phase 3 with a post-close guard.** Refuse adoption when
  `!occExpiry.isAfter(workflowEtDate())` (on/after expiry date) **AND** it is past the expiry-
  session close (so an intraday, still-tradeable orphan on its own expiry day is STILL adopted and
  managed). This needs a deterministic "past close" check (e.g. `workflowNow()` past 16:00 ET, or a
  MarketCalendar activity — must be replay-deterministic).
- (2C) **Refuse on/after expiry date unconditionally** (drop the "past close" guard). Simplest, but
  RISKY: it would refuse to adopt a genuinely orphaned, still-tradeable 0DTE lot early on its
  expiry day → that lot would go unmanaged/unflattened. On prod_real (real money) that is a worse
  failure than the churn it prevents.

**DECISION (operator ridopark@gmail.com, 2026-07-12 — LOCKED):**
- **Fork 1 → A (add the three floor fields to the watchlist config).** Confirmed. Ship Phase 1 first; risk-confirm the values match copytrade-v1 before O2/O3 DB seeds.
- **Fork 2 → 2B (ship Phase 3 with the post-close guard).** Confirmed. "Past close" boundary = **hard 16:00 ET** via a replay-deterministic `workflowNow()`-in-ET check (NOT a MarketCalendar activity — keeps Phase 3 minimal and history-deterministic). The half-day/early-close edge case (a 13:00 ET-close contract stays adoptable until 16:00 ET) is explicitly ACCEPTED: Phase 2 self-terminates any such re-adopted lot as expire-worthless, so the residual is at most a little bounded recon churn on a half-day, never an unmanaged position. Do NOT implement 2C.

**Ship order stands: separate PR per phase, risk order (Phase 1 → 2 → 3), recon change LAST, per-phase operator merge + deploy gate before the next phase begins.**

---

## 5. Ship order recap

1. **Phase 1** (config: watchlist exit-floor fields) — no replay gate; then O4 `kubectl apply`
   40-tenants-config.yaml + O2/O3 DB seeds.
2. **Phase 2** (PositionWorkflow worthless-close broadened to eod/expiry_lead) — new marker
   `expire-worthless-scheduled-v1`; fixes the linger. Mandatory core.
3. **Phase 3** (recon refuse-expired on/after expiry date, post-close guarded) — new marker
   `recon-refuse-expired-sameday-v1`; removes residual re-adoption churn. Ships LAST (widest blast
   radius); gated on the Fork 2 decision.

Each phase = its own PR, its own orchestrator redeploy (O5) in this order.
