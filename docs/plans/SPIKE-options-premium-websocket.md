# SPIKE — option premium over WebSocket (OPRA)

**Status:** open. Research only *on this branch* — but the spike's findings have since shipped as
code in **#694, #695, #700 and #701**, all merged to `main` and live. Worktree `options-ws-streaming`.
**Opened:** 2026-08-16. **Last reconciled against production:** 2026-08-17 02:00 CDT.

**Goal:** decide whether option premium should move from the current 2s REST poll to a real-time
OPRA WebSocket — and, separately, whether buying the OPRA entitlement is worth it on its own.

> **This is a living document.** Append every new finding to the [Log](#log) at the bottom, dated,
> newest last. When a finding invalidates something above, **edit the section above AND log the
> retraction** — a spike doc that quietly rewrites itself teaches nothing. Retractions so far are
> marked ⟲.

---

## TODO — what to actually do, in order

Ranked by value × confidence ÷ effort. **The largest finding of this spike is not about the options
WebSocket at all** — it is [#693](https://github.com/ridopark/oh-my-tradeagent/issues/693), and it
outranks everything the spike set out to investigate.

None of these need a Temporal version gate, a `contract/schemas` change, a ConfigMap drift re-sync,
or a live-tenant YAML edit.

### Do first

- [x] ~~**1. Fill listener `onBinary`**~~ — ✅ **SHIPPED in [#694](https://github.com/ridopark/oh-my-tradeagent/pull/694)**
      (`b86d948` + `2e0e974`), merged to `main` 2026-08-16 from a parallel session working off
      `docs/plans/PLAN-2026-08-16-fill-listener-binary-frames.md`. Implementation matches the plan:
      byte accumulator decoded once on `last`, `onText` retained with both channels routed into
      `handleFrame`, `MAX_FRAME_BYTES` parity, and the `authorization` reply logged. The
      cross-source dedup flag was discharged rather than assumed — the poller only selects
      `SUBMITTED` rows past the grace window, so a WS-terminalized row is never polled.
      **Not yet verified in production — step 2 is now the live gate.**

- [ ] **2. Verify in production** ⬅ **STILL THE NEXT ACTION — needs a live fill, so 2026-08-17 RTH.**
      *(operator, during RTH — gate on this before step 3. #694 is merged and deployed but unproven
      against a real fill, which is exactly how the original bug survived: `#167` validated the
      transport against a `NoopFillDispatcher` and shipped.)*

      **Half of it is already answered.** As of 2026-08-17 02:00 CDT the live pod runs the #694/#695
      image and the ack line #694 added prints for all three live tenants:
      ```
      fill-listener[prod_real]    authorization reply status=authorized action=authenticate
      fill-listener[prod-kipark]  authorization reply status=authorized action=authenticate
      fill-listener[prod-jinchul] authorization reply status=authorized action=authenticate
      ```
      **Auth was succeeding all along**, so dropped binary frames were the *entire* bug — the
      "auth vs binary frames, still unconfirmed" caveat in #693 is now resolved in favour of binary
      frames. Counters read 0 with `last_event_age_seconds=+Inf`, correct with markets closed; that
      is the pre-open baseline. What remains is purely whether a real fill arrives on the socket.
      ```bash
      kubectl -n copytrade exec deploy/exec-alpaca-live -- wget -qO- localhost:8080/actuator/prometheus \
        | grep -E 'fill_listener_(events_received|poll_fills_detected)'
      ```
      Success = `events_received{event="fill"}` climbing, `poll_fills_detected` near zero. Then
      re-run `scripts/research/fill_observation_lag.sql`: p50 should collapse from 30–69s to
      sub-second. The ack log also finally reveals whether auth was succeeding all along.

- [ ] **3. Alert on the invariant** — `#693` Phase 2. Poller finding fills the socket never
      reported ⇒ mute socket. Only meaningful once the socket *can* succeed. → **PR 2**

- [x] ~~**4. market-data manifest**~~ — ✅ **SHIPPED in [#700](https://github.com/ridopark/oh-my-tradeagent/pull/700)**
      (`b5d1d47`). Verified live 2026-08-17: `strategy` is `{"type":"Recreate"}` and
      `ALPACA_STOCK_FEED=sip` is declared in the Deployment's env. market-data was also removed from
      `RESTART_ONLY` in `deploy.yml`, so CI applies the manifest again.

### Do next

- [x] ~~**5. Premium poll 2000ms → 500ms**~~ — ✅ **SHIPPED in [#701](https://github.com/ridopark/oh-my-tradeagent/pull/701)**
      (`d79a5a4`), merged 01:36Z 2026-08-17, deploy run green 01:48:06Z. **500ms is the live cadence
      now** — established by digest lineage, not by the deploy timeline: the running market-data
      image `sha256:c21099f1` is tagged `01d1862`, and that tree carries the 500ms default while the
      cluster leaves `ALPACA_PREMIUM_POLL_INTERVAL_MS` unset. The stale "~200 req/min" comment is
      corrected in **both** places that carried a default (`application.yml` *and*
      `AlpacaMarketDataProperties`, which still returned 2000L).

      ⚠ **The sequencing warning above was not honoured** — #694 and #701 both land in the same RTH
      session, so a change in exit quality on 2026-08-17 is **not attributable** to either one alone.
      Noted rather than hidden; it is the cost of shipping both on a Sunday night.

      ⚠ Its adversarial review surfaced three things this doc did not anticipate, all live now and
      **none covered by a test**: the snapshot read timeout was tightened 1500ms → 400ms to preserve
      the documented `read < interval` invariant (an over-budget snapshot is fail-soft, so it now
      costs a *sample*); the real concurrency ceiling is the **4-thread pool**, ~20 contracts at
      500ms, past which `scheduleAtFixedRate` silently degrades the realized cadence while feed
      health stays green; and **`peakPremium` ratchets higher** at a faster sample rate, because a
      discretely-sampled running max is a downward-biased estimator of the continuous max. That last
      one means this was **not** a pure "react sooner" change — it shifts the realized exit
      distribution earlier. Watch it in the first session.

- [ ] **6. Doc corrections** (zero risk): `AlpacaMarketData`'s javadoc claims the options WS "never
      delivered ticks" as a property of Alpaca's feed — it was our bug, twice; and the `data-ws-url`
      default still points at `indicative`, measured **$3.39 low on the bid** with a 3.7× wider
      spread. Fold into PR 4 or ship alone.

- [ ] **7. Ask Alpaca the OPRA redistribution question** *(not code, do in parallel)* — one
      pod-global key feeds real-time OPRA into other tenants' dashboards. Live today, not
      hypothetical.

### Do later, or decide not to

- [ ] **8. Revisit the #686 entry re-peg** — after step 2 has been live a few sessions. Its 30s
      timer has been asking "am I still unfilled?" against a journal 69s behind; its behaviour under
      *accurate* fill state has never been observed.

- [ ] **9. Options WS — one RTH capture session.** Passive recorder: WS quotes vs 2s REST snapshots,
      a few contracts, one day; analysis offline afterwards. Answers the only surviving question —
      does the socket surface bid withdrawals the REST snapshot misses (exit criterion 3)?
      **Decide whether you still care after 1–5. "No" is a legitimate outcome and closes the spike.**

### Systemic note

Three `WebSocket.Listener`s in this repo; **two shipped broken the same way** — the options WS (June:
header auth + no `onBinary`) and trade-updates (no `onBinary`) — and the third, stocks, needed
`9ec7387` to fix the same class. That is a pattern, not bad luck. Worth a standing check on any
WebSocket listener here: **does it handle both frame types, and does it log the auth ack?**

---

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

## ⟲⟲ SUPERSEDED — "500ms buys nothing" was WRONG

**Retracted 2026-08-16 after the operator pushed back: "in 2 seconds the price of a contract can
change wildly in a volatile market — wouldn't it be better to poll shorter to react faster?"**
That intuition is correct and my measurement was not capable of detecting it. See
[Corrected measurement](#corrected-measurement-detection-delay-is-the-right-metric) below. The
original section is kept intact underneath because the *reasoning error* is the useful part.

### Why the original result was an artifact

1. **The replay data could not resolve 500ms.** Median gap between consecutive trade prints is
   **1520ms**, and **45.6% of gaps exceed 2000ms**. A 500ms poll of that series re-reads the same
   value three or four times. **The experiment could not have shown a benefit even if one existed.**
2. **The "blind spot" metric was partly tautological.** A longer window contains more price movement
   *by construction*; that is not the same as reacting worse. It should never have been presented as
   evidence of equivalence.
3. **Averaging over whole trading days was the wrong frame.** A stop does not fire on an average day
   — it fires during a fast move. 18 trail exits mostly landed in ordinary conditions, so the cases
   that matter were washed out by the calm majority.
4. **I dismissed my own signal.** The 4 sweep cases that *did* differ ranged −1.43% to +0.71%. I
   called that noise. It is the right order of magnitude for the effect measured below.

---

## Corrected measurement: detection delay is the right metric

The cost of a slow poll is not "movement inside a window." It is **how late you observe the stop
being crossed.** A poll at interval *I* observes a crossing on average *I*/2 late:

| poll | avg detection delay |
|---|---|
| 2000ms | 1.000s |
| 500ms | 0.250s |

So 2s costs **0.75s of extra exposure** while the price keeps moving. The question reduces to: how
fast does premium actually move per second? Measured over 127,193 samples, 1s look-ahead, 52
contract-days (`scripts/research/option_velocity.py`):

| downward velocity | %/s | cost of the extra 0.75s |
|---|---|---|
| median | 0.224 | 0.17% |
| p90 | 0.799 | 0.60% |
| p99 | 2.313 | **1.74%** |
| p99.9 | 4.718 | **3.54%** |
| max | 16.021 | 12.02% |

**In the tail — which is exactly when a stop fires — a 2s poll costs roughly 1.7–3.5% of premium
versus 500ms.** And this **understates** it: trade prints arrive with a 1520ms median gap while
quotes update far more often, so true premium velocity is higher than this proxy can show.

### What survives from the original result

- **Temporal history is still safe:** 4× polling produced only **+9.2%** more emitted signals. That
  measurement was not resolution-limited in the same way and still holds. The 1% throttle binds
  during calm periods (most of the day) — which is *why* the whole-day average washed out — but not
  during a fast move, where a 1% move takes under half a second at p99 velocity.
- **The gap-risk point still holds** for moves that happen between two consecutive prints. It just
  does not generalise to "sampling rate doesn't matter."

### Consequences

- **Step 1 flips back ON, and is now the strongest recommendation in this doc.** Dropping
  `premium-poll-interval-ms` 2000 → 500 costs nothing (we use a fraction of 10,000 req/min), grows
  history ~9%, and plausibly saves 1.7–3.5% of premium on the exits that matter.
- **The WS case is strengthened too.** If 2s → 500ms is worth ~1.7–3.5% in the tail, 500ms →
  streaming removes a further ~0.25s of average delay, worth roughly another 0.6–1.2% at p99–p99.9.
  The "faster updates buy nothing" argument I wrote earlier is withdrawn.

---

## ~~Measurement: what 500ms actually buys~~ (SUPERSEDED — see above)

~~**Answer: measurably nothing.**~~ Reproduce with `scripts/research/option_poll_interval_sweep.py`.

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

### 3. A market-data roll silently orphans every armed trailing stop

Surfaced by #701's review, not by this spike directly, but it is the reason #701 carries a
"deploy pre-open or with no armed trails" instruction that exists nowhere in the repo.

**Verified in code 2026-08-17**, rather than taken from the PR body that asserted it:

- `AlpacaMarketData:75` — `bySymbol` is a plain in-process `ConcurrentHashMap`.
- There is **no `@PostConstruct` anywhere in `services/market-data`**, so nothing rebuilds it on boot.
- The activity does not self-heal either. `SubscribePremiumActivityImpl`'s own javadoc: *"swallows
  source-side exceptions and returns FAILED so the workflow can audit and proceed without a trail
  (instead of going into Temporal retry)."* It **completes**, so Temporal never re-runs it.
- All three `subscribePremium` call sites in `PositionWorkflowImpl` (2077, 2207, 2459) are
  **event-driven** — arm, watchlist-exit setup, trim. None is periodic, and none fires on worker or
  provider restart.

So any market-data restart leaves an already-armed trailing stop receiving no further ticks, while
`PositionWorkflow` continues to report `trailingArmed=true`. The position looks protected and is not,
and nothing anywhere will notice. `strategy: Recreate` (finding 1) makes this *worse* in one narrow
sense: it guarantees a gap with no pod at all, where RollingUpdate briefly had two.

**It is trading-critical, unfixed, and untracked** — recorded only in a merged PR body until this
entry. The fix is a re-subscribe-on-boot path; the interim mitigation is a tribal-knowledge deploy
window, and finding 4 below shows that mitigation is **not sufficient**.

**It did not bite tonight, and the reason is luck.** At 02:00 CDT there are four open
`PositionWorkflow`s — one DRAM `270319C00100000` LEAP across `prod_real`, `prod-kipark`,
`prod-jinchul` (19h old) and `staging_paper` (3 days). All four query
`{"armed":false,...,"ticksReceived":0}`, so none had ever subscribed, so the restart dropped nothing.
Had any been armed, it would have entered the 2026-08-17 open blind.

### 4. A node reboot is an uncontrolled deploy — and it bypasses the mitigation above

The homelab node rebooted at **06:56Z 2026-08-17** (`up 21 min` at 02:16 CDT; every container in
`copytrade` *and* `temporal` shows `Error exit=1` at 06:56:12Z — the ungraceful host kill, not an app
crash). Cause unknown and worth a separate look.

Two consequences the estate does not account for:

- **Every service re-pulled `latest` on restart** (`imagePullPolicy: Always`), so the whole estate
  silently rolled forward onto `01d1862` — the newest `main` — with **no deploy run involved.** The
  running version can therefore change without anything in CI recording it. This is *how* the 500ms
  poll and #694/#695 came to be running in the pods observed above, and it is why the digest, not
  the deploy timeline, is the thing to trust.
- **It is a market-data restart**, so it drops premium subscriptions exactly like a deploy — which
  means "deploy pre-open or with no armed trails" cannot be the whole mitigation for finding 3. A
  node event does not consult the trading calendar.

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
`infra/k8s/53-market-data.yaml`. Neither depends on this spike's outcome.
✅ **DONE — #700 (`b5d1d47`), verified on the live Deployment 2026-08-17.**

**Step 1 — spend the plan we already own. No new code.** Drop `premium-poll-interval-ms` 2000 → ~500
(REST is 10,000 req/min, not 200) and correct the stale `~200 req/min` comment. Measure what a 4×
faster poll alone does to trail behaviour. Reversible by one config value.
⭐ **STRONGEST RECOMMENDATION IN THIS DOC.** An initial replay said it bought nothing; that result was
an artifact of trade-print resolution and has been **retracted** — see
[Corrected measurement](#corrected-measurement-detection-delay-is-the-right-metric). A 2s poll costs
~**1.7–3.5% of premium** versus 500ms on the fast moves that actually trigger stops, for ~9% more
Temporal history and no meaningful REST cost.
✅ **DONE — #701 (`d79a5a4`), live since 01:47Z 2026-08-17.** See TODO 5 for the three caveats its
review added, and note that the **+9.2% history figure is not usable evidence** — it comes from
`option_poll_interval_sweep.py`, whose own header says DO NOT QUOTE, because ~3 of every 4 simulated
500ms samples were arithmetically incapable of emitting at a 1520ms print gap. It is a floor, not an
estimate, and history growth is therefore **unmeasured** on a `PositionWorkflow` with no
continue-as-new.

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

### 2026-08-16 (night) — ⟲⟲ the operator was right; "500ms buys nothing" RETRACTED

Operator: *"in 2 seconds while polling, the price of contract change wildly in volatile market.
wouldn't it be better to poll shorter to react faster?"* Tested rather than defended, and the
pushback was correct.

- **My replay could not resolve 500ms.** Median gap between trade prints is **1520ms**; **45.6% of
  gaps exceed 2000ms**. A 500ms poll of that data re-reads the same value 3–4×. The experiment was
  incapable of detecting the effect it was asked about — the "14/18 identical" result measured my
  proxy's resolution, not the market.
- **My blind-spot metric was partly tautological** — longer window ⇒ more movement inside it, by
  construction. It should never have been offered as evidence of equivalence.
- **Averaging over whole days was the wrong frame.** Stops fire during fast moves, not on average
  days. The calm majority washed out the cases that matter.
- **I dismissed my own signal:** the 4 sweep cases that differed spanned −1.43%…+0.71%, which is the
  right magnitude for the effect below. I called it noise.
- **Corrected metric — detection delay.** A poll at interval *I* sees a stop crossing ~*I*/2 late, so
  2000ms costs 0.75s more exposure than 500ms. Measured premium velocity (127,193 samples): p99
  downward **2.31%/s**, p99.9 **4.72%/s** ⇒ the extra 0.75s costs **1.74%–3.54% of premium**, and
  that understates it because quotes move faster than trades.
- **Recommendation flipped:** step 1 goes from "don't bother" to the strongest item in this doc, and
  the WS case is *strengthened* rather than weakened — streaming removes a further ~0.25s, worth
  roughly another 0.6–1.2% at the same percentiles.
- **Lesson worth keeping:** a negative result from a proxy dataset needs a resolution check *before*
  it is believed. I had the print-gap data in hand the whole time and did not look at it.

### 2026-08-17 (02:00 CDT) — reconciled against production before publishing

The spike branch had never been pushed. Reconciling it against `main` and the live cluster before
opening a PR turned up that **four of its nine TODO items had shipped** in parallel sessions while
this document still called them "not started."

- **Shipped since the last entry:** #694 + #695 (fill listener), #700 (market-data manifest, TODO 4),
  #701 (500ms premium poll, TODO 5). Verified live, not assumed: `strategy` is `Recreate` and
  `ALPACA_STOCK_FEED=sip` is declared in the Deployment env.
- **⟲ Two claims in this entry's own first draft were wrong, and checking them found finding 4.**
  I wrote that the market-data pod "started 01:47Z, 11 minutes after #701's deploy went green." The
  deploy run actually *ended* 01:48:06Z — the pod came up 10 seconds **before** it went green, not 11
  minutes after. Worse, the running container is not from that rollout at all: the node rebooted at
  06:56Z and every container re-pulled `latest`. The correct evidence for "500ms is live" is the
  **image digest** (`sha256:c21099f1`, tagged `01d1862`), not a deploy timeline. A plausible
  timeline that happens to sit next to the truth is the easiest kind of claim to publish unchecked.
- **The auth question is settled.** #694's ack line prints `status=authorized action=authenticate`
  for all three live tenants. #693 listed "binary frames vs auth" as unconfirmed; **it was binary
  frames, entirely.** Auth had been succeeding for 11 weeks while every msgpack frame was dropped on
  the floor. All three of this repo's `WebSocket.Listener`s have now been bitten by the same missing
  override — see the Systemic note; that is worth a standing check, not another postmortem.
- **The sequencing rule in TODO 5 was broken.** #694 and #701 both go into the 2026-08-17 open, so
  neither improvement will be attributable. Recording it rather than quietly hoping.
- **A trading-critical hazard is untracked** — a market-data restart orphans armed trails. Written up
  as live finding 3, and **verified in code rather than inherited from the PR body that claimed it**:
  no `@PostConstruct` in market-data, the subscribe activity completes (so Temporal never retries
  it), and all three workflow call sites are event-driven. It did not bite tonight only because all
  four open positions query `armed:false, ticksReceived:0`.
- **The node rebooted at 06:56Z** and the estate silently rolled itself onto newest `main` with no
  deploy run — live finding 4. It also means a restart can drop premium subscriptions at an hour
  nobody chose, so finding 3's "deploy pre-open" mitigation is incomplete.
- **Lesson about the spike process itself:** a living document held on an unpushed branch in a locked
  worktree stops being a plan of record within hours, because parallel sessions ship its items
  faster than it updates. The evidence — 744 doc lines and 12 scripts, including the only
  reconstruction of option premium velocity we will ever get from trade prints — existed in exactly
  one place on one disk. Push early next time; the doc's value is as a shared anchor, and it cannot
  anchor anything nobody can read.

## ⚠ Out-of-scope finding: the copytrade latency budget is 96% broker-fill wait

Traced 2026-08-16 from `audit_log` on the live cluster, prompted by the operator asking whether the
2s premium poll caused missed BTOs / weak STC fills. **It does not — it is not in that path at all.**
BTO entry limits come from `BtoPricing` (author's posted price + slippage cap, no live quote); STC
exits use a one-shot `GetOptionQuoteActivity`. The premium poll only feeds trail/chandelier.

`audit_log.subject->>'posted_at'` carries the **author's Discord message timestamp**, so true
end-to-end is measurable. Median, BTO (n=203 detect / 131 place / 89 fill):

| stage | p50 |
|---|---|
| author posts → we detect | **0.751s** (p95 1.185s) |
| detect → pre-trade checks pass | 0.847s |
| checks → order submitted | 0.464s |
| **order submitted → fill observed** | **74.366s** |
| **total** | **77.457s** (p95 92.201s) |

STC: detect 0.803s → exit requested 0.450s ≈ **1.25s to get the order out.**

**Our software is ~2s of a ~77s pipeline.** This *retracts* my earlier suggestion that porting
`chat_watcher.py`'s MutationObserver to the signal path is a big win — it would optimise <1% of the
budget. The 1.0s Discord poll is real but nearly irrelevant.

### The suspicious part

Weekly BTO fill times, June→August: **the fastest fill in every single week is ~60s. Zero fills under
60.4s across 89 fills and 11 weeks.**

```
week        n   fill_p50  fill_p95  fastest  under_5s
2026-08-10  21     79.4      87.9     60.5      0
2026-07-27  14     73.0      89.6     61.5      0
2026-06-29  11     76.9      88.3     60.8      0
...
```

A market process would sometimes fill instantly. A hard floor that never varies is a *system*
constant — and `exec.fill-listener.poll.grace-ms` is **exactly 60000**: the poller deliberately skips
orders younger than 60s, deferring to the trade-updates WebSocket. **A 60s floor is the signature of
the WS never winning, with the 30s poller silently covering for it.**

Corroborating but not conclusive: `AlpacaTradeUpdatesStream`'s `Listener` implements `onText` only —
**no `onBinary`** — which is the *identical* shape to the two bugs already found here (the June
options WS, and the stocks WS before `9ec7387`). Startup logs show `sockets_started` but **no
`authenticated` line**.

### ✅ PROVEN — and it did not need RTH after all

The exec journal carries the **broker's own** `filled_at` alongside our `last_state_at`, which
separates real fill latency from our observation lag. From `order_intent_journal` in
`exec_alpaca_live`:

| side | n | broker fill p50 | broker fill p95 | **our observe lag p50** | observe lag p95 | observed <5s |
|---|---|---|---|---|---|---|
| SELL | 129 | **0.06s** | 18.10s | **30.21s** | 30.34s | 4 |
| BUY | 47 | **0.05s** | 17.07s | **69.24s** | 89.51s | **0** |

**Orders fill in ~50 milliseconds. We find out 30–69 seconds later.**

The SELL lag is p50 30.21s / p95 30.34s — a spread of 0.13s across 129 fills. That is a timer, not a
market, and it is exactly `poll.interval-ms: 30000`. The BUY lag centres on 69s = `grace-ms: 60000`
plus up to one poll cycle. **The trade-updates WebSocket is delivering nothing; the poller discovers
every fill.**

So the 74s "fill wait" in the table above is ~100% *our* blindness, not the market.

Two consequences that matter more than the latency:

- **Positions are unprotected for 60–90s after they are actually open** — the exit path cannot arm a
  stop or trail on a position it does not know is filled. This spike has been tuning a 2s premium
  poll to protect a position that is invisible for over a minute.
- **The #686 entry re-peg fires at 30s on stale knowledge**, evaluating "am I still unfilled?"
  against a journal that has not caught up.

Filed as **[#693](https://github.com/ridopark/oh-my-tradeagent/issues/693)**. Probable cause is
`AlpacaTradeUpdatesStream.Listener` implementing `onText` with **no `onBinary`** — the identical
shape to the June options-WS bug and the pre-`9ec7387` stocks-WS bug — but which of the two (binary
frames vs auth) is still unconfirmed.

## Sources

- [Streaming Market Data (connection limits, error codes, auth)](https://docs.alpaca.markets/us/docs/streaming-market-data)
- [About Market Data API (plan comparison)](https://docs.alpaca.markets/us/docs/about-market-data-api)
- [Real-time Option Data (msgpack, endpoints, schemas)](https://docs.alpaca.markets/docs/real-time-option-data)
- [OPRA FAQs (subscriber agreement, pro/non-pro)](https://www.opraplan.com/faqs)
- In-repo: `scripts/alpaca-ws-conn-check.py` (empirical per-endpoint verification, 2026-06-20)
