# PLAN — 2026-08-04 Copytrade BTO entry bounded single re-peg

**Goal.** Stop abandoning copytrade BUY entries on fast-moving options. Wire the **already-spec'd,
currently-unwired** `repeg_after_ms` strategy-config field so the BTO entry order performs **one
bounded re-peg** from a tight initial limit up to the slippage-capped ceiling before it expires —
never chasing past the ceiling.

Source of finding: live diagnosis on `prod_real` (real money) 2026-08-04. All `file:line` anchors
below were re-read at authoring time.

---

## Incident summary (confirmed from the audit trail)

The BTO limit is `BtoPricing.computeBtoLimit` (`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/domain/BtoPricing.java:68`):
`min(price + max_slippage_abs, price × (1 + max_slippage_pct))`, rounded to a penny tick
(`OptionTick.round`, `:98`). It is **anchored to the signal's already-stale `payload.getPrice()`**
(`:69`) and has **no ask term** — it never reaches toward the live market.

The order is submitted **once** at that limit (`exec.placeOrder`,
`CopytradeSignalWorkflowImpl.java:649`; `OrderSubmitted` audit `:651`), then a single
`Workflow.await(Duration.ofSeconds(ttlSecs), …)` (`:665-666`, `ttlSecs` from `pendingTtlSecs`,
`:663` → `:1511-1514` = `pending_ttl_paper_secs` else the `DEFAULT_PENDING_TTL_PAPER_SECS = 90L`
constant at `:291`). On timeout `handleTtlExpired` (`:738` → `:1189`) emits
`OrderCancelRequested reason=ttl_expired` (`:1196`), `exec.cancelOrder` (`:1204`), then
`OrderCancelled` / `EntryExpired` (`:1245-1276`). **No re-peg, no chase, no retry.**

Real-money misses today:
- NVDA 8/10 212.5C — limit 2.95, option ran 2.95→3.25 within 2 min → expired unfilled.
- AAPL 8/14 315C — limit 2.51 while the option was already 2.55–2.61 at submit → never
  marketable → expired.

**Interim mitigation already shipped (config only):** `max_slippage_abs = 0` on all copytrade
tenants, so the effective limit is `price × (1 + max_slippage_pct)` = `price × 1.05`. Helps, but
still (a) misses >5% runners and (b) is one-shot with no chase.

**Target field is present but inert.** `contract/schemas/strategy-config.json` defines
`repeg_after_ms` (schema ~`:168-172`): *"Milliseconds the BTO limit sits at its initial price
before a single re-peg toward the slippage-capped ceiling … Spec-only: no orchestrator/exec code
consumes this field."* The generated POJO getter exists —
`StrategyConfig.getRepegAfterMs()` returns `Long`
(`contract/java/target/generated-sources/jsonschema2pojo/…/StrategyConfig.java:1123`) — and a
repo-wide grep confirms **the only references are in generated sources** (unwired). No schema add
is needed; wiring it needs no `jsonschema2pojo`/pydantic regen.

---

## The material design decision (read before Fork C)

Today `computeBtoLimit` **already returns the ceiling** — the single `min(…)` formula is the
max-acceptable cost, and with the `abs = 0` mitigation it collapses to `price × 1.05`. So "initial
limit" and "ceiling" currently **coincide**, and a re-peg from one to the other would be a no-op.
For the re-peg to have any effect, the **initial submit peg must be defined strictly below the
ceiling**.

This plan's recommended resolution (Fork C): **keep the ceiling = today's `computeBtoLimit`
(unchanged) and keep feeding it to the risk gates + sizing; submit initially at a tighter peg (the
signal mirror price), and re-peg up to the ceiling.** This has two payoffs:
1. The notional-cap / buying-power gates already receive `priced.limit()` = the ceiling
   (`checkEntryWithLimit`, `CopytradeSignalWorkflowImpl.java:479-486`; `notionalCapHeadroomContracts`,
   `:552-555`; `RiskActivities.java:40-45,111-116`). Because the re-peg targets that **same
   ceiling**, the higher re-peg limit is **already pre-gated** — no re-check at re-peg is needed.
2. `computeBtoLimit` and its callers are untouched, so the pure-math change (Phase 1) is
   behavior-neutral and the workflow change (Phase 2) is the only replay surface.

---

## P0 / operator follow-ups (NOT code phases)

1. **Set `repeg_after_ms` per copytrade tenant** once Phase 2 is deployed. This is the switch that
   turns the feature from dark (unset = no re-peg = today's behavior) to live. Field is
   schema-driven so it surfaces in `/config` automatically (per the config-editor drift job); it is
   editable per-tenant.
   - **Config sources are the usual 4 spots** (dev yaml + `40-tenants-config.yaml` ConfigMap +
     onboard template + per-live-tenant DB row). Editing any `tenants/dev/*` file trips the
     **ConfigMap drift guard** — regen `40-tenants-config.yaml` in the same PR. Live-tenant YAMLs
     are **live-cluster-only**; a naive `kubectl apply` of the ConfigMap DROPS live blocks — apply
     the merged ConfigMap, never a partial.
2. **Resolve Fork C's config recipe** (below): confirm the slip config so the initial peg sits
   **below** the ceiling (else the re-peg is a harmless no-op). With the recommended
   mirror-price initial, no slip change is required; with the alternative tight-abs initial, the
   operator must restore a small `max_slippage_abs`.
3. **Canary order:** flip `repeg_after_ms` on `staging_paper` first, watch a few entries re-peg +
   fill, then `prod_real` / `prod-kipark`.
4. **Deploy gate + homelab verify** after each code phase (deploy targets the k3s homelab).

---

## Phase 1 (code, lowest blast radius) — split initial peg vs ceiling in `BtoPricing`

**Concern:** pure-math only — expose an initial submit peg alongside the existing ceiling.
No Temporal surface, no replay gate, no audit, behavior-neutral for all current callers.

**Module:** `services/orchestrator`

**Replay-gate decision:** NONE — `BtoPricing` is a pure, determinism-safe function with no
Temporal command surface. This phase changes no workflow command stream.

**Changes (anchors):**
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/domain/BtoPricing.java:68`
  — leave `computeBtoLimit` returning the **ceiling** exactly as today (all existing callers keep
  the ceiling → risk gates unchanged). Add a sibling pure method — e.g.
  `computeBtoInitialPeg(payload, config)` — returning the **initial submit peg**:
  - Recommended (Fork C): the signal mirror price `payload.getPrice()`, penny-tick-rounded through
    the same `OptionTick.round` (`:98`) so entry/exit stay in lock-step.
  - Return the ceiling itself when the initial would be ≥ ceiling (degrade-to-one-shot safety).
  - Keep it a single-use pure helper (KISS): no new record/abstraction unless a second caller
    appears.

**Success criteria (TDD, add to
`services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/domain/BtoPricingTest.java`):**
- `computeBtoLimit` output is **byte-identical** to today for every existing branch case
  (MIRROR / SLIP_ABS / SLIP_PCT / SLIP_MIN) — assert no regression.
- `computeBtoInitialPeg` returns the mirror price (penny-rounded) when it is strictly below the
  ceiling.
- `computeBtoInitialPeg` returns the ceiling when mirror ≥ ceiling (no-op degrade).
- Both outputs are ≤ 2 dp (penny tick) so neither can trip Alpaca's HTTP 422.

**Verify command:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator test -Dtest=BtoPricingTest
```

---

## Phase 2 (code, version-gated workflow) — wire the bounded single re-peg into `handleBto`

**Concern:** the actual behavior change — one re-peg on the entry path. This is the **command-shape
change** and the only replay surface. Ships **inert** (no re-peg unless `repeg_after_ms` is set),
so it can merge/deploy before any config flip. Depends on Phase 1.

**Module:** `services/orchestrator`

**Replay-gate decision:** **REQUIRED.** New commands inside `CopytradeSignalWorkflowImpl` (a new
re-peg-delay **timer**, a new `exec.cancelOrder` + `exec.placeOrder`, and re-peg audit calls) are a
command-shape change on a workflow with in-flight histories. Add a new marker
`VERSION_BTO_ENTRY_REPEG = "bto-entry-repeg-v1"`, read **once, unconditionally**, at a stable scope
at the top of `handleBto` (mirroring the existing markers, e.g. `VERSION_LIVE_PROMOTION_GATE`
read-once discipline at `:620`). Pre-fix histories replay on `DEFAULT_VERSION` = today's single
order + single `await(ttl)` + single `handleTtlExpired`, with **no re-peg command**.
> Note: the limit VALUE itself is an activity-input to `exec.placeOrder` and is **not**
> replay-checked (Temporal 1.27 checks command type/ordering only); it is the new **timer + cancel
> + placeOrder + audit commands** that require the gate.

**Changes (anchors):**
1. `CopytradeSignalWorkflowImpl.java:648` — build the initial `OrderIntent` at the **initial peg**
   (`BtoPricing.computeBtoInitialPeg`) instead of `priced.limit()`, **only** when the re-peg branch
   is active (`v>=1` AND `repegAfterMs != null` AND `repegAfterMs < ttl`); otherwise keep
   `priced.limit()` verbatim so the inert path is byte-identical.
2. `:663-666` — replace the single `Workflow.await(ttl, …)` with a bounded two-window await **only**
   on the active branch:
   - Await `Duration.ofMillis(repegAfterMs)` on `fillEvent != null || riskBreachReceived`.
   - If unfilled and no breach after that window: **re-peg once** — `OrderCancelRequested`
     (reused kind, `reason=repeg`) → `exec.cancelOrder(intentKey)` → `OrderCancelled`
     (reused kind, `reason=repeg`), then `exec.placeOrder` a **new intent at the ceiling**
     (`priced.limit()`) with a **distinct intent key** `Workflow.getInfo().getWorkflowId() +
     ":entry:repeg"` (the original `":entry"` key at `:647` was cancelled; exec is idempotent by
     intent key), then `OrderSubmitted` (reused kind, subject `peg=ceiling`).
   - Await the **remaining** window (`ttl − repegAfterMs`) on the same predicate.
   - Cancel-on-filled race at the re-peg boundary: when `exec.cancelOrder` returns `state=FILLED`,
     adopt via the existing `handleCancelOnFilled(… RECOVERY_CANCEL_ON_FILLED)` (`:704`, `:1286`) —
     do **not** place the re-peg order (bounded, no double order, no orphan).
   - **Bounded: at most ONE re-peg.** Never chase past the ceiling.
3. `:712-739` — the existing FILLED branch and the terminal `handleTtlExpired(…)` (`:738`) stay
   unchanged and handle the final window (still `EntryExpired` at TTL, now against the re-pegged
   ceiling order's `intentKey`).
4. When the branch is inert (`v==DEFAULT_VERSION`, or `repegAfterMs` null, or `repegAfterMs >= ttl`)
   the code path is the **existing single `await(ttl)` + `handleTtlExpired`** — a `v>=1` tenant that
   never sets the field is byte-identical to today.

**Audit — no new kind (KindRegistryGuard NOT triggered).** The re-peg reuses `OrderCancelRequested`
/ `OrderCancelled` / `OrderSubmitted` (all already in `AuditEventKinds.ALL_KINDS`,
`services/audit/src/main/java/com/ohmytradeagent/audit/AuditEventKinds.java:148-151`) with a new
**reason string** `"repeg"` (reason values are not registered). This avoids any
`AuditEventKinds`/`KindRegistryGuardTest`
(`services/audit/src/test/java/com/ohmytradeagent/audit/lint/KindRegistryGuardTest.java`) change.
A dedicated `BtoRepegged` kind was considered and **rejected** for blast radius — the reused kinds
plus a `reason=repeg` tag give forensics the same signal. The second `OrderSubmitted` (at the
ceiling) is exactly what the success test asserts.

**Notional / buying-power interaction — NO re-check at re-peg (verified).** The gates already run
against the **ceiling**: `checkEntryWithLimit` is fed `priced.limit()` (`:479-486`), and
`notionalCapHeadroomContracts` is fed `priced.limit()` (`:552-555`); `RiskActivities.java:40-45`
documents `limit` as "the BTO max-cost threaded into both the notional-cap gate and the
buying-power compare." Since the re-peg's target is that same `priced.limit()` ceiling, the higher
re-peg limit is **already covered** by the initial gate. Sizing (`Sizing.computeEntry`, `:537`) is
likewise computed against `priced.limit()` — the max cost — so contract count does not grow at
re-peg. Conclusion: **do not** re-dispatch the risk gate on re-peg.

**Repo gates folded in:**
- Run `spotless:apply` on `services/orchestrator` before commit (impl env skips it → CI fails).
- `KillSwitchWorkflowImplTest` is flaky → re-run if it fails; not caused by this change.
- `gh pr edit --body` is broken here — set the PR body at `gh pr create` time or via
  `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>` and verify it persisted.
- Do **not** touch `.github/workflows/*`.
- PR body: include `Closes #<issue>` if issue-linked.

**Success criteria (TDD, add to
`services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/workflows/CopytradeSignalWorkflowImplTest.java`):**
1. **`repeg_fills_at_ceiling` (incident repro):** a signal whose option ticks above the initial peg
   within `repeg_after_ms`; after the delay the order re-pegs to the ceiling and fills. Assert a
   **second `OrderSubmitted` at the ceiling limit** followed by `EntryFilled` (models NVDA/AAPL).
2. **`repeg_bounded_no_chase_past_ceiling`:** a runner that blows past the ceiling → re-pegs to the
   ceiling once, does **NOT** chase further, and `EntryExpired` fires at TTL. Assert exactly **one**
   re-peg `OrderSubmitted` and a terminal `EntryExpired`.
3. **`normal_fill_no_repeg`:** a signal that fills at the initial peg before `repeg_after_ms` → **no**
   second `OrderSubmitted`, **no** re-peg cancel; single `EntryFilled`.
4. **`repeg_unset_is_single_shot`:** `repeg_after_ms` null at `v>=1` → byte-identical single
   `await(ttl)`, one order, one `handleTtlExpired` (inert-when-unset guarantee).
5. **`repeg_cancel_on_filled_adopts`:** the initial order fills in the cancel race at the re-peg
   boundary (`cancelOrder` → `state=FILLED`) → adopt via `handleCancelOnFilled`, **no** re-peg
   order placed, **no** orphan.
6. **Replay:** a pre-fix `CopytradeSignalWorkflow` history (single order, single 90s TTL, no
   re-peg) replays **green** on `DEFAULT_VERSION` (existing histories-replay harness).

**Verify command:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator test -Dtest=CopytradeSignalWorkflowImplTest,BtoPricingTest
# existing replay-history harness must stay green
```

---

## Phase 3 (DEFERRED fast-follow, NOT this ship) — STC symmetric re-peg toward bid

The `repeg_after_ms` description also covers a **symmetric STC re-peg toward bid**. Recommend
**deferring** it: it is a separate command-shape change on the exit path (`handleStc` /
`PositionWorkflow` exit reprice), needs its own forensics + its own replay gate, and shipping it
with BTO doubles the blast radius. Track as a fast-follow PR; not specced here.

---

## Ship order

1. **P0 config prep** — decide Fork A (delay) + Fork C (initial recipe); do NOT set `repeg_after_ms`
   yet (feature stays dark).
2. **Phase 1** (`BtoPricing`) — behavior-neutral pure-math PR; safe to merge/deploy anytime.
3. **Phase 2** (workflow wiring, `bto-entry-repeg-v1` gate) — merges/deploys **inert** (no re-peg
   until the field is set). Existing histories replay on `DEFAULT_VERSION`.
4. **Operator flips `repeg_after_ms`** — `staging_paper` canary → `prod_real` / `prod-kipark`,
   through the 4 config sources with the ConfigMap drift guard.
5. **Phase 3 (STC)** — deferred fast-follow after the BTO path is proven live.

**Gating note:** every code phase is safe to ship ahead of the behavior flip; the real-money
behavior change is gated behind the operator setting `repeg_after_ms`, giving a controlled canary
without a code redeploy.

---

## Forks — ALL RESOLVED by the lead 2026-08-04 (do not re-litigate)

- **Fork A — RESOLVED → `repeg_after_ms ≈ 30000` (30s), per-tenant, NO hardcoded default.** Unset =
  today's one-shot (safe no-op). The initial peg gets ~30s to fill cheap, then chases to the ceiling
  for the remaining ~60s of the 90s window. Operator sets the value per tenant in P0.
- **Fork B — RESOLVED → BTO-only for v1.** STC symmetric toward-bid re-peg is the DEFERRED Phase 3
  fast-follow, NOT this ship.
- **Fork C — RESOLVED → initial = signal mirror price (`payload.getPrice()`), ceiling = today's
  `computeBtoLimit` (unchanged, still fed to the risk gates).** `computeBtoInitialPeg` returns the
  penny-rounded mirror when strictly below the ceiling, else the ceiling (no-op degrade). **NO slip
  config change** — today's `max_slippage_abs=0` / `max_slippage_pct=0.05` mitigation STAYS (the
  ceiling is `price × 1.05`); the cushion alternative is rejected. P0 requires no slip edit, only
  setting `repeg_after_ms`.
