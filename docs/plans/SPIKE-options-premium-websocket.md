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

1. **Entitlement** — is the data key entitled to `opra`, and does the redistribution question below
   have an answer from Alpaca? *(open)*
2. **One decoded quote** — a real OPRA option quote received and decoded from the WS. The June 2026
   attempt never got a single frame; this is the gate everything else waits behind. *(open)*
3. **Does the socket beat a 500ms OPRA poll?** Measured in shadow, not argued. The honest possible
   answer is "no." *(open)*
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

| | Basic (we are here) | Algo Trader Plus ($99/mo) |
|---|---|---|
| options feed | `indicative` | **`opra`** |
| stocks feed | IEX | all US exchanges (SIP) |
| REST data | **200 req/min** | **10,000 req/min** |
| options WS symbols | 200 quotes | **1000 quotes** |
| equity WS symbols | 30 | unlimited |
| **connections per endpoint** | **1** | **1** |

We are on **Basic**: `application.yml:23` defaults to `.../v1beta1/indicative`, and the poll interval
is explicitly "sized against Alpaca's data-REST limit (~200 req/min)".

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

## ⚠ A live bug this research exposed (unrelated to options)

`infra/k8s/53-market-data.yaml` has **no `strategy:` block**, so it defaults to `RollingUpdate` with
`maxSurge` 25% → **1** at `replicas: 1`. The new pod starts **before** the old terminates, so every
deploy briefly runs two pods against the same data endpoint — a guaranteed 406 collision on the
**stocks WS that is live today**. `strategy: {type: Recreate}` is the fix, and it is worth doing
**whether or not this spike proceeds**.

## ⟲ Retraction — the REST-ceiling argument was wrong

Originally listed: "the REST budget caps you at ~6 concurrently-trailed contracts, and only streaming
can raise it." **Wrong.** Algo Trader Plus takes REST from 200 → **10,000 req/min**, which at
30 req/min/contract supports **~330 concurrently-polled contracts**. Buying OPRA raises the *polling*
ceiling ~55× by itself.

This **separates two decisions that looked like one**:

1. **Buy Algo Trader Plus.** Real OPRA prices, ~6-contract ceiling gone, and — REST no longer being
   scarce — the poll interval can simply drop 2s → ~500ms for a 4× latency win. **Zero new code, zero
   new failure modes, reversible by one config value.**
2. **Then, separately, switch to the WS.** Sub-second granularity, no resample artifact, honest
   silence detection. Costs a msgpack dependency, a binary frame path, a compact/padded symbol map, a
   permanent single-replica pin, and 407 backpressure risk.

Decision 1 delivers most of the value at a fraction of the risk and is a prerequisite for 2 anyway.

## The price-quality argument (independent of transport)

Per `reference_premium_tick_mid_vs_bid`, the exit path **trades on the bid**. Today the trail is armed
off an **indicative** bid on the display path and a REST snapshot on the exit path. OPRA improves both.
This is a correctness argument for the *purchase*, and it holds even if the socket never ships.

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

**Step 0 — free, do it regardless.** `strategy: {type: Recreate}` on the market-data Deployment. Fixes
a live 406 collision on the existing stocks WS at every deploy. *(not started)*

**Step 1 — buy Algo Trader Plus, change no code.** Resolve the entitlement question first. Then point
the feeds at `opra` and drop `premium-poll-interval-ms` 2000 → ~500. Measure what that alone does.
Reversible by one config value. *(blocked on entitlement answer)*

**Step 2 — probe, no product code.** Re-run `scripts/alpaca-ws-conn-check.py` to confirm `/v1beta1`
auth and OPRA entitlement, then extend it to `subscribe` one live OCC and print decoded quotes.
**⚠ Do not run during RTH:** steps B and C deliberately open competing connections and will 406 or kick
the live stocks WS that watchlist triggers depend on. *(not started)*

**Step 3 — decode-only, dark.** msgpack + `onBinary` + `dispatchOptionWsMessage` + tests against
captured frames. No subscriber wiring, no behaviour. *(not started)*

**Step 4 — shadow.** WS alongside the poll, fan out nothing, log divergence: rate, latency, bid
agreement, any 407. Produces the data to re-tune the 35% band and answers exit criterion 3.
*(not started)*

**Step 5 — cut over per-tenant**, poll retained as fallback. *(not started)*

Steps 0–4 are reversible and touch no live exit path. **Step 5 is trading-critical.**

## Open questions

- **For Alpaca:** does one pod-global OPRA key feeding premium into *other tenants'* dashboards count
  as redistribution, or flip the classification to professional? OPRA requires a signed Subscriber
  Agreement classifying each recipient professional/non-professional. We hold one pod-global data key
  (`replicas: 1`), and `/live` **displays** option premium to tenant users (`ccdd073`) — and
  `prod-kipark` / `prod-jinchul` are **other people's** accounts. Today that data is `indicative`
  (derived, free), so it's uninteresting; real-time OPRA changes the question. Ask before paying.
- **Is the socket still worth it after a 500ms OPRA poll?** Genuinely open. Step 4 answers it.
- Is 2s latency demonstrably costing money today, or is this a correctness/complexity win only?

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

## Sources

- [Streaming Market Data (connection limits, error codes, auth)](https://docs.alpaca.markets/us/docs/streaming-market-data)
- [About Market Data API (plan comparison)](https://docs.alpaca.markets/us/docs/about-market-data-api)
- [Real-time Option Data (msgpack, endpoints, schemas)](https://docs.alpaca.markets/docs/real-time-option-data)
- [OPRA FAQs (subscriber agreement, pro/non-pro)](https://www.opraplan.com/faqs)
- In-repo: `scripts/alpaca-ws-conn-check.py` (empirical per-endpoint verification, 2026-06-20)
