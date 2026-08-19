# PLAN — 2026-08-19 recover a starved trade-updates socket (#715)

Eleven weeks of "the WebSocket delivers nothing" resolved on 2026-08-19 to a single mechanism,
demonstrated end to end:

**A trade-updates socket stops being fed while remaining open, and nothing reconnects it.**

## The evidence

| observation | value |
|---|---|
| fresh socket, probes at 10m → 385m | **delivering** (8 probes, `ws_callbacks` +2 each) |
| same socket at 416m, then 2 confirmations | **silent** (`38 → 38 → 38`) while orders were accepted+cancelled at the broker |
| `reconnects_total` throughout | **0** — no close frame, no error frame, no exception |
| after `rollout restart`, fresh socket | **delivering immediately** (`4 → 7` on the first probe) |

Same account, same image, same broker, minutes apart. The only variable was the connection.

A second socket (established 23:58:17Z) was already silent at **19 minutes** old, so **this is not an age
threshold** — it is an event that stops the feed. The consequence is identical either way, because
nothing ever notices and nothing ever reconnects.

Raw frames captured from a standalone client confirm delivery is otherwise healthy: `opcode=2`
(binary), `FIN=true`, ~1KB, carrying `pending_new` / `new` / `canceled` with the full order object.

## Why it never self-healed

`runForever` (`AlpacaTradeUpdatesStream:504-530`) reconnects only when `connectAndRun()` returns or
throws — i.e. on **close or error**. A starved socket produces neither, so the loop blocks in
`closed.await()` for the life of the pod. `reconnects_total = 0` was read as proof of health in every
prior investigation, including the support email; it is in fact the fault.

## What it costs today

Fills are still booked, ~30s late, by the workflow's bounded await → `getOrderStatus` reconcile
(`pending_ttl_live_secs = 30`). Measured lag: **30.2s, near-constant**. Nothing is lost; everything
is slow. That 30-second window is what let the 2026-08-17 flatten size itself against stale state and
request 35 contracts against a position of 24 (#716).

---

## Phase 1 — force a reconnect (the fix)

**The plumbing already exists.** `connectAndRun` blocks on `closed.await()`; the oversize-abort path
already does `webSocket.abort(); closed.countDown();`, and `currentSocket` is held per runner. Closing
the socket makes `runForever` reconnect on its own, and `metrics.recordReconnect()` makes it visible.

**The hard part is the trigger, because silence is ambiguous.** A quiet socket and a starved socket
look identical: no orders means no events, legitimately. Three options were considered:

| trigger | verdict |
|---|---|
| WebSocket ping/pong liveness | **rejected** — the JDK answers pings internally and a live transport says nothing about whether the *event feed* is attached. That is precisely the failure: transport up, feed detached. |
| staleness timer on `last_event_age_seconds` | **rejected alone** — indistinguishable from a genuinely quiet market; would reconnect constantly overnight, or need a threshold so high it misses the 19-minute case. |
| **proactive periodic reconnect** | **chosen** — no detection required, no false positives, and it matches the demonstrated behaviour exactly: a fresh socket works. |

**Design:**
- Cycle each tenant's socket on a configurable interval (`exec.fill-listener.recycle-interval-ms`),
  default well under the shortest observed death (19 min) — **suggest 10 minutes**, tunable to 0 to
  disable.
- **Stagger per tenant** so three live sockets never reconnect together and leave a window with no
  listener at all. Offset by a hash of the tenant id.
- Reconnect is cheap: one TLS handshake plus `authenticate` + `listen`. There is no subscription
  state to rebuild.

**Accepted cost, stated rather than discovered:** events occurring inside the sub-second reconnect
window are missed. That is bounded and already covered — the 30s reconcile and the poller both remain.
Worst case for one order is today's behaviour. Do **not** remove either fallback: they are why eleven
weeks of a dead socket cost latency instead of money.

**Tests:** a socket cycled by the scheduler reconnects and resumes delivering; cycling is staggered
across tenants; `recycle-interval-ms=0` disables it; a cycle during an in-flight fragment does not
corrupt the accumulator. Falsify each.

## Phase 2 — an order-correlated starvation alarm

Phase 1 makes starvation self-correcting but hides how often it happens. This measures it.

We know when we place an order. If an order reaches a terminal state via the reconcile/poller and
**no WS event was seen for it**, the socket was starved. That is a definitive signal with no false
positives — unlike a bare staleness timer — and it yields the real failure rate.

Emit a counter and a WARN. This is how we learn whether Phase 1's interval is right, and whether the
underlying behaviour changes on Alpaca's side.

## Phase 3 — hygiene found while capturing frames

The authorization reply has carried this on **every connection for eleven weeks**, discarded because
the handler logs only `status` and `action`:

```
"message":"this authentication format is being deprecated. Please use the format:
           {\"action\": \"auth\", \"key\": \"x\", \"secret\": \"x\"}"
```

- Switch the handshake to `{"action":"auth","key":…,"secret":…}`.
- **Log the full authorization payload**, not two extracted fields. A deprecation notice sat in front
  of us for eleven weeks on a socket that was already failing silently.

Independent of the starvation bug, but a deprecated auth path on this connection is exactly the kind
of thing that turns into the next silent failure.

---

## What this buys

| | today | after |
|---|---|---|
| fill observation | **~30.2s** (TTL-bound, near-constant) | **~50ms** |
| position unprotected after entry | up to 30s — a stop cannot be armed on an unseen fill | sub-second |
| #716 race window | 30s | ~50ms (**~600× narrower**) |
| `PartialExitFillTimeout` → retry churn | every exit | rare |
| starved socket | invisible, permanent | self-correcting, counted |

The headline is not the latency number — it is that **the 30-second window is the precondition for
#716**, where a flatten sized itself against state that was correct 30 seconds ago. The reduce-only
clamp already blocks the catastrophic outcome; this removes most of the opportunity for it to arise.

## Ship order

1. **Phase 1** — the fix. Smallest change that ends the failure.
2. **Phase 3** — trivial, and prevents a future silent break.
3. **Phase 2** — measurement, once the bleeding has stopped.

Each its own PR, spotless on every touched module, operator merge gate. **`exec-alpaca-live` is
excluded from CI deploy** and needs a manual `kubectl rollout restart`, done flat or market-closed.
