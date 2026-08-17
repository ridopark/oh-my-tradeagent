# PLAN — 2026-08-17 fill-listener subscription (issue #715)

The Alpaca trade-updates WebSocket authenticates and then receives **zero** `trade_updates` frames on
both exec pods. #693 found `onBinary` missing; #694 fixed it and added authorization-reply logging.
Frames now arrive and the auth reply parses — but not one trade-update of any kind has reached either
pod. The socket is open, authorized, and mute.

Source: issue #715. Code re-read 2026-08-17 against
`services/exec/src/main/java/com/ohmytradeagent/exec/fill/AlpacaTradeUpdatesStream.java` @ `b2b921f`.

---

## What the code confirms

**1. The handshake does send both frames back to back** — `AlpacaTradeUpdatesStream.java:484-485`:

```java
sendAuth(ws, endpoint);
sendListen(ws);
```

`sendTextWithTimeout` (`:510-519`) blocks on `ws.sendText(...).toCompletableFuture().get(...)`, which
completes when the frame is **written locally**, not when the server acks. So `listen` genuinely goes
out microseconds after `authenticate`, while the observed authorization reply took ~1.2s.

**2. The frame that would name the cause is discarded** — `handleFrame:563-565`:

```java
if (!"trade_updates".equals(streamNode.asText())) {
  return;
}
```

Anything that is not `authorization` or `trade_updates` is dropped silently. That includes Alpaca's
`{"stream":"listening","data":{"streams":[...]}}` confirmation **and any error reply to a rejected
`listen`**. This is the same blind spot #693 diagnosed for the auth frame, left in place one step
downstream — and it is why the root cause is still unconfirmed.

**3. The issue's inference from `last_event_age_seconds = +Inf` is sound.** `metrics.markEvent()` is
called at `:571`, *before* the fill/partial_fill filter, so it covers every `trade_updates` frame of
every event type. (`recordReceived` at `:572` only has counters for `fill`/`partial_fill` —
`FillListenerMetrics:44-52` pre-registers exactly those two and `recordReceived` no-ops otherwise — so
the `events_received` series alone could not have proven this. The gauge could, and did.)

**4. Both pods run per-tenant mode against distinct accounts**, `reconnects_total = 0` on both.

## The protocol, established 2026-08-17

From Alpaca's official docs (<https://docs.alpaca.markets/us/docs/websocket-streaming.md>) and the
reference client `alpaca-trade-api-python` (`alpaca_trade_api/stream.py`):

- A successful `listen` is acked with exactly
  `{"stream":"listening","data":{"streams":["trade_updates"]}}`.
- **A `listen` sent before the authorization reply is answered on the `authorization` stream**, not
  the `listening` one: `{"stream":"authorization","data":{"status":"unauthorized","action":"listen"}}`.
  Docs verbatim: *"In the case that the socket connection is not authorized yet, a new message under
  the authorization stream is issued in response to the listen request."*
- An empty `streams: []` in a `listening` ack is a real documented state, but it means the requested
  streams were **not available/entitled** — a distinct, non-overlapping failure from the pre-auth
  race. An earlier draft of this plan conflated the two; they must not share one warning.
- `TradingStream._auth()` blocks on `await self._ws.recv()` and raises unless
  `status == "authorized"` before `_subscribe_trade_updates()` is ever called. The reference client
  treats listen-before-auth as a bug and structurally prevents it.

## H1's documented signature is ABSENT on the cluster

`handleFrame`'s `authorization` branch (`:539-561`) logs **every** frame on that stream, including
the else-branch WARN that carries `action=`. If the pre-auth rejection had occurred, it would already
be in the logs. It is not. All five sockets across both pods, 24h window:

```
fill-listener[prod_real]      authorization reply status=authorized action=authenticate
fill-listener[prod-kipark]    authorization reply status=authorized action=authenticate
fill-listener[prod-jinchul]   authorization reply status=authorized action=authenticate
fill-listener[staging_paper]  authorization reply status=authorized action=authenticate
fill-listener[paper_jinchiul] authorization reply status=authorized action=authenticate
```

Exactly one `authorization` frame each, all `action=authenticate`. **No `action=listen` rejection.**
No `ws closed`, no `connect/run failed`, no reconnect — the sockets opened at 06:56 and sat open and
mute for ~16h.

## What remains

- **H1a — the server silently dropped the pre-auth `listen`** without issuing the documented
  rejection. The docs describe intent; the implementation may simply ignore it. Still the race, with
  a different observable.
- **H2 — the subscription succeeded and Alpaca delivered nothing.** The `listening` ack would have
  been discarded at `:563-565`, so this is invisible today.
- **H3 — connection contention.** Alpaca's Python README states a one-connection-per-account limit,
  but does not say whether a second connection is refused, evicts the first, or leaves both mute, and
  does not confirm it governs trade_updates specifically. `reconnects_total = 0` and three distinct
  live accounts argue against it. Low, and **unverified** rather than excluded.

**Phase 1 is the sole remaining discriminator.** A `listening` ack naming `trade_updates` proves H2;
no `listening` frame at all proves H1a and makes Phase 2 the fix.

**Do not probe this by hand.** Opening a second trade-updates socket for a live account risks
displacing the pod's own listener on a real-money path. The evidence must come from the pod.

---

## P0 — separate defect, needs forensics before it needs code

On live, `poll_fills_detected_total = 0` against **15 real fills**; paper detected 7 of 9. The WS
being dark is survivable *because* the poller is the backstop — on live neither worked, which is what
let two tenants size an exit off stale state. #715 mentions this only in passing.

`FillPoller` scans `state='SUBMITTED'` rows (`FillPoller.java:102-103`). First query:

```sql
SELECT state, count(*) FROM order_intent_journal
WHERE tenant_id = 'prod_real' AND created_at::date = '2026-08-17' GROUP BY state;
```

If live fills sat in `RECORDED` (the known 422 zombie class) or moved to a terminal state by another
path before a poll cycle, the poller never saw them. **This is not part of the phases below** — it is
a distinct failure needing its own investigation and, likely, its own issue.

---

## Phase 1 — log the subscription confirmation (services/exec)

**Goal:** make a mute-but-authorized socket distinguishable from a healthy one. **No behaviour
change to the handshake.**

**Changes** (anchors):
- `AlpacaTradeUpdatesStream.java:563` — before the `trade_updates` filter, handle
  `"listening"`: INFO naming the echoed `data.streams` when it contains `trade_updates`, and **WARN
  when it does not** — Alpaca echoes the *effective* subscription, so a list without
  `trade_updates` (including `[]`) is an unentitled/unavailable stream and must not read as success.
- Same anchor — log any other unrecognised `stream` value at WARN, so a future server-side reply is
  never silently eaten again. `handleFrame` runs on the WS reader thread and `TenantRunner` is
  per-tenant, so per-socket damping state is available if the log proves noisy.
- `FillListenerMetrics` — add `fill_listener.subscription_confirmed` counter, registered eagerly
  alongside the others.

**Tests (TDD):** in `AlpacaTradeUpdatesStreamTest` (uses the existing in-process
`RecordingWsServer`, and the established `attachLogCapture` / `awaitLog` / `detachLogCapture` idiom
at `:469-497` — mirror the three existing `authorization`-frame tests at `:405-467`) —
- `listeningConfirmationIsLoggedAtInfo` — broadcast `{"stream":"listening","data":{"streams":["trade_updates"]}}`, assert INFO + counter.
- `listeningWithoutTradeUpdatesIsLoggedAtWarn` — `streams: []` → WARN, counter **not** bumped.
- `unrecognisedStreamIsLoggedAtWarn`.

- `preAuthListenRejectionIsLoggedAtWarn` — `{"stream":"authorization","data":{"status":"unauthorized","action":"listen"}}`
  → WARN naming `action=listen`. An earlier draft of this plan called this test unnecessary on the
  grounds that the existing `authorization` branch already handles the shape. That was wrong: the
  branch handles it, but **no test pinned it**, and the two existing authorization tests cover only
  `action=authenticate`. `action` is the sole field separating "listen sent too early" (a race) from
  "bad credentials", the absence of this line on the cluster is what refuted the race, and nothing
  stopped a future refactor from dropping the field. Pinning the discriminator is what makes that
  reasoning repeatable.

**Verify:** `mvn -pl services/exec -am spotless:apply && mvn -pl services/exec -am test`.
**Behavioural assertion:** after deploy, each tenant logs either a `listening` line or nothing —
and *which* one it is decides whether Phase 2 is the fix or a red herring.

**No Temporal version gate.** This class is a Spring component outside workflow history.

---

## Phase 2 — await authorization before subscribing (services/exec)

**Goal:** send `listen` only on an authorized connection, and fail loudly rather than sit mute.

**Changes** (anchors):
- `AlpacaTradeUpdatesStream.java:484-485` — between `sendAuth` and `sendListen`, await an
  authorization outcome on a per-connection latch counted down from `handleFrame`'s `authorization`
  branch (`:539-561`).
- The latch must also be released by `onClose` (`:705`) and `onError` (`:712`), or a socket that dies
  mid-handshake blocks the runner thread for the whole timeout.
- On timeout **or** a not-authorized status: `ws.abort()` and throw, so `runForever`'s existing
  `catch (RuntimeException)` (`:453`) applies backoff and reconnects. A mute socket must not be a
  stable resting state.
- Keep the wait on the runner thread (`connectAndRun` already owns it). **Do not** send `listen` from
  `handleFrame` — that runs on the WS reader thread and `sendTextWithTimeout` blocks.

**Fixture change this forces — the main hidden cost of this phase.** `RecordingWsServer.onMessage`
(`AlpacaTradeUpdatesStreamTest:945-947`) only records; it never replies. Gating `listen` on the auth
reply hangs **every** existing handshake test — `sendsAuthAndListenFramesOnConnect`,
`reconnectsAfterServerClose`, `backoffResetsAfterSuccessfulConnection`,
`binaryFrameExceedingMaxBytesAbortsSocketAndReconnects`,
`perTenantOpensOneSocketPerTenantWithThatTenantsCreds`, and the `awaitHandshake()` helper
(`:907-913`). The fixture must auto-reply `authorization/authorized` on receiving `authenticate`.
Note that `sendsAuthAndListenFramesOnConnect` currently **encodes the bug** and must be rewritten.

**Tests (TDD):**
- `listenIsNotSentUntilAuthorizationReplyArrives` — fixture withholds the reply; assert only ONE
  frame arrives, then release and assert `listen` follows. **This is the incident reproduction** and
  it must fail against today's code.
- `authorizationTimeoutAbortsAndReconnects`.
- `notAuthorizedReplyAbortsRatherThanSubscribing`.
- `tradeUpdateAfterOrderedHandshakeReachesDispatcher` — fixture honours `listen` only post-auth
  (Alpaca's presumed state machine), proving end-to-end delivery.

**Verify:** same commands. **Behavioural assertion:** with the auth-gating fixture, a `trade_updates`
broadcast reaches `FillDispatcher`; without the fix, it does not.

---

## Phase 3 — staleness alert (conditional, services/exec)

**Only if Phases 1-2 leave a residual silent-failure mode.** `fill_listener_last_event_age_seconds`
at `+Inf` across a full RTH session with fills in the journal is a hard anomaly and should page.
Prefer a Grafana alert on the existing gauge over new code. If code is needed, a new audit kind must
be registered in `AuditEventKinds.ALL_KINDS` or the pre-push `KindRegistryGuardTest` blocks the push.

---

## Ship order & gating

1. **Phase 1** — observability only, zero behaviour change, safe to roll mid-session.
2. **Phase 2** — handshake state machine on the real-money fill path.

**Phase 1 first, deliberately: it can invalidate Phase 2.** If the pods come back logging
`listening streams=["trade_updates"]`, the subscription was live all along and Phase 2 would be a
change to the real-money handshake that fixes nothing. That evidence costs one extra roll of
`exec-alpaca-live`.

**Phase 2 is HELD as of 2026-08-17** and was deliberately not implemented in the first execution run.
The cluster read above refuted the *documented* signature of the race, leaving H1a (silent drop) and
H2 open. Writing a handshake state machine for a real-money fill path on an unproven mechanism is the
thing this plan's ordering exists to prevent. Phase 2 resumes only if Phase 1 shows **no `listening`
frame at all**.

Each: own PR, `spotless:apply` on `services/exec`, operator merge gate.

## Operator follow-ups (not code phases)

- **`exec-alpaca-live` needs a MANUAL roll.** It is not picked up by the normal deploy path; a merge
  alone will not deliver either phase.
- Deploy **outside RTH**. Phase 2 changes reconnect behaviour on the live fill path.
- Confirm the three live sockets are three genuinely distinct Alpaca accounts (847309116 /
  313392388 / prod-kipark's •6593) — this is what rules H3 out rather than merely arguing it down.
- Re-check `poll_fills_detected` on live per the P0 section above; it is the more urgent of the two
  failures and is not addressed by any phase here.
