# PLAN — 2026-11-08 darkpool-oom-bounded-fetch

Issue: #1041. Base: `c8a9a317` (origin/main). Branch: `fix/1041-darkpool-oom-bounded-fetch`.
Baseline verified in this worktree: `cd services/signal-source-discord && uv run pytest -q
tests/test_darkpool_collector.py tests/test_darkpool_main.py` → 88 passed; `uv run ruff check` clean.
Scope addition from the operator (2026-11-08): refill the 11-05/11-06 gaps once the fix is live.

## Goal

The dark-pool collector never holds more than a bounded number of trades in memory, however far
behind one symbol's cursor is: a backlog drains over successive cycles while the other symbols keep
being served, and a restart resumes the round-robin where it stopped instead of at the head of the
universe. Once live, the collector itself drains the 11-04-afternoon → 11-06 backlog from the saved
cursors, so every ET day 11-04, 11-05 and 11-06 ends with all 59 symbols covered.

**Done when:**
- `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q` passes, including
  the new tests named in Steps 1 and 2 (each fails on `c8a9a317`).
- In a `DarkpoolCollector.cycle()` test where one symbol's backlog spans more pages than the cap:
  that symbol issues exactly `max_pages` trades requests per cycle, its cursor advances every cycle,
  the symbol after it in the universe is served in the SAME cycle, and after enough cycles the
  cumulative bar equals the full backlog (no trade lost, none double-counted).
- `AlpacaDataClient.trades()` has no code path that appends more than `max_pages` pages to one list.
- A restart from a saved `cursors.json` starts the first cycle at the symbol the previous cycle was
  cut at; a `cursors.json` written by the current code (flat `{symbol: ts}`) still loads.
- Memory bound stated in code, from measurement (this session, `tracemalloc`, Python 3.12, real
  `Trade.from_alpaca` on 10k-row pages): ~385 B retained per `Trade`, ~3.7 MiB per 10k page, plus
  ~6.5 MiB transient per page for the parsed JSON and response body. Cap 10 pages → ≤ 100,000
  trades ≈ 37 MiB retained, ≈ 44 MiB peak, on a ~40 MiB idle process → < 130 MiB even with a 2×
  fragmentation allowance, against the 256 Mi limit. (20 pages measured ≈ 80 MiB peak: too close.)
- The current pod's `cursors.json` (59 symbols, AMZN at 2026-11-05T14:3x Z, the symbols after
  AMZN at or before that) is saved off the cluster BEFORE the roll (Step 0) and seeded into the new
  pod (Step 3); the drain runs by itself in the Monday 04:00 ET poll window.
- Verification (Step 4, read-only SELECTs on the `dashboard` DB): for ET days 11-04, 11-05 and
  11-06 `count(DISTINCT symbol) = 59` in `darkpool_print` and in `darkpool_bar_5m`, with prints and
  bars per day in the range of 11-03/11-04 (66,396 / 87,206 prints); and the Monday cycle line
  settles at `skipped=0 truncated=0` with a flat restart count across the 09:30 ET open.

## Non-goals

- No WebSocket, ever (standing rule; module docstrings and the manifest header say why).
- No change to the request-budget semantics (`DEFAULT_MAX_REQUESTS`, `X-Ratelimit-Remaining` floor,
  429 handling) beyond what the page cap needs.
- No env knob for the cap; it is a module constant (see Open questions 1).
- No manifest change (memory limit stays 256 Mi, the state volume stays an emptyDir).
- No refill tooling: the refill is the collector's own drain from seeded cursors (Step 3); no
  one-off script, no historical-mode flag.
- The 2026-10-30 09:35–10:10 gap (#1032) stays OUT of scope.
- No heartbeat per trades page (see Open questions 5).
- No BFF change; the wire contract (`print_to_wire`, bars) is byte-identical.

## Spec

### Problem Statement

Since 2026-11-04 the `darkpool-collector` pod OOM-kills every ~10 minutes (353 restarts by 11-07).
AMZN's saved cursor is stuck at 2026-11-05T14:30Z; every start re-requests the same paged trades
window and dies before anything is committed. Coverage collapsed from 59 symbols (11-04) to 6
(11-06): symbols ordered before AMZN get data, everything after starves. ET days 11-05 and 11-06
(and the 11-04 afternoon for the symbols after AMZN) are missing prints and bars.

Root cause, verified in code on `c8a9a317`:
- `AlpacaDataClient.trades()` joins every page of the window into one list before returning. The
  only stop is the per-cycle request budget (1,000 requests = up to 10M trades), far beyond what
  256 Mi holds, so memory dies before `BudgetExhausted` can fire.
- `DarkpoolCollector.cycle()` commits a symbol (aggregate, cursor advance, prints) only after
  `_fetch` returns; the prefix-commit path exists only for a budget stop during NBBO lookups. A kill
  during the trades pages commits nothing, `_save_cursors()` never runs, the cursor never moves.
- `_next_start` (round-robin resume) is in memory only; every restart begins at the universe head.
- The docstring guarantee "a symbol that alone needs more than one cycle's budget still advances
  every cycle" holds for requests, not for memory.

### Solution

Fetch at most a fixed number of trades pages per symbol per cycle. The pages fetched are a
time-ordered prefix of the window (Alpaca `sort=asc`), so the EXISTING commit path handles them:
aggregate, advance the cursor to the last trade seen, queue the kept prints, save. A symbol whose
backlog exceeds the cap is marked truncated, logged, and the cycle moves on to the next symbol; the
next cycle re-fetches that symbol from its new cursor. A backlog therefore drains at `cap × 10,000`
trades per cycle while the rest of the universe keeps being served. The round-robin start is saved
in `cursors.json` next to the cursors so a restart resumes the rotation.

The refill is that same drain applied to the incident's own cursors. Every symbol's saved cursor
points exactly where its data stopped (saved only after a stored post), so a collector that loads
that file and has the cap drains the whole gap forward with no new tool. The one obstacle is that
the roll replaces the pod and the state volume is an emptyDir, so the file must be copied out of the
current pod before the roll and copied into the new one, followed by a container (not pod) restart
so `_load_cursors` runs again.

### Self-drain mechanics, verified in code on `c8a9a317` (coordinator item a)

- **A cursor days behind is honoured.** `_load_cursors` sets `floors[sym] = cursors[sym].last_t =
  bucket5m(saved ts)` with no clamp to `now − lookback`; the lookback is used only for a symbol with
  NO saved cursor. `_fetch` requests `[cursor.last_t, now − 5 s]` and drops only `t.ts < floor`.
- **Old buckets are posted whole and not half-posted.** `aggregate` creates a `BarAccumulator` per
  (symbol, bucket) for every trade committed this cycle and returns the touched keys; `cycle()`
  builds `bars` from `touched` BEFORE `prune_accumulators`, so every bucket a cycle touched is
  posted, however old. Pruning then keeps the 2 newest buckets per symbol; the bucket cut by the
  page cap (or a budget stop) is by construction that symbol's newest, so its accumulator survives,
  the next cycle adds the rest of the bucket to it, and the bar is re-posted cumulative. The BFF
  upserts bars whole on (symbol, bucket) (`ON CONFLICT DO UPDATE`), so a bucket spanning a cycle
  boundary is overwritten with the fuller version, never summed. Prints dedupe on
  (symbol, ts, trade_id) (`ON CONFLICT DO NOTHING`). A page boundary inside one instant is handled
  by `Cursor.seen_at_last_t` (tested in Step 1).
- **Load rewinds to the bucket start.** `Cursor.start(bucket5m(ts))` re-reads the saved cursor's
  bucket whole, so a bar partially stored on 11-05 is rebuilt complete, not overwritten with a tail.
- **Budget and rate limits.** Pages count against the 1,000-request cycle budget as today (≤ 10 per
  symbol). 100k trades of a heavy name hold more kept prints than the budget allows NBBO lookups
  for, so the drain is budget-bound: a `BudgetExhausted` mid-lookup commits the prefix, ends the
  cycle, and the next cycle resumes at that symbol (round-robin, existing behaviour). 1,000
  sequential requests per ~60 s cycle is a tenth of the account's 10,000/min, the
  `X-Ratelimit-Remaining < 2,000` floor and the 429 stop are unchanged, and at 04:00 ET the other
  consumers of the limit are idle. A `NO progress` rotation needs the pages alone to exhaust the
  budget, impossible with the cap (10 ≪ 1,000) unless the ratelimit floor trips.
- **Estimate.** Requests ≈ one NBBO lookup per missing kept print + pages. Missing prints ≈ 11-05
  (85k expected from 11-03/11-04 − 54k stored ≈ 30k) + 11-06 (≈ 85k − 24k ≈ 60k) + the 11-04
  afternoon for the symbols after AMZN → ~90–120k lookups → ~100–130 cycles at the budget. Pages:
  SPY (~0.6–0.9M trades/day) is 60–90 pages/day, QQQ 40–60; three days would be 200–300 pages for
  SPY at the 10-page cap = 20–30 cycles for SPY alone, but SPY and QQQ sit BEFORE AMZN in the
  universe and kept advancing through the loop (they are 2 of the 6 symbols covered on 11-06), so
  their cursors are at ~11-06 20:00 ET and the weekend tape is empty; the heavy drain is AMZN
  (~1.5 sessions, ~100–150 pages) and the 52 symbols after it. A cycle is ~60 s sleep + ~30–50 s of
  sequential requests → 100–130 cycles ≈ 2.5–4 h.
- **Poll window.** `in_poll_window` is weekdays 04:00–20:00 ET: a weekend roll + seed does nothing
  until Monday 04:00 ET, which is exactly when the drain should run; from 04:00 it finishes by
  ~07:00–08:00 ET, before the 09:30 open. If it is still draining at the open, the live tape lags
  (cursors) but nothing is lost. During the drain the cycle line shows `skipped>0` (budget stops)
  and `truncated>0` (page caps) — expected, not a regression; the dead-man only greps the prefix.

No code change is needed for the drain beyond Steps 1–2 (Step 2's acceptance of the flat file shape
is what lets the seeded file load).

### User Stories

1. As the operator, I want the collector's memory bounded regardless of how far one cursor lags,
   so that a backlog never OOM-kills the pod.
2. As the operator, I want a lagging symbol to advance its cursor every cycle, so that a backlog
   drains instead of being re-fetched forever.
3. As the operator, I want the other symbols served in the same cycle as a lagging one, so that one
   bad symbol cannot collapse coverage for the universe.
4. As the operator, I want a restart to resume the round-robin where it stopped, so that symbols
   late in the universe are not starved across restarts.
5. As the operator, I want the cycle summary to say how many symbols were truncated, so that the
   dead-man log and a `kubectl logs` grep show a backlog draining.
6. As the operator, I want a `cursors.json` written by today's pod to load unchanged after the roll,
   so that the incident's cursors can be seeded and the gap refilled without a tool.
7. As a `/dark-pool` page user, I want 11-04, 11-05 and 11-06 to show all 59 symbols with bars and
   prints equal to what an uninterrupted collector would have stored.
8. As a `/dark-pool` page user, I want bars and prints from a drained backlog to be exactly what a
   single fetch would have produced (no double counts, no gaps), so that the page stays trustworthy.
9. As the reviewer, I want the memory bound derived from a measurement stated next to the constant,
   so that the cap can be re-derived when the memory limit or the Trade shape changes.

### Implementation Decisions

- The cap lives on the data client as `max_pages` (default: new module constant
  `MAX_TRADE_PAGES = 10`), beside `max_requests`. `trades()` stops after `max_pages` pages when a
  `next_page_token` remains and reports it: it returns `(trades, truncated)`. The default is bounded;
  there is no unbounded mode.
- The collector's `_fetch` passes the flag through (its tuple grows by one element). `cycle()`
  commits as today; when the symbol was truncated it increments a new `CycleStats.truncated`, logs
  one warning naming the symbol, pages fetched, trades committed and the new cursor instant, and
  CONTINUES to the next symbol (it does not break and does not set `_next_start`: every symbol
  resumes from its own cursor). A budget stop (`exhausted`) keeps today's break-and-resume behaviour
  and takes precedence when both happen.
- Correctness of the prefix cut relies on what the cursor already guarantees: Alpaca's `start` is
  inclusive, so the next fetch begins at the committed `last_t` and `seen_at_last_t` drops the
  same-instant trades already committed, including a page boundary that splits one instant.
- `cursors.json` gains a shape: `{"cursors": {symbol: ts}, "next_start": symbol}`. The loader
  accepts the old flat `{symbol: ts}` shape (no `cursors` key) with `next_start` = head of the
  universe. `next_start` is a symbol name, not an index, so a universe edit cannot misplace it; a
  saved symbol no longer in the universe means start at the head. Saved and loaded together with
  the cursors, under the same "only after a stored post" rule.
- The cycle summary log line keeps its `darkpool cycle:` prefix (the homelab dead-man greps it) and
  gains `truncated=%d`.
- Docstrings: the collector module paragraph on per-symbol isolation and the main module
  paragraphs on the budget guarantee and on `cursors.json` are corrected to describe the page cap
  and the saved round-robin start. No other prose changes.
- The refill is operational (Steps 0, 3, 4), uses only `kubectl exec` with `cat` and `sh -c 'kill 1'`
  (the image is Ubuntu noble; `/bin/sh` is what the liveness probe already runs), and posts through
  the existing BFF ingest.

### Testing Decisions

- A good test observes behaviour through the public seams only: the HTTP requests the client
  issues (symbols, `start`, `page_token`, count) and what reaches the BFF (bars, prints), plus the
  cursor values and `CycleStats`. No test mocks `_fetch`, `_get`, or any other internal.
- Seams (existing, both already used by the suite):
  1. `AlpacaDataClient.trades()` over `httpx.MockTransport` (`tests/test_darkpool_collector.py`'s
     `FakeAlpaca`, which already paginates). Catches: the cap stops the loop, the flag is right, no
     extra request leaves the process. Misses: what the collector does with the prefix.
  2. `DarkpoolCollector.cycle()` over `FakeAlpaca` + `FakeBff` (`tests/test_darkpool_main.py`).
     Catches: prefix commit, cursor advance, other symbols served, cumulative bars, persistence,
     round-robin resume after restart. Misses: real Alpaca paging quirks and real memory.
- The main-test `FakeAlpaca` serves one page per symbol today; it gets an optional `page_size`
  that splits its rows into pages with `next_page_token`, default off so existing tests are
  untouched.
- Memory is NOT asserted in a unit test (tracemalloc in CI is slow and flaky). The bound is a
  computation from the measurement above, written beside the constant; the test asserts the
  request count per symbol per cycle, which is what bounds memory by construction.
- The drain is verified live by SQL (Step 4), not by a unit test: its mechanics are the existing
  ones already covered by `test_bar_is_resent_with_cumulative_totals_across_cycles`,
  `test_rebuilt_bucket_after_restart_is_whole` and the Step 1 incident test.
- Prior art: `test_budget_hit_mid_symbol_commits_the_prefix_and_resumes_from_it`,
  `test_request_cap_skips_the_rest_of_the_cycle_and_resumes_next_cycle`,
  `test_restart_resumes_from_the_saved_cursor_rewound_to_its_bucket`,
  `test_trades_pagination_joins_pages_in_order`.

### Out of Scope

The 10-30 09:35–10:10 gap, a memory-limit bump, an env knob for the cap, a per-page heartbeat, a
persistent state volume, any BFF or dashboard change, any change to the universe or the manifest.

### Further Notes

- Why the incident self-heals on deploy and why that is not the fix: the roll creates a new pod; the
  `state` volume is an emptyDir, so `cursors.json` (with the stuck AMZN cursor) is gone and the
  collector cold-starts at `now − 10 min`. Without Step 0/3 that also throws away the only record of
  where each symbol's data stopped, i.e. the refill. The next long outage (node reboot, image roll
  during the open, BFF down for an hour) would rebuild the same backlog; the cap is what makes that
  survivable.
- Drain rate at cap 10: 100,000 trades per symbol per cycle, ~20× AMZN's open-time rate, so a
  symbol a full session behind catches up in well under an hour of cycles when the budget allows.
- Liveness: the heartbeat fires after each symbol and per NBBO lookup. Worst case between beats is
  now bounded at 10 pages × 15 s read timeout = 150 s, under the probe's 180 s staleness, which
  itself needs 3 consecutive failures (90 s) before a kill.

## Steps

### Step 0 — Save the incident's cursors off the cluster (operator/orchestrator, read-only, BEFORE any merge)

**Goal:** The current pod's `/app/state/cursors.json` is copied to the homelab and to this
worktree's PR evidence, so the roll cannot destroy it. Must happen before the PR merges.

**Blocked by:** none. Blocks Step 3.

**Seams:** `kubectl exec ... cat` on the running container (read-only). Catches: the file exists
and holds the expected cursors. Misses: nothing to miss; no state is changed.

**Commands** (on `ssh ridopark@192.168.10.123`):
```
POD=$(kubectl -n copytrade get pod -l app=darkpool-collector -o jsonpath='{.items[0].metadata.name}')
kubectl -n copytrade get pod "$POD"            # expect Running (out of the poll window it sleeps, so stable)
kubectl -n copytrade exec "$POD" -- cat /app/state/cursors.json > ~/darkpool-cursors-1041-$(date +%Y%m%d%H%M).json
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(len(d), min(d.values()), max(d.values()), d.get("AMZN"))' ~/darkpool-cursors-1041-*.json
```

**Success checklist:**
- [ ] The saved file is a flat JSON object with 59 keys — evidence: the `len` printed.
- [ ] `AMZN` is at `2026-11-05T14:3x` Z, and the minimum timestamp is at or before it (the symbols
      after AMZN stopped at or before the stuck point) — evidence: the printed min/max/AMZN.
- [ ] The maximum is around 2026-11-06T2x Z (the symbols before AMZN kept advancing) — evidence: the
      printed max.
- [ ] A copy of the file's content (or its summary line) is pasted into the PR description under
      "Refill evidence" — evidence: the PR body.

**Failure checklist:**
- [ ] The file is missing or unreadable (the emptyDir was recreated since 11-07, e.g. a node reboot):
      then the gap cannot be refilled from cursors; STOP and report; the fallback is a hand-written
      seed file (all 59 symbols at `2026-11-04T13:00:00Z`), which re-reads 11-04 whole and is an
      operator decision, not this plan's default.
- [ ] Fewer than 59 keys, or AMZN later than 11-05: the premise is wrong; STOP and report.
- [ ] Any command that is not `get`/`exec ... cat`.

### Step 1 — Per-symbol page cap with prefix commit (the incident test)

**Goal:** `AlpacaDataClient.trades()` fetches at most `max_pages` pages and reports truncation;
`DarkpoolCollector.cycle()` commits the prefix, advances the cursor, logs it, counts it in
`CycleStats.truncated`, and continues with the next symbol. The incident is reproduced by a test
that fails on `c8a9a317` and passes after.

**Blocked by:** none.

**Files:** `services/signal-source-discord/ohmytradeagent_sidecar/darkpool_collector.py`
(`MAX_TRADE_PAGES`, `AlpacaDataClient.__init__`, `trades`),
`services/signal-source-discord/ohmytradeagent_sidecar/darkpool_main.py` (`CycleStats`, `cycle`,
`_fetch`, module docstring), `services/signal-source-discord/tests/test_darkpool_collector.py`,
`services/signal-source-discord/tests/test_darkpool_main.py` (`FakeAlpaca.page_size`, new tests).

**Seams:**
- `AlpacaDataClient.trades()` via `MockTransport` — catches the cap and the flag; misses the commit.
- `DarkpoolCollector.cycle()` via `FakeAlpaca`/`FakeBff` — catches prefix commit, cursor advance,
  continuation to the next symbol, cumulative bars; misses real paging and real memory.

**Tests to write first (names are binding; bodies are the builder's):**
- `test_darkpool_collector.py::test_trades_stops_at_the_page_cap_and_reports_more`: 3 pages,
  `client(max_pages=2)` → ids of pages 1–2 only, `truncated is True`, exactly 2 requests; the same
  pages with `max_pages=3` → all ids, `truncated is False`.
- `test_darkpool_collector.py::test_trades_pagination_joins_pages_in_order`: adapt the call to the
  new `(trades, truncated)` shape and add `assert truncated is False`; every existing assertion stays.
- `test_darkpool_main.py::test_issue_1041_backlog_symbol_drains_over_cycles_without_starving_the_rest`:
  universe `["AMZN", "ZS"]`; AMZN has 10 lit trades 1 s apart served with `page_size=2`, ZS one
  trade; `max_pages=2`. Cycle 1: AMZN trades requests == 2 and no third, `stats.truncated == 1`,
  `stats.skipped == 0`, the posted batch has bars for BOTH symbols, AMZN bar `total_trades == 4`,
  `cursors["AMZN"].last_t` == the 4th trade's `ts`. Cycle 2: AMZN's first request has `start` ==
  that `last_t` and no `page_token`; cursor at trade 8. Cycle 3: `stats.truncated == 0`, AMZN bar
  cumulative `total_trades == 10`, `total_volume` == the sum of all 10 sizes (nothing lost, nothing
  doubled). ZS's trade is posted once.
- `test_darkpool_main.py::test_page_cap_boundary_inside_one_instant_is_not_double_counted`: two
  AMZN trades at the same `ts` split across the page boundary (`page_size` so the cut lands between
  them); after two cycles the bar's `total_trades` counts each once.

**Success checklist** (all must be checked, each with the evidence to collect):
- [ ] The four tests above exist with exactly those names; the new client test and the two new
      `cycle()` tests FAIL before the implementation — evidence: the builder records the RED run's
      assertion lines before implementing.
- [ ] All pass after — evidence: `cd services/signal-source-discord && uv run pytest -q` output
      line (`N passed`, N ≥ 88 + 3, whole suite green).
- [ ] `uv run ruff check .` clean — evidence: its output.
- [ ] `MAX_TRADE_PAGES = 10` is defined in `darkpool_collector.py` with a comment stating the
      measured ~385 B/Trade, ~3.7 MiB per page retained, ~6.5 MiB transient per page, the 256 Mi
      limit and the resulting bound — evidence: the comment text in the diff.
- [ ] `trades()` returns `(list[Trade], bool)` and its loop returns as soon as `max_pages` pages
      were fetched with a token remaining — evidence: the function body in the diff; no other loop
      or helper accumulates pages.
- [ ] `cycle()` does not `break` and does not set `_next_start` on truncation alone — evidence: the
      incident test's "both symbols in the same batch" assertion and `stats.skipped == 0`.
- [ ] The cycle summary line still starts with `darkpool cycle:` and includes `truncated=%d` —
      evidence: `grep -n '"darkpool cycle: ' darkpool_main.py` and the format string.
- [ ] The `darkpool_main` module docstring no longer claims the budget alone guarantees progress and
      describes the page cap; the `darkpool_collector` docstring mentions the cap — evidence: diff.
- [ ] No change under `services/tenant-dashboard-bff`, `dashboard/`, `infra/k8s/` — evidence:
      `git diff --stat c8a9a317` lists only the four files above (plus this plan).

**Failure checklist** (any one means STOP and report, do not work around it):
- [ ] A test needs to patch or mock `_fetch`, `_get`, `trades` or any other internal of the two
      modules to pass.
- [ ] An existing test's assertion must be weakened or deleted (adapting the unpack shape in
      `test_trades_pagination_joins_pages_in_order` is the ONLY permitted edit to existing tests).
- [ ] The implementation needs an unbounded mode (`max_pages=None`) or a code path that still joins
      all pages.
- [ ] Making the prefix commit work requires changing `Cursor`, `aggregate`, `prune_accumulators`,
      `print_to_wire`, or the BFF contract.
- [ ] The incident test passes on `c8a9a317` (then it does not reproduce the incident).
- [ ] Any WebSocket, streaming client, or change to the budget constants/semantics.

### Step 2 — Persist the round-robin start with the cursors

**Goal:** A restart resumes the round-robin at the symbol the previous (saved) cycle was cut at.
`cursors.json` carries `next_start`; a file in today's flat shape still loads (this is also what
makes Step 3's seed load).

**Blocked by:** none (independent regions of `darkpool_main.py`; build after Step 1 on the same
branch so the test file's `FakeAlpaca` already has `page_size`).

**Files:** `services/signal-source-discord/ohmytradeagent_sidecar/darkpool_main.py`
(`_load_cursors`, `_save_cursors`, module docstring), `services/signal-source-discord/tests/test_darkpool_main.py`.

**Seams:**
- `DarkpoolCollector` constructed with `state_path` + `cycle()` over `FakeAlpaca`/`FakeBff` —
  catches save shape, load of both shapes, resume order of the next process's requests; misses a
  real emptyDir and a mid-write kill (the existing tmp+replace write covers that already).

**Tests to write first:**
- `test_darkpool_main.py::test_restart_resumes_the_round_robin_at_the_saved_symbol`: universe
  `A1..A5`, each with one trade, `max_requests=3`, `state_path` set; cycle 1 of process 1 stops
  after A3 (as `test_request_cap_skips_the_rest_of_the_cycle_and_resumes_next_cycle`). A NEW
  collector from the same `state_path`: its first cycle's trades requests are for `A4, A5, A1` in
  that order (not `A1, A2, A3`).
- `test_darkpool_main.py::test_flat_cursor_file_from_before_1041_still_loads`: write
  `{"QQQ": "2026-10-29T13:38:00Z"}`; the collector's `floors["QQQ"]` is `13:35:00Z`, no
  "unreadable" warning, `_next_start == 0`; after one stored cycle the file parses as a dict with
  keys `{"cursors", "next_start"}` and `cursors["QQQ"]` present.
- `test_darkpool_main.py::test_saved_next_start_not_in_the_universe_starts_at_the_head`: file
  `{"cursors": {...}, "next_start": "GONE"}` → first request is for the universe's first symbol, no
  crash, no "unreadable" warning.

**Success checklist:**
- [ ] The three tests exist with those names; the first and third FAIL on the Step 1 commit (the
      second may pass before and must still pass after) — evidence: RED output pasted, then
      `uv run pytest -q` `N passed` with N ≥ Step 1's N + 3.
- [ ] `uv run ruff check .` clean — evidence: output.
- [ ] `_save_cursors` writes `{"cursors": {sym: ts}, "next_start": <symbol>}` in one atomic
      tmp+replace as today — evidence: the function body; the existing
      `test_failed_post_does_not_advance_the_saved_cursor` still passes (file unchanged on a failed
      post, so `next_start` is also not saved then).
- [ ] `_load_cursors` accepts both shapes and maps `next_start` to an index via the universe, 0 when
      absent or unknown — evidence: the two shape tests.
- [ ] `_load_cursors`'s cold-start-on-bad-file behaviour is unchanged
      (`test_corrupt_state_file_is_ignored` passes untouched).
- [ ] The `darkpool_main` module docstring sentence on `cursors.json` mentions the saved round-robin
      start — evidence: diff.
- [ ] `git diff --stat <step1 commit>` lists only `darkpool_main.py` and `test_darkpool_main.py`.

**Failure checklist:**
- [ ] `next_start` must be stored as an index, or the loader must raise on an unknown symbol.
- [ ] Backward compatibility needs a version field or a migration of the existing file.
- [ ] Any existing persistence test must be edited.
- [ ] The save happens anywhere other than the existing `_save_cursors()` call site (i.e. a
      rotation would be saved after a FAILED post).

### Step 3 — Seed the saved cursors into the new pod (operator/orchestrator, after the roll, before Monday 04:00 ET)

**Goal:** The post-roll pod loads the Step 0 file, so its first in-window cycle starts every symbol
where its data stopped and the backlog drains by itself.

**Blocked by:** Step 0; Steps 1–2 merged and `Roll darkpool-collector` complete (new pod Running on
the new image digest).

**Seams:** `kubectl exec` write of one file into the writable `state` emptyDir, then a container
restart (`kill 1` → restartPolicy Always → same pod, same emptyDir), then `kubectl logs`. Catches:
the loader's `darkpool cursor state loaded for 59 symbols` line. Misses: nothing about the drain
itself, which Step 4 verifies.

**Commands** (on the homelab; `SEED` is the Step 0 file):
```
POD=$(kubectl -n copytrade get pod -l app=darkpool-collector -o jsonpath='{.items[0].metadata.name}')
kubectl -n copytrade get pod "$POD" -o jsonpath='{.status.containerStatuses[0].imageID}{"\n"}{.status.containerStatuses[0].restartCount}{"\n"}'
kubectl -n copytrade exec -i "$POD" -- sh -c 'cat > /app/state/cursors.json' < "$SEED"
kubectl -n copytrade exec "$POD" -- cat /app/state/cursors.json | cmp - "$SEED" && echo SEEDED
kubectl -n copytrade exec "$POD" -- sh -c 'kill 1'      # container restart, NOT a pod delete
sleep 20; kubectl -n copytrade logs "$POD" --tail=20 | grep -E 'cursor state loaded|darkpool collector starting'
```
Timing: the roll happens on merge (weekend → the pr-deploy-agent approves at any hour, see Repo
gates). Seed any time after the new pod is Running and before Monday 04:00 ET; the collector sleeps
until then and the drain starts at 04:00. If the roll happens on Monday instead, seed immediately
after it: the drain then overlaps pre-market/RTH and the live tape lags for 2–4 h, nothing is lost.

**Success checklist:**
- [ ] The pod's image digest differs from the pre-merge one and the file written compares equal
      to the Step 0 file — evidence: the `imageID` line and `SEEDED`.
- [ ] After `kill 1` the SAME pod name shows `restartCount` +1 and the log has
      `darkpool cursor state loaded for 59 symbols` — evidence: the log grep.
- [ ] Monday ≥ 04:00 ET: the first cycle lines show `skipped>0` and/or `truncated>0` (the drain),
      later lines settle at `skipped=0 truncated=0`; `restartCount` unchanged through 10:00 ET —
      evidence: `kubectl logs --since=…` excerpt and `get pod`.
- [ ] `kubectl exec "$POD" -- cat /app/state/cursors.json` after the drain shows every symbol
      within a few minutes of now (new shape) — evidence: the file.

**Failure checklist:**
- [ ] `kubectl delete pod` or a rollout restart would be needed (that recreates the emptyDir and
      loses the seed); STOP, re-seed instead.
- [ ] The loader logs `darkpool cursor state unreadable` on the seed (shape not accepted: Step 2's
      compat is broken) — STOP, do not hand-edit the file.
- [ ] `restartCount` keeps rising Monday morning (OOM or liveness) — STOP; collect
      `kubectl describe pod` reason + the last cycle lines; the cap or liveness assumption is wrong.
- [ ] `NO progress` or 429 lines repeating — STOP; the budget assumption is wrong.

### Step 4 — Verify the refill (operator/orchestrator, read-only SQL, Monday after the drain)

**Goal:** ET days 11-04, 11-05 and 11-06 are whole in the dashboard DB.

**Blocked by:** Step 3 (cycle line at `skipped=0 truncated=0`).

**Seams:** `psql` SELECTs on the `dashboard` DB on `postgres-0` (user `temporal`), the same tables
the BFF reads. Catches: coverage and volume per ET day. Misses: per-bucket correctness against
Alpaca's own bars (the 10-30 comparison showed exact match; not repeated here).

**Query** (`ssh ridopark@192.168.10.123 kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d dashboard`):
```sql
SELECT (ts AT TIME ZONE 'America/New_York')::date AS et_day,
       count(*)               AS prints,
       count(DISTINCT symbol) AS symbols
FROM   darkpool_print
WHERE  ts >= '2026-11-02' AND ts < '2026-11-10'
GROUP  BY 1 ORDER BY 1;

SELECT (bucket AT TIME ZONE 'America/New_York')::date AS et_day,
       count(*)               AS bars,
       count(DISTINCT symbol) AS symbols,
       sum(total_trades)      AS trades
FROM   darkpool_bar_5m
WHERE  bucket >= '2026-11-02' AND bucket < '2026-11-10'
GROUP  BY 1 ORDER BY 1;

-- symbols still short on a day (expect no rows for 11-04..11-06)
SELECT d.et_day, u.symbol
FROM   (VALUES ('2026-11-04'::date), ('2026-11-05'), ('2026-11-06')) AS d(et_day)
CROSS  JOIN (SELECT DISTINCT symbol FROM darkpool_bar_5m WHERE bucket >= '2026-11-03' AND bucket < '2026-11-05') AS u
WHERE  NOT EXISTS (SELECT 1 FROM darkpool_bar_5m b
                   WHERE b.symbol = u.symbol
                     AND (b.bucket AT TIME ZONE 'America/New_York')::date = d.et_day)
ORDER  BY 1, 2;
```

**Success checklist:**
- [ ] `symbols = 59` for 11-04, 11-05 and 11-06 in BOTH result sets — evidence: the two tables.
- [ ] Prints for 11-05 and 11-06 are each within the 11-03/11-04 range (66,396–87,206) ± 30%, and
      11-04 is ≥ its pre-refill 87,206 — evidence: the first table. (Before: 54,182 / 24,309.)
- [ ] Bars per day for 11-05 and 11-06 are within ± 15% of 11-03/11-04's bar counts — evidence:
      the second table.
- [ ] The third query returns no rows — evidence: `(0 rows)`.
- [ ] Results pasted into the issue before it is closed (the PR's `Closes #1041` closes it on
      merge, so paste them as a comment on #1041 and in the PR).

**Failure checklist:**
- [ ] Any `INSERT`/`UPDATE`/`DELETE` would be needed to reach the numbers.
- [ ] `symbols < 59` on a day after the cycle line has settled — a symbol's seed cursor was wrong
      or a symbol errored repeatedly (`darkpool <sym> fetch failed` in the logs); report, do not
      hand-edit cursors.

## Repo gates

- Python sidecar only: `cd services/signal-source-discord && uv run ruff check . && uv run pytest
  -q` (CI job `sidecar` runs `uv sync`, `uv pip install -e .`, `uv run pytest -q`). No Java, so no
  spotless; no Temporal, no schema regeneration, no `strategy-config.json`.
- No manifest change → no manual `kubectl apply`; `66-darkpool-collector.yaml` is in deploy.yml's
  matrix and the Deployment already exists (first-time create was done 2026-10-29).
- Deploy blast radius: any `services/signal-source-discord/*` change rebuilds the sidecar image and
  rolls EVERY consumer of it: `signal-source-discord` (live Discord signal watcher), the three
  mirrors, `lotto-signal`, `flow-collector` and `darkpool-collector`. The pr-deploy-agent classes
  `signal-source-discord` as **signal-watcher: approve only while the market is CLOSED** (Mon–Fri
  09:00–16:30 ET is open); `darkpool-collector` itself is any-time. The whole run waits on the
  strictest service, so: merge over the weekend (2026-11-08/09 before 09:00 ET Monday) and the agent
  approves at any hour; a merge that slips into Monday RTH defers the roll to after 16:30 ET and
  misses the operator's 04:00 ET target. Do not merge the sidecar image during RTH by hand either.
- Step 0 before the merge: the roll replaces the pod and its emptyDir; the incident's cursors exist
  nowhere else.
- Production touches in Steps 0, 3, 4 are: `kubectl get/logs/exec cat` (read), one file write into
  the collector's own emptyDir and one container restart of a non-trading pod (Step 3), and
  `SELECT`s (Step 4). Nothing on a trading path; no broker, Temporal or trading-DB mutation.
- PR body needs `Closes #1041`; attribution lines per the session's system-reminder.
- The homelab dead-man cron (`~/scripts/darkpool_check.sh`, not in the repo) greps `darkpool cycle:`
  — keep that prefix. It will NOT fire on the drain (restarts flat, cycle lines present).

## Operator follow-ups (no code steps in this plan)

1. **Optional memory bump** (`infra/k8s/66-darkpool-collector.yaml` limits 256 Mi → 512 Mi). Not
   needed for correctness after Step 1. If done, `MAX_TRADE_PAGES` can scale with it (the comment
   gives the per-page figure); the manifest rolls through deploy.yml.
2. **Monday watch**: Step 3's log checks and Step 4's SQL; also the `/dark-pool` page for 11-05 and
   11-06 once the drain settles.
3. The 10-30 09:35–10:10 gap (#1032) is a separate decision, not touched here.

## Open questions

1. **Cap value and knob.** Assumed `MAX_TRADE_PAGES = 10` (100k trades, ~44 MiB peak, < 130 MiB
   worst case at 256 Mi) as a module constant, no env var (CLAUDE.md §2: no configurability not
   asked for). If the operator wants `DARKPOOL_MAX_PAGES` to tune alongside a memory bump, it is a
   three-line follow-up plus a manifest comment.
2. **Truncation does not stop the cycle.** Assumed the issue's "the other symbols still get
   served" means in the SAME cycle, so a truncated symbol is logged and the loop continues; only a
   budget stop breaks and round-robins, as today.
3. **Return shape.** `trades()` returns a `(list[Trade], bool)` tuple rather than a new method or a
   dataclass; the one existing test that unpacks it adapts its call shape only.
4. **`next_start` as a symbol name**, not an index, defaulting to the head when absent/unknown.
   Assumed acceptable; an index would break on a universe edit.
5. **No heartbeat per page.** Worst-case gap between beats is 10 × 15 s = 150 s < 180 s probe
   staleness (and the probe needs 3 failures). Assumed acceptable; if the operator prefers margin,
   a per-page heartbeat needs a callback on the data client and is a separate small change.
6. **Refill premise corrected.** The coordinator's note assumed the saved cursors survive the
   deploy; they do not (emptyDir, new pod). Assumed the extract-and-seed procedure (Steps 0 and 3,
   `kubectl exec` only, one container restart) is acceptable in place of new code; the alternative
   (a PVC or a historical-mode flag) is more change than the gap warrants.
7. **Drain duration and live lag.** Estimated 2.5–4 h at the 1,000-request budget, finishing in the
   Monday pre-market if seeded over the weekend. Assumed the operator accepts a lagging live tape
   should it overrun into RTH (nothing is lost; cursors). Raising `DARKPOOL_MAX_REQUESTS` for the
   drain was NOT planned (shared 10k/min limit with the market-data pod during RTH).
8. **11-04 coverage.** 11-04 already shows 59 symbols; the afternoon tail for the symbols after
   AMZN may be short. The seed cursors refill whatever is missing; the Step 4 check only requires
   11-04's print count not to fall.
