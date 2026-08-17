# PLAN — 2026-08-17 premium poll latency

The option-premium feed is a fixed-rate REST poll at **2000ms** (`application.yml:28`,
`AlpacaMarketData#startPremiumPoll:882`). A trailing stop can be no tighter than its sampling
interval: a poll at interval *I* observes a stop crossing ~*I*/2 late. Measured premium velocity
(127,193 samples, `scripts/research/option_velocity.py`) puts the cost of that delay at
**1.7–3.5% of premium** in the tail — which is exactly when a stop fires.

The interval was chosen against a constraint this account has never had. `application.yml:27` says
*"keep total under Alpaca's ~200 req/min data-REST limit."* **That is stale and wrong**: the account
is on Algo Trader Plus, verified by REST probe 2026-08-16 — the real limit is **10,000 req/min**.
A ~500ms poll has been available all along, at no cost and no code.

Source: `docs/plans/SPIKE-options-premium-websocket.md` (Step 1 — its strongest standing
recommendation, after the spike **retracted** its own earlier "500ms buys nothing" result as a
resolution artifact of trade-print data).

> **Read the spike's retraction before touching the numbers here.** Its first measurement said this
> change was worthless. That result was an artifact — median trade-print gap is 1520ms, so a 500ms
> replay re-read the same value 3–4× and could not have detected the effect. The corrected metric is
> **detection delay**, not movement-inside-a-window.

## P0 — Immediate operational (no code; operator)

- **None.** Nothing is at risk right now; this is a latency improvement, not an incident.
- ⚠ **Do not roll market-data before Phase 1 ships.** Every roll currently 406-collides its own live
  stocks WebSocket (see Phase 1). That is pre-existing, and it is why the poll change cannot simply
  be deployed today.

## Current behaviour (verified anchors)

| anchor | what it does |
|---|---|
| `services/market-data/src/main/resources/application.yml:28` | `premium-poll-interval-ms: ${ALPACA_PREMIUM_POLL_INTERVAL_MS:2000}` — the image default; the env var is **unset on the cluster**, so the default is the live value |
| `application.yml:27` | the stale `~200 req/min` justification |
| `AlpacaMarketData#startPremiumPoll:882-892` | `scheduler.scheduleAtFixedRate` per open OCC |
| `AlpacaMarketData#pollOnce:822` | one `snapshotQuote` per tick; fail-soft on 429/5xx |
| `AlpacaMarketData:530-534` | **resample dedup** — a quote whose `retrievedAt` equals the previous one is REJECTED. This is the ceiling on what faster polling can buy |
| `infra/k8s/53-market-data.yaml:26` | `replicas: 1`, and **no `strategy:` block** → RollingUpdate `maxSurge: 25%` → 2 pods briefly → 406 on the live stocks WS |
| `application.yml:43` | `stock-feed: ${ALPACA_STOCK_FEED:}` — **live-only override, absent from the manifest** |
| `.github/workflows/deploy.yml` `RESTART_ONLY` | includes `market-data`, so CI never applies its manifest |

**The design crux is `:530-534`.** Polling faster than OPRA actually updates produces rejected
duplicates — REST cost, zero information. The spike measured *trade print* gaps but states plainly
it could **not** measure quote update frequency. That unmeasured number is what separates a
justified 500ms from a wasteful 200ms, which is why Phase 3 measures before tuning.

---

## Phase 1 — make market-data safely deployable (infra + CI)

**Goal:** allow a market-data roll at all. Prerequisite for Phase 2, and worth doing even if the
rest of this plan is abandoned. **No behaviour change to the poll.**

**Changes** (anchors):
- `infra/k8s/53-market-data.yaml` — add `strategy: {type: Recreate}` to the Deployment spec. At
  `replicas: 1`, `maxSurge: 25%` rounds to 1, so the new pod starts before the old terminates and
  two pods hold connections to the same Alpaca data endpoint. The limit is **1 connection per
  endpoint** and does not rise with the paid plan, so this is a guaranteed 406 on the **stocks WS
  that is live today** — the feed watchlist triggers depend on. Confirmed on the live cluster.
- `infra/k8s/53-market-data.yaml` — **declare `ALPACA_STOCK_FEED=sip`**. It is set imperatively and
  is absent from the manifest AND from `last-applied-configuration`; it survives `kubectl apply`
  only by strategic-merge accident. Without it `effectiveStockDataWsUrl()` returns empty and
  `subscribeEquity` fail-closes with `StockFeedGatedException`. Same class as the exec drift in #696.
- `.github/workflows/deploy.yml` — remove `market-data` from `RESTART_ONLY`, **only after** the
  above, and **only after** confirming `kubectl diff -f infra/k8s/53-market-data.yaml` exits 0.

**Version gate:** none (infra only, no workflow code).

**Tests / verification:**
- `kubectl diff -f infra/k8s/53-market-data.yaml` exits **0** before removing it from `RESTART_ONLY`.
  A non-zero exit means another undeclared override exists — declare it first.
- After apply: exactly one market-data pod at all times through a roll; `fill`/equity subscribe logs
  show no 406.
- `k8s (kubeconform)` green.

**Ordering note:** the `RESTART_ONLY` removal must merge **with or after** the declarations, never
before — the reverse order lets a deploy strip `ALPACA_STOCK_FEED` and silently fail-close the
equity feed. Same hazard, and same resolution, as #696.

---

## Phase 2 — 2000ms → 500ms (market-data)

**Goal:** take the measured win. One value, reversible.

**Changes** (anchors):
- `application.yml:28` — default `2000` → `500`. Change the **image default**, not a manifest env
  var: `ALPACA_PREMIUM_POLL_INTERVAL_MS` is unset on the cluster, so the default *is* the live
  value, and a manifest env would add a second place to drift.
- `application.yml:27` — replace the stale `~200 req/min` comment with the measured budget
  (**10,000 req/min** on Algo Trader Plus, verified 2026-08-16). Leaving it invites the next person
  to re-derive 2000ms from a constraint that does not exist.

**Budget check (state it in the PR, do not assume):** 500ms = 120 req/min per open OCC. At 10,000
req/min that is ~83 concurrent contracts before the poll alone saturates the budget — and
`snapshotQuote` is also used by kill-switch MTM, `GetOptionQuoteActivity` and `resolveTrailAnchor`,
so the headroom is shared. Note the ceiling explicitly; today's concurrency is far below it.

**Temporal history:** safe, and only because the 1% min-move throttle lives in
`SubscribePremiumActivityImpl#shouldEmit`, **downstream** of the feed. Measured: 4× polling produced
only **+9.2%** more emitted signals. `PositionWorkflow` still has no continue-as-new, so re-check
this rather than assuming it holds at other rates.

**Tests (TDD):**
- Existing `AlpacaMarketDataTest` premium-poll tests must pass unchanged — the interval is config,
  not logic. If any test hard-codes 2000ms, that is the test to fix.
- No new behavioural test: this phase changes a number, and its effect is measured in production,
  not asserted in a unit test. Say so in the PR rather than inventing a test that pins a constant.

**Verify / success criteria:** `mvn -pl services/market-data -am spotless:apply` + module tests.
Behavioural assertion **in production, one session after deploy**: `optionPollFailures` / 429 rate
unchanged from the 2000ms baseline, and premium ticks per armed position up roughly 4× *before* the
throttle while emitted signals rise ~10%.

---

## Phase 3 — measure the quote rate, THEN decide on 200ms (research)

**Goal:** answer the only question that decides whether to go below 500ms. **Measurement first; the
tuning change is conditional on the result and may correctly be "no".**

`AlpacaMarketData:530-534` rejects a quote whose `retrievedAt` matches the previous one, so polling
faster than OPRA updates yields **rejected duplicates — REST cost, zero information**. The spike
could not measure quote update frequency (there is no historical options *quotes* endpoint; only
`trades`, `bars`, `quotes/latest`), so it can only be recorded live.

**Measurement** (new `scripts/research/option_quote_rate.py`): during one RTH session, poll
`snapshotQuote` for 3–5 liquid contracts as fast as the budget allows for ~60s each; record every
distinct `retrievedAt`. Report the distribution of inter-update gaps (p50/p90/p99), separately for
an ATM 0DTE and a longer-dated contract — they will differ, and the 0DTE is the case stops fire on.

**Decision rule, fixed in advance so the result cannot be rationalised:**
- p50 gap **< 200ms** → 200ms is justified; expected further saving ~0.35% (p99) to ~0.71% (p99.9).
- p50 gap **200–500ms** → 500ms is already at the knee; **stop at Phase 2**.
- p50 gap **> 500ms** → Phase 2 is already past the knee; consider whether even 500ms is over-polling.

**Also record**, since it is free at that point and is the WS spike's one surviving open question:
whether the REST snapshot ever misses a **bid withdrawal / book widening** that produces no trade
print. That is the only remaining argument for the options WebSocket, and it cannot be answered
from history.

**Concurrency ceiling:** at 200ms one contract costs 300 req/min → ~33 concurrent contracts against
the 10,000 budget, versus ~83 at 500ms. Fine at today's volumes; a structural cap on a growing
fleet. Weigh it as part of the decision, not after.

**Verify / success criteria:** the script is committed and reproducible; the decision rule is
applied to real numbers in the PR description; if the answer is "stop at 500ms", that is a
**successful** outcome of this phase, not a failed one.

---

## Ship order & gating

1. **Phase 1** (infra; unblocks deploying market-data at all, and fixes a live 406 on every roll).
2. **Phase 2** (one config value; the measured win).
3. **Phase 3** (measure, then decide — may correctly end here with no further change).

Each: own PR, spotless on every touched module, operator merge gate. Phase 2 must not deploy before
Phase 1, or the roll that delivers it takes down the equity feed on the way in.

**Do not compress Phases 2 and 3.** Going straight to 200ms skips the one measurement that says
whether it does anything, on a code path that silently discards the extra samples.

## Operator follow-ups (not code phases)

- `market-data` is one of the **ten drifted manifests** the drift check found (#698). Phase 1 fixes
  its two known overrides; if `kubectl diff` still exits non-zero afterwards, there is a third.
- **`data-ws-url` still defaults to `indicative`** (`application.yml:23`). Harmless today — nothing
  consumes it — but the spike measured the indicative bid **$3.39 low** with a **3.7× wider spread**
  on `SPY260817C00500000` versus OPRA. Any future WS work that trusts the default inherits a bid
  that wide of truth on a path whose whole job is deciding when to sell. Flip it to `opra` when the
  WS work resumes, not after.
- The **fill poller is a different poll and must not be tuned to match.** Its 30s interval is not the
  binding constraint — `grace-ms: 60000` skips orders younger than 60s — and order status is the
  *trading* API (200 req/min), not the 10,000 data budget. After #694 the WS should deliver fills in
  ~50ms and make poll latency irrelevant; confirm that at Monday's open before touching it.

## Related

- `PositionWorkflow` has no continue-as-new; armed post-throttle tick rate is still unmeasured.
- The blown-ask anchor gap in `resolveTrailAnchor` shares a root cause with this file:
  `snapshotQuote` is unfiltered while `pollOnce` is filtered.
