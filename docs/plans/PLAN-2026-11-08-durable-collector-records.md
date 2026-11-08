# PLAN — 2026-11-08 durable-collector-records

Source: GitHub issue #1044. Branch `fix/1044-durable-collector-records`, base `fb32a188`.
Planner: Fable 5.1 (mp-planner). Builder: Sonnet 4.6, one step per fresh context, test-first at the
seams named below. Operator rule for this flow: no approval gate; stop only on a failure-checklist hit.

## Goal

Both collectors keep a record that survives pod replacement: a JSONL run log on a persistent volume that
holds every cycle / metrics line, every session summary, and every connect, auth, subscribe and
archive-write failure; dark-pool cursors live on a PVC so a deploy needs no manual seeding; the flow
collector's next session (Monday 2026-11-09) is either recorded or explained, and a dead-man alerts if it
is neither.

**Done when:**
- Monday 2026-11-09 at 09:40 ET: `kubectl -n copytrade exec deploy/flow-collector -- tail -n 3 /app/archive/run.log`
  prints a JSON line whose `msg` starts with `flow metrics:` and whose `ts` is within 2 minutes; and
  `kubectl -n copytrade exec deploy/flow-collector -- stat -c %s /app/archive/2026-11-09.trades.ndjson.gz`
  prints a number > 0 — OR `~/scripts/flow_check.sh` (cron 09:40 ET) has posted a red alert to the
  staging_paper Discord channel naming the failed check, and `run.log` contains the WARNING/ERROR lines
  that say why (`flow: slot held`, `flow: disconnected`, `flow: transport error`, `flow collector crashed`, …).
- Monday after 16:25 ET: `/app/archive/2026-11-09.manifest.json` exists (even for a zero-trade session,
  with `trades: 0` and the gap reasons) and `run.log` contains a `flow session summary:` line.
- `kubectl -n copytrade exec deploy/darkpool-collector -- tail -n 3 /app/state/run.log` prints
  `darkpool cycle:` JSON lines during the 04:00–20:00 ET window; after a market-closed
  `kubectl -n copytrade rollout restart deploy/darkpool-collector` the new pod logs
  `darkpool cursor state loaded for 59 symbols` with no `kubectl cp`.
- `DRY_RUN=1 ~/scripts/darkpool_check.sh` on the homelab still prints `OK` inside the poll window after
  the roll (the dead-man keeps reading the pod log's `darkpool cycle:` lines, which remain).
- `cd services/signal-source-discord && uv run pytest -q && uv run ruff check .` pass.

## Non-goals

- Flow Step 2 (BFF posting, `FLOW_DRY_RUN=false`, lowering the 400 Mi limit) — stays as planned in
  `PLAN-2026-10-29-flow-page.md`.
- Rewriting or adding `darkpool_check.sh` to the repo (it lives only on the homelab and keeps working
  unchanged); any central logging (Loki/Prometheus); archive offload automation.
- A new reconnect strategy, quote-set changes, or any change to what the collector posts.
- Proving the cause of the silent week after the fact. It is not recoverable (see Investigation); the
  deliverable is that Monday's session is diagnosable.
- Touching `.github/workflows/*` (deploy.yml already applies manifests 66 and 67 — see Repo gates).

## Spec

### Problem Statement

The operator ran the flow collector's Step 1 measurement for a week and got nothing: the archive PVC is
empty, and the only other record (the pod log) vanished when the 11-08 sidecar roll replaced the pod. No
alert fired because the dead-man was deferred. The dark-pool collector keeps its cursors in an emptyDir, so
every roll forgets its position and the operator had to copy the file off and back by hand (#1041); its
cycle lines, which the dead-man reads, also die with the pod. In both cases the information needed to
run and to diagnose the collectors lives in the one place that is lost on every deploy.

### Solution

Each collector appends one JSON line per log record to `run.log` on a persistent volume (flow: the
existing `flow-archive` PVC at `/app/archive/run.log`; dark pool: a new small `darkpool-state` PVC
mounted at `/app/state`, which also makes `cursors.json` durable). Size-capped with simple rotation.
The flow collector logs every stage of a session (connected, authenticated, subscription acknowledged,
first trade, first archive flush, manifest written) at INFO and every failure at WARNING/ERROR,
including process crashes, so a zero-byte day leaves a readable reason. A manifest is written for every
session, including a zero-trade one. A `flow_check.sh` dead-man on the homelab (same shape and alert
channel as `darkpool_check.sh`) alerts when the session is not producing.

### User Stories

1. As the operator, I want each collector's cycle/metrics lines and session summaries kept on a volume, so
   that a pod replacement does not erase the record of what the collector did.
2. As the operator, I want connect, auth, subscribe and archive-write failures written to that same
   durable log, so that a silent week cannot happen again.
3. As the operator, I want to read the record with `kubectl exec … cat|tail`, so that no new tooling is
   needed.
4. As the operator, I want the log size-capped and rotated, so that it cannot fill the volume the tape
   archive needs.
5. As the operator, I want a process crash (uncaught exception) to land in the durable log with its
   traceback, so that a crash loop is explained, not just counted.
6. As the operator, I want the flow collector to tell me in INFO lines that it connected, authenticated,
   got its subscription acknowledged, saw its first trade and wrote its first archive bytes, so that the
   first missing line localises a failure to one stage.
7. As the operator, I want a 406 ("slot held") day to end with a manifest and a summary like any other
   day, so that the session's record says "slot held all day" instead of nothing.
8. As the operator, I want a manifest even when no trade was archived, so that a zero-trade day is a
   recorded fact with its gap reasons, not an absence.
9. As the operator, I want a startup line stating whether the archive directory is writable and how much
   space is free against the guard thresholds, so that a path/permission/disk cause is visible at boot.
10. As the operator, I want the disk-guard warning to carry the measured numbers, so that a false stop is
    recognisable.
11. As the operator, I want a hung handshake (no greeting / no auth reply) to become a logged error and a
    reconnect rather than a silent liveness kill.
12. As the operator, I want malformed or unknown frames counted and the first few logged, so that a
    protocol change on Alpaca's side is visible.
13. As the operator, I want dark-pool cursors to survive a deploy, so that no manual copy-off/copy-back is
    needed and the backlog keeps draining across rolls.
14. As the operator, I want the dark-pool Deployment to run one pod at a time, so that two pods never
    write `cursors.json` concurrently.
15. As the operator, I want the liveness heartbeat to stay at `/app/state/heartbeat`, so that the probe
    needs no change.
16. As the operator, I want `darkpool_check.sh` to keep working unchanged after the roll.
17. As the operator, I want `flow_check.sh` to alert the staging_paper channel when, inside the window,
    no metrics line is recent, or after 10:00 ET today's archive has zero bytes, or after 16:25 ET today's
    manifest is missing or has too few trades, deduped per day like the dark-pool one.
18. As the operator, I want the PR to say whether `deploy.yml` applies manifests 66/67 or a manual
    `kubectl apply` is needed, and in which order (PVC before roll).
19. As the operator, I want these sidecar-image rolls to happen only while the market is closed.
20. As the operator, I want all of this live before Monday 2026-11-09 09:25 ET, or the smallest subset
    that can be.
21. As a researcher, I want the flow archive format document to describe `run.log` and the nullable
    manifest fields, so that offload scripts handle a zero-trade manifest.

### Implementation Decisions

- **One shared helper, stdlib only.** A function in the sidecar's `runtime` module attaches a
  `logging.handlers.RotatingFileHandler` with a tiny JSON formatter to the root logger. Both collector
  entrypoints call it right after `setup_logging`. One JSON object per line:
  `{"ts": "<UTC RFC-3339 ms Z>", "level": "INFO", "logger": "flow_collector", "msg": "<formatted message>", "exc": "<traceback text, only when present>"}`.
  Rotation: `maxBytes=50_000_000`, `backupCount=2` (≤ 150 MB per collector). The helper never raises: an
  unwritable directory logs one WARNING to stdout and the collector runs on stdout only. It returns the
  handler (or `None`) so tests can detach it and the entrypoint can log the path.
- **Locations.** Flow: `<FLOW_ARCHIVE_DIR>/run.log` (the archiver's dir-size scan will count it; 150 MB
  against a 20 GB cap is noise). Dark pool: `<STATE_DIR>/run.log`. No new environment variables.
- **Stdout logging is unchanged.** `kubectl logs` keeps showing the same lines; `darkpool_check.sh` keeps
  reading `darkpool cycle:` from the pod log.
- **Crash capture.** Each entrypoint wraps its run loop in a `try/except BaseException` that logs
  `"<name> collector crashed"` with the traceback and re-raises, so an uncaught exception reaches `run.log`
  before the container exits.
- **Flow session diagnostics** (all in the stream client and the top-level loop):
  - The greeting frame is inspected: an `error` control with code 406 raises the slot-held error; any
    other `error` control raises the reconnectable stream error; the greeting is logged at INFO.
  - Greeting and auth replies are awaited with a handshake timeout (constant, 10 s); a timeout is a
    reconnectable stream error ("no greeting within 10 s" / "no auth acknowledgement within 10 s").
  - INFO once per session: `flow: authenticated`; `flow: subscription ack …` for every
    `subscription`/`subscription_updated` control (there are only a handful per session);
    `flow: first trade …` with the trade timestamp; `flow: first archive flush …` with bytes.
  - `decode_error`, `non_dict`, `bad_symbol`, `parse_error` and non-406 `error` controls increment a
    `stream_errors` counter; the first five per session are logged at WARNING. `stream_errors=%d` is
    added to the metrics line and the session summary.
  - The 406 branch of the top-level loop records the disconnect timestamp with reason `slot_held`, so the
    existing window-close branch ends the session (gap recorded with that reason, manifest written,
    summary logged). The reconnect counter is not incremented for a 406 (unchanged).
  - `close_session` always writes the manifest. When no trades file exists: `file: null, bytes: 0,
    sha256: null, trades: 0, first_t/last_t: null`, gaps as recorded. The end-of-session code logs
    `flow: manifest written <path> trades=<n>` at INFO.
  - Startup logs `flow: archive dir <dir> writable=<bool> free_bytes=<n> cap_bytes=<n> floor_bytes=<n>`
    (writability via `os.access`, no probe file). The disk-guard WARNING includes
    `dir_size=… cap=… free=… floor=…`; an `OSError` in the free-space query logs a WARNING instead of
    silently failing closed.
- **Dark-pool state on a PVC.** Manifest 66 gains a `PersistentVolumeClaim` `darkpool-state`
  (`local-path`, RWO, `1Gi`, documentation-only size like `flow-archive`) placed above the Deployment;
  the `state` volume becomes that claim; `strategy: type: Recreate` is added. `STATE_DIR` stays
  `/app/state`, so heartbeat, `cursors.json` and `run.log` all live there and the liveness probe is
  untouched. No Python change: the collector already saves/loads `STATE_DIR/cursors.json`.
- **Dead-man.** `scripts/ops/flow_check.sh` in the repo, installed to `~/scripts/flow_check.sh` on the
  homelab by the orchestrator, modelled line-for-line on `darkpool_check.sh` (Deployment ready, restarts ≤ 3,
  per-day dedupe, staging_paper webhook resolved from `discord-alert-credentials`, `DRY_RUN=1`/`TEST=1`).
  Checks: inside the window (weekdays 09:25–16:20 ET) a `flow metrics:` line in `run.log` with `ts` within
  6 minutes; weekdays after 10:00 ET today's `.trades.ndjson.gz` has size > 0; weekdays after 16:25 ET
  today's manifest exists and `trades >= FLOW_MIN_TRADES` (default 100000, the flow plan's number) and a
  `flow session summary:` line exists in `run.log`. Cron on the homelab (host clock is Central; ET = CT + 1 h
  through both DST changes): `40 8 * * 1-5` (09:40 ET) and `35 15 * * 1-5` (16:35 ET).
- **Deploy.** `deploy.yml` applies both files: neither `darkpool-collector` nor `flow-collector` is in its
  `RESTART_ONLY` list and both Deployments exist (bootstrap guard passes), so the PVC in 66 is created by
  CI's `kubectl apply` ahead of the Deployment in the same file. No manual `kubectl apply` is required; a
  manual pre-apply of 66 is harmless and optional. The darkpool PVC starts empty, so the roll is
  bracketed by a cursor copy-off / copy-in / one extra restart (Step 5). Market-closed only.

### Testing Decisions

- A good test drives a public seam and asserts observable output: a file on disk, a log line in `caplog`,
  a raised error type, a manifest's JSON. No test mocks an internal collaborator; the socket is the
  existing `FakeWebSocket`, REST is `httpx.MockTransport`, kubectl is a stub script on `PATH`.
- Modules tested: `runtime.attach_run_log` (new), `flow_main.FlowSession.run` / `run_loop`,
  `flow_collector.TapeArchiver.close_session`, manifest 66 (parsed YAML facts), `flow_check.sh`
  (stub kubectl + `DRY_RUN=1`).
- Prior art: `tests/test_flow_main.py` (`FakeWebSocket`, `make_session`, `caplog` on the metrics line,
  `run_loop` with injected `clock`/`sleep`), `tests/test_flow_collector.py` (TapeArchiver on `tmp_path`),
  `tests/test_darkpool_main.py` (`test_loop_survives_a_cycle_exception`), `scripts/tests/test_deploy_matrix_routing.py`
  (Python driving a bash script).

### Out of Scope

Everything under Non-goals; a Java/orchestrator change of any kind; changes to the universe ConfigMap;
retention of the archive; the BFF.

### Further Notes

The weekend probe (Investigation §3) ran from inside the idle flow-collector pod on 2026-11-08 ~19:25Z
and is read-only; it is recorded here so the builder does not repeat it, and so the ranking is evidence-based.

## Investigation — how the flow session can fail while the process stays alive and quiet

All paths in `services/signal-source-discord/ohmytradeagent_sidecar/`. Line numbers are at base `fb32a188`.

### 1. Failure modes, by stage

| # | Stage | Code | What happens today | Where the evidence went |
|---|-------|------|--------------------|-------------------------|
| F1 | Greeting | `flow_main.py:480-482` | The first frame is parsed and logged at **DEBUG only**, never inspected. An `error` control arriving as the first frame (e.g. a 406 sent before any `connected`) is ignored; auth is then sent on a doomed socket and the server closes it. | The close surfaces as `ConnectionClosed` from `ws.recv()` at `:490`, which is *not* wrapped inside `run()`; it is caught by the top-level loop at `:1042-1051` as `flow: transport error … reconnecting in Ns` (WARNING), back-off grows to 60 s, retried all day. Pod log only. |
| F2 | Auth wait | `flow_main.py:489-502` | `ws.recv()` with **no timeout**. If the server never answers, the coroutine hangs; the heartbeat is touched only inside the receive loop (`:521`), so after 180 s the liveness probe kills the container. | Nothing logged; only the pod's restart count, which nobody read and which died with the pod. |
| F3 | Auth error | `flow_main.py:495-498` | 406 → `_SlotHeldError`; any other code → `_StreamError`. | `:1032` / `:1036` WARNING, pod log only. |
| F4 | 406 all day | `flow_main.py:1031-1034` | The 406 branch sleeps 60 s and retries but **does not set `last_disconnect_ts`**, so the window-close branch at `:984-1000` never calls `_end_session`: **no manifest, no summary** for the whole day. The only path that leaves nothing at all even with the archiver present. | Per-minute `flow: slot held (406)` WARNINGs, pod log only. |
| F5 | Subscribe | `flow_main.py:510-511`, `:563-566` | The subscribe is sent; the ack is never logged. A non-406 `error` control in-session is logged once (WARNING) and the loop idles for the day receiving nothing. | Metrics lines would show `trades/s mean=0.0`; pod log only. |
| F6 | Bad frames | `flow_collector.py:149-185`, `flow_main.py:547-566` | `decode_error`, `non_dict`, `bad_symbol`, `parse_error` controls are returned by `parse_frame` and **silently dropped** by the loop (only `action == "error"` is logged). | Nowhere. |
| F7 | Crash | `flow_main.py:1076-1128` (`_run`), `:557-558` + `:593-595` | No `try/except` around `run_loop`: an exception not in `(WebSocketException, OSError)` propagates, the traceback goes to stderr, the container exits and restarts. Archive rows are buffered and flushed only at the 1-s tick, so a crash while processing the first frames leaves **zero bytes**. | Traceback in the pod log (lost) and the restart count (lost). |
| F8 | Transport | `flow_main.py:1042-1051` | DNS/TLS/handshake errors (incl. a 406 at HTTP-upgrade level, `InvalidStatus`) → WARNING + back-off ≤ 60 s, retried; session ends normally at window close with a zero-trade summary. | Pod log only. market-data logged `data.alpaca.markets` connect failures on Fri 11-07 at 14:17Z and 17:38Z, so cluster egress does hiccup. |
| F9 | Disk guard | `flow_collector.py:656-691` (`_disk_check`), `:717-726` | `shutil.disk_usage` `OSError` → `free = 0` → `disk_free_floor`, **silently**; the one WARNING `disk guard triggered (…)` carries no numbers. Writes are refused for the session. | One WARNING, pod log only. |
| F10 | Write error | `flow_collector.py:745-757` | `OSError` on open/write → one WARNING, then silent for the rest of the session. | One WARNING, pod log only. |
| F11 | Manifest | `flow_collector.py:776-805` (`close_session`, `:787-788`) | Returns `None` **without logging** when no trades file exists. So every zero-trade failure mode (F1–F10) leaves an empty PVC with no manifest — the missing manifest does not discriminate between them. | Nowhere. |
| F12 | Archiver creation | `flow_main.py:898-914`, `:969` | Eager (`TapeArchiver` built in `_make_session_state` at loop start), not lazy — ruled out as a cause. | — |
| F13 | Window | `flow_main.py:110-113` | Verified correct offline by the operator; the pod clock matches `date -u`. Ruled out. | — |

Dark pool, for completeness: `darkpool_main.py:452-472` logs `darkpool cycle error` with traceback to stdout only;
the cycle line at `:362-376` and `cursors.json` at `:408-419` live in `STATE_DIR`, an `emptyDir` in
`infra/k8s/66-darkpool-collector.yaml` (`volumes: state: emptyDir: {}`), so both die with the pod.

### 2. Who could hold the single OPRA connection

The options endpoint `wss://stream.data.alpaca.markets/v1beta1/opra` allows one connection per account.
Checked:
- `services/market-data`: `AlpacaMarketDataProperties.java:12` says `dataWsUrl` is **no longer used**; the
  live Deployment's only feed env is `ALPACA_STOCK_FEED=sip` (stock socket `/v2/sip`, a different slot).
  Options are REST (`/v1beta1/options/snapshots`). Not a holder.
- Homelab host processes: `option_quote_collector.py` ×2 (REST via `urllib`, `scripts/data/option_quote_collector.py:48-51`),
  `option_oi_collector.py` (REST), `watchlist_forward.py capture-flow` (cron 09:35 ET, REST
  `feed=opra` trades endpoint, `scripts/research/watchlist_forward.py:255`). No websocket process; `ss` shows
  no host connection to `stream.data.alpaca.markets` (34.145.195.91).
- Workstation: no `alpaca-options-ws-probe.py`/research process running; crontab has no Alpaca job.
- Not checkable: any other machine the operator may have run `scripts/alpaca-options-ws-probe.py` or a
  research notebook on during RTH.

### 3. Weekend read-only probe (done 2026-11-08 ~19:25Z from inside `flow-collector-785b4ff49c-xfjwg`)

- WebSocket to `/v1beta1/opra`: greeting `connected` in 0.00 s → `authenticated` → subscribe
  `trades:["*"]` acknowledged `{"T":"subscription","trades":["*"]}` → unsubscribe ack. The key prefix
  `AKHS…` matches the repo `.env`'s `APCA_API_KEY_ID_DATA`.
- REST `/v1beta1/options/snapshots/SPY?feed=opra` → 200 (and `indicative` → 200): entitlement is fine.
- `/app/archive`: exists, `os.access(W_OK) = True` as uid 1001 (groups 100, 1001), `shutil.disk_usage.free =
  92.0 GB` (floor 15 GB), directory empty; `FLOW_ARCHIVE_CAP_BYTES`/`FLOW_ARCHIVE_FREE_FLOOR_BYTES`/`LOG_LEVEL`
  unset (defaults). PV host dir `/var/lib/rancher/k3s/storage/pvc-905105cc-…_copytrade_flow-archive`
  is `drwxrwsrwx root:1001`, mtime `Oct 30 19:42` = its creation: **never written**. Node root disk 86 G free.
- `websockets 17.3` in the image (new asyncio implementation). Current flow pod restarts: 0 (new pod,
  idle). ReplicaSets: three on 10-31 (00:42, 06:03, 06:55Z); `76c76cf9bb` served the week.
- Dark-pool pod: 1 restart since 13:33Z; `/app/state/cursors.json` 1.9 KB, most symbols at
  `2026-11-06T21:09Z`, a backlog block (AMZN, ARM, AVGO, CAT, COIN, …) still at `2026-11-05T14:30Z`.

### 4. Ranked causes of the empty week

1. **The OPRA slot was held during the window by a connection outside the cluster, retried all day as a
   "transport error"** (F1 + F8, or F4 if the 406 arrived in the auth reply). It is the only mechanism
   that produces no manifest, no bytes, and nothing above WARNING for five straight sessions while the
   pod stays healthy, and the slot was demonstrably free on Sunday. No holder is visible on the homelab
   or this workstation; it would have to be another machine (probe script, research notebook) running
   weekday mornings — or Alpaca's eviction semantics are "new connection wins" as manifest 66's header
   claims for stocks, in which case a 09:35 ET REST job is not it but any later socket would be. Unprovable
   now; Monday's `run.log` settles it (F1/F4 lines).
2. **A crash loop from an uncaught exception on the first frames** (F7). Nothing flushes before the exit,
   the traceback and restart count died with the pod. I found no deterministic raise path in
   `parse_frame`/`classify`/`OrderAggregator`/`trade_to_archive_row` (parsing is guarded, `vwap` guards
   zero size); the remaining suspects are library-level behaviours of websockets 17's asyncio client under
   the 0.1-s `wait_for` cancel loop. Unprovable now; Step 1's crash capture settles it.
3. **Persistent egress/DNS failure to `stream.data.alpaca.markets` during RTH** (F8). market-data shows
   bursts of `data.alpaca.markets` connect failures on Friday, but they were minutes long and Sunday's
   connect worked. Low.
4. **Handshake hang → liveness kill loop** (F2). Low: the server answered in milliseconds; a hang would
   also have shown as restarts.
5. **Disk guard, permissions, path** (F9/F10) — **ruled out** by §3.
6. **Window/clock** (F13) — ruled out.
7. **Auth / entitlement / wildcard refusal** (F3/F5) — ruled out for the current key by §3.

Everything the plan adds is needed regardless of which of 1–4 it was: the next failure must leave its
reason on the PVC.

## Steps

Order: 1 → 2 → 3 → 4 → 5. Steps 1–4 are commits on this branch and ship as one PR; Step 5 is the
orchestrator's deploy choreography after merge. **Cut line if time runs short** (merge must land before
Monday 12:00Z / 07:00 ET so the roll is well clear of 09:25 ET): ship Steps 1 + 2 (+ Step 5's flow part
and the insurance tail) and Step 4; defer Step 3 to a market-closed roll Monday after 16:30 ET. Steps 1
and 2 alone make Monday diagnosable.

### Step 1 — Shared durable JSONL run log, wired into both collectors, with crash capture

**Goal:** Every log record either collector emits is also appended as one JSON line to `run.log` on its
volume, size-capped and rotated; an uncaught exception in either entrypoint is logged with its traceback
before the process exits.
**Blocked by:** none.
**Seams:**
- `runtime.attach_run_log(log_dir, *, max_bytes, backup_count) -> logging.Handler | None` + the stdlib
  logger — catches the line format, the fields, traceback capture, rotation and the unwritable-dir
  behaviour; misses the entrypoint wiring (verified on the homelab in Step 5).
- `flow_main._run` / `darkpool_main._run` are env-driven glue and stay untested, as today.
**Success checklist:**
- [ ] After `attach_run_log(tmp_path)` and `logging.getLogger("x").warning("hello %s", 1)`, `tmp_path/run.log`
      holds one line that `json.loads` to `{"ts": <RFC-3339 Z>, "level": "WARNING", "logger": "x", "msg": "hello 1"}`
      with no `exc` key — evidence: test name in `tests/test_runtime.py` (new) and `uv run pytest -q tests/test_runtime.py`.
- [ ] A record logged via `log.exception` inside an `except` yields `exc` containing `Traceback` and the
      exception type — evidence: test name.
- [ ] With `max_bytes=300, backup_count=1`, writing 20 records produces `run.log` and `run.log.1`, and the
      total size stays under 2 × 300 B + one record — evidence: test name.
- [ ] `attach_run_log(<path whose parent is a regular file>)` returns `None`, raises nothing, and a WARNING
      naming the path appears in `caplog` — evidence: test name.
- [ ] `attach_run_log(None)` returns `None` and attaches nothing — evidence: test name.
- [ ] `flow_main._run` calls `attach_run_log(archive_dir)` after `setup_logging` and logs `run log: <path>`;
      `darkpool_main._run` calls `attach_run_log(state_dir)` likewise — evidence: `grep -n attach_run_log`
      on both files shows the call before the `starting` line.
- [ ] Both `_run` functions wrap their `run_loop` in `try/except BaseException` that logs
      `"flow collector crashed"` / `"darkpool collector crashed"` via `log.exception` and re-raises —
      evidence: `grep -n "collector crashed"` + the existing tests still pass.
- [ ] The handler is attached to the **root** logger (so `websockets`/`httpx` warnings land too) at the
      same level as stdout — evidence: test logs via `logging.getLogger("websockets.client").warning(...)`
      and finds the line.
- [ ] Tests detach the handler in teardown (fixture), and the full suite passes twice in a row —
      evidence: `uv run pytest -q` ×2 and `uv run ruff check .`.
**Failure checklist** (any one → STOP and report):
- [ ] The helper needs a new dependency or a new environment variable.
- [ ] Making it work requires changing `setup_logging`'s stdout behaviour or the line format that
      `darkpool_check.sh` greps (`darkpool cycle:` must still appear in `kubectl logs`).
- [ ] A change outside `runtime.py`, the two `_run()` functions and `tests/` is needed.

### Step 2 — Flow session diagnostics: every stage logged, 406 day ends with a manifest, manifest always written

**Goal:** A flow session leaves, in `run.log`, an INFO line for each stage it reached (connected,
authenticated, subscription ack, first trade, first flush, manifest written) and a WARNING/ERROR for the
stage where it failed; a 406 day and a zero-trade day both end with a manifest and a summary.
**Blocked by:** Step 1 (same files; sequential on one branch).
**Seams:**
- `FlowSession.run(state, ws)` over `FakeWebSocket` (`tests/test_flow_main.py:make_session`) — catches
  greeting-error handling, handshake timeouts, the INFO stage lines, `stream_errors` counting; misses
  Alpaca's real frame order.
- `run_loop(...)` with injected `connect_fn`/`clock`/`sleep` on `tmp_path` — catches the 406-day
  manifest/summary, the gap reason; misses real time.
- `TapeArchiver.close_session` on `tmp_path` — catches the zero-trade manifest shape; misses disk reality.
- `caplog` on the start-up self-check and the disk-guard WARNING — catches the field names.
**Success checklist:**
- [ ] `FakeWebSocket([error_406_frame])` (406 as the **first** frame, no greeting) → `run()` raises
      `_SlotHeldError`; a first frame `{"T":"error","code":500}` → `_StreamError` — evidence: two new test names.
- [ ] A `FakeWebSocket` whose next `recv()` never resolves (test helper: an `asyncio.Event().wait()` frame
      sentinel) with `flow_main.HANDSHAKE_TIMEOUT_SECS` monkeypatched to 0.05 → `_StreamError` whose message
      contains `no greeting` (and a second test: greeting then hang → `no auth acknowledgement`) — evidence: test names.
- [ ] With frames greeting → auth → `{"T":"subscription","trades":["*"]}` → one trade, `caplog` holds, in
      order, `flow: connected`, `flow: authenticated`, `flow: subscription ack`, `flow: first trade`, and
      after the 1-s tick with an archiver on `tmp_path`, `flow: first archive flush` with `bytes=` > 0 —
      evidence: test name.
- [ ] Frames containing an undecodable payload, a non-dict item and a trade with a bad OCC symbol → the
      metrics line contains `stream_errors=3`; exactly three WARNINGs `flow: stream error` in `caplog`; a
      sixth bad frame is counted but not logged — evidence: test name; `test_metrics_line_contains_all_required_fields`
      updated to require `stream_errors=`.
- [ ] `run_loop` where `connect_fn` always yields greeting → 406 and the injected clock crosses 16:20 ET on
      the third call → `<date>.manifest.json` exists with `trades: 0`, `file: null`, `bytes: 0`, `sha256: null`,
      and `gaps[0].reason == "slot_held"` with `to == null`; `caplog` has `flow session summary:` and
      `flow: manifest written … trades=0`; `reconnects` in the summary is 0 — evidence: test name.
- [ ] `TapeArchiver(tmp_path).close_session("2026-11-09")` with no prior writes returns a dict with the
      null shape above and writes `2026-11-09.manifest.json`; `test_archiver_manifest_sha256_matches_file`
      still passes unchanged — evidence: test names.
- [ ] The disk-guard WARNING (`test_archiver_free_floor_stops_writes` path) now contains `free=`, `floor=`,
      `dir_size=`, `cap=`; an `OSError` from `shutil.disk_usage` (monkeypatched) logs a WARNING containing
      `disk_usage` — evidence: test names.
- [ ] `run_loop` start-up logs `flow: archive dir <dir> writable=True free_bytes=<n> cap_bytes=<n> floor_bytes=<n>`
      (and `writable=False` when `tmp_path` is chmod 0o500) — evidence: test name.
- [ ] `docs/ops/flow-archive-format.md` documents `run.log` (location, JSONL fields, rotation) and the
      nullable manifest fields (`file`, `sha256`, `first_t`, `last_t`) and the new gap reason `slot_held` —
      evidence: `grep -n "run.log\|slot_held\|null" docs/ops/flow-archive-format.md`.
- [ ] `uv run pytest -q` and `uv run ruff check .` pass — evidence: output tails.
**Failure checklist** (any one → STOP and report):
- [ ] An existing test must be weakened or deleted (other than adding `stream_errors=` to the required
      metrics fields).
- [ ] The change needs a mock of `TapeArchiver`, `QuoteSet`, `OrderAggregator` or `Metrics`.
- [ ] The reconnect/back-off timing or the 60-s 406 back-off has to change to make a test pass.
- [ ] Any code path that *sends* something new to Alpaca (other than what is sent today) is needed.

### Step 3 — Dark-pool state on a PVC (manifest 66), one pod at a time

**Goal:** `cursors.json`, `heartbeat` and `run.log` for the dark-pool collector live on a PVC that
survives pod replacement; the Deployment never runs two pods.
**Blocked by:** Step 1 (so the `run.log` comment in the manifest is true).
**Seams:**
- The manifest as parsed YAML — catches the facts below; misses provisioning behaviour (Step 5 covers it).
**Success checklist:**
- [ ] `infra/k8s/66-darkpool-collector.yaml` contains a `PersistentVolumeClaim` named `darkpool-state`
      (`accessModes: [ReadWriteOnce]`, `storageClassName: local-path`, `requests.storage: 1Gi`) placed
      **before** the Deployment document — evidence: `python3 -c "import yaml,sys; d=list(yaml.safe_load_all(open('infra/k8s/66-darkpool-collector.yaml'))); print([x['kind'] for x in d])"`
      prints `['ConfigMap', 'PersistentVolumeClaim', 'Deployment', 'NetworkPolicy']`.
- [ ] The Deployment's `state` volume is `persistentVolumeClaim: {claimName: darkpool-state}`; no `emptyDir`
      remains for `state` (`tmp` stays emptyDir); `STATE_DIR` is still `/app/state`; the liveness probe is
      byte-for-byte unchanged — evidence: `git diff fb32a188 -- infra/k8s/66-darkpool-collector.yaml`.
- [ ] `spec.strategy.type: Recreate` is present with a comment explaining why (single writer of
      `cursors.json`; one missed cycle per roll, cursors make it lossless) — evidence: the diff.
- [ ] The header comment's "emptyDir … wipes cursors" wording is replaced by the PVC description, and the
      docstring at `darkpool_main.py:18-19` ("The state dir is an emptyDir …") is corrected to match —
      evidence: `grep -n emptyDir infra/k8s/66-darkpool-collector.yaml services/signal-source-discord/ohmytradeagent_sidecar/darkpool_main.py`
      shows only the `tmp` volume.
- [ ] `67-flow-collector.yaml` gains a comment (no spec change) that `/app/archive/run.log` is the durable
      run log; `kubectl diff` for 67 would therefore be empty — evidence: `git diff` shows only comment lines in 67.
- [ ] On the homelab (read-only): `ssh ridopark@192.168.10.123 'kubectl -n copytrade apply --dry-run=client -f -' < infra/k8s/66-darkpool-collector.yaml`
      prints `persistentvolumeclaim/darkpool-state created (dry run)` and `deployment.apps/darkpool-collector configured (dry run)` — evidence: the output.
**Failure checklist** (any one → STOP and report):
- [ ] The storage class on the homelab is not `local-path` or has `volumeBindingMode` other than
      `WaitForFirstConsumer`/`Immediate` (check `kubectl get sc`) — a different provisioner changes Step 5.
- [ ] The change requires touching any env value, the universe ConfigMap, the NetworkPolicy, or `deploy.yml`.
- [ ] A Python change to where cursors are saved is needed (it must not be: `STATE_DIR/cursors.json` already).

### Step 4 — `flow_check.sh` dead-man (repo copy + homelab install recipe)

**Goal:** A read-only script that exits 0 when the flow collector is producing and otherwise posts one
deduped red alert per day to the staging_paper channel naming the failed check.
**Blocked by:** Steps 1 and 2 (it reads `run.log` and relies on the manifest always existing).
**Seams:**
- The script's CLI with a stub `kubectl` on `PATH` and `DRY_RUN=1` — catches the decision logic and the
  message text; misses the real cluster and Discord (Step 5's `DRY_RUN=1` run on the homelab covers the former).
**Success checklist:**
- [ ] `scripts/ops/flow_check.sh` exists, `chmod +x`, `set -uo pipefail`, header states READ-ONLY and the
      checks; same `STATE_DIR`-dedupe, webhook resolution (`discord-alert-credentials` →
      `ALERT_DISCORD_WEBHOOK_URLS` → `staging_paper=` entry, URL never printed), `DRY_RUN`/`TEST` semantics
      and exit codes as `~/scripts/darkpool_check.sh` — evidence: `diff <(grep -n "DRY_RUN\|TEST\|last_sig\|ALERT_DISCORD_WEBHOOK_URLS" ~/scripts/darkpool_check.sh) <(…flow_check.sh)` shows the same mechanisms.
- [ ] Checks implemented: Deployment `flow-collector` ready `1/1`; restarts ≤ 3; in window (weekday,
      09:25 ≤ ET < 16:20): newest `flow metrics:` line in `kubectl exec deploy/flow-collector -- tail -n 200 /app/archive/run.log`
      has `ts` ≤ 6 min old; weekday ≥ 10:00 ET: `stat -c %s /app/archive/<today>.trades.ndjson.gz` > 0;
      weekday ≥ 16:25 ET: `<today>.manifest.json` exists, its `trades` ≥ `${FLOW_MIN_TRADES:-100000}`, and
      `flow session summary:` appears in `run.log` for today — evidence: `scripts/tests/test_flow_check.py`
      (new; stub kubectl written to a temp dir, `FAKE_NOW` env honoured by the script for the ET clock, `DRY_RUN=1`)
      with cases: all OK → exit 0 and `OK` in stdout; in-window + stale metrics line → exit 1 and
      `no 'flow metrics:'` in stdout; after 10:00 + zero-byte archive → exit 1 naming `archive bytes`;
      after 16:25 + missing manifest → exit 1 naming `manifest`; weekend → exit 0.
- [ ] The summary line prints `ready= restarts= in_window= archive_bytes= manifest_trades= last=` so a
      Discord reader sees the numbers — evidence: test asserts the fields.
- [ ] Install recipe is written into the script header: `scp scripts/ops/flow_check.sh ridopark@192.168.10.123:~/scripts/`,
      `crontab -l > ~/scripts/crontab.bak-$(date +%Y%m%d-%H%M)`, then append
      `40 8 * * 1-5 /home/ridopark/scripts/flow_check.sh >> /home/ridopark/scripts/flow-check.log 2>&1` and
      `35 15 * * 1-5 /home/ridopark/scripts/flow_check.sh >> /home/ridopark/scripts/flow-check.log 2>&1`
      (host clock is Central) — evidence: `head -40 scripts/ops/flow_check.sh`.
- [ ] `python3 scripts/tests/test_flow_check.py` passes; `shellcheck scripts/ops/flow_check.sh` has no
      errors (warnings acceptable, as for the sibling) — evidence: outputs.
**Failure checklist** (any one → STOP and report):
- [ ] The script needs anything but `kubectl`, `python3`, `curl`, `date`, `sed`, `grep`, `sha1sum`, `base64`.
- [ ] It needs write access to the cluster or any DB (it must be `get`/`logs`/`exec … tail|stat|cat` only).
- [ ] It posts in `DRY_RUN=1`.

### Step 5 — Deploy (market closed), seed cursors, install the dead-man, verify, Monday insurance

**Goal:** The new image and manifests are live on the homelab before Monday 2026-11-09 09:25 ET with the
dark-pool backlog cursors preserved, `flow_check.sh` installed, and both `run.log` files proven to exist.
**Blocked by:** Steps 1–4 merged (or the cut-line subset).
**Seams:** the live cluster, read via `kubectl get/logs/exec`; the only writes are the roll itself (by
deploy.yml), two `kubectl cp` + one `rollout restart` for the cursor seed, and installing a cron.
**Success checklist** (orchestrator runs these over `ssh ridopark@192.168.10.123`):
- [ ] Pre-merge, market closed (now, Sunday, or before Monday 07:00 ET): `kubectl -n copytrade cp $(kubectl -n copytrade get pod -l app=darkpool-collector -o jsonpath='{.items[0].metadata.name}'):/app/state/cursors.json ~/scripts/cursors-$(date -u +%Y%m%dT%H%M).json`
      and `python3 -m json.tool` on it shows `"cursors"` with 59 entries — evidence: the file and its size.
- [ ] PR body states: "deploy.yml applies 66 and 67 (neither is in RESTART_ONLY; both Deployments exist so
      the bootstrap guard passes); the `darkpool-state` PVC is created by that apply ahead of the Deployment
      in the same file; no manual `kubectl apply` is needed; cursors are seeded after the roll" and
      `Closes #1044` — evidence: `gh pr view --json body`.
- [ ] After merge, the deploy run's matrix includes `darkpool-collector` and `flow-collector`, both
      `rollout status` steps succeed — evidence: `gh run watch`/`gh run view` of the deploy workflow.
- [ ] `kubectl -n copytrade get pvc darkpool-state` is `Bound`; `kubectl -n copytrade get deploy darkpool-collector -o jsonpath='{.spec.strategy.type}'`
      prints `Recreate`; exactly one darkpool pod — evidence: outputs.
- [ ] Seed: `kubectl -n copytrade cp ~/scripts/cursors-<ts>.json <new darkpool pod>:/app/state/cursors.json`,
      then `kubectl -n copytrade rollout restart deploy/darkpool-collector && kubectl -n copytrade rollout status deploy/darkpool-collector`;
      the new pod's first log lines include `darkpool cursor state loaded for 59 symbols` — evidence: `kubectl logs`.
- [ ] `kubectl -n copytrade exec deploy/darkpool-collector -- sh -c 'ls -la /app/state; tail -n 2 /app/state/run.log'`
      shows `cursors.json`, `heartbeat`, `run.log`, and the last line is the `starting` JSON record — evidence: output.
- [ ] `kubectl -n copytrade exec deploy/flow-collector -- tail -n 3 /app/archive/run.log` shows JSON lines
      for `run log:`, `flow: archive dir /app/archive writable=True free_bytes=…`, and `flow collector starting` — evidence: output.
- [ ] `DRY_RUN=1 ~/scripts/darkpool_check.sh` exits 0 (outside the window on Sunday the cycle check is
      skipped; the ready/restarts checks must pass) — evidence: output line `OK`.
- [ ] `flow_check.sh` installed per Step 4's recipe; `DRY_RUN=1 ~/scripts/flow_check.sh` prints `OK`
      (Sunday: out of window); `crontab -l | grep flow_check` shows both lines — evidence: outputs.
- [ ] Insurance for Monday regardless of the above: before 09:25 ET start on the homelab
      `nohup kubectl -n copytrade logs -f deploy/flow-collector >> ~/scripts/flow-collector-2026-11-09.log 2>&1 &`
      (read-only; captures the pod log externally even if `run.log` has a problem) — evidence: the file grows after 14:25Z.
- [ ] Monday 09:40 ET: `DRY_RUN=1 ~/scripts/flow_check.sh` and `tail -n 20 /app/archive/run.log` are read
      and the result (bytes > 0, or the first WARNING line naming the stage) is posted back to issue #1044
      as a comment — evidence: the comment.
**Failure checklist** (any one → STOP and report):
- [ ] The merge or roll would land between 09:25 and 16:20 ET on a weekday (the sidecar image carries the
      live Discord watcher and a mid-session flow restart leaves a permanent gap — `deploy_gate.py:55`).
- [ ] The darkpool pod stays `Pending` > 3 min after the roll (`kubectl describe pvc darkpool-state` first;
      do not delete the PVC or fall back to emptyDir without reporting).
- [ ] The k8s-drift job's diff for 66 shows anything beyond the PVC, the `strategy`, the `state` volume
      and comments (an undeclared live override would be reverted by the apply).
- [ ] `run.log` is missing in either pod after the roll, or `darkpool_check.sh` reports a problem.
- [ ] The copied `cursors.json` has fewer than 59 cursors or fails `json.tool`.

## Repo gates

- Python only: `uv run ruff check .` and `uv run pytest -q` in `services/signal-source-discord` (CI job
  `working-directory: services/signal-source-discord`). No spotless, no schema regeneration, no Temporal
  version gates (nothing here touches a workflow).
- `deploy.yml`: a change under `services/signal-source-discord/*` rolls signal-source-discord, the three
  mirrors, lotto-signal, darkpool-collector and flow-collector (`add_sidecar`, `deploy.yml:282`); a change
  to `infra/k8s/66-*.yaml`/`67-*.yaml` rolls its own service (`:346-347`). Both collectors get `kubectl apply -f`
  (not in `RESTART_ONLY`, `:557`) then `rollout restart`. New PVC inside 66 → created by that apply. No
  workflow edit in this PR, so the Claude review runs normally.
- **Market-closed merge only** (sidecar image includes the live Discord watcher; flow-collector is
  "a mid-session restart leaves a permanent gap", `scripts/ops/pr-deploy-agent/deploy_gate.py:55`).
- `k8s-drift.yml` posts a `kubectl diff` for the infra change; the expected diff is the PVC, `strategy`,
  the `state` volume and comments.
- Issue hygiene: PR body carries `Closes #1044`; `gh pr edit --body` is broken here — use
  `gh api -X PATCH repos/ridopark/oh-my-tradeagent/pulls/<n>`.
- Memory rules honoured: shared manifests still need a manual apply (not the case here — 66/67 are
  per-service manifests); never `--previous`-less assumptions about restarts: read `restartCount`.

## Open questions (each with the assumption planned on)

1. **Run-log format and size.** Assumed: JSONL, 50 MB × 2 backups per collector via stdlib
   `RotatingFileHandler`, attached to the root logger at the stdout level. If the operator prefers plain
   text or a different cap, it is a two-constant change.
2. **Where the dark-pool run log lives.** Assumed: the same PVC as the state (`/app/state/run.log`) rather
   than a second volume.
3. **PVC name/size.** Assumed: `darkpool-state`, 1Gi (documentation-only on local-path, like `flow-archive`).
4. **`Recreate` for the dark-pool Deployment.** Assumed yes: one writer of `cursors.json`; cost is one
   missed 60-s cycle per roll, which the cursors make lossless.
5. **Zero-trade manifest.** Assumed the operator wants it written (`file: null`), so a failed day is a
   record, and the format doc is updated accordingly.
6. **Dead-man thresholds and times.** Assumed: 6-min metrics recency, archive bytes > 0 after 10:00 ET,
   `trades ≥ 100000` after 16:25 ET (the flow plan's number), crons at 09:40 and 16:35 ET, alerts to the
   staging_paper webhook like `darkpool_check.sh`.
7. **Repo home for the dead-man.** Assumed `scripts/ops/flow_check.sh` (the dark-pool one stays
   homelab-only; not retro-added).
8. **Cursor seeding at the roll.** Assumed the backlog block still at `2026-11-05T14:30Z` matters and is
   seeded via copy-off / copy-in / one extra restart; if the operator does not care, skip the seed and the
   new pod starts at a 10-min lookback.
9. **Alpaca's 406 frame order.** Assumed the 406 may arrive as the first frame (the code today only
   handles it in the auth reply); handling both is harmless either way.
10. **Handshake timeout** 10 s and "first five bad frames logged" are the planner's numbers.
11. **Timing.** Assumed the full set can be built, reviewed, merged and rolled tonight (Sunday 2026-11-08,
    19:30Z now; Monday 09:25 ET is 14:25Z). Hard stop for the merge: Monday 12:00Z. Past it, apply the
    cut line (Steps 1, 2, 4 + flow part of 5), and the insurance `kubectl logs -f` tail covers Monday even
    if nothing ships.
