# PLAN — 2026-08-19 armed trailing stops survive a market-data restart (#717)

An armed trailing stop stops receiving premium ticks the moment `market-data` restarts, while
`PositionWorkflow` goes on reporting `trailingArmed=true`. The position looks protected, is not, and
**nothing anywhere notices**. Fired for real on 2026-08-18: merging #726 rolled market-data at
09:26:46Z, the poll rate went **120 → 0 req/min**, and `trailingState` was byte-identical before and
after. It cost nothing only because the market was closed — luck, not design.

Trading-critical. Two armed real-money trails exist right now (prod_real: SPY 260825P00760000
50-lot, DRAM 270319C00100000).

## Why it cannot self-heal today (verified, not inferred)

| fact | anchor |
|---|---|
| the registry is in-process only; **market-data has no Redis, no datasource, no persistence at all** | `AlpacaMarketData:74-89`; `services/market-data/pom.xml` (`"No persistence in Phase 4"`) |
| no `@PostConstruct` rebuilds it; the scheduler starts with zero tasks | `AlpacaMarketData:207` |
| `subscribePremium` **never throws** — it catches, logs, and returns `FAILED`, so Temporal's retry is never engaged | `SubscribePremiumActivityImpl:186-197`, javadoc `:33-34` |
| all three call sites are one-shot and set `trailingArmed`/`exitArmed` **once, never re-checked** | `PositionWorkflowImpl:2077` (auto arm), `:2207` (operator `arm_trail`), `:2459` (watchlist exit) |
| there is **no periodic timer anywhere** in `PositionWorkflowImpl` — every `newTimer` is one-shot, the main loop is a pure event-driven `Workflow.await` | `:1233-1290`; timers at `:1103,:1112,:1139,:1538,:2414,:2427,:3043` |
| there is **no unsubscribe path at all**; the only teardown is reactive, when a later tick dispatch hits `WorkflowNotFoundException` | `SubscribePremiumActivityImpl:204-215` |

The asymmetry that defines this bug: the system handles **workflow gone / subscription alive**, and
has nothing whatsoever for **workflow alive / subscription gone**.

## The blast radius is wider than "don't deploy market-data"

#726 touched only the two gateways plus one helper in `contract/java`. `deploy.yml`'s own header:

> A change to shared Java code (`contract/**`, root `pom.xml`, the java-service Dockerfile) **still
> rolls all Java services**, because they are all rebuilt.

So **any shared-Java PR is a market-data roll**, and an author touching only a gateway has no reason
to think they are touching a trailing stop. A node reboot does the same with no deploy at all.

## A dropped subscription is invisible by construction

`trailingState` cannot distinguish "throttled and quiet" from "orphaned". Live proof, prod_real DRAM
on 2026-08-18: `lastTickAt` sat 5+ minutes stale while completely healthy, because the contract is
illiquid (~562ms between quote updates, $0.55 spread) and the 1% throttle emitted 1 tick from ~700
polls. **Staleness is therefore unusable as an alarm.** The only working signal today is
market-data's REST poll rate, read by hand — and there is no gauge on `bySymbol.size()`,
`premiumPolls.size()` or `active.size()`, so a fresh worker that subscribed nothing is
indistinguishable from a healthy idle one.

---

## Phase 1 — market-data re-subscribes armed trails on boot

**Goal:** the demonstrated failure mode stops existing. **Zero Temporal history cost, zero workflow
code, therefore zero replay risk.**

market-data already holds a `WorkflowClient` — it signals `chandelierTick` directly
(`SubscribePremiumActivityImpl:200-219`). Everything needed to rebuild a subscription already lives
in this service; it is only missing the list of *(OCC, positionWorkflowId)* pairs. Temporal is the
durable store it already talks to, so no new infrastructure is required.

On boot (best-effort, must never block or fail startup):

1. Visibility query `WorkflowType='PositionWorkflow' AND ExecutionStatus='Running'`.
2. For each, recover the OCC from the workflow id — **reuse `WorkflowIds.occFromPosition()`**, added
   for #718. (`ContractSymbol` is also a search attribute, so either source works; the id parse needs
   no extra round-trip.)
3. Query `trailingState`; re-subscribe where `armed == true`.
4. Audit/log each re-subscription, and each skip, with its reason.

**Must also cover the watchlist-exit subscription** (`:2459`, `exitArmed`), which `trailingState`
does not report — otherwise Phase 1 silently protects only two of the three arm paths. Confirm the
query surface before implementing; if none exists, that is a prerequisite, not an afterthought.

**Tests:** a restart with an armed position re-subscribes it; with no armed position subscribes
nothing; Temporal unreachable at boot leaves the service healthy and retries rather than crash-looping.
Falsify each: break the `armed` filter and confirm the "subscribes nothing" test reddens.

**Verify in production:** roll market-data with a trail armed **on paper**, then confirm the poll
rate returns to ~120 req/min per armed contract and `ticksReceived` advances. Do not verify on
real money first.

## Phase 2 — an already-armed trail can be re-asserted

**Goal:** restore the operator's recovery path. Today there is none.

`arm_trail` short-circuits before the subscribe (`PositionWorkflowImpl:2146-2155`), returning
`ALREADY_ARMED`. Combined with a lost subscription this makes the state **unrecoverable through any
operator surface** — `PositionWorkflow` exposes only `force_close`, `partial_close`, `arm_trail`, and
there is no disarm. On 2026-08-18 the only way out was to **terminate a live real-money position
workflow** and let recon re-adopt it.

The idempotency guard is correct on its own terms and must be preserved: *"a double-click (or two
operators, or two tabs) must never widen a stop that is already protecting this lot."* So:

- On the already-armed path, **still call `subscribePremium`** (it is idempotent — `computeIfAbsent`
  + `synchronized` at `AlpacaMarketData:296-311`) before returning.
- **Do not touch `peakPremium` or `givebackPct`.** The stop is never widened, never re-anchored.
- Return a status distinguishing "already armed, feed re-asserted" from a plain no-op, so the
  operator learns something happened.

**Version gate:** none needed — `arm_trail` is a new Update, no recorded history contains one, and
the existing handler documents that reasoning at `:2140-2142`. Re-confirm before relying on it.

**Falsify:** a test that the re-assert does **not** move `peakPremium`/`givebackPct` — break it by
re-anchoring and confirm the test reddens. That invariant is the whole reason the guard exists.

## Phase 3 — make an orphaned subscription observable

**Goal:** `armed:true` with no live subscription becomes detectable rather than inferable.

- Gauge `omo_premium_subscriptions_active` (from `premiumPolls.size()` / `bySymbol.size()`).
  `FeedHealth` already registers gauges (`FeedHealth:45-52`), so this is a few lines in an existing
  pattern.
- The real alarm is **disagreement**: any workflow reporting `armed: true` for an OCC that
  market-data holds no subscription for. That is the actual failure condition, and neither side can
  see it alone.

Depends on #721 for alerting (nothing scrapes the `copytrade` namespace at all), but the gauge is
worth shipping regardless — today you cannot even read the number by hand.

---

## Prerequisite risk, larger than this issue: history growth

`PositionWorkflowImpl` has **no continue-as-new** (confirmed absent; it exists only in
`KillSwitchWorkflowImpl:264`, `AccountKillSwitchWorkflowImpl:556`,
`WatchlistTriggerWorkflowImpl:465`). The repo's own watermark is 10,000 events
(`KillSwitchWorkflowImpl:159-169`) against a Temporal frontend cap of ~51,200; this cluster sets no
dynamic-config override, so server defaults apply.

`PLAN-2026-08-16-premium-feed-silence-backstop.md:195-196` records the armed **post-throttle** rate
as unmeasured. **Measured 2026-08-19 on the live prod_real SPY position:**

| | |
|---|---|
| armed | ~10h (one RTH session + after-hours) |
| emitted ticks | 1,038 |
| `HistoryLength` | **4,221** |
| `HistorySize` | 584 KiB |

≈ **4 history events per emitted tick, ~4,200 events per armed session.** That crosses the repo's own
10,000 watermark in **~2.5 trading days** and the ~51,200 hard cap in **~12**, on a workflow that is
explicitly multi-day. At the cap the workflow *fails* — and on this system a failed `PositionWorkflow`
orphans the position and recon re-adopts it, **silently dropping the trail**. That is the same end
state as #717, reached by a different route, and it is live right now on a 50-lot expiring 08-25.

**This is why Phase 1 deliberately spends no history.** A periodic workflow-side re-assert was
considered and rejected: at 60s it adds ~1,950 events/day (10,000 in ~5 days); at 5min ~390/day.
Either layers a second unbounded growth source onto one that already has no ceiling. If a periodic
re-assert is ever wanted, **continue-as-new is a prerequisite, not a companion** — and CAN here is
materially harder than in the kill-switch: ~40 mutable fields, several one-shot timers needing
re-arming across the boundary, and version-gated legacy-replay obligations.

File the history ceiling as its own issue. It is not blocked by this plan, and this plan is not
blocked by it.

## Ship order

1. **Phase 1** — removes the demonstrated failure. Self-contained in market-data; no workflow code.
2. **Phase 2** — restores the recovery path, so a future orphan does not require terminating a live
   position.
3. **Phase 3** — makes the condition observable.

Each its own PR, spotless on every touched module, operator merge gate. **Note the deploy hazard this
plan is about: Phase 1 ships in market-data, so the roll that delivers it is itself a
subscription-dropping restart. Deploy it with nothing armed, or immediately re-arm and verify the
poll rate afterwards.**
