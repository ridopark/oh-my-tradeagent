# PLAN — 2026-08-10 /live manual BTO (operator-initiated entry)

Give the operator a one-click, audited way to **open a position by hand** from the dashboard `/live`
page: paste an OCC contract string, see the live NBBO, type a contract count, confirm, and get an
entry that runs through **the exact same machinery a Discord BTO runs through** — risk gates,
notional cap, live-promotion gate, order journal, `PositionWorkflow` with all its exits (STC,
chandelier trail, EOD/expiry timers).

**Operator interaction** (inline, no modal — the repo has none):

```
Manual entry
  [ NVDA 260821C00225000 ]  [ Buy ]
    click Buy →  NVDA 260821C00225000 · Aug 21 2026 · $225 Call
                 bid 2.30  mid 2.33  ask 2.35   (quoted 3s ago)
                 qty [ 3 ]     ≈ $705 at the ask
                 [ Confirm — BUY 3 at market (limit 2.47) ]  [ Cancel ]
    click Confirm →  Submitting… → Order submitted · waiting for fill
                                   (or) Rejected: NOTIONAL_CAP_EXCEEDED
```

## Implementation status (read this first)

**Phases 1-4 are IMPLEMENTED and green on `feat/live-manual-bto` → PR #662** (branched off `main`
@ `bbdeb4e`). Phase 5 is operator-only and deliberately NOT in that PR.

Verified at authoring time: orchestrator 1244 tests green (incl.
`CopytradeSignalWorkflowImplLegacyReplayTest` at 16 run / 5 skipped — byte-identical to the
pre-change baseline, re-checked with the changes stashed); tenant-dashboard-bff 242 green (203
before); contract Java + Python round-trips green with the regen-drift guard clean; full `mvn -T 1C
test` reactor green; dashboard `tsc --noEmit` + `next build` clean, plus every UI state driven in a
real browser against a throwaway stub BFF — including the flag-off dark default.

Two deviations from the plan as written, both deliberate:
- **`entryStatus` gained a `FAILED` state** (7, not the 6 sketched below) to mirror the existing
  `EntryWorkflowFailed` audit on the `process()` top-level catch. Without it a workflow that died on
  an unhandled failure would report a stale `PENDING` forever.
- **`WorkflowWriteGuards` is a new class**, not a set of helpers left in `PositionsController`; the
  controller now delegates to it. Same behavior (proven by the pre-existing force-close/partial-close
  guard tests), one implementation of the tenant boundary.

## Core design decision — reuse `CopytradeSignalWorkflow`, don't rebuild

A manual entry is a `CopytradeSignalPayload{action:BTO}` started on the orchestrator task queue,
exactly as `services/signal-source-discord/.../emitter.py:_start_workflow_deduped` does it. **No new
entry workflow.** Everything downstream of `processInternal` is untouched: the strategy-enabled
gate, `assertPreTradeCheckRoutable` → `dispatchPreTradeCheck`, `checkEntryWithLimit` (notional cap +
buying power), the account-cash sizing switch, the F4B cap-headroom clamp, the LIVE promotion gate,
`exec.placeOrder`, the TTL await, and `startPositionWorkflow`.

A new entry workflow — or a BFF that calls `exec.placeOrder` directly — would fork every one of
those gates. That is the failure mode this plan exists to avoid.

## Operator decisions locked before implementation

1. **Pricing** = marketable limit anchored on the **live ask** at submit time. The ask becomes
   `CopytradeSignalPayload.price`; `BtoPricing.computeBtoLimit` then applies the tenant's existing
   `max_slippage_pct` / `max_slippage_abs` to produce the limit. Behaves like a market order but
   capped, and — critically — sizing, the notional cap, and buying power all consume the same
   number they consume today. **No true market order** (`limit_price: null`): the entry path needs a
   price, and an uncapped fill on a wide 0DTE spread is not worth the simplification.
2. **Quantity** = **operator-typed contract count**, not `capital_weight` auto-sizing. The risk
   gates still run and can reject; the qty is a ceiling the operator chose, never raised by us.
3. **Feedback** = bounded status poll on a new **Query** (queries emit no commands → replay-safe).
   Without it a gate rejection (EOD cutoff, notional cap, kill switch, missing live promotion) looks
   identical to an accepted entry until someone reads `audit_log`.

## What the operator is NOT protected from (state these in the UI)

- **Buying power.** `checkEntryWithLimit` probes the notional gate with a conservative **1-contract**
  cost (`RiskActivitiesImpl.entryNotional(limit, 1L)`), so an oversized qty is caught by the F4B
  cap-headroom clamp (Phase 2) or, absent a configured cap, by the broker (422/403 → the existing
  `EntryWorkflowFailed` audit + alert). Not silently, but not pre-flight either.
- **Duplicate exposure.** `WorkflowIds.position` includes the entry `signal_id`, so a manual BTO on a
  contract the tenant already holds opens a **second** `PositionWorkflow` for the same OCC — the same
  thing a repeated Discord BTO does today. Surface the existing holding in the confirm step (Phase 4)
  rather than blocking it.
- **A later Discord STC closes it.** `handleStc` matches on OCC via `PositionLookupActivities`, so a
  manually-opened leg is a legitimate STC target. That is usually what you want; say so in the UI.

---

## Phase 1 — Contract DTOs (`contract/`) — non-trading-critical

**Goal:** the two optional payload fields the manual path needs, plus the status-Query result.

**Changes:**
- `contract/schemas/copytrade-signal-payload.json` — add two OPTIONAL properties (the schema is
  `additionalProperties: false`, so the BFF cannot send them otherwise):
  - `source` — `enum ["discord","manual"]`, absent ⇒ `discord`. Drives the supersede suppression in
    Phase 2 and makes manual entries greppable in `audit_log` forensics.
  - `qty_override` — `integer, minimum: 1`. Absent ⇒ today's `Sizing.computeEntry` path, byte for
    byte. Named `qty_override` (not `qty`) so it reads as "operator overrode sizing" at the call site.
- `contract/schemas/copytrade-entry-status.json` (NEW) — `CopytradeEntryStatus{schema_version,
  state, reason_code, reason_detail, option_symbol, contracts, broker_order_id, filled_qty,
  avg_fill_price}` where `state ∈ {PENDING, REJECTED, SUBMITTED, FILLED, EXPIRED, ABORTED}`. Every
  field but `schema_version`/`state` optional.

**Do NOT remove or repurpose any existing field** — see `[[reference_strategyconfig_fields_not_removable]]`;
the same replay hazard applies to any DTO an in-flight workflow deserializes.

**Verify:**
- `contract/python/regen.sh` run and the regenerated pydantic models committed (the `make hooks`
  pre-commit guard and the CI `Python (pydantic round-trip + regen drift)` job both enforce this).
- `mvn -q -pl contract/java test` green (jsonschema2pojo round-trip).
- An absent `source`/`qty_override` deserializes to `null` on the Java side — assert it in the
  round-trip test, since that null is what makes every existing signal path unchanged.

## Phase 2 — Orchestrator: qty override, supersede suppression, status Query — **TRADING-CRITICAL**

**Goal:** `CopytradeSignalWorkflowImpl` honors an operator qty, never auto-supersedes on a manual
signal, and can be asked what happened.

**Changes** (`services/orchestrator/.../workflows/CopytradeSignalWorkflowImpl.java`):

1. **Qty override** — in `handleBto`, right after the existing `Sizing.computeEntry` block:
   ```java
   if (payload.getQtyOverride() != null) {
     contracts = payload.getQtyOverride();   // operator-chosen ceiling
   }
   ```
   Then, still on the override branch and **unconditionally** (not only when a notional cap is
   configured, unlike the F4B clamp): reject when `contracts > config.getMaxContracts()` or
   `contracts < config.getMinContracts()` with `reason_code = MANUAL_QTY_OUT_OF_BOUNDS`. Fail-closed:
   no `placeOrder`, no `PositionWorkflow`, same return shape as every other reject.
2. **Clamp becomes reject on the manual path** — the existing F4B block computes
   `clamped = min(contracts, headroom, max_contracts)`. For an auto-sized signal, clamping down is
   correct. For an operator who typed `10`, silently buying `2` is a surprise. When `qty_override` is
   set and `clamped < contracts`, **reject** with `reason_code = NOTIONAL_CAP_EXCEEDED` and
   `reason_detail = "manual_qty_exceeds_headroom requested=10 headroom=2"`.
3. **Supersede suppression** — first line of `maybeSupersedePriorLeg`, after the version read:
   `if (SOURCE_MANUAL.equals(payload.getSource())) return;`. Without it, a manual BTO on the same
   underlying+strike+right but a different expiry, within the 120s `SUPERSEDE_WINDOW` of a just-filled
   Discord leg, would **auto-flatten that Discord leg**. Narrow, but destructive and real-money.
4. **Status Query** — add `@QueryMethod CopytradeEntryStatus entryStatus()` to
   `CopytradeSignalWorkflow` + a private `state` field the existing code paths stamp: `PENDING` at
   entry, `REJECTED(reason_code, reason_detail)` at each `KIND_SIGNAL_REJECTED` / `LivePromotionMissing`
   site, `SUBMITTED` after `exec.placeOrder`, `FILLED` in the `if (filled)` branch, `EXPIRED` in
   `handleTtlExpired`, `ABORTED` on the risk-breach path. Queries are **not** commands, so this adds
   nothing to any history.

**Replay-safety argument (the part a reviewer will check):**
- Changes 1–3 add commands (a `logAudit`) or omit them **only when `qty_override`/`source` is
  present** — fields no pre-deploy history can carry, since nothing emitted them. A legacy history
  therefore replays through the identical command stream. Per
  `[[reference_temporal_replay_activity_input]]`, Temporal 1.27 checks command type + ordering, not
  activity-input values, so no `getVersion` marker is warranted here — adding one would be the
  over-engineering that memory warns about. **State this reasoning in the PR description**; it is the
  one thing that makes this phase reviewable.
- Change 4 (Query) emits no commands at any version.

**Verify:**
- `CopytradeSignalWorkflowImplLegacyReplayTest` (and the full orchestrator suite) green — this is the
  gate that proves the argument above.
- New tests: qty_override honored end-to-end; over-`max_contracts` rejected; headroom-clamp rejected
  (not silently clamped) when override is set but still clamped for an auto-sized signal;
  `maybeSupersedePriorLeg` no-ops for `source=manual` and still fires for a Discord signal;
  `entryStatus()` returns each terminal state.
- `mvn -q -pl services/orchestrator spotless:apply test` — spotless on **every** touched module, per
  `[[feedback_spotless_precommit]]`.

## Phase 3 — BFF: quote read, OCC parse, three endpoints — dark

**Goal:** the dashboard's server-side seam. Same guard shape as `/api/positions/partial-close`.

**Changes** (`services/tenant-dashboard-bff/`):
- `proximity/MarketDataQuoteClient.java` — add `optionQuote(occ)` returning `{bid, mid, ask}`. The
  market-data endpoint (`MarketDataQuoteController` `GET /md/option/{occ}`) **already returns all
  three**; today's `optionPremium` throws away bid/ask. It is a REST snapshot
  (`AlpacaMarketData.snapshotQuote` → `/v1beta1/options/snapshots`), so it works for a contract the
  tenant does not hold and needs no WS subscription. Keep the existing fail-soft (2s timeouts →
  `null`) — but the manual-entry path treats `null` as a hard **503 `quote_unavailable`**, never as
  "price it anyway".
- `entries/OccParser.java` (NEW) — `^([A-Z]{1,6})(\d{6})([CP])(\d{8})$` over the whitespace-stripped
  input → `(ticker, expiry, right, strike)` with `strike = digits / 1000`. The BFF does not depend on
  the orchestrator module, so `orchestrator/.../domain/OccSymbol` is not reachable; a ~30-line parser
  with its own unit test is the right call over a module dependency. **Round-trip test is mandatory:**
  the parsed tuple must recompose to the same OCC through `ContractActivities.resolve`
  (`OccSymbol.of`, which pads the root to 6), or the order goes to the wrong contract. Reject a
  numeric/dotted root with `400 unsupported_root` — the payload schema's `ticker` pattern is
  `^[A-Z]{1,6}$`.
- `web/ManualEntryController.java` (NEW), `@RequestMapping("/api/entries")`, behind its **own** dark
  flag `entries.manual.write-enabled` (default false → the write route 404s
  `{"error":"manual_entry_disabled"}`), reusing `TenantContext`, the `X-Operator-Id` sanitizer, and
  the `t-<tenant>/` + `/sig/` workflow-id guards from `PositionsController` (extract the shared
  helpers rather than copying them):
  - `GET /api/entries/quote?occ=…` → `200 {occ, underlying, expiry, strike, right, bid, mid, ask,
    quoted_at}` · `400 invalid_occ` · `503 quote_unavailable`. Read-only, gated by the same flag so
    the preview cannot be probed while the write is dark.
  - `POST /api/entries/manual` `{occ, strategy_id, qty, quoted_ask, quoted_at, idempotency_key}` →
    `202 {signal_id, workflow_id}`.
    - Validate `strategy_id` against `platform/StrategyConfigReader.configsForTenant(tenant)` —
      fail-closed `403 unknown_strategy`; the tenant is NEVER a client parameter.
    - **Re-snapshot the ask** and reject `409 quote_stale` when `quoted_at` is older than 30s or the
      fresh ask exceeds `quoted_ask × 1.10`. The operator confirmed a price; a 3× gap-up between
      confirm and submit must not be filled silently. The **fresh** ask is what gets sent.
    - Start `CopytradeSignalWorkflow` on `${temporal.orchestrator-task-queue:orchestrator-core}`
      (the `BrokerPositionsClient` pattern), workflow id
      `WorkflowIds.copytradeSignal(tenant, strategy, signalId)`, search attribute
      `TenantStrategy = WorkflowIds.tenantStrategy(...)`, `WorkflowIdReusePolicy.REJECT_DUPLICATE`;
      map `WorkflowExecutionAlreadyStarted` → `409 duplicate_submission`.
    - Payload: `signal_id = "manual:" + idempotency_key`, `message_id = idempotency_key`,
      `author` = sanitized operator (fallback `tenant:<t>`), `posted_at` = now UTC, `price` = fresh
      ask, `source = manual`, `qty_override = qty`, **`tail = ""`** (non-empty tail feeds
      `KeywordPartialMatcher` — scale-in and de-risk cues — and must not fire here),
      `raw_line = "MANUAL BTO <occ> qty=<n> ask=<ask> operator=<actor>"`.
  - `GET /api/entries/{signalId}/status` → the Phase-1 DTO via `WorkflowStub.query`, `404` when the
    workflow is unknown, behind the same tenant-prefix guard.

**Verify:** WebMvc tests mirroring `PositionsPartialCloseControllerWebMvcTest` +
`PositionsPartialCloseDarkLaunchTest` — flag-off 404 on all three routes, cross-tenant 403, unknown
strategy 403, invalid OCC 400, stale quote 409, duplicate 409, happy path 202 with the payload
asserted field-by-field (this assertion is the contract between BFF and orchestrator). Plus
`OccParserTest` incl. the resolve round-trip. `mvn -q -pl services/tenant-dashboard-bff spotless:apply test`.

## Phase 4 — Dashboard: the `/live` Manual entry panel — dark

**Goal:** the UI, gated by `MANUAL_ENTRY_WRITE_ENABLED === "true"` (dashboard-side twin of the BFF
flag — both must be on, exactly like the Trim pair).

**Changes** (`dashboard/`):
- `lib/bff.ts` — `getOptionQuote(occ)`, `submitManualEntry(...)`, `getEntryStatus(signalId)` on the
  existing `bffGet`/`bffPost` seams, each returning a **typed non-throwing result** with the 400/403/
  409/503 branches named (the `trimPosition` contract), so the UI can show the actual reason.
- `components/ManualEntryPanel.tsx` (NEW client island) — the three-step machine from the sketch:
  input → quote+qty → confirm → submit → poll. Reuse `TrimButton`'s hard-won interaction details:
  the explicit `submitting` lock (React 18.3.1 closes a transition scope at the first `await`, which
  would re-expose the button mid-flight), the `contains(relatedTarget)` blur containment plus
  `preventDefault` on mousedown (the bug fixed in `a48c665` — without both, every control but the
  autofocused one is unclickable), and the auto-disarm timer. **No retry affordance on failure** — a
  timed-out submit may already have opened a position; recovery is refresh-and-re-read, not one-click
  repeat.
- Mint `idempotency_key` (`crypto.randomUUID()`) when the confirm step **opens**, not on click, so a
  double-click is one workflow.
- `app/live/page.tsx` — render the panel above Holdings behind the flag; pass the tenant's strategies
  from a `getStrategyConfig()` read (auto-select when there is exactly one, otherwise a `<select>`),
  and pass the current Holdings OCCs so the confirm step can warn "you already hold 4 of these".
- Server actions co-located in `page.tsx` beside `trimAction`/`forceExitAction`: re-verify the
  session, thread `s.user?.email ?? s.user?.name` as `X-Operator-Id`, `revalidatePath("/live")` on a
  FILLED poll result.

**Verify:** `npm run typecheck && npm run lint && npm run build` (the dashboard has no test runner —
`dashboard/**/*.test.*` is empty, so the browser walk-through IS the test). Drive every state
manually: invalid OCC, unknown strategy, quote unavailable, stale quote, qty over `max_contracts`,
a rejected entry, a filled entry, and a double-click on Confirm.

## Phase 5 — Operator follow-ups (no code)

- Deploy, then flip **both** flags on the live cluster via `kubectl set env` (survives deploys; the
  repo manifests stay dark): BFF `ENTRIES_MANUAL_WRITE_ENABLED=true` and dashboard
  `MANUAL_ENTRY_WRITE_ENABLED=true`. Note `[[reference_deploy_yml_apply_scope]]` — `deploy.yml` only
  applies per-service manifests.
- **Canary on `staging_paper` first** (paper tenant, mirrors dev signals): one 1-contract manual BTO,
  watched end-to-end — `SignalReceived{source:manual}` → `SignalAccepted` → `OrderSubmitted` →
  `EntryFilled` → the Holdings row → then a `/live` Force exit to close it out.
- Only then a supervised 1-contract entry on a real-money tenant. `[[project_live_trim_button]]` is
  the precedent: dark flags flipped, first supervised use still pending.

---

## Ship order & gating

```
Phase 1 (contract)  →  Phase 2 (orchestrator, TRADING-CRITICAL)  →  Phase 3 (BFF)  →  Phase 4 (dashboard)
                              ↑ merge + DEPLOY before Phase 3 merges
```

Phase 3 sends `source`/`qty_override` on the wire; if the orchestrator deployed to the cluster does
not yet understand them, Jackson drops the unknown fields and the entry silently auto-sizes off
`capital_weight` instead of the operator's qty. **Phase 2 must be deployed, not merely merged, before
the Phase 3 flag is flipped.** Phases 3 and 4 are inert until the flags flip, so they can merge
freely.

Each phase is one PR. Per `[[feedback_claude_pr_workflow_edits]]` plain CI is the real gate; per
`[[feedback_spotless_precommit]]` run `spotless:apply` on every touched Java module before pushing.

## Known edges (accepted, not blockers)

- **Entry TTL.** The entry is a one-shot limit expiring after `pendingTtlSecs(config)` (~90s) →
  `EntryExpired`. Anchoring on a **fresh ask** rather than a stale author price makes this far less
  likely than the copytrade case in `[[project_bto_entry_fill_misses_repeg]]`, and the repeg work in
  `docs/plans/PLAN-2026-08-04-bto-entry-repeg.md` benefits this path for free when it lands.
- **Signal-feed mirror.** `SignalReceived` drives the Discord signal-feed alerter, so a manual entry
  posts to that channel. Desirable (it is an audit trail) — just don't let it read as a source signal;
  the `source:manual` field plus the `MANUAL BTO …` `raw_line` disambiguate it.
- **Watchlist strategies.** The strategy picker lists every strategy on the tenant. A manual BTO
  against a watchlist strategy technically works but inherits watchlist exit config (time-stop, etc.).
  Not blocked; the picker should label each strategy so the choice is deliberate.
