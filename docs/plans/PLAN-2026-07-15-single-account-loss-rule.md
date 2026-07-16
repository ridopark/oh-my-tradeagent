# PLAN — 2026-07-15 single account-level daily-loss rule

Consolidate prod_real's daily-loss protection into **one** rule: the account-level
`account_daily_loss_pct` (10% mark-to-market, whole-account). On breach it **halts new entries and
pages the operator loudly — it does NOT auto-flatten**; the operator decides whether/when to flatten.
Retire the redundant per-strategy `daily_loss_threshold`. Source: live forensics this session
(conversation 2026-07-15) + [[project_account_loss_cap_db]].

## Background / why

Two overlapping daily-loss breakers exist, enforced by two separate Temporal workflows:

| | per-strategy `daily_loss_threshold` | account `account_daily_loss_pct` |
|---|---|---|
| Workflow | `KillSwitchWorkflowImpl` (per strategy) | `AccountKillSwitchWorkflowImpl` (per tenant) |
| P&L basis | realized-only | realized + open (MTM) |
| Scale | absolute $ | % of start-of-day equity (auto-scales) |
| Scope | one strategy | whole account |
| On trip TODAY | halt entries + **auto-flatten** (that strategy) | **auto-flatten** book (cascade) |
| On trip AFTER this plan | (field removed) | **halt entries + loud Discord page; NO auto-flatten** |

The account cap is the strictly-better loss-minimizer (counts open drawdown, auto-scales). The goal
is to make it the sole rule, and — per operator decision 2026-07-15 — to change its trip action from
auto-flatten to **halt-new-entries + page**, leaving the flatten to the operator.

### Crux 1 (verified this session — the entry-halt gap)

Neither the copytrade NOR the watchlist **entry** path consults the account kill switch:
- The shared entry gate `RiskActivitiesImpl.runStrategyAgnosticGates` (`:228`) — called by copytrade
  (`checkEntry` → `checkEntryInternal`) AND by the watchlist entry path (`checkWatchlistEntry:268`,
  from `WatchlistTriggerWorkflowImpl.java:595`) — calls only `checkKillSwitch` (per-strategy, `:237`);
  never `checkAccountKillSwitch` (`:750`).
- The only place that checks BOTH is `checkKillSwitchHalt` (`:673-679`), which is the watchlist
  ADOPTION/reconcile path (`WatchlistTriggerWorkflowImpl.java:786`), NOT the fresh-entry path.

**Net:** when the account cap trips, OPEN positions are flattened but NEW signals (copytrade AND
watchlist) are still admitted. The fix lands in the shared gate, so both entry paths gain the
account-KS check — strictly more protective and correct.

### Crux 2 (the operator decision) — trip = alert, not auto-flatten

- `AccountKillSwitchWorkflowImpl.doTrip:838` fires `Async.function(cascade::cascadeAccountRiskBreach, …)`
  which `riskBreach`-signals every open `PositionWorkflow` → MARKET flatten. The per-strategy
  `KillSwitchWorkflowImpl:478` does the equivalent (`cascade::cascadeRiskBreach`).
- The loud page already exists: `KillSwitchAlerter` (`KillSwitchAlerter.java:20,45,99`) pages a red
  Discord embed on EVERY `KillSwitchTripped` (keys on kind, so account-scope trips already page).
- The change: remove/gate the auto-flatten so a loss-cap trip HALTS entries + PAGES (with a message
  that says positions were NOT flattened), and the operator flattens manually.

## P0 — Immediate operational (no code; already applied this session)

- **DONE** `account_daily_loss_pct` 0.40 → **0.10** on `tenant_config` (prod_real), DB CAS v1→v2.
  Verified: BFF `GET /api/tenant-config` → 200 `{account_daily_loss_pct:0.10,version:2}`.
- **DONE** copytrade-v1 `daily_loss_threshold` walked back $10,000 → **$2,500** (DB CAS v12→v13) —
  restores copytrade's entry-halt breaker until Phase 1 ships. Unset in the Phase 3 follow-up.
- **DONE (PR #588, merged)** `GRANT SELECT ON tenant_config TO bff_readonly` codified in the
  operator-setup doc.

---

## Phase 1 — copytrade entry gate consults the account kill switch (orchestrator)

**Goal:** an account-cap trip halts NEW copytrade entries, making the account cap a real entry-halt
breaker for copytrade.

**Changes** (anchors):
- `services/orchestrator/.../activities/RiskActivitiesImpl.java:237` — in `runStrategyAgnosticGates`,
  after the existing `checkKillSwitch` short-circuit, add a `checkAccountKillSwitch(tenantId, now)`
  check with identical fail-closed semantics (both helpers exist: `checkAccountKillSwitch:750`,
  `checkKillSwitch:779`). Per-strategy first (preserves existing behavior), then account.
- **Shared gate → watchlist entries also gain the check.** `runStrategyAgnosticGates` is used by both
  copytrade AND the watchlist entry path, so both now consult the account KS. Intended and strictly
  more protective (a watchlist entry should not be admitted while the account is halted). The
  watchlist ADOPTION path (`checkKillSwitchHalt`) already checked both — unchanged.
- **No Temporal version gate.** The change is entirely INSIDE the `checkEntry` **activity**; it adds
  no workflow command (the account-KS read is a client query within the activity, like the existing
  per-strategy read). In-flight `CopytradeSignalWorkflow` histories replay the RECORDED `checkEntry`
  result; only NEW executions run the new logic. Confirm by adding NO `getVersion` to
  `CopytradeSignalWorkflowImpl`.

**Tests (TDD):** `RiskActivitiesImplTest` (copytrade risk suite):
- account KS tripped + per-strategy KS clean → copytrade `checkEntry` → `REJECTED KILL_SWITCH_TRIPPED`
  (reproduces the gap; previously ALLOWED).
- both clean → allowed (regression guard; existing copytrade risk suite must stay green — default the
  account stub to untripped).
- account KS query throws / null → `REJECTED KILL_SWITCH_UNAVAILABLE` (fail-closed).

**Verify / success criteria:**
`mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator test`.
Behavioral: with the account KS tripped and per-strategy KS untripped, a copytrade signal →
`SignalRejected` (kill_switch_tripped); `exec.placeOrder` never called. Watchlist ENTRY also rejected
(shared gate, intended); watchlist adoption path unchanged. No `getVersion` added.

**Pre-deploy gate (from risk review):** `AccountKillSwitchWorkflow` must be running + queryable for
every `-live` tenant, else `checkAccountKillSwitch` fail-closes on `WorkflowNotFoundException` and
halts ALL entries. Verified this session: `t-prod_real/account/killswitch` is Running. **Follow-up
(not blocking):** the account and per-strategy paths both emit `KILL_SWITCH_UNAVAILABLE` with only the
exception class as detail — add a scope tag + a metric/alert so a flaky account-KS query that silently
halts entries is visible.

---

## Phase 2 — loss-cap trips halt + page, NO auto-flatten (orchestrator; risk-manager sign-off)

**Goal:** a daily-loss-cap trip halts new entries and pages loudly, but does NOT auto-flatten — the
operator flattens manually. Applies to BOTH kill switches for a consistent policy.

**Changes** (anchors):
- `services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java:838-839` — gate the
  `Async.function(cascade::cascadeAccountRiskBreach, …)` behind
  `Workflow.getVersion("account-trip-no-auto-flatten-v1", DEFAULT_VERSION, 1)`: at `DEFAULT_VERSION`
  keep the cascade (byte-identical replay for in-flight histories); at `v>=1` SKIP it (no flatten).
  This gates a workflow command → version gate REQUIRED.
- `services/orchestrator/.../workflows/KillSwitchWorkflowImpl.java:478` — same treatment for the
  per-strategy `cascade::cascadeRiskBreach` behind
  `Workflow.getVersion("strategy-trip-no-auto-flatten-v1", DEFAULT_VERSION, 1)`. (Keeps parity while
  the per-strategy field still exists in the interim; consistent once it's removed.)
- **Alert copy — make the page actionable.** The trip still emits `KillSwitchTripped` →
  `KillSwitchAlerter` already pages. Add a `flatten="manual"` (or `auto_flatten=false`) key to the
  `doTrip` audit subject in BOTH workflows, and surface it in `KillSwitchAlerter`'s embed
  (`KillSwitchAlerter.java:88-99`) as an explicit line: **"Open positions NOT auto-flattened — flatten
  manually if desired."** No new audit KIND (still `KillSwitchTripped`), so no `AuditEventKinds`
  registration needed.

**Decision folded in (operator, 2026-07-15):** flatten is operator-only. Consequence: the cap
becomes an entry-halt + alert, NOT a hard flatten-stop — open drawdown past −10% is bounded only by
per-position exits + operator response to the page, not by the cap. This is an accepted softening of
the loss cap; **risk-manager sign-off required before merge.**

**Tests (TDD):**
- `AccountKillSwitchWorkflowImplTest`: new execution, trip (`auto:account_daily_loss`) → NO
  `cascadeAccountRiskBreach` dispatched; `KillSwitchTripped` still emitted with `flatten=manual`.
- `AccountKillSwitchWorkflowImplLegacyReplayTest` / `KillSwitchWorkflowImplTest` (flaky — **re-run,
  don't fix**): pinned pre-gate history still dispatches the cascade (byte-identical at
  `DEFAULT_VERSION`).
- `KillSwitchAlerterTest`: embed for a `flatten=manual` trip contains the manual-flatten line.

**Verify / success criteria:**
`mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator test`.
Behavioral: a fresh account-cap trip emits `KillSwitchTripped` + a red Discord page whose body says
positions were not auto-flattened, and NO `riskBreach` signal reaches any `PositionWorkflow`;
in-flight replay of a pre-change trip still flattens.

---

## Phase 3 — make `daily_loss_threshold` optional for live; require the account cap armed (orchestrator)

**Goal:** an armed account cap (`account_daily_loss_pct > 0`) satisfies the live loss-breaker
invariant, so a `-live` strategy no longer REQUIRES a per-strategy `daily_loss_threshold`; a `-live`
tenant MUST have the account cap armed (it is now the sole breaker).

**Changes** (anchors):
- `services/orchestrator/.../bootstrap/StrategyConfigInvariants.java:36-45` — make
  `daily_loss_threshold` optional (null OK) IF the tenant's account cap is armed. `validateLiveRequiredGates`
  takes only `StrategyConfig` (no tenant cap), so thread the account-cap value in via
  `LiveRequiredGateValidator.validate` (`LiveRequiredGateValidator.java:44`) — add a `TenantRegistry`
  read of `account_daily_loss_pct` per tenant, pass it down. Boot-time — **no replay concern.**
- Same method — ADD an invariant: a `-live` strategy whose tenant has NO armed account cap (pct and
  absolute both null/≤0) throws `IllegalStateException` ("live tenant missing account loss cap").
- `services/orchestrator/.../workflows/KillSwitchWorkflowImpl.java:253-264` — relax the
  `auto:missing_loss_threshold` fail-closed branch so a null threshold on a `-live` strategy no longer
  trips (paper-like no-op), relying on the boot invariant. **Version gate REQUIRED**
  (`Workflow.getVersion("killswitch-missing-threshold-optional-when-account-cap-v1", DEFAULT_VERSION, 1)`);
  at `DEFAULT_VERSION` keep the exact current trip.

**Note:** the account cap is tighten-only and can't be removed (`TenantConfigWriter`), so once the
boot invariant validates it armed, it stays armed — the relaxed heartbeat can rely on it without a
new activity read in the per-strategy KS.

**Tests (TDD):**
- `StrategyConfigInvariantsTest`: live + null threshold + cap armed → no throw; live + null threshold
  + cap NOT armed → throws; live + cap null → throws (new invariant); paper unchanged.
- `KillSwitchWorkflowImplTest` (flaky — **re-run, don't fix**): new execution, live, null threshold →
  no `auto:missing_loss_threshold`. `KillSwitchWorkflowImplLegacyReplayTest`: pre-gate history still
  trips (byte-identical at `DEFAULT_VERSION`).

**Verify / success criteria:**
`mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator test`.
Behavioral: `-live` strategy with `daily_loss_threshold` unset + `account_daily_loss_pct=0.10` boots
clean, no `auto:missing_loss_threshold`; same strategy with the account cap ALSO unset fails boot.

**Operator follow-up (AFTER this phase deploys + is verified on homelab):**
1. Confirm prod_real `account_daily_loss_pct=0.10` armed (BFF 200; no `AccountKillSwitchCapInactive`).
2. Only then unset copytrade-v1 `daily_loss_threshold` via direct DB CAS:
   `UPDATE strategy_config SET config = config - 'daily_loss_threshold', version = version+1, updated_by='ops:single-account-rule' WHERE tenant_id='prod_real' AND strategy_id='copytrade-v1' AND version = <cur>;`
   **Ordering is load-bearing:** unsetting BEFORE the relaxed heartbeat is live would trip
   `auto:missing_loss_threshold` and halt copytrade. Direct DB write (bypasses the DANGEROUS-field
   `CONFIG_CHANGED` gate; KS re-reads DB each heartbeat — do it market-closed).

---

## Phase 4 — deprecate `daily_loss_threshold` from operator surfaces (orchestrator + dashboard)

**Goal:** stop presenting the now-dead field as a live control, so /config shows a single loss rule.

**Fork — pick the scope (flag for user decision):**
- **4a (recommended, low blast radius):** keep the field in the schema (nullable, ignored) but remove
  it from operator-facing surfaces:
  - `StrategyConfigWriter.java:459-460` — drop `daily_loss_threshold` from the DANGEROUS hard-block set.
  - `AuditQueryActivitiesImpl.java:58` — remove `daily_loss_threshold` from `RISK_RELEVANT_CONFIG_KEYS`
    (a change to it should no longer void a live promotion / `CONFIG_CHANGED`).
  - `StrategyConfigReader.java:42` — drop from `FIELD_CLASSES` (mirror of the writer).
  - `dashboard/app/config/page.tsx` — stop rendering the `daily_loss_threshold` row.
  - The `KillSwitchWorkflowImpl` auto-trip-on-realized-loss (`:246-291`) stays but is dormant when the
    field is null (already so after Phase 3). No workflow change → no version gate.
- **4b (optional, heavier — own follow-up):** remove the field from
  `contract/schemas/strategy-config.json` (out of `properties` + `required`), regen Java POJO + Python
  pydantic model, retire the per-strategy auto-trip path (`KillSwitchWorkflowImpl:246-291`) behind a
  version gate. Larger cross-language blast radius + workflow-history change; only if the field must
  leave the contract.

**Tests (TDD):** `StrategyConfigWriterTest` — `daily_loss_threshold` no longer DANGEROUS;
`AuditQueryActivitiesImpl` promotion test — a `TenantConfigChanged` touching it no longer returns
`CONFIG_CHANGED`; dashboard render — field absent from /config.

**Verify / success criteria:**
`mvn -pl services/orchestrator,services/tenant-dashboard-bff -am spotless:apply` + those module tests;
`/config` shows only the "Account daily-loss cap" section for the loss rule.

---

## Ship order & gating

1. **Phase 1** (isolated activity change, no version gate) → deploy + verify (account-KS-tripped
   copytrade signal rejected).
2. **Phase 2** (version-gated cascade removal + alert copy; risk-manager sign-off) → deploy + verify
   (trip pages "manual flatten", no `riskBreach` fired).
3. **Phase 3** (version-gated KS heartbeat + boot invariant) → deploy + verify → THEN operator unsets
   copytrade `daily_loss_threshold` (ordering above).
4. **Phase 4a** (operator-surface cleanup) → deploy + verify /config.

Each: TDD-first, `spotless:apply` on every touched module before commit, its own single-concern PR,
operator merge+deploy gate (trading-critical). Prod tenant configs are cluster-only / DB-sourced
(`STRATEGY_CONFIG_SOURCE=db`, `TENANT_CONFIG_SOURCE=db`) — the copytrade unset is a direct-DB CAS
operator step, not a repo YAML edit; no `tenants/dev/*` or `40-tenants-config.yaml` change (no
ConfigMap-drift check triggers).

## Risks / decisions

- **No auto-flatten (operator decision, Phase 2) — needs risk-manager sign-off:** the loss cap
  becomes an entry-halt + loud page, NOT a hard flatten-stop. Open drawdown past −10% is bounded only
  by per-position exits + the operator's response to the page. The account cap's page and the
  `AccountKillSwitchCapInactive` pager are now safety-critical — ensure both alert to the live
  channel and are seen. Accepted per operator request 2026-07-15.
- **Defense-in-depth (Phase 3) — needs risk-manager sign-off:** removing the per-strategy realized
  breaker leaves ONE loss auto-trip (the account cap). Phase 1 first makes that cap an entry-halt for
  copytrade; the cap is strictly more protective (MTM + auto-scaling). Accepted, but a real reduction
  in redundancy — surface to the operator.
- **Fork to resolve:** Phase 4a (deprecate/hide, recommended) vs 4b (full contract removal). Default
  4a unless the field must leave the contract.
