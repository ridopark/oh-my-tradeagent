# Fill listener runbook

## What it is

Two cooperating components in the exec service that detect broker fills and
signal `CopytradeSignalWorkflow.onFill`:

- **`AlpacaTradeUpdatesStream`** — long-running WebSocket client subscribed to
  `wss://paper-api.alpaca.markets/stream`. Primary detector; sub-second latency.
- **`FillPoller`** — `@Scheduled` (30s default) safety net that scans the
  journal for `SUBMITTED` rows older than the grace window and asks the broker
  directly. Catches anything the WS silently misses.

Both feed the same `FillDispatcher` (journal lookup → `WorkflowClient.signal("onFill", …)`),
so dedup + idempotency live in one place.

Cross-reference: [issue #165](https://github.com/ridopark/oh-my-tradeagent/issues/165)
handles the cancel-on-filled race when the listener is absent or slow. This
runbook covers the steady-state listener path; #165's
`PositionOrphan` recon path is the last line of defence.

## Single-pod constraint

Neither component is leader-elected. The exec deployment must run at
`replicas: 1` — multi-pod would multiply broker calls and Temporal signals by
N until leader-election lands (separate follow-up).

`infra/k8s/52-exec-alpaca-paper.yaml` is pinned to `replicas: 1` with an
inline comment naming this constraint. Do not raise it without first wiring
leader election.

## Enabling per deployment

Both default to OFF in `application.yml`. Production deployments opt in
via environment variables:

```yaml
env:
  - name: EXEC_FILL_LISTENER_ENABLED
    value: "true"
  - name: EXEC_FILL_LISTENER_POLL_ENABLED
    value: "true"
```

Test profiles leave both unset → both beans are absent from the context.

## Health metrics

All emitted on the standard `/actuator/prometheus` scrape:

| Metric | Type | Meaning |
|---|---|---|
| `fill_listener_subscription_confirmed_total` | counter | Sockets that received a `listening` ack naming `trade_updates`. **Positive evidence the subscription exists.** Should reach one-per-tenant shortly after boot; short of that, the socket is authenticated but not subscribed. |
| `fill_listener_events_received_total{event="fill"\|"partial_fill"}` | counter | WS messages received (after auth+listen handshake). |
| `fill_listener_events_dispatched_total` | counter | Events handed to `FillDispatcher` after filter + dedup. |
| `fill_listener_events_dropped_dedup_total` | counter | WS reconnect-replays the LRU dedup caught. |
| `fill_listener_events_unknown_order_total` | counter | Fills whose `broker_order_id` had no matching journal row. |
| `fill_listener_signal_workflow_not_found_total` | counter | Signal arrived after workflow completed (benign). |
| `fill_listener_signal_errors_total` | counter | Non-NOT_FOUND Temporal failures. **Investigate.** |
| `fill_listener_reconnects_total` | counter | WS reconnect attempts. Steady non-zero rate → network instability. |
| `fill_listener_last_event_age_seconds` | gauge | Seconds since the most recent WS event. **+Inf before first event.** |
| `fill_listener_poll_cycles_total` | counter | Successful poller cycles. |
| `fill_listener_poll_scan_failures_total` | counter | Poller cycles where the journal scan threw. **Investigate.** |
| `fill_listener_poll_rows_scanned_total` | counter | SUBMITTED rows examined (sum across cycles). |
| `fill_listener_poll_fills_detected_total` | counter | Poller-detected FILLED rows handed to the dispatcher. |

## Triage

### "Stream is up but no fills are arriving"

Symptoms: `fill_listener_last_event_age_seconds` climbing past minutes during
market hours, `events_received_total` flat.

1. Confirm the bean is active:
   ```sh
   kubectl logs deploy/exec-alpaca-paper -n copytrade | grep "fill-listener started"
   ```
   Empty → `EXEC_FILL_LISTENER_ENABLED` not set; fix env.
2. Look for reconnect storms:
   ```sh
   kubectl logs deploy/exec-alpaca-paper -n copytrade | grep "fill-listener ws closed\|connect/run failed"
   ```
   Repeated rapid closes → Alpaca rejecting the auth frame. Check
   `APCA_API_KEY_ID` / `APCA_API_SECRET_KEY` in the `alpaca-credentials` Secret.
3. **Confirm the socket actually SUBSCRIBED, not merely authenticated** (#715):
   ```sh
   kubectl logs deploy/exec-alpaca-live -n copytrade | grep "subscription confirmed\|subscription ack\|authorization reply"
   ```
   Expect one `subscription confirmed streams=["trade_updates"]` per tenant.
   - `authorization reply status=authorized` but **no** `subscription confirmed`
     → the socket is authenticated and NOT subscribed. It will sit open and
     mute forever; reconnects will be zero and nothing will go red.
   - `authorization reply NOT authorized ... action=listen` → the `listen`
     was sent before authentication completed. Distinct failure, distinct fix.
   - `subscription ack does NOT name trade_updates` → Alpaca accepted the
     socket but not the stream (entitlement).
4. Confirm Alpaca's status page; the trade-updates stream has historical outages.
5. **Do not assume the poller is covering.** It is the intended safety net, but
   on 2026-08-17 `exec-alpaca-live` detected **0 of 15 real fills** while the WS
   was dark (#719) — both detectors failed at once and the only symptom was
   exits sized off stale state. `poll_fills_detected_total = 0` is also the
   correct value on a genuinely quiet day, so it cannot be read as health on its
   own. Verify against broker truth:
   ```sh
   # journal rows by state for today, PER TENANT (a pod-wide count hides a
   # single-tenant failure — exec-alpaca-live serves three)
   psql -d exec_alpaca_live -c "SELECT tenant_id, state, count(*) \
     FROM order_intent_journal WHERE created_at::date = CURRENT_DATE \
     GROUP BY tenant_id, state ORDER BY tenant_id;"
   ```
   Compare `poll_cycles_total` / `poll_rows_scanned_total` /
   `poll_fills_detected_total` in that order: zero cycles means the bean never
   ran; cycles with zero rows scanned means nothing is sitting in `SUBMITTED`;
   rows scanned with zero fills detected means `getOrderStatus` never returned
   `FILLED`.

### "Poller is catching too much"

If `poll_fills_detected_total > 0` for more than ~1 hour during market hours,
the WS is silently dropping events the poller is recovering. Treat as a soft
alarm — investigate the WS path even if the metric chart looks healthy.

Useful ratio in Prometheus:
```promql
rate(fill_listener_poll_fills_detected_total[5m])
  /
rate(fill_listener_events_dispatched_total[5m])
```
Steady state should be << 1%. Sustained > 5% means the WS is unreliable.

### `signal_errors_total` non-zero

Each increment is a Temporal RPC failure other than NOT_FOUND. Check exec logs
for the underlying exception:

```sh
kubectl logs deploy/exec-alpaca-paper -n copytrade | grep "fill-dispatcher.*temporal"
```

Common causes: Temporal frontend unreachable, RBAC denied, malformed payload
(record shape drift between `FillSignalPayload` and orchestrator's `FillEvent`).

### `poll_scan_failures_total` non-zero

Journal SQL is throwing. Almost always Postgres connectivity. Check the
datasource:

```sh
kubectl logs deploy/exec-alpaca-paper -n copytrade | grep "fill-poller journal scan failed"
kubectl exec -it deploy/postgres -n copytrade -- psql -U temporal -d exec_alpaca_paper -c '\d order_intent_journal'
```

### Workflow already completed (`signal_workflow_not_found_total` rate)

This is **benign by design**. A fill arriving for a `CopytradeSignalWorkflow`
that already completed (TTL expired, killed, or a prior signal already
finished it) is logged + counted, never thrown. Expect a small steady rate
proportional to TTL-expired entries.

## At-least-once contract

The listener and poller may both signal the same fill. The workflow's
`onFill` handler is idempotent by structure: it sets a single private field
once and the workflow's main path reads-and-acts on the first value through
`Workflow.await`. Subsequent signals either (a) overwrite a no-longer-read
field, or (b) hit `WorkflowNotFoundException` and are swallowed.

If `onFill`'s handler ever grows logic beyond "set a field", add a
guard or version fence — the workflow's idempotency contract is what makes
the at-least-once dispatch safe.
