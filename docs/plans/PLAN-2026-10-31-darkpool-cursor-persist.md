# PLAN — 2026-10-31 darkpool-cursor-persist

Source: GitHub issue #1032 ("darkpool-collector: restart during the open loses lagging symbols' data").
Scope agreed by the operator: the issue's "Proposed fix", collector only, one PR.

## Goal

A slow symbol at the open no longer starves the darkpool-collector's liveness heartbeat. If the
container still restarts during the poll day, every symbol resumes from the start of the 5-minute
bucket its cursor was in, instead of from `now − lookback`. Lagging symbols lose no bars, and no
complete bar is overwritten by a partial one.

**Done when:**
- `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q` exits 0.
- A test whose fake NBBO lookups advance a fake clock by 20 s each over one symbol with 20 kept
  prints (400 s for that symbol) passes, and it asserts that no gap between heartbeat touches
  exceeds 20 s. On `main` the same test fails, because there is one touch after about 400 s.
- A test that runs a cycle, builds a new `DarkpoolCollector` on the same state file with the clock
  moved forward 30 min (same ET day), and runs a cycle passes. It asserts that the first trades
  request starts at `bucket5m(saved last_t)`, not at `bucket5m(now − 10 min)`, and that the first
  posted bar for that symbol is the rebuilt-whole bucket.
- Tests prove that a corrupt file, a file from an earlier ET day, and a missing file all fall back
  to `bucket5m(now − lookback)` without raising, and that the corrupt and old cases log a warning.
- `darkpool_main._run` passes `STATE_DIR/cursors.json` to the collector, and the module docstring
  no longer says "There is no cursor persistence".

## Non-goals

- No resume from the DB across a pod replacement (a deploy or a node move still starts at
  `now − lookback`). That would need a BFF read endpoint.
- No refill of 2026-10-30 09:35–10:10 ET. The operator decides on that; the PR body mentions it.
- No change to `infra/k8s/66-darkpool-collector.yaml`. The probe, the `state` emptyDir and the
  read-only root filesystem stay as they are. Its comment "touched per symbol" becomes understated
  but stays true, so leave it alone: shared manifests are applied by hand.
- No change to the request budget, the round-robin resume, `prune_accumulators`, side
  classification or the ingest client.
- No fix for the trade-id collision found while answering the issue's last observation (see Open
  questions, Q1). That is a separate issue.
- No heartbeat inside the trades-page loop of `AlpacaDataClient.trades` (see Open questions, Q3).

## Spec

### Problem Statement

On 2026-10-30, the collector's first full session, up to 46 of 59 symbols have no
`darkpool_bar_5m` rows and no prints for 09:35–10:10 ET. That is 228 missing symbol-bars, about 23%
of the open hour. At the open, each cycle spends its 1,000-request budget, mostly on NBBO lookups,
so symbols' cursors fall up to about 35 min behind. That is harmless as long as the process keeps
running. At about 10:18 ET the container restarted (`restarts=1`). Cursors live only in memory, so
every symbol restarted at `bucket5m(now − 10 min)`, and each lagging symbol's range between its
cursor and that floor was never fetched. The likely restart trigger is the liveness probe:
`/app/state/heartbeat` older than 180 s. The heartbeat is touched once per symbol, after `_fetch`
returns, and `_fetch` does every NBBO lookup for that symbol first. SPY or QQQ at the open can need
hundreds of lookups.

### Solution

1. Touch the heartbeat before every NBBO lookup inside a symbol's fetch. The longest gap between
   touches is then one HTTP request (15 s read timeout) rather than a whole symbol.
2. After each committed symbol, write every symbol's cursor (`last_t`) and floor to a JSON file in
   `STATE_DIR` (the writable `state` emptyDir that already holds the heartbeat). Write to a temp
   file in the same directory, then rename it over the target. When the collector starts and the
   file exists, is readable, and was written on the same ET poll day, resume each listed symbol at
   the start of the bucket that holds its saved `last_t`. This is the existing bucket-aligned
   restart rule, applied to the saved cursor instead of `now − lookback`. Treat a missing, corrupt
   or old file as absent; log a warning for corrupt and old files. An emptyDir survives a container
   restart (a liveness kill) but not a pod replacement, which is exactly the scope.

### User Stories

1. As the operator, I want a symbol with hundreds of NBBO lookups at the open not to trip the 180 s
   liveness probe, so that the collector is not killed in the middle of its busiest window.
2. As the operator, I want a container restart to resume each symbol from where it had got to, so
   that a lagging symbol's backlog is still fetched and no bars go missing.
3. As the operator, I want a resumed symbol to rebuild its current bucket from its first trade, so
   that the BFF's whole-bar upsert never replaces a complete bar with a partial one.
4. As the operator, I want a corrupt or truncated state file to be ignored and logged, never fatal,
   so that a bad write cannot crash-loop the pod.
5. As the operator, I want yesterday's state file to be ignored, so that the first restart of a new
   day does not trawl the overnight tape from yesterday's cursor.
6. As the operator, I want a pod replacement (deploy) to behave exactly as today
   (`now − lookback`), so that the change has no deploy-time surprises.
7. As the operator, I want a failed state write to log and let the cycle continue, so that a full
   or unwritable state dir degrades to today's behaviour instead of stopping ingest.
8. As the operator, I want symbols added to the universe since the file was written to start at
   `now − lookback`, and symbols removed from it to be ignored, so that editing the universe
   ConfigMap needs no state cleanup.
9. As a dashboard reader, I want the open hour's bars to be complete after a restart, so that the
   dark share at the open is not silently missing a quarter of its symbols.
10. As a maintainer, I want the module docstring to describe the persistence and its limits
    (container restart yes, pod replacement no), so that the next reader does not trust a stale
    "no cursor persistence" note.

### Implementation Decisions

- **Heartbeat.** `DarkpoolCollector` already takes a `heartbeat` callable. The per-symbol fetch
  calls it before each `quote_before`, and the per-symbol `finally` touch stays. Touching on every
  lookup rather than every N is simplest: one `utime` against an HTTP round-trip. A symbol with no
  kept prints therefore still touches exactly once, so the existing
  `test_heartbeat_callback_fires_once_per_symbol` stays valid unchanged.
- **State file.** `DarkpoolCollector` gains an optional `state_path` (default `None`, which means
  no persistence, as today, so every existing test is unaffected). `_run` passes
  `state_dir / "cursors.json"`.
- **Format.** JSON: `{"version": 1, "poll_day": "<ET date of the write, YYYY-MM-DD>",
  "symbols": {"<SYM>": {"last_t": "<RFC-3339 UTC>", "floor": "<RFC-3339 UTC>"}}}`. Use the existing
  `format_ts` and `parse_ts`. `last_ids` is not persisted: the resume restarts at a bucket start,
  before `last_t`, so the ids at `last_t` are re-read and filtered by a fresh cursor anyway.
- **Write.** After the cursor of a committed symbol advances (both the full commit and the
  budget-cut prefix commit), serialise the whole cursor map, write it to a temp file in the same
  directory, then `os.replace` it onto `state_path`. No fsync: the emptyDir does not survive a node
  crash anyway. Any `OSError` is logged as a warning and the cycle carries on. Symbols whose fetch
  failed are written with their unchanged cursor, because the whole map is written each time.
- **Load.** At construction, when `state_path` is set and the file exists. If the JSON is invalid,
  the version is wrong, a timestamp does not parse, or `floor > last_t`, treat the file as absent
  and log a warning naming the reason. If `poll_day` is not the ET date of `clock()` at
  construction, treat the file as absent and log at info level with both dates. Otherwise, for each
  universe symbol in the file, set `floor = bucket5m(last_t)` and `cursor = Cursor.start(floor)`.
  `bucket5m(last_t) ≥ saved floor` always holds, so the saved floor serves only as a sanity check
  and a debugging aid.
- **Bars across a resume.** The accumulators start empty after a restart, as today. The resumed
  bucket is rebuilt from its first trade (the floor is inclusive), so its upsert is whole. Earlier
  buckets were complete before `last_t` and are not touched. `prune_accumulators` is unchanged.
- **Prints across a resume.** Re-read prints in the resumed bucket are re-posted, and the BFF
  inserts them ON CONFLICT DO NOTHING on (symbol, ts, trade_id). No change is needed.
- **Docstring.** Replace the "There is no cursor persistence" paragraph in `darkpool_main` with the
  new rule: same-day container restarts resume at the saved cursor's bucket start, and pod
  replacements use `now − lookback`. Keep the explanation of why the restart point is
  bucket-aligned.

### Testing Decisions

- Test external behaviour only, through `DarkpoolCollector.cycle()`, using the HTTP fakes that
  already exist in `tests/test_darkpool_main.py` (`FakeAlpaca` / `FakeBff` on
  `httpx.MockTransport`, `Clock`, `make_collector`). Use pytest's `tmp_path` for the state dir.
  Do not mock internal collaborators, and do not assert on the private fetch helper.
- Observable outputs to assert on: the `start` param of the trades request Alpaca receives, the
  bars and prints the fake BFF receives, the public `cursors` / `floors` attributes (existing tests
  already read them), heartbeat invocations recorded against the fake clock, and log records via
  `caplog`.
- Prior art: `test_restart_cursor_is_bucket_aligned_and_never_posts_an_earlier_bucket` (restart
  floor and first posted bucket), `test_budget_hit_mid_symbol_commits_the_prefix_and_resumes_from_it`
  (prefix commit), `test_heartbeat_callback_fires_once_per_symbol`.

### Out of Scope

DB-backed resume across pod replacement; refilling 2026-10-30 09:35–10:10 ET; manifest changes;
the trade-id collision bug (Q1); heartbeat in trades paging (Q3).

### Further Notes

- **Answer to the issue's last observation ("1,757 prints labelled buy/sell/mid lie more than 0.1%
  outside their NBBO on 10-30: does #1013's fix cover them?").** Mostly yes. The remainder is not
  #1013's band at all but a different bug. Evidence comes from read-only SELECTs on the dashboard
  `darkpool_print` table for ET date 2026-10-30. Rows are labelled buy/sell/mid with bid and ask
  > 0; "band" means outside [bid − D, ask + D], D = max(spread, 10 bp of mid).
  - 1,757 matches the issue's count: 503 buy, 1,038 mid, 216 sell.
  - 1,358 of them have a stored `quote_ts` after the print or more than 5 s before it. `side()`
    returns `unknown` for such a quote in both the pre-#1021 and post-#1021 code, so these labels
    did not come from `side()` with the stored quote (see Q1).
  - 399 have a valid quote age. 104 of them are inside the band, which the #1013 rule allows when
    the spread is more than 10 bp. 295 are outside the band. Of those, 292 were ingested before
    11:52Z (07:52 ET), when the 04:00 ET pod still ran the pre-#1021 image (#1021 merged 10:51Z;
    ReplicaSet `darkpool-collector-668d56544d` was created at 11:51:37Z). That is expected. The
    other 3 (AAPL, ids 15562 / 15719 / 15865, 09:30 ET) were ingested after the fix, but they are
    exchange `Q` rows, so they belong to Q1, not to a gap in the band.
  - Conclusion: no D print with a valid quote age was labelled outside the band after #1021 was
    deployed. The band rule holds. The residual is Q1.
- PR body must mention: (a) the refill of 2026-10-30 09:35–10:10 ET is the operator's decision;
  (b) the fix does nothing for a pod replacement; (c) Q1 as a follow-up issue to file.

## Steps

### Step 1 — Heartbeat inside the NBBO lookup loop

**Goal:** a symbol whose NBBO lookups take longer than 180 s in total cannot leave the heartbeat
older than one lookup.
**Blocked by:** none.
**Seams:**
- `DarkpoolCollector.cycle()` with a `heartbeat` callable that records `Clock.now`, and a
  `FakeAlpaca` whose quotes handler advances the same `Clock`. Catches: any regression to touching
  only per symbol or per cycle. Misses: the real file `touch` and the probe's `stat` arithmetic
  (`run_loop` already passes `heartbeat_path.touch`, and `test_heartbeat_touched_every_cycle`
  covers the file).

**Success checklist:**
- [ ] New test (name it e.g. `test_heartbeat_is_touched_between_nbbo_lookups_of_one_symbol`) has
      one symbol with 20 kept D prints, and each quote request advances the clock 20 s. It asserts
      that the maximum gap between consecutive recorded heartbeat times, and between cycle start
      and the first touch, is ≤ 20 s — evidence: `uv run pytest -q tests/test_darkpool_main.py -k
      heartbeat` passes.
- [ ] The same new test fails on the unmodified code — evidence: the builder runs it before the
      production change and records the assertion failure (one touch, at about 400 s).
- [ ] `test_heartbeat_callback_fires_once_per_symbol` passes unmodified — evidence: `git diff
      main -- tests/test_darkpool_main.py` shows no edit to that test.
- [ ] Full suite and lint are green — evidence: `cd services/signal-source-discord && uv run ruff
      check . && uv run pytest -q` exits 0.
- [ ] The diff touches only `ohmytradeagent_sidecar/darkpool_main.py` and
      `tests/test_darkpool_main.py` — evidence: `git diff --stat main`.

**Failure checklist** (any one means STOP and report):
- [ ] The test needs to patch or mock the private fetch helper, or `AlpacaDataClient` internals,
      instead of driving `FakeAlpaca` over `MockTransport`.
- [ ] An existing test has to be edited, weakened or deleted to pass.
- [ ] The change needs `AlpacaDataClient` (in `darkpool_collector.py`) to learn about heartbeats.
      That widens the scope into the client (see Q3).
- [ ] Any file outside `services/signal-source-discord/` must change.

### Step 2 — Persist cursors to STATE_DIR and resume at the bucket start

**Goal:** a container restart on the same ET day resumes each symbol at `bucket5m(saved last_t)`.
A corrupt, old or missing file falls back to `bucket5m(now − lookback)`.
**Blocked by:** none. It is independent of Step 1. Run it after Step 1 on the same branch so the
two do not conflict in `darkpool_main.py`.
**Seams:**
- `DarkpoolCollector(state_path=…)` construction followed by `cycle()`, observed through the
  fake Alpaca's trades-request `start` param, the fake BFF's bars, and `cursors` / `floors`.
  Catches: a resume from the wrong point, a partial first bar, an old-day file being honoured, and
  a corrupt file crashing startup. Misses: real emptyDir semantics across a container restart
  (k8s behaviour, verified only by the post-deploy observation below) and rename atomicity under a
  crash.
- The state file on disk (`tmp_path / "cursors.json"`), read only to corrupt it or to back-date
  its `poll_day` for the fallback tests. Do not assert on its exact layout beyond what those tests
  need. Catches: the write actually happens after a committed symbol. Misses: nothing beyond the
  above.

**Success checklist:**
- [ ] Resume test: cycle 1 at 09:40 ET commits QQQ trades up to 09:38:10 ET. A new collector with
      the same `state_path` and the clock at 10:10 ET (lookback floor would be 10:00) sends a first
      trades request whose `start` is `2026-10-29T13:35:00Z` (bucket of 09:38:10), and
      `floors["QQQ"]` equals that. The first bar posted is the 13:35Z bucket, with `total_trades`
      counting every trade of that bucket, not only those after `last_t` — evidence: the test
      passes.
- [ ] Budget-cut test: a cycle that stops mid-symbol on the budget writes that symbol's prefix
      cursor, and a new collector resumes at that prefix cursor's bucket start — evidence: the test
      passes.
- [ ] Fallback tests (parametrised or separate): an invalid-JSON file, a file with
      `floor > last_t`, a `poll_day` of the previous ET day, and no file at all each give a first
      trades request `start` of `bucket5m(now − 10 min)`. None of them raises. The first two log a
      WARNING and the old-day case logs at least INFO (assert via `caplog`) — evidence: the tests
      pass.
- [ ] Universe drift test: the file holds `QQQ` and `OLD`, and the universe is `["QQQ", "NEW"]`.
      `NEW` starts at `bucket5m(now − lookback)`, and `OLD` never appears in any Alpaca request —
      evidence: the test passes.
- [ ] Write failure does not stop the cycle: with `state_path` pointing into a directory that does
      not exist (or a read-only dir), `cycle()` still posts bars and logs a WARNING — evidence: the
      test passes.
- [ ] No temp file is left next to the target after a successful cycle — evidence: the resume test
      asserts that `tmp_path` holds only `cursors.json`.
- [ ] `_run` passes `state_dir / "cursors.json"` — evidence: `grep -n 'cursors.json'
      services/signal-source-discord/ohmytradeagent_sidecar/darkpool_main.py` shows it in `_run`.
- [ ] The module docstring no longer contains "There is no cursor persistence" and does state the
      container-restart / pod-replacement split — evidence: `grep -c 'There is no cursor
      persistence' …/darkpool_main.py` prints 0.
- [ ] Every pre-existing test passes unedited (they construct without `state_path`) — evidence:
      `uv run pytest -q` exits 0, and `git diff main -- tests/` shows only additions.
- [ ] Lint is green — evidence: `uv run ruff check .` exits 0.

**Failure checklist** (any one means STOP and report):
- [ ] The resume would start any symbol at its exact `last_t` instead of its bucket start, or
      would post a bar for the resumed bucket built from fewer trades than the bucket holds.
- [ ] The state file has to live outside `STATE_DIR`, or anything has to be written to the root
      filesystem: the pod runs `readOnlyRootFilesystem: true`.
- [ ] A load or write error can raise out of the constructor or `cycle()`.
- [ ] The change needs a manifest edit (`66-darkpool-collector.yaml`), a BFF change, or a schema
      change.
- [ ] `prune_accumulators`, the budget/round-robin logic, or `side()` has to change to make a
      test pass.
- [ ] An existing test has to be edited, weakened or deleted.

## Repo gates

- `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q` (the CI job's two
  gates for this service).
- Python only: no spotless, no Temporal version gate, no schema regeneration.
- A merge under `services/signal-source-discord/` rebuilds the shared image, and the deploy rolls
  the live Discord signal watcher (trading path). The local deploy agent holds that until the
  market is closed. Do not bypass it, and do not merge-and-roll during RTH.
- The darkpool-collector Deployment uses `:latest` with `imagePullPolicy: Always`. It picks up the
  new image only when its pod is restarted or replaced (it was last replaced at 2026-10-31
  00:39Z), so confirm the running image digest after the deploy before you rely on the fix.
- No change to `infra/k8s/66-darkpool-collector.yaml`. Shared manifests need a manual
  `kubectl apply`, and none is needed here.
- PR body: `Closes #1032`, and mention the refill decision and Q1.
- Post-deploy observation (operator, not a builder checklist item): on the next session, if
  `restarts` increments, the collector logs a resume from `cursors.json` and there is no bar gap
  for lagging symbols.

## Operator acceptance (2026-10-31)

All open-question assumptions accepted by operator 2026-10-31. Q1 is filed as its own issue (out of
scope for this PR). Q2–Q5 assumptions apply as written.

## Open questions

- **Q1 — trade-id collision (a real gap, but not in #1013's band).** `DarkpoolCollector._fetch` and
  `cycle()` key `sides` and `quotes` by `trade_id` alone. Alpaca trade ids are unique only per
  exchange, so two trades of one symbol in one fetch can share an id. When that happens, (a) a lit
  trade whose id equals a kept D print's id is posted as a print with that print's quote and side,
  and (b) two D prints that share an id swap one quote/side between them, which also skews
  `buy_volume`/`sell_volume`. Measured on 2026-10-30: 1,799 posted rows with exchange ≠ `D`
  (keep() only passes `D`), 1,441 (symbol, trade_id) groups with more than one row, and every one
  of the 1,755 labelled rows with an impossible quote age has an id twin. Twin-free D rows with a
  bad age: 0. Longer fetch windows (lagging cursors, and the bucket-start resume in this plan) make
  collisions more likely. **Assumption:** out of scope for this PR. File a follow-up issue: key by
  (exchange, trade_id) or by trade identity, and clean up the bad rows with a DELETE of non-D
  prints after operator approval.
- **Q2 — persisting `floor`.** The issue says to persist "cursors and floors", but on load the
  floor is always recomputed as `bucket5m(last_t)`. **Assumption:** write both, as asked, and use
  the saved floor only as a sanity check (`floor ≤ last_t`). Drop it from the file if the reviewer
  prefers the smaller format.
- **Q3 — heartbeat during trades paging.** `AlpacaDataClient.trades` pages 10,000 rows at a time
  with no heartbeat. A symbol 35 min behind at the open could need several pages before its first
  NBBO lookup. **Assumption:** each page is one HTTP call (15 s read timeout), so a few pages stay
  well inside 180 s. Not in scope; revisit if a restart recurs with a heartbeat touched per lookup.
- **Q4 — "same poll day" definition.** **Assumption:** the ET calendar date of the write equals
  the ET date of `clock()` at startup. A restart at 03:59 ET still loads that day's file (the
  window opens at 04:00), and the first restart on a new day ignores yesterday's file.
- **Q5 — refill of 2026-10-30 09:35–10:10 ET.** Operator decision, not in this PR.
