# PLAN — 2026-10-31 darkpool-trade-id-key

Issue: #1035. Base: `0e0e1f47` (origin/main, includes #1034 cursor persistence + per-lookup heartbeat).
Assumptions accepted by operator 2026-10-31.

## Goal

The dark-pool collector matches each print to its own NBBO quote and side, and posts only the
off-exchange prints it actually kept: a trade that merely shares an Alpaca trade id with a kept
print (a lit trade, or a below-threshold `D` print) is never posted, and two kept prints with the
same id each carry their own quote and side.

**Done when:**
- `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q` passes, including
  the new tests named in Step 1 (each fails on `0e0e1f47`).
- In a `DarkpoolCollector.cycle()` test fed {D id 7 kept, lit id 7, D id 7 kept at a later ts},
  the posted batch holds exactly the two `D` rows, each with the quote at or before its own `ts`
  and the side computed from its own price; the bar's `buy_volume`/`sell_volume` match those sides.
- The wire contract is byte-identical: `print_to_wire` keys and the BFF are untouched (`git diff
  0e0e1f47 -- services/tenant-dashboard-bff` is empty), so there is no deploy ordering to manage.
- A cleanup dry-run SQL file and a guarded apply SQL file exist for the operator; the dry-run
  reproduces 1,831 rows to delete (1,799 non-`D` + 32 below-threshold `D`), 2 rows to relabel,
  0 bars to adjust, on the 2026-10-30 data.
- Post-deploy (operator, first session after the roll): `darkpool_print` rows ingested after the
  roll with `exchange <> 'D'` = 0, and rows with `quote_ts > ts OR ts - quote_ts > 5 s` = 0.

## Non-goals

- No BFF change: no new unique key, no migration, no ingest-side `exchange = 'D'` validation.
- No change to the keep rule, the side band (#1013/#1021), the bar maths, the cursor persistence
  format (`cursors.json` stores only `last_t`), or the request budget.
- No re-fetch of historical quotes from Alpaca to repair the 2 mis-quoted kept rows; they are
  relabelled `unknown` (operator may choose to leave them).
- The builder never runs the cleanup apply script.

## Spec

### Problem Statement

The operator's `/dark-pool` page shows lit-exchange trades as dark-pool prints and, for some dark
prints, a quote taken after the print with a side computed from another trade's price. Alpaca SIP
trade ids (`i`) are unique only per exchange (and, for `D`, apparently per reporting facility,
which Alpaca does not expose), but the collector keys every per-trade structure by `trade_id`.

Root cause, verified in code on `0e0e1f47`
(`services/signal-source-discord/ohmytradeagent_sidecar/`):
- `darkpool_main.DarkpoolCollector._fetch` fills `sides[t.trade_id]` and `quotes[t.trade_id]` for
  each kept trade; a later kept trade with the same id overwrites the earlier one's quote and side.
- `darkpool_main.DarkpoolCollector.cycle` posts `for t in fresh: if t.trade_id in sides` — any
  trade in the window whose id equals a kept print's id is posted (lit trades, and `D` prints below
  the keep thresholds), stamped with the kept print's quote and side.
- `darkpool_collector.aggregate` looks up `sides.get(t.trade_id)` for kept trades, so a kept print
  whose id twin was looked up later gets the twin's side in `buy_volume`/`sell_volume`. Lit and
  below-threshold trades never read `sides` in `aggregate` (`keep()` is false), so bars are only
  affected by kept-`D`/kept-`D` twins in the same fetch.
- `darkpool_collector.Cursor.last_ids` is a set of trade ids at `last_t`. A collision can only
  make the cursor DROP a trade (a not-yet-seen trade at exactly `last_t` whose id matches an
  already-seen trade on another exchange/facility is filtered as seen). It can never double-read:
  membership only removes. A dropped lit trade under-counts `total_volume`; a dropped kept `D`
  print is lost. This is not measurable from stored rows (dropped trades leave none).

Evidence, dashboard DB, read-only SELECTs run 2026-10-31 06:21Z:
- 2026-10-30: 97,622 rows, **1,799 non-`D`** (Q 670, P 295, V 210, Z 177, N 107, K 101, …);
  every one has a `D` row with the same `(symbol, trade_id)`, none at the same `ts`.
- **1,441** duplicate `(symbol, trade_id)` groups (3,330 rows); 1,363 span more than one exchange;
  91 groups hold two `D` rows (same tape, never the same `ts`; gap min 0.12 s, median 2 h 15 m).
  So `(exchange, trade_id)` would NOT separate the `D`/`D` twins.
- 32 posted `D` rows are below both keep thresholds (notional < 200,000 and size < 10,000, the
  live env values); all 32 have a twin. E.g. AMD id 30: size 1 @ 533.96 posted with the quote of
  the size-965 print 5 s later.
- 1,785 rows have `quote_ts > ts` or `ts - quote_ts > 5 s`; all 1,785 have a twin (1,755 of them
  labelled buy/sell/mid — the issue's number). Of these only 2 are kept `D` prints, and neither is
  labelled buy/sell, so no bar's `buy_volume`/`sell_volume` is wrong in the stored data.
- 2026-10-29: 991 rows, 0 non-`D`. 2026-10-31 is a Saturday: the poll window is weekdays only, no
  rows (last `ingested_at` 2026-10-30 23:59:20Z).

### Solution

Key every per-trade structure by the `Trade` object itself (a frozen, hashable dataclass of every
field Alpaca returns: symbol, id, ts, ts_raw, price, size, exchange, conditions, tape).

Why the `Trade` and not `(exchange, trade_id)`: the 91 `D`/`D` twin groups have the same exchange
code, so `(exchange, trade_id)` still collides on them. Two `Trade`s that are value-equal are
indistinguishable on every field, and the quote (a function of symbol + ts) and side (of price,
quote, ts) are then identical too, so value-equality can never attach a wrong quote or side.

- `_fetch`: `sides` / `quotes` become `dict[Trade, …]`.
- `cycle`: posts `for t in fresh: if t in sides`, with `quotes[t]` and `sides[t]`.
- `aggregate`: `sides: Mapping[Trade, str]`, looked up with the trade.
- `Cursor`: the same-instant set holds the `Trade`s seen at `last_t` instead of their ids.

The BFF unique key `(symbol, ts, trade_id)` stays. After the fix only `D` rows are posted; among
`D` rows `exchange` is constant, so adding it to the key would not distinguish anything. The only
remaining collision is two `D` prints with the same symbol, id and microsecond `ts` from different
facilities: observed 0 times in 96,814 `D` rows; its cost is one print dropped by `ON CONFLICT DO
NOTHING`, never a wrong quote. Accepted and documented, not fixed (it would need price/size in the
key, i.e. a migration for a case never seen).

### User Stories

1. As the operator reading `/dark-pool`, I want only off-exchange (`D`) prints listed, so that lit
   exchange volume is not presented as dark-pool activity.
2. As the operator, I want each listed print's bid/ask/quote time to be the NBBO at or before that
   print, so that the buy/sell/mid label is about that print.
3. As the operator, I want the 5-minute bars' buy/sell volume to use each kept print's own side,
   so that a same-id twin cannot move volume between buy and sell.
4. As the operator, I want below-threshold `D` prints to stay out of the print list even when they
   share an id with a kept print, so that the list honours the configured thresholds.
5. As the operator, I want the cursor not to drop a trade at the resume instant just because its
   id matches a different trade already read, so that bar totals and kept prints are complete.
6. As the operator, I want the historical bad rows cleaned only when I choose to, with a dry run
   showing exactly what goes, so that history is never rewritten automatically.
7. As the maintainer, I want the fix confined to the collector image with an unchanged wire
   contract, so that the BFF and collector can roll in either order.
8. As the maintainer, I want tests that build same-id trades on different exchanges, so that the
   per-exchange id semantics are pinned and the bug cannot return.

### Implementation Decisions

- Identity of a trade inside the collector = the `Trade` value. No new type, no helper key
  function; `Trade` is already frozen and hashable (`conditions` is a tuple).
- `aggregate`'s `sides` parameter changes from `Mapping[int, str]` to `Mapping[Trade, str]`;
  its docstring says so. Its callers are `cycle` and the collector tests.
- `Cursor` keeps its name and methods (`start`, `is_new`, `filter_new`, `advance`); the field that
  holds the same-instant set is renamed to say what it holds (e.g. `seen_at_last_t:
  frozenset[Trade]`). It is never serialized (`_save_cursors` writes only `last_t`), so no state
  migration.
- `print_to_wire` and the module docstrings' wire description are unchanged. The `darkpool_main`
  module docstring sentence "Prints dedupe on (symbol, ts, trade_id)" stays true. The V15 comment
  claiming `i` is unique per symbol per day is wrong but lives in the BFF; leave it (non-goal) and
  note it in the PR body. Correct any collector comment that makes the same claim.
- Data cleanup is two SQL files under `scripts/ops/` (dry-run, apply) plus a README entry; the
  apply file runs in one transaction and aborts unless the counts equal the values the operator
  passes from the dry run.
- Cleanup rules (predicates fixed in the file, thresholds 200,000 / 10,000 matching the live env):
  - DELETE rows with `exchange IS DISTINCT FROM 'D'`, or `D` rows below both thresholds, that have
    a same-`(symbol, trade_id)` twin (all 1,831 do; the twin condition keeps the delete from ever
    touching a row the bug did not create).
  - RELABEL kept `D` rows whose `quote_ts` is after the print or more than 5 s before it: set
    `bid`, `ask`, `quote_ts` to NULL and `side` to `unknown` (what the collector writes when it
    has no usable quote). 2 rows today.
  - BARS: for each relabelled row whose stored side was `buy`/`sell`, subtract its size from that
    bar's `buy_volume`/`sell_volume`. The dry run shows this set is empty today (0 rows), and the
    apply file still contains the statement so the rule is explicit. Deleted rows need no bar
    change: lit and below-threshold trades never fed `buy_volume`/`sell_volume`.

### Testing Decisions

- A good test drives the collector through `DarkpoolCollector.cycle()` with the existing
  `FakeAlpaca` / `FakeBff` `httpx.MockTransport` fakes and asserts on the POSTed body: that is
  what the BFF stores. No mocks of `_fetch`, `aggregate` or `quote_before`.
- Pure-function seams in `darkpool_collector` (`Cursor`, `aggregate`) are tested directly, as the
  existing tests do.
- Prior art: `test_darkpool_main.py::seed_kept_print`, `test_live_cycle_posts_snake_case_prints_and_bars`,
  `test_budget_hit_mid_symbol_commits_the_prefix_and_resumes_from_it`;
  `test_darkpool_collector.py::test_cursor_resume_excludes_already_seen_t_and_i`,
  `test_cursor_advance_unions_ids_at_the_same_instant`, `test_aggregate_hand_computed_bars`.
- Existing tests that pass `{trade_id: side}` to `aggregate` or assert `last_ids == frozenset({…})`
  are updated to the new key form; their behavioural assertions (filtered ids, bar numbers) do not
  change. That is a re-expression, not a weakening.

### Out of Scope

- BFF-side defence (rejecting non-`D` prints at ingest).
- Re-quoting historical rows from Alpaca.
- The theoretical same-microsecond `D`/`D` collision in the BFF unique key.

### Further Notes

- The sidecar image is shared by seven Deployments; a merge rolls all of them, including the live
  signal watcher (`signal-source-discord`). The local deploy agent holds that roll until the market
  is closed. Today is Saturday, so a weekend merge rolls with no session open.
- A roll is a new pod, so the darkpool-collector's emptyDir `cursors.json` is gone and it cold-
  starts with the 10-minute lookback; on a weekend that loses nothing.

## Steps

### Step 1 — Collector keys per-trade quote, side, posting and cursor by the Trade

**Goal:** a same-id twin (lit, below-threshold `D`, or another kept `D`) can no longer be posted,
borrow a quote or side, skew a bar, or be dropped by the cursor.
**Blocked by:** none.
**Seams:**
- `DarkpoolCollector.cycle()` with `FakeAlpaca`/`FakeBff` — catches what is posted (rows, quotes,
  sides) and the bars sent; misses real Alpaca id semantics (the fixture asserts them).
- `Cursor.filter_new` / `Cursor.advance` — catches same-instant drop/double-read; misses
  persistence (unchanged, covered by the #1034 tests).
- `aggregate()` — catches bar side attribution with twins; misses the posting path (covered above).

**Success checklist:**
- [ ] New test `test_same_id_lit_trade_is_not_posted_and_d_keeps_its_own_quote` (cycle seam): trades
      D id 7 @13:38:10 p=500.00 s=400 (quote 13:38:09 499.98/500.00 → buy) and V id 7 @13:38:15;
      posted prints == one row, `exchange == "D"`, `quote_ts == "2026-10-29T13:38:09Z"`,
      `side == "buy"` — evidence: test passes; fails on `0e0e1f47` (two rows posted).
- [ ] New test `test_same_id_d_prints_each_get_their_own_quote_and_side` (cycle seam): D id 7
      @13:38:10 (buy as above) and D id 7 @13:38:20 p=499.90 s=500 with quote 13:38:19
      499.90/500.10 (→ sell), plus a lit V id 7 between them; posted == the two `D` rows, each
      with its own `quote_ts` ≤ its `ts` and its own side; the bar has `buy_volume == 400` and
      `sell_volume == 500` — evidence: test passes; fails on `0e0e1f47`.
- [ ] New test `test_below_threshold_d_twin_is_not_posted` (cycle seam): kept D id 7 + D id 7
      size 1 → only the kept row posted — evidence: test passes; fails on `0e0e1f47`.
- [ ] New test `test_same_id_d_prints_at_the_same_instant_are_both_posted` (cycle seam): two D
      id 7 prints at the same ts with different price/size → both posted, each with its own side
      — evidence: test passes.
- [ ] New test `test_cursor_keeps_a_same_instant_trade_whose_id_matches_another_exchange`
      (`Cursor` seam): advance over D id 2 at t0; `filter_new([D id 2 @t0, V id 2 @t0, D id 2
      @t0 different price])` returns the V and the second D only; a further `advance` then filters
      all three — evidence: test passes; fails on `0e0e1f47` (V dropped).
- [ ] New `aggregate` test: two kept D trades with the same id and different sides in the map →
      `buy_volume`/`sell_volume` split by their own sides — evidence: test passes.
- [ ] Existing darkpool tests pass with only key-form edits (no assertion on behaviour removed) —
      evidence: `git diff 0e0e1f47 -- services/signal-source-discord/tests` reviewed;
      `uv run pytest -q` green.
- [ ] `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q` exits 0 —
      evidence: command output.
- [ ] No `trade_id`-keyed map remains in the collector — evidence: `grep -n "trade_id\]" 
      ohmytradeagent_sidecar/darkpool_*.py` prints nothing, and `grep -n "last_ids"` prints
      nothing in the sidecar package.
- [ ] Wire contract unchanged — evidence: `print_to_wire` diff empty and
      `test_live_cycle_posts_snake_case_prints_and_bars` passes unmodified;
      `git diff --stat 0e0e1f47` lists only files under `services/signal-source-discord/`
      (and this plan).

**Failure checklist:**
- [ ] A test needs to mock `_fetch`, `aggregate`, `quote_before` or any internal collaborator
      instead of the HTTP fakes.
- [ ] Making the tests pass requires changing `print_to_wire`'s keys/values, the BFF, a migration,
      or the cursor state file format.
- [ ] An existing test's behavioural assertion (which trades are filtered, bar numbers, posted
      rows) has to be changed or deleted, rather than only its key form.
- [ ] The change touches a file outside `services/signal-source-discord/` (other than this plan).
- [ ] `Trade` turns out not to be hashable as returned by `from_alpaca` (e.g. a list field), so
      keying by it needs a new identity type — stop and report.

### Step 2 — Operator cleanup scripts (written, not run)

**Goal:** the operator has a read-only dry run that shows exactly which rows would be deleted,
relabelled, and which bars adjusted, and a guarded apply file to run when they choose.
**Blocked by:** none (it reads data only; the operator runs the apply after Step 1 is deployed).
**Seams:**
- The dry-run file executed read-only against the dashboard DB via
  `scripts/data/homelab_psql.sh dashboard < <dry-run file>` — catches the counts and the exact
  rows; misses the transaction behaviour of the apply (only the operator runs it).

**Success checklist:**
- [ ] `scripts/ops/darkpool_1035_cleanup_dryrun.sql` contains only `SELECT`/`WITH … SELECT`
      statements — evidence: `grep -inE "^\s*(delete|update|insert|alter|drop|truncate|grant)"`
      on it prints nothing.
- [ ] Dry run outputs: rows to delete by `(ET date, exchange)` and total **1,831** (1,799 non-`D`,
      32 below-threshold `D`), rows to relabel **2**, bar adjustments **0** — evidence: the
      command output pasted into the PR body (builder may run THIS file; it is SELECT-only).
- [ ] `scripts/ops/darkpool_1035_cleanup_apply.sql` runs in one `BEGIN … COMMIT`, takes
      `:expected_delete` and `:expected_relabel` psql variables, and `RAISE EXCEPTION`s (rolling
      back) if the affected counts differ — evidence: file content; the reviewer reads it.
- [ ] Apply file's DELETE predicate is identical to the dry run's (same text, twin condition
      included) — evidence: diff of the two predicate blocks.
- [ ] `scripts/ops/README.md` gains an entry: what it does, the dry-run command, the apply command
      with `-v expected_delete=… -v expected_relabel=…`, run as the `temporal` superuser, and the
      alternative "leave history; rows age out at 90 days (2027-01-28)" — evidence: README diff.

**Failure checklist:**
- [ ] Any statement other than SELECT is executed against the homelab by the builder.
- [ ] The dry-run counts differ from 1,831 / 2 / 0 on data ingested before the fix — stop and
      report (the root cause or the predicates are wrong).
- [ ] The DELETE needs to touch `darkpool_bar_5m` rows or delete `D` rows at or above a threshold.

### Step 3 — Deploy and verify (operator-gated)

**Goal:** the fixed collector is live and new data is clean; history is cleaned if the operator
says so.
**Blocked by:** 1, 2.
**Seams:** the dashboard DB, read-only SELECTs after the roll.

**Success checklist:**
- [ ] The sidecar PR merges and the deploy rolls `darkpool-collector` (and the other sidecar
      Deployments; the deploy agent holds the roll outside RTH) — evidence: `kubectl -n copytrade
      get deploy darkpool-collector -o jsonpath='{.status.updatedReplicas}'` and the pod's image
      digest equal to the merge build.
- [ ] After the first post-roll session (Mon 2026-11-02): `SELECT count(*) FROM darkpool_print
      WHERE ingested_at > '<roll time>' AND exchange IS DISTINCT FROM 'D'` = 0 — evidence: output.
- [ ] Same window: `count(*) WHERE quote_ts IS NOT NULL AND (quote_ts > ts OR ts - quote_ts >
      interval '5 s')` = 0 — evidence: output.
- [ ] Same window: posted `D` rows below both thresholds = 0 — evidence: output.
- [ ] Operator decision recorded on #1035 (clean or leave); if clean, the apply output with the
      matching counts — evidence: issue comment.

**Failure checklist:**
- [ ] Any non-`D`, below-threshold, or out-of-window-quote row appears after the roll.
- [ ] The apply script's guard trips (counts changed since the dry run) — do not edit the
      expected values to force it; re-run the dry run and report.

## Repo gates

- Sidecar: `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q`.
- No Java change, so no spotless / BFF module tests / dashboard migration structure test; if any
  BFF file changes, that is a Step 1 failure, not a reason to run them.
- No schema change, so no three-artifact regeneration and no PG16 column-SELECT grant.
- Sidecar merges roll seven Deployments including the live signal watcher; the local deploy agent
  holds the roll until the market is closed. Do not bypass it.
- `pr-review-loop` merges on READY; the PR body needs `Closes #1035` (issues-drain rule).
- Production DB access is SELECT-only for everyone except the operator running the Step 2 apply.

## Open questions

1. Keep the BFF unique key `(symbol, ts, trade_id)`? **Assumed yes**: after the fix only `D` rows
   are posted, `exchange` would not distinguish them, and same-ts same-id `D` twins were seen 0
   times (91 `D`/`D` groups, all with different `ts`).
2. Key by `(exchange, trade_id)` or the `Trade`? **Assumed the `Trade`**: 91 groups of two `D`
   prints share an id, so `(exchange, trade_id)` still collides.
3. Clean history or leave it? **Assumed**: the plan ships the scripts; the operator decides. The
   default recommendation is to clean (1,831 rows inflate the page's large-print levels), and the
   2 mis-quoted kept rows are relabelled `unknown` rather than re-quoted.
4. May the builder run the dry-run SELECT? **Assumed yes** (SELECT-only, same as the planner's
   evidence queries); never the apply file.
5. Could a cursor collision have double-read trades? **Assumed no** from code reading: the id set
   only filters, so the failure mode is drop-only; its size cannot be measured from stored rows.
