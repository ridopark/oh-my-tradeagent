# PLAN — 2026-08-18 surface the armed trailing stop on /live

**Symptom (operator, 2026-08-18):** armed the operator trailing stop on `DRAM 270319C00100000`
for prod-real via `/live`. On refresh the Holdings row shows the plain **"Stop-loss"** button again —
nothing says the position is trailing, at what percentage, or where the stop sits.

**This is not a bug in the arm path.** The stop IS armed on the workflow. `/live` simply has no
wire that carries the armed state back to the page, and the control was shipped that way knowingly:

> `dashboard/components/StopLossButton.tsx:~100` — *"NOTHING SUPPLIES THIS YET. /live cannot: the
> BFF's PositionsReader maps only (contractSymbol, remainingQty, entryPremium) off the PositionState
> query, which carries no trailing state, so surfacing it needs that query record, the BFF's
> reflective mapping, the API shape and the page all extended — a separate change from this one."*

This plan is that separate change. The `armedGivebackPct` prop already exists and already renders
`Trailing 35% · stop now ≈ $x`; nothing supplies it. One vertical slice: query record → BFF mirror →
API body → TS type → page prop.

## Current behaviour (verified anchors)

| anchor | what it does |
|---|---|
| `services/orchestrator/.../workflows/PositionWorkflowImpl.java:917-919` | `trailingArmed`, `peakPremium`, `givebackPct` — the live trailing state |
| `PositionWorkflowImpl.java:2224-2226` | `armTrail` (the operator Update `/live` drives) latches all three |
| `PositionWorkflowImpl.java:2366` | `trailStopPrice(peak, giveback)` — the ONE authoritative fire-threshold formula |
| `PositionWorkflowImpl.java:2670-2681` | `positionState()` — returns 5 fields, **none of them trailing** |
| `services/orchestrator/.../workflows/PositionState.java:29` | the 5-component query record + a back-compat 3-arg ctor |
| `services/tenant-dashboard-bff/.../positions/PositionsReader.java:182` | `PositionStateView` — the BFF's 3-field transport mirror |
| `PositionsReader.java:~152` | `OpenPosition` — the 6-component reader output |
| `services/tenant-dashboard-bff/.../portfolio/PortfolioService.java:298` | `positionItem(...)` — builds the `open_positions[]` JSON row |
| `dashboard/lib/bff.ts:~460` | `Position` — the TS shape of that row |
| `dashboard/app/live/page.tsx:~397` | renders `<StopLossButton …>` **without** `armedGivebackPct` |
| `dashboard/components/StopLossButton.tsx:~185` | the armed branch — already written, never reached |

### Why not reuse the proximity path

`ProximityReader.exitProximity` already carries `trailingArmed` + `givebackPct` + `peakPremium`, but
`ProximityReader#positions` **drops any position with `armed() == false`** (`exitArmed`, the
watchlist-exit levels flag). DRAM is a copytrade position with no watchlist exit levels, so it is
filtered out before the trailing fields are read. Loosening that filter would change what the
"Position exit proximity" table means. `positionState` is the query every holdings row already
makes — extending it costs no extra round-trip.

### Why the stop price must come from the workflow

`StopLossButton.stopPriceFor(currentPrice, giveback)` computes `mark × (1 - giveback)`. That is
correct as a *preview* before arming, and **wrong for a stop already armed**: the trail is
peak-anchored (`peak × (1 - giveback)`, `PositionWorkflowImpl:2366`) and `peakPremium` never falls,
so any position off its high renders a stop LOWER than the one that will actually fire. An operator
reading it would believe they have more room than they do. The armed badge therefore renders the
workflow's own `trailStopPrice`, and falls back to the mark-derived estimate only when the workflow
supplies none.

---

## REVISED after adversarial review — do NOT widen `positionState`

The first implementation extended `PositionState` (the `positionState` query) with the trailing
triple. Two independent reviewers rejected it, and the second **reproduced** the failure with the
real `DefaultDataConverter`:

```
PROBE-A OLD<-NEW: THREW DataConverterException ::
  UnrecognizedPropertyException: Unrecognized field "trailGivebackPct" ... not marked as ignorable
```

`PositionState` has **no** `@JsonIgnoreProperties(ignoreUnknown = true)`, and three FAIL-CLOSED
orchestrator paths deserialize that query with their own copy of the record:
`VisibilityPortfolioSnapshot:323`, `AccountPnlActivitiesImpl:174`, `PositionLookupActivitiesImpl:226/389`.
`infra/k8s/51-orchestrator.yaml:22` is `replicas: 1` under default RollingUpdate (maxSurge→1,
maxUnavailable→0), so during a roll an OLD pod and a NEW pod both poll the queue. An old pod
querying a workflow served by a new pod throws on every position → `ValueResult.failure()` →
`AccountKillSwitchWorkflowImpl:887` fail-closes on `combinedFailures` → `auto:account_mtm_unavailable`
→ **halt + flatten on a live account**. That cap has already tripped once on a profitable day from a
single miss (2026-07-21). Widening a risk-gate query to paint a dashboard badge is not a trade worth
making.

**The revised design queries `trailingState` instead** — it already exists on `PositionWorkflow`
(shipped with the chandelier, live today), is consumed by nothing else, and returns strictly more
than is needed: `armed`, `givebackPct`, `thresholdPremium` (the exact fire trigger), plus
`lastTickAt` / `ticksReceived` for a future staleness indicator.

What this buys:
- **The hazard disappears by construction.** No orchestrator change at all — no risk-gate contract
  touched, nothing to sequence, and no special no-overlap roll for the operator to remember.
- **The stop price gets MORE accurate.** `thresholdPremium` is the unrounded `peak × (1 - giveback)`
  the tick loop actually compares against; `trailStopPrice()` penny-rounds HALF_UP, which would have
  overstated the stop by up to half a cent.
- Cost: one extra Temporal query per open position per page load, separately try-caught so a failed
  badge read can never drop a holdings row.

The phase below is superseded on the orchestrator points; its BFF and dashboard changes stand.

---

## Phase 1 — carry trailing state from the workflow query to the /live row (single PR)

**Goal:** after a refresh, an armed Holdings row reads
`Trailing 35% · stop now ≈ $2.63` (peak-anchored) instead of offering the "Stop-loss" button again.
No write path, no new workflow behaviour, no new query.

### Changes (anchors)

**Orchestrator**
- `workflows/PositionState.java` — add three components to the canonical record:
  `boolean trailingArmed`, `BigDecimal trailGivebackPct`, `BigDecimal trailStopPrice`
  (the last two null when unarmed). Keep the existing 3-arg ctor AND add a delegating **5-arg** ctor
  (`…, entryAt, partialExited` → unarmed defaults) so the ~31 existing construction sites — all of
  them tests plus `PositionWorkflowImpl` — keep compiling unchanged. Document that these are
  DISPLAY-only fields: no risk gate reads them.
- `workflows/PositionWorkflowImpl.java:2670` — populate them from `trailingArmed` / `givebackPct` /
  `trailStopPrice(peakPremium, givebackPct)`. Reuse the existing private helper; do NOT re-derive
  the formula. The `input == null` race guard keeps returning unarmed defaults.

**BFF**
- `positions/PositionsReader.java` — add the same three fields to `PositionStateView` (mirror; keeps
  `@JsonIgnoreProperties(ignoreUnknown = true)`) and to `OpenPosition`, plus a delegating 6-arg
  `OpenPosition` ctor so the existing test call sites compile unchanged. `valuePosition` threads
  them through; a null/absent trailing block yields `false / null / null`.
- `portfolio/PortfolioService.java:298` — `positionItem` puts `trailing_armed` **always** (boolean,
  so the row always states its protection status) and `trail_giveback_pct` / `trail_stop_price` only
  when non-null, matching the existing broker-marks convention.
- `web/PositionsController.java:313` — mirror the same three keys in `item(...)` so `/api/positions`
  and `/api/portfolio` do not disagree about the same position.

**Dashboard**
- `lib/bff.ts` — `Position` gains `trailing_armed?: boolean`,
  `trail_giveback_pct?: string | number | null`, `trail_stop_price?: string | number | null`
  (optional: an older BFF omits them).
- `components/StopLossButton.tsx` — new optional prop `armedStopPrice?: number | null`. The armed
  branch prefers it and falls back to `stopPriceFor(currentPrice, armedGivebackPct)` only when it is
  null. **Replace the "NOTHING SUPPLIES THIS YET" comment** — leaving it would make the file lie
  about its own wiring, which is the exact failure mode it was written to prevent.
- `app/live/page.tsx:~397` — pass `armedGivebackPct` and `armedStopPrice` from the row.

### Version gate

**None.** `positionState` is a Temporal **query** — it appends nothing to history, so a wider result
is not a replay-affecting change (`PositionState`'s own javadoc already states this). Both
mixed-version directions degrade safely and are asserted in tests:
- old orchestrator → new BFF: fields absent, Jackson defaults to `false`/`null` → renders un-armed.
- new orchestrator → old BFF: `@JsonIgnoreProperties(ignoreUnknown = true)` absorbs them (this is
  exactly the drift that once showed prod_real **0 open positions** — see `PositionStateViewDriftTest`).

### Tests / verification

1. `services/orchestrator` — `PositionWorkflowImplTest`: after the operator `armTrail`,
   `positionState()` reports `trailingArmed=true`, the armed giveback, and
   `trailStopPrice == peak × (1 - giveback)`; an un-armed position reports `false / null / null`.
2. `services/tenant-dashboard-bff` — `PositionStateViewDriftTest`: (a) an 8-field payload
   deserializes through `DefaultDataConverter` with the trailing fields populated; (b) the **5-field
   legacy payload still deserializes** and yields `trailingArmed=false` / null giveback — the
   mixed-version guard.
3. `services/tenant-dashboard-bff` — `PositionsReaderTest`: trailing state on the queried
   `positionState` reaches `OpenPosition`; an unarmed one stays false/null.
4. `services/tenant-dashboard-bff` — `PortfolioServiceTest`: an armed position's `open_positions[]`
   row carries `trailing_armed=true` + both numeric keys; an unarmed row carries
   `trailing_armed=false` and **omits** `trail_giveback_pct` / `trail_stop_price`.
5. `dashboard` — no test runner in this package (`package.json` has none); verification is
   `npm run typecheck && npm run lint && npm run build`.

**Commands**

```bash
mvn -q -pl services/orchestrator -am -DskipITs test
mvn -q -pl services/tenant-dashboard-bff -am -DskipITs test
mvn -q -pl services/orchestrator,services/tenant-dashboard-bff spotless:apply   # CI gate
cd dashboard && npm run typecheck && npm run lint && npm run build
```

### Success criteria (executable)

- [ ] `PositionState` has the three trailing components; every pre-existing construction site
      compiles **without edit** (delegating ctors).
- [ ] Orchestrator test asserts `positionState()` reports the armed trail with the peak-anchored
      stop price, and reports un-armed for a position with no trail.
- [ ] `PositionStateViewDriftTest` proves BOTH the 8-field and the legacy 5-field payloads
      deserialize, the latter as un-armed.
- [ ] `PortfolioServiceTest` asserts the three JSON keys' presence/absence rules.
- [ ] `dashboard` typecheck + lint + build clean.
- [ ] `spotless:apply` run on both touched Java modules; `mvn spotless:check` clean.
- [ ] The `armedGivebackPct` doc comment no longer claims nothing supplies it.

### Halt conditions

- If adding components to `PositionState` breaks any **risk-gate** consumer
  (`VisibilityPortfolioSnapshot`, `AccountKillSwitchWorkflowImpl`, `PositionLookupActivitiesImpl`)
  in a way a delegating ctor cannot absorb — stop and report. Those paths fail CLOSED and must not
  be touched by a display change.
- If `PositionWorkflowImplTest` fails in a way unrelated to this change, re-run once: it is a known
  timing flake under load. A second failure halts.

### Deploy note (operator, after merge)

Roll **orchestrator first**, then **tenant-dashboard-bff**, then **dashboard**. Both orders are
safe (each direction degrades to "un-armed"), but bff-first means the badge stays blank until the
orchestrator lands, which reads exactly like the bug being fixed. No flag flip: the badge rides the
existing `STOP_LOSS_WRITE_ENABLED` actions column, which is ON by default in prod.

---

## Explicitly out of scope

- **A stale-trail warning.** A market-data restart orphans an armed trail while the workflow still
  reports `trailingArmed=true` (in-process subscription registry, no re-subscribe on restart —
  unfixed, tracked separately). This plan makes the workflow's belief visible; it does not verify
  the tick stream behind it. Surfacing that needs `lastTickAt` + a staleness threshold and is its
  own change. **The badge therefore means "the workflow has a trail armed", not "ticks are flowing".**
- **Showing the badge when `STOP_LOSS_WRITE_ENABLED=false`.** The whole actions column is gated on
  the write flags today; the flag is ON in prod, so this changes nothing live.
- Widening `ProximityReader#positions` to non-watchlist positions.
- `peakPremium` on the row (the stop price already encodes it).
