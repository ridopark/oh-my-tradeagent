# PLAN — 2026-08-16 fill-listener binary frames

Alpaca's trade-updates stream delivers **binary WebSocket frames containing JSON**.
`AlpacaTradeUpdatesStream.Listener` implements `onText` only, and the JDK's default `onBinary`
silently `request(1)`s and discards. So **no fill has ever been delivered by the WebSocket**, and the
30s poller behind a 60s grace window has quietly discovered every fill for at least 11 weeks.

Measured on the live cluster 2026-08-16 (`order_intent_journal`, `exec_alpaca_live`):

| side | n | broker fill p50 | **our observe lag p50** | observe lag p95 | observed <5s |
|---|---|---|---|---|---|
| SELL | 129 | **0.06s** | **30.21s** | 30.34s | 4 |
| BUY | 47 | **0.05s** | **69.24s** | 89.51s | **0** |

**Orders fill in ~50ms. We find out 30–69s later.** The SELL lag spans 0.13s across 129 fills
(p50 30.21 → p95 30.34) — that is `poll.interval-ms: 30000`, not a market. BUY centres on 69s =
`grace-ms: 60000` + one poll cycle.

Root cause **confirmed by probe** against the paper endpoint, not inferred:

```
0. BINARY : b'{"stream":"authorization","data":{"action":"","message":"cod...'
```

The payload is JSON; only the frame *channel* is wrong. Everything downstream of `handleFrame` is
already correct.

Source: issue **#693**; investigation recorded in `docs/plans/SPIKE-options-premium-websocket.md`.

**Why this matters more than the latency:** a position is **unprotected for 60–90s after it is
actually open** — the exit path cannot arm a stop or trail on a fill it has not seen.

## This is NOT a regression — it has never worked

Checked explicitly, because it looks like something that broke:

- **`onBinary` has never existed in this repo.** `git log -S"onBinary"` across all history returns
  only today's spike docs. There is no fix to regress from.
- **Every month since the live cutover sits on the poller's timers.** No working period exists:

  | month | BUY observe p50 | SELL observe p50 |
  |---|---|---|
  | 2026-06 | 76.38s | 30.33s |
  | 2026-07 | 68.61s | 30.19s |
  | 2026-08 | 76.05s | 30.21s |

  SELL pinned to 30.19–30.33s across three months is `poll.interval-ms: 30000` every time. (Four
  SELLs did land under 5s, scattered across July and August — a different writer, not a working
  window.)

**How it shipped broken and stayed hidden.** `e6cf2bd` (#167) built the listener with the poller in
the *same* PR, and its own message says: *"Phase 1 ships transport only — the FillDispatcher bean is
a **NoopFillDispatcher**."* The transport was therefore validated against a fake that accepted
anything. Phase 2 swapped in the real dispatcher but did not re-verify the transport against a live
Alpaca socket — and the fallback shipped alongside it meant fills kept arriving, just late. No error,
no reconnect (`fill_listener_reconnects_total` = `0.0`), no alert.

**Several later fixes touched fills but all sat downstream of receipt** — `9b600dc` (#562, route
entry fills by intent-key prefix), `8adc5ba` (stop losing a dropped fill silently), `834bb99` (make
flatten fill-await broker-authoritative). Each fixed a real bug. None touched the transport, and some
read in hindsight as compensating for a fill stream that was always a minute stale.

The design lesson belongs in Phase 2: **a fallback that silently covers for a broken primary is
indistinguishable from a working system until someone measures the primary.**

---

## P0 — Immediate operational (no code; operator)

- **None required.** The poller is a *correct* fallback, so nothing is broken or at risk right now —
  only late. This is why it survived 11 weeks unnoticed. No cleanup, no blocked account, no manual
  intervention.
- **Optional stopgap, operator's call:** lowering `EXEC_FILL_LISTENER_POLL_GRACE_MS` from 60000 to
  ~10000 trades broker rate budget for 50s of blindness on every fill, and would help immediately
  without waiting for Phase 1. Not recommended if Phase 1 ships promptly — it is churn on a path
  Phase 1 makes moot.

---

## Phase 1 — receive the frames (exec)

**Goal:** implement `onBinary` so trade-update frames reach the existing, already-correct parser.

**Changes** (anchors, all `services/exec/src/main/java/com/ohmytradeagent/exec/fill/AlpacaTradeUpdatesStream.java`):

- `:585` `Listener` — add an `onBinary(WebSocket, ByteBuffer, boolean last)` override that mirrors
  `onText` (`:601-620`): accumulate, and on `last` hand the assembled frame to
  `owner.handleFrame(String)` (`:512`), then `webSocket.request(1)`.
- `:588` `partialFrame` — a `StringBuilder` cannot back the binary path. **Accumulate BYTES, not
  chars:** a UTF-8 multi-byte sequence can split across fragments, so decoding each fragment
  independently corrupts the boundary. Use a separate `ByteArrayOutputStream` (or `ByteBuffer`) and
  decode **once** on `last` with `StandardCharsets.UTF_8`.
- Keep `onText` unchanged and route both into `handleFrame`. Alpaca's channel choice is not
  contractual and paper/live may differ; handling both is one line and removes the whole class.
- Enforce `MAX_FRAME_BYTES` (`:79`) on the byte accumulator exactly as `onText` does at `:602`,
  including the `webSocket.abort()` + `closed.countDown()` recovery.
- **Log the handshake reply.** Today `handleFrame` (`:521-523`) silently drops any frame whose
  `stream != trade_updates`, which is *why the failure was invisible*: the `authorization` ack was
  discarded even when it did arrive. Add an INFO for `stream == "authorization"` mirroring the
  stocks-WS `authenticated` line from `9ec7387`. This is the diagnostic whose absence cost 11 weeks.

**Version gate:** **NONE.** This is exec listener code, not workflow code — it issues no Temporal
command and no workflow history changes. `FillDispatcherImpl` already signals through the existing
path.

**Tests (TDD)** — `services/exec/src/test/java/com/ohmytradeagent/exec/fill/AlpacaTradeUpdatesStreamTest.java`:

- `binaryFrame_isParsedAndDispatched` — **the incident reproduction.** Feed the exact byte payload
  `{"stream":"trade_updates","data":{"event":"fill","order":{...}}}` via `onBinary` and assert
  `FillDispatcher.dispatch` is called. **Must fail before the change.**
- `binaryFrame_splitAcrossFragments_isReassembled` — deliver in 2+ fragments with `last=false` then
  `last=true`; assert one dispatch with intact fields.
- `binaryFrame_splitMidUtf8Sequence_doesNotCorrupt` — split a multi-byte character across the
  fragment boundary. This is the test that fails a naive per-fragment `new String(bytes)`
  implementation, and it is the reason the accumulator must be byte-based.
- `binaryFrame_exceedingMaxBytes_abortsSocket` — parity with the existing `onText` guard.
- `textFrame_stillParsedAndDispatched` — proves the existing path is untouched.
- `authorizationFrame_isLogged` — the missing-diagnostic fix.

**Verify / success criteria:**
`mvn -pl services/exec -am spotless:apply && mvn -pl services/exec -am test`.
Behavioral assertion: a binary frame carrying `event=fill` produces exactly one
`FillDispatcher.dispatch` with the correct `broker_order_id`, `filled_qty`, `avg_fill_price`; the
same payload delivered as text produces exactly one dispatch as well (no double-dispatch).

**⚠ Flag for the implementer — a dead code path wakes up.** `FillDispatcher` is documented as "the
single dispatch path so the WebSocket listener and the polling fallback share dedup + idempotency",
and `FillDispatcherImpl:50` states an at-least-once contract with idempotent `onFill`. That design is
sound — but with the WS silent for 11 weeks, **the WS×poll dedup has never actually been exercised in
production.** Phase 1 turns it on. Assert cross-source dedup explicitly (same
`broker_order_id|filled_qty` arriving via WS and then via poll ⇒ one dispatch) rather than trusting
the javadoc.

---

## Phase 2 — alert on the invariant, not the socket (exec)

**Goal:** make this class of silent failure impossible to sit undetected for 11 weeks again.

A connected-but-mute socket is exactly what happened: no error, no reconnect
(`fill_listener_reconnects_total` was `0.0`), no alert. Monitoring the socket would not have caught
it. **Monitoring the relationship between the two sources would have**, on day one.

**Changes** (anchors):

- `services/exec/src/main/java/com/ohmytradeagent/exec/fill/FillListenerMetrics.java` — the existing
  `fill_listener.last_event_age_seconds` gauge (`:101-102`) is the right primitive but insufficient
  alone: it is `+Inf` on a quiet weekend and on a dead socket alike. Add a gauge for the **invariant**:
  poll-sourced fills observed while WS-sourced fills remain zero.
- Emit a WARN (and an audit/alert kind if the operator wants paging) when
  `poll_fills_detected > 0 && events_received{fill,partial_fill} == 0` **within a trading session** —
  the poller finding fills the socket never reported is, by construction, a mute socket.

**New audit kind:** if this pages, register it in
`services/audit/src/main/java/com/ohmytradeagent/audit/AuditEventKinds.java` `ALL_KINDS` — the
pre-push `KindRegistryGuardTest` blocks otherwise — and add it to `OrderFailureAlerter`
`DEFAULT_FAILURE_KINDS` **plus the `application.yml` image default** (env is unset on homelab and not
applied by deploy).

**Version gate:** NONE (exec-side, no workflow commands).

**Tests (TDD):**
- `pollFindsFillsWhileWsSilent_raisesAlert` — the incident, as a monitoring assertion.
- `bothSourcesDelivering_noAlert`.
- `quietSession_noAlert` — a weekend with zero fills from either source must not page. This is the
  case that makes a naive "last_event_age" alert useless.

**Verify / success criteria:**
`mvn -pl services/exec -am spotless:apply && mvn -pl services/exec,services/audit -am test`.
Behavioral assertion: simulate 11 weeks of the observed production shape (poll fills > 0, WS fills
== 0) ⇒ alert raised on the first session, not never.

---

## Ship order & gating

1. **Phase 1** (exec listener, isolated, no version gate, no config change) — fixes the defect.
2. **Phase 2** (exec metrics/alerting) — prevents the recurrence, and is only meaningful once
   Phase 1 gives the WS a chance to succeed.

Each: TDD, `mvn -pl <touched modules> -am spotless:apply` before commit, its own PR, operator merge
gate (trading-critical path — fills drive every position workflow). `Closes #693` on Phase 1.

No Temporal version gate, no `contract/schemas` change, no `tenants/dev/*.yaml` change, and therefore
**no ConfigMap drift re-sync** and no live-tenant out-of-band edit. `KillSwitchWorkflowImplTest` is a
known flake — re-run, do not fix.

## Operator follow-ups (not code phases)

- **Confirm the fix in production during an RTH session** — the one measurement that proves it:
  ```bash
  kubectl -n copytrade exec deploy/exec-alpaca-live -- wget -qO- localhost:8080/actuator/prometheus \
    | grep -E 'fill_listener_(events_received|poll_fills_detected)'
  ```
  Success = `events_received{event="fill"}` climbing and `poll_fills_detected` staying near zero —
  the exact inverse of today.
- **Re-run the lag query after a session** to confirm the p50 collapses from 30–69s to sub-second:
  `scripts/research/fill_observation_lag.sql`.
- **Re-check the #686 entry re-peg** once fills are real-time. Its 30s timer has been evaluating
  "am I still unfilled?" against a journal running 69s behind; the re-peg's behaviour under *accurate*
  fill state has never been observed. Not a defect found here — an assumption worth revisiting.
- `exec-alpaca-paper` gets the same fix from the same image; no separate action.
