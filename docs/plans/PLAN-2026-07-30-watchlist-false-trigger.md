# PLAN — 2026-07-30 watchlist false-trigger (aberrant / stale equity tick)

**Source:** `_workspace/01_forensics_nvda_false_trigger_2026-07-29.md`.

On 2026-07-29 a watchlist BREAKOUT leg (`staging_paper`, paper) fired NVDA/C `ABOVE 201` at
18:47:51Z and bought a −$155 lot, although NVDA's SIP RTH high all day was 197.07 and it never
traded ≥200. The evaluator is provably correct (`EntryStateMachine.evaluateBreakout` only fires on
a live cross with `last ≥ level`), so an `EquityTick.last ≥ 201` with **no basis in the tape**
reached it and nothing rejected it. Corroborated by QQQ/C `ABOVE 680` firing at 19:22Z when QQQ was
671 — the real 680 tag was 20 minutes earlier (staleness). Root cause is at the **price-source /
evaluation boundary**: the trigger path trusts a single raw trade print with no outlier guard, a
**dead** staleness guard, and no confirm-on-fire re-read; and there is no durable record of the
fired price. This plan ships four single-concern PRs, isolated feed-layer fixes first, Temporal
workflow-history changes last.

**Blast radius:** the market-data feed + trigger code are shared by ALL tenants. Only prod_real's
intentional watchlist deactivation prevented a real-money false fire. The defect is systemic.

---

## Anchor verification (done before writing phases) + corrections to the report

- **F1** `AlpacaMarketData.recordToEquityTick` `services/market-data/.../provider/alpaca/AlpacaMarketData.java:384-401`, `isHaltedOrStale` :408-420 (drops only condition codes `H`/`P`/`Z`), `snapshotEquityPrice` :224 — **confirmed**. Module: **market-data**. Fan-out loop `dispatchStockWsMessage:339-355`. **This module does NOT write `audit_log`** (no `logAudit`/`AuditEventKinds`/`AuditWriter` anywhere under `services/market-data/src/main`); it records notable events with structured `log.error("AUDIT …")` lines (:277/:330/:500/:588/:596) — re-confirmed 2026-07-30 for the FORK-1 observability requirement.
- **F2** `WatchlistTriggerWorkflowImpl.fire` `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImpl.java:502`; FIRE decision at :449; `exec.placeOrder(intent)` at :628 — **confirmed**. Module: **orchestrator** (workflow code).
- **F3** same fire/decision path; crossing `last` is available at the tick loop `machine.onTick(tick.getLast())` :446 (it is NOT available inside `fire()`). New audit kind `TriggerFired` is **absent** from the codebase and from `AuditEventKinds.ALL_KINDS` (confirmed: only `TriggerFireRejected`, `TriggerFeedStale` at `services/audit/.../AuditEventKinds.java:453-454`). Module: **orchestrator** + **audit**.
- **F4** `SubscribeEquityActivityImpl.toEquityTick` `:310-318`, hardcoded `out.setStale(false)` at **:316**, `retrievedAt` carried at :315 (`t.retrievedAt()`, the trade's own timestamp; set, never age-checked) — **confirmed (re-verified 2026-07-30)**. Workflow stale-drop `WatchlistTriggerWorkflowImpl.java:438` (`if (Boolean.TRUE.equals(tick.getStale()))` → `logAudit(KIND_FEED_STALE)` + `continue`) — **confirmed, already deployed, currently dead**. `KIND_FEED_STALE = "TriggerFeedStale"` already registered.
  - **CORRECTION to the report:** the report's §3/§5 place this file under `services/orchestrator/.../activities/`. It is actually in the **market-data** module: `services/market-data/src/main/java/com/ohmytradeagent/marketdata/activities/SubscribeEquityActivityImpl.java`. Line :316 is correct. This changes F4's module + replay classification (see below).
- **Evaluator invariant (DO NOT CHANGE):** `EntryStateMachine.evaluateBreakout` `services/orchestrator/.../domain/EntryStateMachine.java:144-160`; band `bandHigh = level*(1+g)`, `bandLow = level*(1-g)` (:73), `gapTolerance` default `WatchlistTriggerWorkflowImpl.java:1055`. It is mathematically impossible to fire without a supplied `last ≥ level`. This is the correctness anchor every phase must preserve.

### Replay classification (reasoned per phase — the dominant constraint)

- **F1** lives entirely in the market-data WS adapter (`recordToEquityTick`). It is not workflow code and produces no Temporal command. **→ no getVersion gate.**
- **F4 (recommended: activity-only)** populates `stale` in `toEquityTick` (market-data) by age-checking `retrievedAt` at emit time (wall-clock in the activity thread is allowed — not workflow code). The workflow drop at `:438` is **pre-existing deployed code**, and which branch it takes is driven by the **recorded signal payload** (`EquityTick.stale`), not by any new workflow code. Temporal replay compares commands-from-code given recorded history; the code at :438 is unchanged and the signal payload replays byte-identically (old histories recorded `stale=false` → branch not taken → identical commands; new executions record `stale=true` fresh). This is the same class as the repo note "replay ignores activity-input payloads; a divergent recorded value won't wedge the workflow." **→ no getVersion gate for the activity-only implementation.**
  - **Rejected alternative:** adding a NEW `retrievedAt` age-check *inside the workflow* (new `logAudit`/drop command in workflow code) WOULD be a command-shape change and would require a gate. We do NOT do this — keep the age decision in the activity so the existing :438 branch simply starts working. Stated as a FORK below only so the implementer does not silently move it into the workflow.
- **F3** adds a new `logAudit(TriggerFired, …)` **command inside workflow code** at the fire path → command-shape change. **→ requires `Workflow.getVersion("watchlist-trigger-fired-audit-v1", DEFAULT_VERSION, 1)`** at stable scope; pre-fix histories replay on DEFAULT_VERSION (no audit command).
- **F2** adds a new snapshot-quote **activity call inside `fire()`** before `placeOrder` → new command. **→ requires `Workflow.getVersion("watchlist-confirm-on-fire-v1", DEFAULT_VERSION, 1)`**; pre-fix histories replay on DEFAULT_VERSION (no confirm read, no possible new reject branch).

---

## P0 — Immediate operational (no code; operator)

- **HARD GATE (trading-critical) — RE-ENABLE GATE IS `F1 + F4`:** do **NOT** re-enable watchlist on
  `prod_real` or any real-money tenant until **F1 (tight outlier guard) AND F4 (staleness guard) are
  merged, deployed to homelab, and verified**. The same market-data path + trigger code serve every
  tenant; only prod_real's watchlist deactivation (memory `project_prod_real_watchlist_deactivated`)
  currently prevents a false fire from placing a **real-money** order. Per the lead's FORK-1 decision,
  F1 is now **tight enough to reject the NVDA-type phantom itself** (≈2.55% deviation), so F1 + F4
  together are the sufficient act-time defense for re-enablement. **F2 (confirm-on-fire) is NOT a
  blocker** — it is reclassified as follow-up hardening (Phase 4) and may ship after re-enablement.
- **No live-state remediation required.** 2026-07-29 was paper; both lots closed same session
  (NVDA/C −$155, QQQ/C −$0.18 R), no orphans, no stuck journal rows. Nothing carries risk now.
- **No tenant-YAML / ConfigMap edits in this plan.** All phases hardcode their thresholds for v1
  (see FORK-1 / FORK-2 resolutions). If a threshold is later promoted to per-tenant config it becomes
  a separate operator follow-up (`staging_paper`/`prod_real` strategy YAMLs are live-cluster-only, not
  in repo; dev YAML changes would also need the `infra/k8s/40-tenants-config.yaml` re-sync).

### Forks / open decisions — BOTH RESOLVED by the lead (do not re-litigate)

- **FORK-1 — RESOLVED → "F1 + F4 only, tight F1."**
  - F1 ships a **tight** single-tick outlier guard: **`MAX_DEVIATION_PCT = 2.0%` (0.02)** measured
    against the **prior accepted tick** for that ticker (a rolling last-accepted reference that is
    updated *only* by accepted prints, so a rejected phantom never becomes the reference). NVDA's
    phantom (201 vs prior ~196 ≈ **2.55%**) exceeds 2.0% → **rejected**; the QQQ case is staleness →
    caught by F4. 2.0% is the chosen point in the lead's 2–3% window, biased tight.
  - **Why this reference distinguishes a genuine breakout from a phantom:** during liquid RTH, trade
    prints on NVDA/QQQ are milliseconds apart, so even a *fast* momentum breakout moves the tape in
    small inter-print steps (cents / well under 1% tick-to-tick). A **single** print that jumps 2%+
    from the immediately-prior accepted print and is not corroborated by the next print is
    non-physical — it reverts on the following real trade (exactly the NVDA 201→196 signature). We do
    NOT measure against an intraday anchor/open (a legit trend would false-reject against that); we
    measure against the *most recent accepted print*, which a real breakout is always within 2% of.
  - **Sustained-gap safety (prevents wedging a real move):** because a tight guard could otherwise
    reject a legitimate gap-open / halt-resume repeatedly, the guard **re-seeds on corroboration**:
    the first print of a ticker seeds the reference unconditionally, and if a rejected value is
    **corroborated by the very next print within `MAX_DEVIATION_PCT` of each other**, the second print
    is accepted and advances the reference (two agreeing prints beat a stale reference; a lone phantom
    that reverts is never corroborated). This keeps the guard surgical while ensuring a genuine
    sustained move cannot deadlock the feed.
  - **Observability (required, because F1 is now tight → higher false-reject risk):** every F1
    rejection MUST emit a structured record carrying **rejected `last`, the reference used, and the
    computed deviation %**, so false-rejects are visible and 2.0% can be tuned post-deploy.
    **Path:** the market-data module does **NOT** write `audit_log` (confirmed 2026-07-30 — no
    `logAudit`/`AuditEventKinds` under `services/market-data/src/main`), so this is emitted as a LOUD
    structured `log.warn/error("AUDIT stock-tick-outlier-rejected ticker=… last=… ref=… devPct=…")`
    line matching the existing `AUDIT …` style (:277/:330). **No new `AuditEventKinds.ALL_KINDS`
    registration is applicable or needed for F1** — that DB-audit path is unreachable from the feed
    layer; do not invent one.
  - The P0 **re-enable gate is F1 + F4** (see above). **F2 is follow-up hardening, not a blocker.**
- **FORK-2 — RESOLVED → `MAX_TICK_AGE = 15s` (hardcoded, v1).**
  - F4's staleness threshold is a hardcoded **`Duration MAX_TICK_AGE = Duration.ofSeconds(15)`**
    (`retrievedAt` = the trade's own timestamp). The QQQ decoupling was ~20 min, so 15s still drops
    the stale print with wide margin and cannot drop a live SIP print (sub-second latency). Stays
    hardcoded — no schema / ConfigMap surface for v1.

---

## Phase 1 — F1: reject aberrant single-print outliers at the equity feed (market-data)

**Goal:** a gross/phantom single trade print (the NVDA-type 2%+ single-tick jump that reverts) never
fans out to any trigger subscriber, while a genuine fast breakout is NOT false-rejected.

**Changes (anchors):**
- `services/market-data/.../provider/alpaca/AlpacaMarketData.java:384-401` (`recordToEquityTick`) —
  after the existing `isHaltedOrStale` drop and the `price.isNumber()` check, compare the new price
  against the ticker's **prior accepted** price:
  - If there is no reference yet (first print for the ticker) → **accept** and seed the reference.
  - If `|price − ref| / ref > MAX_DEVIATION_PCT` → **candidate outlier**: drop this record (return
    `null`) BUT remember it as a `pendingCandidate`. If the *next* print for the ticker lands within
    `MAX_DEVIATION_PCT` of that `pendingCandidate` (corroboration → a real sustained move) → accept it
    and advance the reference to it; otherwise the candidate is discarded (lone phantom that reverted)
    and the reference is unchanged.
  - Otherwise (in-band) → accept, advance the reference, clear any `pendingCandidate`.
  - On every rejection emit the FORK-1 observability line:
    `log.warn("AUDIT stock-tick-outlier-rejected: ticker={} last={} ref={} devPct={}", …)` (LOUD,
    matches the existing `AUDIT …` log style at :277/:330). **Structured logging only — this module
    cannot write `audit_log`; no `AuditEventKinds` registration.**
  - Add per-ticker state: `ConcurrentHashMap<String,BigDecimal> lastAcceptedPrice` (+ a small
    `pendingCandidate` map) — surgical, mirrors the existing per-ticker `byTicker` map.
- `MAX_DEVIATION_PCT` hardcoded **= 0.02 (2.0%)** per **FORK-1 (RESOLVED)**, reference = prior
  accepted tick, corroboration re-seed as above. No workflow, no Temporal command → **no getVersion
  gate**.
- Do NOT alter `isHaltedOrStale` (:408-420) or `snapshotEquityPrice` (:224); keep the guard additive.

**Tests (TDD)** — `services/market-data/.../provider/alpaca/AlpacaMarketDataTest.java`:
- `recordToEquityTick_rejectsGrossOutlierPrint` — **incident reproduction:** feed accepted prints
  ~196, then a lone `201.x` (2.55% > 2.0%) → returns `null` (dropped), `lastAccepted` unchanged; a
  following ~196 print is accepted (phantom reverted).
- `recordToEquityTick_acceptsInBandMove` — a normal <2% move emits a `Tick` and advances
  `lastAccepted`.
- `recordToEquityTick_firstPrintSeedsReference` — first print for a ticker is accepted (no reference
  yet) and seeds `lastAccepted`.
- `recordToEquityTick_corroboratedGapAccepted` — a >2% jump followed by a second print within 2% of
  it (genuine sustained move) → the second print is accepted and advances the reference (no deadlock).
- `recordToEquityTick_rejectionEmitsObservabilityLog` — a rejected outlier logs the
  `AUDIT stock-tick-outlier-rejected` line carrying `last`, `ref`, and `devPct` (assert via a log
  captor / `ListAppender`).
- `dispatchStockWsMessage_outlierNotFannedOut` — end-to-end: an outlier frame reaches no subscriber
  (spy listener never invoked).

**Verify / success criteria:**
- `mvn -pl services/market-data -am spotless:apply` then `mvn -pl services/market-data -am spotless:check test`.
- Behavioral assertion: a synthetic 201.x print after a 196 baseline is dropped at
  `recordToEquityTick` (with an observability log), so `onTick` and the evaluator never see it; a
  corroborated 2%+ move is NOT dropped. No audit-kind / ConfigMap / schema surface touched.

---

## Phase 2 — F4: make the equity staleness guard live (market-data activity)

**Goal:** a late/out-of-sequence print (e.g. the 20-min-old QQQ 680) is marked stale so the
**existing** workflow drop at `:438` finally fires and the evaluator never transitions on it.

**Changes (anchors):**
- `services/market-data/.../activities/SubscribeEquityActivityImpl.java:310-318` (`toEquityTick`) —
  replace the hardcoded `out.setStale(false)` (**:316**) with a real age check:
  `out.setStale(Duration.between(t.retrievedAt(), Instant.now()).compareTo(MAX_TICK_AGE) > 0)`.
  `retrievedAt` is already carried (:315). `MAX_TICK_AGE` hardcoded
  **= `Duration.ofSeconds(15)`** per **FORK-2 (RESOLVED)**. Wall-clock is fine here — this is the
  market-data activity thread, NOT workflow code.
- **No workflow change.** The drop/`logAudit(KIND_FEED_STALE)` at
  `WatchlistTriggerWorkflowImpl.java:438` is pre-existing deployed code; populating `stale` merely
  activates it via the (recorded, replay-safe) signal payload. **→ no getVersion gate** (see Replay
  classification above). `KIND_FEED_STALE`/`TriggerFeedStale` is already registered — no audit-kind
  work.
- **Guardrail for the implementer:** do NOT move the age-check into the workflow (that WOULD need a
  gate). Keep it in `toEquityTick`.

**Tests (TDD)** — `services/market-data/.../activities/SubscribeEquityActivityImplTest.java`:
- `toEquityTick_marksStaleWhenRetrievedAtOlderThanMaxAge` — **incident reproduction:** a `Tick` whose
  `retrievedAt` is 20 min in the past → `EquityTick.stale == true`.
- `toEquityTick_freshTickNotStale` — a `retrievedAt` ~10s in the past (**below** the 15s bound) →
  `stale == false`.
- `toEquityTick_boundaryTickDropsPastMaxAge` — a `retrievedAt` ~20s in the past (**above** the 15s
  bound) → `stale == true`. (Boundary note: ~10s passes, ~20s drops, 15s is the hardcoded cutoff.)
- Add/extend a `WatchlistTriggerWorkflowImplTest` case (orchestrator) proving the existing branch now
  bites: a signalled `EquityTick(last=680, stale=true)` while machine armed at `ABOVE 680` → emits
  `TriggerFeedStale`, `machine.onTick` never called, no FIRE. (This exercises :438 with the
  newly-populated flag; it does not change workflow code.)

**Verify / success criteria:**
- `mvn -pl services/market-data -am spotless:apply` + `mvn -pl services/market-data -am spotless:check test`; also run the touched orchestrator test: `mvn -pl services/orchestrator -am test -Dtest=WatchlistTriggerWorkflowImplTest`.
- Behavioral assertion: a stale 680 tick (and any tick >15s old) is dropped (`TriggerFeedStale`) and
  cannot fire the leg; a ~10s-old tick still passes. Confirm existing `WatchlistTriggerWorkflowImplTest`
  histories still replay green (no workflow change ⇒ no gate).

---

## Phase 3 — F3: durable `TriggerFired` crossing-price audit (orchestrator + audit) — FOLLOW-UP

**Goal:** every fire records `observed_last` / `prev` / `band_low` / `band_high` so the aberrant
value is recoverable post-hoc and a deviation alarm becomes possible (this investigation had to
*infer* the fired price). Observability follow-up — **not** part of the F1+F4 re-enable gate.

**Changes (anchors):**
- `services/audit/.../AuditEventKinds.java` (`ALL_KINDS`, near :453-454) — register new kind `"TriggerFired"`. Observability-only → **`ALL_KINDS` only**, NOT in any `*_KINDS` lifecycle group and NOT in `OrderFailureAlerter.DEFAULT_FAILURE_KINDS`. Required or the pre-push `KindRegistryGuardTest` (`services/audit/.../lint/KindRegistryGuardTest.java`) blocks the push.
- `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImpl.java` — add `KIND_TRIGGER_FIRED = "TriggerFired"` constant (near :83-90). Capture the crossing `last` at the FIRE decision: the value is `tick.getLast()` at the loop `:446` (it is NOT available inside `fire()`), so capture it when `decision == Decision.FIRE` (~:446-449) and emit the audit at :449 before calling `fire()` (or thread it into `fire()`), carrying `observed_last`, `prev` (`machine.prev()`), `band_low`/`band_high` (`payload.getTrigger()` and gap via `gapTolerance`/`level*(1±g)`), and ticker.
- **Version gate:** wrap the new `logAudit(KIND_TRIGGER_FIRED, …)` in `Workflow.getVersion("watchlist-trigger-fired-audit-v1", Workflow.DEFAULT_VERSION, 1) >= 1`, read once at stable scope (mirror the `VERSION_EOD_ENTRY_GUARD` pattern at :510). Pre-fix histories replay on DEFAULT_VERSION and emit no audit command → byte-identical.

**Tests (TDD)** — `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImplTest.java` + `services/audit` guard:
- `fire_emitsTriggerFiredWithCrossingPrice` — **incident reproduction:** arm `ABOVE 201`, drive prev=196 then a 201.x cross → a `TriggerFired` audit with `observed_last=201.x`, `prev=196`, `band_low=201`, `band_high=202.005` precedes `OrderSubmitted`.
- `replay_preFixHistoryHasNoTriggerFired` — a DEFAULT_VERSION replay produces no `TriggerFired` command (getVersion gate honored).
- `KindRegistryGuardTest` passes with `TriggerFired` present in `ALL_KINDS`.

**Verify / success criteria:**
- `mvn -pl services/audit -am spotless:apply` and `mvn -pl services/orchestrator -am spotless:apply`; then `mvn -pl services/audit -am spotless:check test` (must include `KindRegistryGuardTest`) and `mvn -pl services/orchestrator -am spotless:check test -Dtest=WatchlistTriggerWorkflowImplTest`.
- Behavioral assertion: a fire writes a `TriggerFired` row carrying the exact crossing price; existing histories replay green on DEFAULT_VERSION. Spotless on BOTH touched modules (audit + orchestrator).

---

## Phase 4 — F2: confirm-on-fire re-read before placing the order (orchestrator) — FOLLOW-UP HARDENING

**Goal:** defense-in-depth so a single phantom/stale tick that somehow slips past F1+F4 still cannot
open a position — the trigger condition is re-confirmed against an independent snapshot immediately
before `placeOrder`. **Reclassified per FORK-1 (RESOLVED): follow-up hardening, NOT a blocker for
prod_real re-enablement** (the F1 tight guard + F4 staleness guard are the gate). Ship after F1+F4.

**Changes (anchors):**
- `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImpl.java`, inside `fire()` (:502) immediately **before** `exec.placeOrder(intent)` at **:628** — call an independent equity snapshot activity (re-read the underlying spot, e.g. via the market-data `snapshotEquityPrice` :224 exposed as an activity) and re-check the breakout invariant (`snapshot` still on the firing side of `payload.getTrigger()` within band). If the re-read fails-open-closed (null/quote unavailable) or no longer confirms the cross, `logAudit(KIND_TRIGGER_FIRE_REJECTED, reason="confirm_failed"|"confirm_unavailable")` and `return outcome(payload, "confirm_failed")` — NO order. Reuse the existing `KIND_TRIGGER_FIRE_REJECTED` kind (no new audit kind).
- **Version gate:** wrap the new snapshot activity call + reject branch in `Workflow.getVersion("watchlist-confirm-on-fire-v1", Workflow.DEFAULT_VERSION, 1) >= 1`, read once at stable scope. Pre-fix histories replay on DEFAULT_VERSION: no confirm read, no new reject → identical command sequence (mirrors the `VERSION_TTL_FILLED_ADOPTION` :658 / `VERSION_EOD_ENTRY_GUARD` :510 style).
- Prefer an **independent** source from the WS trade feed (a REST snapshot) so the confirm is not just a re-echo of the same aberrant tick.

**Tests (TDD)** — `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImplTest.java`:
- `fire_confirmReadRejectsWhenSnapshotContradicts` — **incident reproduction:** the WS tick fires `ABOVE 201` but the confirm snapshot returns 196 → `TriggerFireRejected reason=confirm_failed`, `exec.placeOrder` **never called**.
- `fire_confirmReadProceedsWhenSnapshotAgrees` — snapshot ≥201 within band → proceeds to `placeOrder` exactly once.
- `fire_confirmUnavailableFailsClosed` — snapshot activity returns empty/throws → reject (no order).
- `replay_preFixHistorySkipsConfirmRead` — DEFAULT_VERSION replay issues no snapshot activity command and reaches `placeOrder` as before.

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply` then `mvn -pl services/orchestrator -am spotless:check test -Dtest=WatchlistTriggerWorkflowImplTest`. If a market-data activity interface/impl is touched, also `mvn -pl services/market-data -am spotless:apply` + module test.
- Behavioral assertion: a contradicting confirm read blocks the order; existing histories replay green on DEFAULT_VERSION.

---

## Ship order & gating

Risk order — isolated feed-layer (no Temporal history change) first, version-gated workflow changes
last (repo rule: "workflow history changes last"). **Deviation from the seeded F1→F3→F4→F2:** anchor
verification showed **F4 is activity-only with no getVersion gate** (F4's file is in market-data, and
the workflow drop at :438 is pre-existing code driven by recorded signal data). That makes F4 lower
blast-radius than F3 (which needs a workflow gate + new audit kind), so both non-gated feed-layer
phases ship before both gated workflow phases. **F1 + F4 are the re-enable gate** (FORK-1 RESOLVED);
F3 and F2 are follow-ups that ship after and do NOT block prod_real re-enablement.

1. **Phase 1 — F1** tight outlier guard (2.0% vs prior-accepted + corroboration re-seed + observability log) · module: `market-data` · **no gate** (adapter, no command). **[re-enable gate]**
2. **Phase 2 — F4** staleness guard (`MAX_TICK_AGE = 15s`) · module: `market-data` · **no gate** (activity-only; activates existing workflow :438 via recorded signal data). **[re-enable gate]**
3. **Phase 3 — F3** `TriggerFired` audit · modules: `orchestrator` + `audit` · **gate `watchlist-trigger-fired-audit-v1`** + register `TriggerFired` in `ALL_KINDS`. **[follow-up]**
4. **Phase 4 — F2** confirm-on-fire re-read · module: `orchestrator` (± `market-data` activity) · **gate `watchlist-confirm-on-fire-v1`**. **[follow-up hardening]**

Each phase: one single-concern PR, TDD-first (incident-reproduction test named above),
`spotless:apply` on **every** touched module before commit, its own merge + homelab deploy, and an
**operator merge gate** (trading-critical — a human approves each merge). `KillSwitchWorkflowImplTest`
is flaky; re-run rather than fix if it trips CI. PR mechanics: set the body at create time or via
`gh api -X PATCH repos/<owner>/<repo>/pulls/<n>` (`gh pr edit --body` is broken here); never touch
`.github/workflows/*.yml`. Use `Closes #<n>` only if an issue is filed for a phase.

**Operator gate reminder:** prod_real watchlist stays deactivated until **Phases 1 (F1) + 2 (F4)**
are deployed and verified on homelab. F2/F3 are follow-ups and are NOT required before re-enablement.
