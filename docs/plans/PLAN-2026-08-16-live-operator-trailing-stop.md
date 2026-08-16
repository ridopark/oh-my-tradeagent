# PLAN — 2026-08-16 Operator-set trailing stop from /live

**Goal.** Let the operator arm a trailing stop on a single open position from `/live`: click
**Stop-loss** → choose a trailing percentage (and see the stop price it implies) → **Set** or
**Cancel**.

**This builds almost nothing new.** A complete chandelier trailing stop already runs inside
`PositionWorkflowImpl` — peak tracking, giveback threshold, breakeven floor, tick debounce,
subscription management, five audit kinds, and a version gate. Today it can only be armed
automatically (STC cue, or target-fire on the runner). The work is an **operator entry point** into
that existing machinery, not a second stop mechanism.

Anchors below were read at authoring time.

---

## STATUS (updated 2026-08-16 — read this before executing anything)

Branch `feat/live-operator-trailing-stop`, commit `71d34bb`.

| Phase | State |
|---|---|
| **Phase 1** — contract DTOs | ✅ **DONE** — `arm-trail-request.json` / `arm-trail-result.json` + Java/pydantic regen |
| **Phase 2** — `arm_trail` Update | ✅ **DONE** — handler, validator, shared `chandelierArmRejection()`, `resolveTrailAnchor()`, 6 tests, both money-guards mutation-verified |
| **Phase 3** — `POST /positions/arm-trail` | ⬜ **TODO — this is the work** |
| **Phase 4** — /live wiring | 🟡 **PARTIAL** — `dashboard/components/StopLossButton.tsx` is written and typechecks; the server action + render site are NOT done and depend on Phase 3 |

**Correction to Phase 2 as originally written: it does NOT need a version gate,
and none was added.** The original text said "REQUIRED". That was wrong.
`partial_close` documents the reasoning at `PositionWorkflowImpl.java:1873` — a
brand-new Update cannot appear in any recorded history, so a legacy replay never
reaches the handler and a marker would be inert. `force_close` carries one only
because it predates that reasoning. Do not add a gate to `arm_trail`.

Verified at that commit: orchestrator 1318, contract 50, python 44, dashboard
`tsc` clean.

---

## What already exists (read before designing anything)

| Piece | Anchor | Note |
|---|---|---|
| Arm signal | `PositionWorkflowImpl.java:1723` `armChandelier(ArmChandelierPayload)` | Version-gated `chandelier-v1`; buffers to `pendingArms`, no activity in the handler |
| Arm validation | `:2033-2057` | `peak > 0` else `invalid_peak`; `0 < giveback <= MAX_GIVEBACK` else `invalid_giveback` |
| Giveback ceiling | `:718` `MAX_GIVEBACK = 0.5` | Hard bound; `trail_giveback_pct` schema also caps at 0.5 |
| Tick subscription | `:2059-2074` | `marketData.subscribePremium`; on FAILED emits `ChandelierSubscriptionFailed` and does **not** arm |
| Arm commit | `:2076-2077` | `trailingArmed = true; peakPremium = peak` |
| Audit kinds | `:90-94` | `ChandelierArmed` / `TrailFired` / `ArmRejected` / `SubscriptionFailed` / `UnarmedByExit` — all already registered |
| Payload | `contract/schemas/arm-chandelier-payload.json` | requires `peak_premium` + `giveback_pct` (+ ids) |

**Existing auto-arm callers** set `peak = current/target bid` and `giveback = trail_giveback_pct`
(`:2194`, `:2329`). The operator path should land in the same place with the same invariants.

---

## The two decisions that shape the build

### Fork A — Update, not signal (RECOMMENDED: Update)

`armChandelier` is a **signal**: fire-and-forget, no result. But an arm can be *rejected
asynchronously* — `invalid_peak`, `invalid_giveback`, or `ChandelierSubscriptionFailed` when
market-data can't subscribe. On a signal the UI's **Set** button would report success for an arm
that silently never happened, and the operator would believe a real-money position is protected
when it is not.

Both sibling operator actions are Updates for exactly this reason: `force_close`
(`PositionWorkflow.java:108`) and `partial_close` (`:135`). **Add an `arm_trail` Update** that
performs the same validation synchronously and returns ACCEPTED / REJECTED(reason). The existing
signal stays untouched for the automatic callers.

> A "stop-loss set" that silently didn't set is the worst failure this feature can have. It is
> indistinguishable from success on the screen and only discovered when the stop fails to fire.

### Fork B — who chooses the anchor (RECOMMENDED: the workflow)

`ArmChandelierPayload` requires a client-supplied `peak_premium`. Threading that from the browser
means a stale or mistyped anchor sets the stop at the wrong level on real money — and the page's
displayed premium can be seconds old.

The workflow already holds a better answer (`peakPremium` / `lastTickPremium`, and it can fetch a
fresh quote the way the exit ladder does). **The operator sends only `giveback_pct`; the workflow
resolves the anchor** as `max(its own peak so far, fresh quote)`. This also removes the "arm below
the current price" foot-gun entirely.

### UI semantics — do not render a fixed stop

A chandelier stop is **peak-anchored and moves up, never down**: `fire = peak × (1 − giveback)`.
The screen must not imply a fixed price the operator has set. Show the trailing % as the thing being
chosen, and the current stop as a **derived, live consequence**:

```
Trailing 15%   ·   stop now ≈ $3.19   (rises with the peak, never falls)
```

If the operator instead types a stop *price*, derive `giveback = 1 − stop / peak` and show the
percentage — but the percentage is still what is stored, and the label must say the stop rises.

---

## P0 / operator follow-ups (NOT code phases)

1. **Flip the dark flag** (`positions.arm-trail.enabled=true` on api-gateway, plus the dashboard's
   own feature flag) once Phase 4 is deployed. Ships dark; every phase merges inert.
2. **Canary on `staging_paper` first** — arm a trail on one paper position, confirm `ChandelierArmed`
   in the audit trail and that the stop rises with the peak, before enabling for live tenants.
3. **Deploy order:** orchestrator BEFORE api-gateway/dashboard. The Update must exist on the worker
   before anything can call it; a call to an unknown Update name fails at the server.
4. No tenant-YAML or ConfigMap change in this plan → **no `40-tenants-config.yaml` re-sync needed**,
   and no live-cluster-only YAML edits.

---

## Phase 1 — contract DTOs for the Update (`contract`)

**Goal:** request/result types for `arm_trail`, mirroring `ForceCloseRequest`/`ForceCloseResult`.

**Changes:**
- `contract/schemas/arm-trail-request.json` — `schema_version`, `operator_id`, `giveback_pct`
  (`exclusiveMinimum: 0`, `maximum: 0.5` — matches `MAX_GIVEBACK` and `trail_giveback_pct`),
  optional `peak_premium` (absent = workflow resolves it, per Fork B).
- `contract/schemas/arm-trail-result.json` — `status` enum `ACCEPTED | REJECTED | ALREADY_ARMED`,
  plus `reason`, `peak_premium`, `giveback_pct`, `stop_price` so the UI can echo what was actually
  set rather than what was requested.
- Regen: jsonschema2pojo + `contract/python/regen.sh`.

**Tests:** contract round-trip in Java + Python; assert `giveback_pct` above 0.5 fails validation.

**Verify:** `mvn -pl contract/java -am spotless:apply && mvn -pl contract/java test`;
`cd contract/python && ./regen.sh && uv run pytest tests/ -q` — working tree clean after regen.

---

## Phase 2 — `arm_trail` Update on PositionWorkflow (`services/orchestrator`)

**Goal:** synchronous operator arm that reuses the existing chandelier path.

**Replay gate: NOT required — do not add one.** (Corrected during implementation; the original
text here said REQUIRED and was wrong.) A brand-new `@UpdateMethod` cannot appear in any recorded
history, so a legacy replay never reaches the handler and the command stream is unchanged. See
`PositionWorkflowImpl.java:1873`, where `partial_close` states exactly this and calls a gate there
"an inert marker". `force_close` has one only because it predates the reasoning.

**Changes (anchors):**
1. `PositionWorkflow.java:135` — add `@UpdateMethod(name = "arm_trail")` beside `partial_close`,
   with an `@UpdateValidatorMethod` that rejects out-of-range giveback **synchronously** (a
   validator rejection never enters history — the cheapest possible rejection).
2. `PositionWorkflowImpl.java:2033` — extract the existing peak/giveback validation into a helper
   shared by the signal path and the Update, so the two can never diverge on what is legal.
3. Anchor resolution (Fork B): when the request omits `peak_premium`, use
   `max(peakPremium, fresh GetOptionQuoteActivity bid)`. **Fail-safe: if no anchor can be resolved,
   REJECT the arm — never arm at a guessed level.**
4. Reuse `:2059-2077` verbatim for subscribe + commit, so a subscription failure REJECTS the Update
   (surfacing to the operator) instead of only writing an audit row.
5. Re-arm semantics: if `trailingArmed` is already true, return `ALREADY_ARMED` with the current
   values **unless** the new giveback is tighter — never silently loosen an existing stop.

**Audit:** reuses `ChandelierArmed` / `ChandelierArmRejected` with `source=operator` +
`operator_id`. **No new kinds → `KindRegistryGuardTest` untouched.**

**Tests (TDD, `PositionWorkflowImplTest`):**
- `armTrail_operatorArmsAndStopRisesWithPeak` — arm at 15%, push ticks up, assert the fire threshold
  rises and never falls.
- `armTrail_rejectsGivebackAboveMax` — 0.6 → REJECTED, `trailingArmed` stays false.
- `armTrail_rejectsWhenSubscriptionFails` — market-data FAILED → REJECTED **and** not armed (the
  operator must not be told a stop exists).
- `armTrail_resolvesAnchorWhenPeakOmitted` and `armTrail_rejectsWhenAnchorUnresolvable`.
- `armTrail_alreadyArmedDoesNotLoosen` — a looser giveback returns ALREADY_ARMED and leaves the
  tighter stop in place.
- **Replay:** a pre-change `PositionWorkflow` history replays green on `DEFAULT_VERSION`.
  **Confirm the fixture has teeth** — defeat the gate and verify it fails; a fixture that passes
  either way is decoration (this repo has shipped two such fixtures).

**Verify:** `mvn -pl services/orchestrator -am spotless:apply`;
`mvn -pl services/orchestrator test -Dtest=PositionWorkflowImplTest,PositionWorkflowImplLegacyReplayTest`.
`KillSwitchWorkflowImplTest` is flaky here — re-run, don't fix.

---

## Phase 3 — `POST /positions/arm-trail` (`services/api-gateway`)

**Goal:** operator endpoint, modelled exactly on `force-close` (`PositionsController.java:85`).

**Changes:**
- `PositionsController.java` — new `@PostMapping("/arm-trail")`. **Copy the tenant-scoping guard
  verbatim** (`:96-101`): reject any `workflow_id` not prefixed
  `WorkflowIds.tenantStrategy(tenant, strategy) + "/"`. That check is what stops one tenant arming
  a stop on another tenant's position — it is the security control of this endpoint, not a
  formality.
- `operator_id` from `ctx.operatorId(req)`, never from the body.
- Dark flag `@ConditionalOnProperty("positions.arm-trail.enabled")`, default false; add the default
  to `application.yml` (the IMAGE default — env is not applied by deploy on homelab).
- Map `ACCEPTED` → 202, `ALREADY_ARMED` → 200, `REJECTED` → 422 with the reason.

**Tests:** `PositionsControllerTest` — cross-tenant `workflow_id` → 400 and **no Update dispatched**;
flag-off → 404; REJECTED → 422 carrying the reason.

**Verify:** `mvn -pl services/api-gateway -am spotless:apply && mvn -pl services/api-gateway test`.

---

## Phase 4 — Stop-loss button on /live (`dashboard`)

**Goal:** the interaction, following `TrimButton.tsx` exactly.

**Changes:**
- `dashboard/components/StopLossButton.tsx` — client island copying TrimButton's model: **Stop-loss**
  → preset trailing percentages → **Set** / **Cancel**, with the same `CONFIRM_TIMEOUT_MS = 5000`
  auto-disarm on timeout and blur, so a stray click never leaves a primed real-money action in the
  table.
- Presets `[0.10, 0.15, 0.20, 0.25]`, each rendered with the stop price it implies at the current
  premium — the same discipline as `qtyForFraction`, where the label states the truth rather than an
  estimate. Never offer a value above `MAX_GIVEBACK` (0.5).
- Label the stop as rising: `stop now ≈ $X · rises with the peak`.
- Show armed state on the row afterwards (trailing %, current stop) so the operator can see a stop
  exists without opening the audit log.
- Server action in `dashboard/app/live/page.tsx` mirroring the trim/force-exit actions; result type
  `{ ok: true } | { ok: false; kind: "already-armed" | "rejected" | "disabled" | "error" }`.

**Tests:** component tests for preset→stop-price arithmetic, auto-disarm, and that a `REJECTED`
response renders a visible failure — **never a silent success**.

**Verify:** `npx tsc --noEmit` + the dashboard test suite.

---

## Ship order & gating

1. **Phase 1** (contract) — no runtime effect.
2. **Phase 2** (orchestrator Update + version gate) — riskiest; ships inert (nothing calls it).
3. **Phase 3** (api-gateway) — behind a dark flag.
4. **Phase 4** (dashboard) — behind the same flag.
5. **Operator:** flip the flag, canary on `staging_paper`, then live tenants.

Each phase: TDD first, `spotless:apply` on every touched module, its own PR, operator merge gate
(trading-critical path).

**Deploy order is load-bearing** — orchestrator first (see P0.3).

---

## Explicitly out of scope

- **Changing how the automatic trail arms.** The STC-cue and target-fire callers keep their current
  behaviour; this plan only adds an operator entry point.
- **A fixed (non-trailing) stop-loss.** That is a different instrument with different semantics, and
  the existing machinery does not implement it. If that is what is wanted, say so — it is a larger
  build, not a variation of this one.
- **Un-arming from the UI.** `ChandelierUnarmedByExit` exists for the exit path; an operator
  *disarm* is a separate concern and a separate fork (should an operator be able to remove a stop
  from a live position at all?).
