# SPIKE — option premium over WebSocket (OPRA)

**Status:** open, research only. **No code changed.** Worktree `options-ws-streaming`.
**Opened:** 2026-08-16.

**Goal:** decide whether option premium should move from the current 2s REST poll to a real-time
OPRA WebSocket — and, separately, whether buying the OPRA entitlement is worth it on its own.

> **This is a living document.** Append every new finding to the [Log](#log) at the bottom, dated,
> newest last. When a finding invalidates something above, **edit the section above AND log the
> retraction** — a spike doc that quietly rewrites itself teaches nothing. Retractions so far are
> marked ⟲.

## Exit criteria

The spike is done when all four are answered with evidence, not assumption:

1. **Entitlement** — ✅ **ANSWERED 2026-08-16: Algo Trader Plus is ACTIVE and the exit path is
   already on OPRA.** The redistribution question remains *(open, and now urgent rather than
   hypothetical — see [Open questions](#open-questions))*.
2. **One decoded quote** — ✅ **TRANSPORT CLEARED 2026-08-16, markets closed.**
   `scripts/alpaca-options-ws-probe.py` authenticated in-band, decoded msgpack, and got a subscribe
   ack for a compact OCC. The June failure mode is **refuted**. *(only live quote FLOW remains, and
   that needs RTH — see [Does this need RTH?](#does-this-need-rth))*
3. **Does the socket beat a 500ms OPRA poll?** Measured in shadow, not argued. The honest possible
   answer is "no." *(open — and now the spike's central question)*
4. **Backpressure** — does an ATM 0DTE at the open trigger Alpaca's 407 slow-client disconnect
   under our consumer design? *(open)*

---

## Established: why streaming keeps coming up

Option premium is a **REST poll** today: one fixed-rate `snapshotQuote` task per open OCC, 2s default
(`AlpacaMarketData#pollOnce`, `startPremiumPoll`). Equities already stream (`ensureStockWs`).

Four of the last five premium-path commits are working around properties of *polling*, not of the market:

| what shipped | what it was actually fixing |
|---|---|
| `e7cc387` `lastQuoteStamp` resample-dedup | `pollOnce` emits on **every** poll with no dedup and stamps from the quote (`latestQuote.t`), so a stalled NBBO produces byte-identical ticks. Consecutive polls resampling *one* quote were counted as *two* corroborating observations. **A polling artifact and only a polling artifact.** |
| `e7cc387` 35% deviation band | The band is wide *because the sampling interval is wide* — "option premium legitimately moves tens of percent between two 2s polls on a short-dated contract." Shrink the interval, shrink the band. |
| corroboration hold | A genuine violent move is admitted "one poll (~2s) later rather than lost." On a 0DTE collapse the stop acts a full 2s late by construction. |
| `PLAN-…-premium-feed-silence-backstop` P3 | Its whole fork exists because **a poll cannot distinguish "feed dead" from "market quiet"** — a 429, a dead poll thread, a withdrawn bid and a dead feed all look identical: no tick. Its recommended option C spends an extra activity call probing REST to recover information the transport should carry. |

And one ceiling polling can't raise: **trail tightness** — a trailing stop can be no tighter than its
sampling interval.

## Established: why the June attempt died, and why that reason is dead

`da492f3` (2026-06-23, #471) removed the options WS. Both causes were **client bugs, not service problems**:

1. Header auth instead of the in-band `{"action":"auth"}` message → the socket sat
   connected-but-unauthenticated and Alpaca honored no subscriptions.
2. The JDK `WebSocket.Listener` never overrode `onBinary`, so **every msgpack frame was silently dropped.**

Bug (1) is the *identical* bug `9ec7387` then fixed for the stocks WS — `ensureStockWs` /
`StockWsListener` / `handleStockControlSuccess` are now a working, tested in-band-auth template in the
same file. Bug (2) is a missing method plus a decoder.

**The options stream was never observed to be broken. Our client was, twice.**

> The class javadoc currently states the WS "never delivered ticks" as though it were a property of
> Alpaca's feed. It should say we never authenticated to it or decoded a frame from it. *(not yet fixed
> — out of scope until the spike concludes)*

## Established: Alpaca's limits

Researched 2026-08-16 — see [Sources](#sources).

| | Basic (free) | **Algo Trader Plus ($99/mo) — WE ARE HERE ✅** |
|---|---|---|
| options feed | `indicative` | **`opra`** |
| stocks feed | IEX | all US exchanges (SIP) |
| REST data | 200 req/min | **10,000 req/min** |
| options WS symbols | 200 quotes | **1000 quotes** |
| equity WS symbols | 30 | unlimited |
| **connections per endpoint** | **1** | **1** |

**Verified 2026-08-16** by read-only REST probe on the `_DATA` key (`AK…`, live):
`feed=opra` → 200 with real data; `feed=sip` → 200 with real data (SPY daily volume **31.4M** vs
IEX's 1.25M — genuine consolidated tape, not a silently-downgraded response).

> ⚠ **`application.yml`'s "sized against Alpaca's data-REST limit (~200 req/min)" comment is stale
> and wrong.** The real limit is 10,000 req/min. The 2s poll interval is calibrated against a
> constraint this account has never had.

### The connection limit does not go away with money

> "The number of connections to a single endpoint from a user is limited based on the user's
> subscription, but in many subscriptions (or without one) this limit is **1**." — error **406
> connection limit exceeded**

**Algo Trader Plus does not raise it.** Paying buys symbols and REST throughput, not sockets.

The saving grace is that the limit is **per endpoint** — verified empirically by this repo on
2026-06-20 (`scripts/alpaca-ws-conn-check.py`): stocks `/v2` and options `/v1beta1` coexist on one
key, and a second connection to the *same* endpoint is 406'd while the existing one survives. So:

- One options socket serves **every tenant and every contract** — market-data is `replicas: 1`
  (`infra/k8s/53-market-data.yaml:26`) on a **pod-global** data key. Architecturally this fits.
- exec's `AlpacaTradeUpdatesStream` is on `wss://api.alpaca.markets/stream` — the **trading** API, a
  different host. It does not contend.
- Cost: market-data is **pinned to one replica permanently**. No HA; the ceiling becomes structural.

### Other limits worth knowing

- **407 slow client** — Alpaca *disconnects* consumers that can't keep up. OPRA on an ATM 0DTE at the
  open is a different order of volume from indicative. The JDK `WebSocket.Listener`'s `request(n)`
  flow control decides whether we take a 407. The poll had backpressure for free; this is the axis
  where a naive port passes tests and fails in production.
- **405 symbol limit exceeded** — 1000 option quotes on Plus.
- `*` wildcard subscribe is **prohibited** for option quotes ("there are simply too many of them").
- The options stream is **msgpack only** — "Unlike the stock and crypto stream, the option stream is
  only available in msgpack format." JSON in the docs is for readability.
- Quote message `T="q"`: symbol, timestamp, bid exchange/price/size, ask exchange/price/size, condition.

## Established: which feed we actually receive (measured)

`snapshotQuote` sends **no `feed` parameter** — just
`/v1beta1/options/snapshots?symbols={s}`. Alpaca defaults an entitled account to OPRA, so **the exit
path has been on OPRA all along.** Measured on `SPY260817C00500000`, 2026-08-16:

| request | bid | ask | spread |
|---|---|---|---|
| **no feed param** (what `pollOnce` sends) | **275.02** ×10 | **277.80** ×20 | 2.78 |
| `feed=opra` | 275.02 ×10 | 277.80 ×20 | 2.78 |
| `feed=indicative` | 271.63 ×10 | 281.92 ×1 | **10.29** |

Same contract, same quote timestamp. The default is **byte-identical to OPRA**.

Two consequences:

1. **The price-quality argument for the purchase is already realised on the exit path.** The trail
   arms and stops on OPRA bids today. Good news, and it removes a motivation the spike was leaning on.
2. **`indicative` is materially wrong, and the config still defaults to it.** The indicative bid is
   **$3.39 low** and the spread **3.7× wider** on this contract. `application.yml:23` still defaults
   `data-ws-url` to `.../v1beta1/indicative`. Nothing consumes it today (the WS is dead), but **any
   future WS work that trusts the default inherits a bid $3.39 wide of truth** on a path whose whole
   job is deciding when to sell. Change the default to `opra` as part of step 3, not after.

## Measurement: what 500ms actually buys

**Answer: measurably nothing.** Reproduce with `scripts/research/option_poll_interval_sweep.py`.

Method: replay real OPRA trade prints through the **actual** pipeline — sample at interval *I* →
1% min-move throttle (`SubscribePremiumActivityImpl`) → 35% trailing stop (the `/live` default).
18 (contract, day) pairs, SPY, 3 expiry weeks (2026-07-28 → 2026-08-14), calls and puts. Only
contracts that **ran up ≥5% then fell** are counted — a trail is only ever armed on a winner.

### Exit price, 500ms vs 2000ms

| | |
|---|---|
| identical exit price | **14 / 18** |
| better | 2 |
| worse | 2 |
| mean | **−0.065%** |
| median | **+0.000%** |
| range | −1.43% … +0.71% |

Two better, two worse, mean slightly *negative*. That is noise, not an improvement.

### Why it can't help — three independent reasons

1. **The throttle absorbs it.** 4× the polling produced only **+9.2%** more emitted signals
   (14,713 → 16,072). The workflow does not receive materially more information, by design — the 1%
   min-move gate is coarser than the sampling gain.
2. **Premium is quantised in pennies.** At a $0.20–$5 premium one tick is 0.2–5%. Sub-second timing
   differences have nowhere to express themselves on a price grid that coarse.
3. **The tail is gap risk, not sampling risk.** Pooled blind spot — adverse move *inside* one
   sampling interval:

   | interval | p95 | p99 | max |
   |---|---|---|---|
   | 2000ms | 3.268% | 6.667% | **20.000%** |
   | 500ms | 3.030% | 6.250% | **20.000%** |

   **The max is identical.** The worst adverse excursion happens between two consecutive prints — no
   polling rate sees it, and neither would a WebSocket. The scary tail is not an observation problem.

### ⚠ What this measurement CANNOT see — and it is the important caveat

**There is no historical options quotes endpoint** (`/v1beta1/options/quotes` → 404; only `trades`,
`bars`, `quotes/latest` exist). So this replays **trade prints**, while the exit path evaluates the
**bid**.

That means the measurement is structurally blind to exactly the pathology that motivated `#690`: a
**withdrawn or widening bid** produces no trade print at all. A book going 2.70×2.90 → 1.35×4.25
walks the bid through a stop while trades may not print. Whether a WS sees that materially sooner
than a 2s snapshot is **not answered here and cannot be answered from history** — it needs live quote
capture (steps 2/4).

So the honest state of the case: **the "faster price updates" argument for streaming is dead.** What
survives is narrower and unmeasured — *quote-level events the REST snapshot misses entirely.*

## Does this need RTH?

Mostly **no**. The work splits three ways, and only one part waits for Monday.

| work | needs RTH? | status |
|---|---|---|
| Poll-interval replay (the 500ms measurement) | **No** — historical trade prints | ✅ done Saturday |
| WS transport gate: auth + msgpack decode + subscribe | **No** — control frames arrive at any hour | ✅ done Saturday |
| Live quote/bid capture and comparison | **Yes** — needs a live session | ⏳ Monday |

### The transport gate cleared with markets closed

The June failure was header-auth and dropped binary frames. **Both manifest on control frames** —
the msgpack-encoded `connected` / `authenticated` / `subscription` replies — which Alpaca sends
regardless of market hours. So the whole "can we even talk to this endpoint" question was answerable
on a Saturday. Run 2026-08-16, markets closed:

```
1. greeting              : BINARY (msgpack)
   decoded               : [{'T': 'success', 'msg': 'connected'}]
2. in-band auth          : AUTHENTICATED
3. subscription ack      : quotes=['SPY260817C00776000']
4. quote frames in 20s   : 0

  msgpack frames decode        : YES
  in-band auth accepted        : YES
  compact OCC subscribe ok     : YES
  live quotes observed         : NO (expected outside RTH)
```

That also settles the **compact-vs-padded OCC** question on the subscribe leg: Alpaca accepted the
compact form and echoed it back in the ack. The inbound `S`-field mapping still has to be handled.

**Safety note:** this probe touches **only** `/v1beta1/<feed>`, which nothing in the estate has used
since June, and Alpaca's limit is per endpoint — so it cannot 406 or kick the live stocks stream on
`/v2/<feed>`. That is why it is safe at any hour, unlike `scripts/alpaca-ws-conn-check.py`, which
deliberately opens a **second stocks** connection and must not be run during RTH.

### What genuinely waits for Monday

Only live quote capture — and it is now the **sole** remaining open question, because there is no
historical options quotes endpoint (confirmed against four candidate routes; `v1beta2` answers
"endpoint not found", `v1beta1/options/quotes` does not exist, and `snapshots` rejects `start`/`end`).
**Option bid history cannot be reconstructed after the fact. It can only be recorded live.**

The right shape for step 4 is therefore a **passive recorder**, not an experiment: during one RTH
session, log the WS quote stream and the 2s REST snapshots side by side for a handful of contracts.
The comparison itself is then offline analysis, runnable any time. The specific question it must
answer: **does the WS surface bid withdrawals / book widenings that the REST snapshot misses?** That
is the only surviving argument for the socket.

## ⚠ Live findings this research exposed

### 1. Every market-data deploy 406-collides its own stocks WS

`infra/k8s/53-market-data.yaml` has **no `strategy:` block**. Confirmed on the live cluster:

```
{"rollingUpdate":{"maxSurge":"25%","maxUnavailable":"25%"},"type":"RollingUpdate"}
```

`maxSurge` 25% → **1** at `replicas: 1`, so the new pod starts **before** the old terminates and two
pods briefly hold connections to the same data endpoint — a guaranteed 406 on the **stocks WS that is
live today**. `strategy: {type: Recreate}` is the fix, and it is worth doing **whether or not this
spike proceeds**.

### 2. `ALPACA_STOCK_FEED=sip` exists only on the cluster, not in the repo

The live deployment carries `ALPACA_STOCK_FEED=sip` — correct, and it is what makes the equity feed
work at all (`effectiveStockDataWsUrl()` returns empty without it and `subscribeEquity` fail-closes
with `StockFeedGatedException`). But:

- It is **absent from `infra/k8s/53-market-data.yaml`**, which declares only `TEMPORAL_TARGET`,
  `TEMPORAL_NAMESPACE`, `MARKET_DATA_PROVIDER`.
- It is **absent from `last-applied-configuration`**, i.e. it was set imperatively.

It survives `kubectl apply` only by an accident of strategic-merge semantics (env has patchMergeKey
`name`, and a field in neither the new config nor last-applied is preserved). It does **not** survive
a delete/recreate, a namespace rebuild, a restore to a fresh cluster, or a switch to server-side
apply. And nobody reading the repo can tell that live watchlist triggers depend on it. Same class as
the tenants-ConfigMap trap. **Declare it in the manifest.**

## ⟲ Retractions

**⟲ 1 — the REST-ceiling argument was wrong, twice over.** Originally: "the REST budget caps you at
~6 concurrently-trailed contracts, and only streaming can raise it." The first correction was that
Algo Trader Plus takes REST to 10,000 req/min (~330 polled contracts). The second, on verifying the
account, is that **the ceiling never existed at all** — this account has been on Plus. The
`application.yml` comment that produced the claim is simply stale. A ~500ms poll is available
**today**, at no cost and no code.

**⟲ 2 — "buy Algo Trader Plus" was not a step.** It was already bought. The price-quality argument I
built for the purchase is moot on the exit path, which has been receiving OPRA all along. What
remains of the case for the socket is **latency and granularity only** — a materially narrower case
than the spike opened with.

**⟲ 3 — one motivation strengthened, not weakened.** The purchase being live means the redistribution
question is not a "before you pay" checkbox. We are *already* on OPRA and *already* rendering premium
into other tenants' dashboards. If there is an entitlement problem, it exists today.

## What a real WS attempt needs

1. **msgpack decode.** `org.msgpack:jackson-dataformat-msgpack` yields an `ObjectMapper` producing
   ordinary `JsonNode`, so `dispatchOptionWsMessage` can be a near-copy of `dispatchStockWsMessage`.
   New dependency; market-data has none today.
2. **`onBinary` with reassembly.** Fragment-oriented (`last` flag) plus `request(1)` flow control —
   mirror the existing `onText` handler.
3. **The padded-vs-compact OCC trap, again.** `snapshotQuote` already strips space-padding for the
   request *and* the response key. A WS repeats it on both legs: subscribe frames carry compact,
   inbound `S` arrives compact, and `bySymbol` is keyed on the **padded** canonical symbol. Needs an
   explicit compact→padded map or every inbound quote silently finds no listeners. Same class as
   `JooqOrderIntentJournal` / `16e4c6e`; it has bitten this repo twice.
   *(2026-08-16: the **subscribe** leg is confirmed — Alpaca accepted the compact form and echoed it
   in the ack. The inbound `S`→padded mapping is still unhandled.)*

Items 1 and 2 are now **de-risked but not done**: the probe proves the protocol works in Python, not
that the JDK `WebSocket.Listener` + Jackson-msgpack path works in Java. That is step 3.

## What streaming does NOT fix

- **Temporal history is already safe** — and only because `a7e8149`/`8cc1b7a` shipped. The 1% min-move
  throttle lives in `SubscribePremiumActivityImpl#shouldEmit`, **downstream** of the feed, so a 100×
  tick rate does not multiply history events on the un-continue-as-new'd `PositionWorkflow`. A week
  earlier, streaming would have been a non-starter on this axis alone.
- **`acceptPremiumQuote` still earns its keep** — the no-bid rejection especially. Only the
  `lastQuoteStamp` resample-dedup becomes vestigial.
- **`snapshotQuote` does not go away.** Kill-switch MTM, `GetOptionQuoteActivity`, and
  `resolveTrailAnchor` all use it. This *adds* a transport. The unfiltered-snapshot vs filtered-tick
  asymmetry the trailing-stop plan flags stays exactly as it is.
- **Silence gets *worse* before better.** A dead poll is loud (`optionPollFailures` →
  `markDisconnected`). A WS that stays open and stops delivering is silent — precisely the failure the
  premium-feed-silence plan is fighting. Streaming wins here only **if** ping/pong liveness ships with
  it; done right it collapses that plan's P3 fork (socket healthy + no quotes = market quiet; socket
  dead = feed dead, no probe activity needed).
- **Backpressure** was free with a poll. It isn't with a socket — and Alpaca disconnects (407) rather
  than buffering.
- **The single-replica pin becomes permanent.**

## Plan of record

**Step 0 — free, do it regardless, unrelated to the socket.** Two config fixes, both live bugs:
`strategy: {type: Recreate}` on the market-data Deployment, and declare `ALPACA_STOCK_FEED=sip` in
`infra/k8s/53-market-data.yaml`. Neither depends on this spike's outcome. *(not started)*

**Step 1 — spend the plan we already own. No new code.** Drop `premium-poll-interval-ms` 2000 → ~500
(REST is 10,000 req/min, not 200) and correct the stale `~200 req/min` comment. Measure what a 4×
faster poll alone does to trail behaviour. Reversible by one config value.
✅ **DONE 2026-08-16 — MEASURED, AND IT BUYS NOTHING.** See
[Measurement](#measurement-what-500ms-actually-buys). 14 of 18 replayed contract-days exit at an
*identical* price; mean difference −0.065%. **Do not ship the interval change for performance.** The
stale `~200 req/min` comment is still worth correcting as a plain doc fix.

**Step 2 — probe, no product code.** ✅ **DONE 2026-08-16 with markets closed.** Added
`scripts/alpaca-options-ws-probe.py` (options endpoint only, so safe at any hour). msgpack decode,
in-band auth, and compact-OCC subscribe all confirmed; the June failure mode is refuted. Live quote
flow is the only untested part and needs RTH. *~~Original text below, kept for the record:~~*

> ~~Extend `scripts/alpaca-ws-conn-check.py` to `subscribe` one live
OCC on `/v1beta1/opra` and print decoded quotes. Entitlement is already confirmed, so this is purely
about getting **one decoded msgpack frame** — the thing June never achieved.
**⚠ Do not run during RTH:** its steps B and C deliberately open competing connections and will 406 or
kick the live stocks WS that watchlist triggers depend on. *(not started)*

**Step 3 — decode-only, dark.** msgpack + `onBinary` + `dispatchOptionWsMessage` + tests against
captured frames. Flip the `data-ws-url` default `indicative` → `opra` here. No subscriber wiring, no
behaviour. *(not started)*

**Step 4 — shadow.** WS alongside the (now 500ms) poll, fan out nothing, log divergence: rate,
latency, bid agreement, any 407. Answers exit criterion 3 and produces the data to re-tune the 35%
band. *(not started)*

**Step 5 — cut over per-tenant**, poll retained as fallback. *(not started)*

Steps 0–4 are reversible and touch no live exit path. **Step 5 is trading-critical.**

> **The spike may correctly end at step 1.** If a 500ms OPRA poll closes most of the latency gap,
> steps 2–5 buy sub-second granularity at the price of a msgpack dependency, a binary frame path, the
> compact/padded OCC trap, a permanent single-replica pin, and 407 backpressure. Do not treat
> finishing the spike as the goal.

## Open questions

- **For Alpaca — now live, not hypothetical.** Does one pod-global OPRA key feeding premium into
  *other tenants'* dashboards count as redistribution, or flip the classification to professional?
  OPRA requires a signed Subscriber Agreement classifying each recipient professional/non-professional.
  We hold one pod-global data key (`replicas: 1`), `/live` **displays** option premium to tenant users
  (`ccdd073`), and `prod-kipark` / `prod-jinchul` are **other people's** accounts. I originally framed
  this as "ask before paying" — the plan is already active and the exit path is already on OPRA, so
  whatever the answer is, it applies to production **today**. Not a blocker for the spike; is a
  question worth asking Alpaca support plainly.
- **Is the socket worth it after a 500ms OPRA poll?** The spike's central question now. Step 4
  answers it; "no" is a legitimate and cheap outcome.
- Is 2s latency demonstrably costing money today, or is this a correctness/complexity win only?
- Does anything else in the estate silently assume the 200 req/min Basic limit the way
  `application.yml` did?

---

## Log

Newest last. One entry per working session; record what was *learned*, including dead ends.

### 2026-08-16 — spike opened, code read, limits researched

- Worktree `options-ws-streaming` created off `main`. market-data module tests green at baseline.
- Read the premium path end to end: `AlpacaMarketData#pollOnce` / `acceptPremiumQuote` /
  `startPremiumPoll`, and `SubscribePremiumActivityImpl#shouldEmit`.
- **Found the June removal commit `da492f3`** and established both failure causes were ours, not
  Alpaca's — and that `9ec7387` already fixed the same auth bug for stocks. This is the finding that
  makes the spike worth opening at all.
- **Researched Alpaca's limits.** Confirmed the operator's recollection: 1 connection per endpoint,
  not raised by Algo Trader Plus. Found 407 slow-client and the msgpack-only constraint.
- **⟲ Retracted my own REST-ceiling argument** (see above) — Plus takes REST to 10,000 req/min, so
  buying the plan raises the *polling* ceiling ~55× and a 500ms poll becomes viable with no code.
  This is the single most decision-relevant thing learned today: the purchase and the socket are
  separable, and the purchase alone may be sufficient.
- **Incidental live bug found:** market-data has no `strategy:` block → rolling update surges to 2
  pods → 406 collision on the live stocks WS every deploy.
- **Not done:** did not run `alpaca-ws-conn-check.py` — it opens competing connections and would kick
  the live stocks feed during RTH.

### 2026-08-16 (later) — verified the subscription; it changed the spike's shape

Operator: "we should have the paid account, verify that." Verified by read-only REST probe
(`scratchpad/alpaca-entitlement-check.sh`) — no WebSocket, so no risk to the live stocks feed.

- **✅ Algo Trader Plus is ACTIVE.** `feed=opra` → 200 with data; `feed=sip` → 200 with data. Not a
  soft downgrade: SPY daily volume came back 31.4M on SIP vs 1.25M on IEX.
- **The exit path has been on OPRA all along.** `snapshotQuote` sends no `feed` param and Alpaca
  defaults entitled accounts to OPRA — verified byte-identical to an explicit `feed=opra`.
- **Measured what `indicative` actually costs:** on `SPY260817C00500000`, indicative bid 271.63 vs
  OPRA 275.02 — **$3.39 low**, with a 3.7× wider spread. The `data-ws-url` default still points at
  `indicative`; harmless today only because nothing consumes it.
- **⟲ Retracted two things** (see Retractions): the REST ceiling never existed, and "buy the plan"
  was never a step. The case for the socket narrows to latency/granularity alone, and the cheapest
  remaining win — a 500ms poll — needs no code at all.
- **Confirmed the 406 rollout hazard on the live cluster:** `RollingUpdate`, `maxSurge: 25%`.
- **New drift finding:** `ALPACA_STOCK_FEED=sip` is live-only — absent from the repo manifest *and*
  from `last-applied-configuration`. It survives `apply` by merge-semantics accident; it would not
  survive a recreate, and the repo gives no hint that live watchlist triggers depend on it.
- **Reframe:** the spike may now correctly end at step 1. That is a good outcome, not a failed spike.

### 2026-08-16 (later still) — measured step 1, and it buys nothing

Operator: "with 500ms, what kind of improvement could we see?" Answered by replay rather than
argument. Scripts committed under `scripts/research/`.

- **Discovered there is no historical options QUOTES endpoint** — `/v1beta1/options/quotes` 404s;
  only `trades`, `bars`, `quotes/latest` exist. Forced the replay onto trade prints, which is the
  measurement's main limitation (see the caveat above). Worth knowing independently: any future
  backtest of a bid-driven exit cannot reconstruct historical bids from Alpaca.
- **Result: 500ms ≈ 2000ms.** 14/18 contract-days identical, 2 better, 2 worse, mean −0.065%.
- **Three independent reasons it can't help:** the 1% throttle absorbs the extra sampling (+9.2%
  signals for 4× polling), premium is penny-quantised so sub-second timing has nowhere to land, and
  the blind-spot **max is identical** at both rates because the tail is gap risk between consecutive
  prints.
- **This transitively weakens the WS case**, which rested on the same latency premise. The remaining
  argument is narrower: quote-level events (bid withdrawal, book widening) that produce no trade
  print and that this method structurally cannot see.
- **Recommendation shifting toward: do step 0, drop steps 1 and 5, and treat steps 2–4 as a
  *measurement* of bid-event visibility rather than a path to a cutover.**

### 2026-08-16 (evening) — cleared the transport gate with markets closed

Operator: "we can't really do this simulation outside RTH, can we? do we need to wait for RTH?"
Turned out most of it doesn't.

- **Confirmed there is genuinely no historical option quotes route** — probed four candidates.
  `v1beta2` → "endpoint not found"; `v1beta1/options/quotes` and two other shapes → route missing;
  `snapshots` rejects `start`/`end` (current-only). **Option bid history cannot be reconstructed; it
  can only be recorded live.** This is the reason step 4 needs RTH at all.
- **Realised the June failure mode is testable without market data.** Header-auth and dropped binary
  frames both manifest on *control* frames, which arrive at any hour. Wrote
  `scripts/alpaca-options-ws-probe.py` and ran it on a Saturday: msgpack greeting decoded, in-band
  auth accepted, compact-OCC subscribe acked, 0 quotes as expected. **The `#471` failure mode is
  refuted empirically.**
- **Bonus:** the subscribe leg of the padded/compact OCC trap is settled — Alpaca takes the compact
  form and echoes it. Only the inbound `S`-field mapping remains.
- **Safety distinction worth keeping:** an options-only probe cannot collide with the live stocks WS,
  because the connection limit is per endpoint and nothing has used `/v1beta1` since June. The RTH
  warning belongs to `alpaca-ws-conn-check.py` specifically, which opens a second *stocks*
  connection. I had previously stated that warning too broadly.
- **Remaining work needing RTH is now one thing:** a passive recorder logging WS quotes against 2s
  REST snapshots for a handful of contracts, to answer whether the socket surfaces bid withdrawals
  the snapshot misses. Capture live; analyse offline.

## Sources

- [Streaming Market Data (connection limits, error codes, auth)](https://docs.alpaca.markets/us/docs/streaming-market-data)
- [About Market Data API (plan comparison)](https://docs.alpaca.markets/us/docs/about-market-data-api)
- [Real-time Option Data (msgpack, endpoints, schemas)](https://docs.alpaca.markets/docs/real-time-option-data)
- [OPRA FAQs (subscriber agreement, pro/non-pro)](https://www.opraplan.com/faqs)
- In-repo: `scripts/alpaca-ws-conn-check.py` (empirical per-endpoint verification, 2026-06-20)
