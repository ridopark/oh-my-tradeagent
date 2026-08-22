# Plan — Full tick/quote archive for traded underlyings

## Answer first: the system CANNOT handle this today. Three independent blockers.

All three are fixable, but none is a tuning knob — each needs a deliberate change.

### Blocker 1 — Storage: no compression path in the current database

**Quote rates are NOT uniform — measured 2026-08-21, they span ~100x:**

| Symbol | quotes/min | | Symbol | quotes/min |
|---|---|---|---|---|
| SPY | **4,434** | | AAPL | 1,260 |
| DRAM (illiquid!) | 3,384 | | RIOT | 1,056 |
| TSLA | 2,000-2,900 | | NBIS | 96 |
| | | | **PLUG** | **36** |

An earlier revision of this plan applied TSLA's rate to all 40 symbols and overstated the cost by
~3x. Two lessons: **sample across the liquidity spectrum before projecting**, and note that
**illiquid names are NOT cheap** — DRAM out-quotes TSLA because wide-spread market makers flicker
constantly. The cheap ones are the genuinely thin small caps.

Weighted for a realistic book (~20 heavy @2,500/min, ~80 mid @500, ~100 tail @100) the average is
**~500/min/symbol**:

| Universe | raw/year | **Parquet+zstd (10-20x)** |
|---|---|---|
| 40 symbols | ~425 GB | **21-42 GB** |
| **200 symbols** | ~1.2 TB | **60-120 GB** |

**Cost is driven by WHICH symbols, not how many.** Adding SPY costs more than adding 100
PLUG-likes. Enrollment should flag a high-volume addition, not gate every addition.

Against **158 GB free** (post-prune): 200 symbols is **~1-2 years**, on the same physical disk as
Postgres. For "keep adding forever", a dedicated multi-TB volume gives 30-60 years at 200 symbols
AND removes the shared-failure-domain problem below.

Row storage remains unusable regardless: **PostgreSQL 16.14 with NO `timescaledb`, `pg_partman`,
or columnar extension available.** Native partitioning does not compress.

### Blocker 2 — Blast radius: this would share a Postgres with the trading system of record

`postgres-0` holds `orchestrator` (**`audit_log`**), `exec_alpaca_live`
(**`order_intent_journal`**), `exec_alpaca_paper`, `dashboard`, and `temporal` — all on one
instance, one PVC, one disk.

A tick firehose in that instance means a full disk stops Postgres accepting writes. And an
orchestrator audit write has **no retry cap** — a wedged in-process Postgres write wedges the
workflow holding it. So a storage-capacity incident in an *analytics* pipeline becomes a
**live-trading outage**. That is not an acceptable coupling for data whose only purpose is
retrospective analysis.

### Blocker 3 — Resources: 1 Gi RAM, shared

`postgres-0` limits are **cpu 2 / memory 1Gi** (currently using 12m / 232Mi). Sustained
~2,300 inserts/sec with WAL, plus autovacuum against a 50 M-row/day table, inside 1 Gi shared
with every trading database, is not viable — and tuning it upward makes Blocker 2 worse by
concentrating more load on the same instance.

---

## What DOES work: two tiers, and tick data never touches postgres-0

### Tier 1 — 1-min bars → Postgres (small, joinable)

All 40 symbols, ~535 MB/year, backfillable from Alpaca so history predates the collector.
Lives in the `dashboard` DB, joins directly against positions and chat. This is what the
correlator (`PLAN-2026-08-21-position-context-correlator.md`) actually reads.

### Tier 2 — Full tick/quote → Parquet files + DuckDB, on a SEPARATE volume

Columnar Parquet with zstd is the right format for this data: timestamps are monotonic
(delta-encoded), prices repeat heavily (dictionary/RLE), sizes are small ints. Expect
**10–20x** over row storage.

| | raw | Parquet+zstd |
|---|---|---|
| Per year, 40 symbols | 1.2–1.5 TB | **~60–120 GB** |

Partition `symbol=<SYM>/date=<YYYY-MM-DD>/quotes.parquet`. Query with DuckDB, which reads
Parquet directly with no server, no WAL, no vacuum, and **no shared failure domain with
trading**. Old partitions archive or move off-node by copying files.

### Ingest: WebSocket, not REST

- **Live: Alpaca stocks WS**, SIP feed (already entitled — `ALPACA_STOCK_FEED=sip`). ~2,300
  msg/sec sustained across 40 symbols. Buffer in memory, flush to a Parquet row-group per
  symbol per N minutes.
- **Backfill: REST, one-time, slow.** 40 M quotes/day at 10 k/page is **~4,000 paginated
  requests/day of history** — it will hit rate limits and must run throttled and off-hours.
  Backfill is bounded work; do not design the live path around it.
- **Separate deployment.** NOT `market-data` — that process runs the 500ms premium poll that
  live trailing stops depend on, and it already sees 429s. A tick firehose in the same JVM
  competes for heap, threads, and Alpaca quota with a trading-critical path.

## Symbol list: grows on BTO, as requested

Seed with the 40 already traded. On each new BTO for an unseen underlying, add it and begin
recording. Growth is slow and bounded by what we actually trade — 40 symbols accumulated over
the system's entire history.

**Cost per added symbol is highly variable: ~0.02 GB/year for a PLUG-like tail name, ~2 GB/year
for an SPY.** State it at enrollment so the tradeoff is explicit — but the default should be
permissive, since the tail is nearly free.

## Hard constraints

- **Tick storage MUST NOT share a filesystem with Postgres without a hard cap.** There is one
  disk (`/dev/sda2`, 218 G). If Parquet fills it, Postgres dies and Blocker 2 arrives by another
  route. Required: a dedicated directory with an enforced quota, a disk-usage alarm well below
  full, and an ingest that **stops writing** rather than filling the last GB.
- **Image pruning must be recurring.** containerd reached 140 G on `:latest` + a fresh digest
  per build (958 images, 37 in use). It was pruned to ~20 G on 2026-08-21, reclaiming 118 GB. It
  will refill. Without a recurring prune, tick capacity planning is meaningless.
- **Read-only w.r.t. trading.** No workflow signals, no orders, no writes to any exec/orchestrator
  table.
- **There is no database backup** — only `stc-intent-alert`/`stc-intent-digest` cronjobs exist.
  Multi-year archives on one un-replicated node are best-effort until that changes.

## P1 RESULT — measured 2026-08-21, gate PASSED

Run on **152,524 real SPY quotes** (30 min, 15:00-15:30Z, SIP), pulled via paginated REST — no
socket opened, market-data's equity connection untouched. 16 requests of REST quota consumed.

| Metric | Result |
|---|---|
| Parquet + zstd | **9.5 bytes/row** (1.45 MB for 30 min of the heaviest symbol) |
| **Compression vs Postgres** | **12.6x** — gate required >=8x |
| Compression vs raw NDJSON | 14.0x |
| Write throughput | **4.3 M rows/sec** |
| Full scan, 152 k rows | **11.9 ms** |
| 2-second NBBO slice | **3.7 ms** |
| Round-trip integrity | byte-identical on `bp` and `ax` |

**Throughput has ~2,500x headroom** over Option F's ~1,700 msg/sec. Whatever limits this
pipeline, it will not be the Parquet writer.

The sample measured SPY at **5,084 quotes/min**, ABOVE the 4,434/min REST estimate used earlier —
real data came in worse than assumed and still cleared comfortably.

**Storage, from measurement rather than estimate:**

| | |
|---|---|
| SPY (heaviest traded symbol) | 18.9 MB/day, **4.75 GB/year** |
| 200 symbols, RTH only | **~95 GB/year** |
| 200 symbols, **+ extended hours** | **~115-120 GB/year** |

### Decision: capture EXTENDED HOURS (04:00-20:00 ET), not RTH only

Costs ~20% more (extended-hours volume is thin), and buys the one window that matters most:
**overnight gaps**. On 2026-08-19 a SPY put's chandelier peak was 1.99 with a 1.4925 threshold, and
it fired at a trigger premium of **1.065** — 29% BELOW the stop, across the overnight gap. RTH-only
capture would omit exactly the interval in which that loss was determined.

The socket persists 24/7 regardless (verified: `omo_feed_connected{equity}=1.0` with markets shut),
so extended-hours capture is a subscription/retention choice, not extra infrastructure.

## Phases

**P1 — DONE (2026-08-21).** Compression 12.6x vs Postgres on real SPY data; 3.7 ms NBBO slice
queries; 4.3 M rows/sec write. See "P1 RESULT" above. Gate passed.

**P2 — Live WS ingest for the current symbol list**, own deployment, own volume, quota-capped,
with the disk alarm wired before first write.

**P3 — BTO-triggered symbol enrollment**, reading new underlyings off the order journal.

**P4 — Backfill history**, throttled, off-hours, lowest priority — it is bounded and can run for
weeks without blocking anything.

## What must be measured before P2

- Actual Parquet compression ratio on real quote data (P1 answers this).
- Sustained WS message rate across the full list during RTH. At 200 symbols the weighted
  estimate is ~1,700 msg/sec — an ordinary ingest rate, but it is an estimate from 10-second
  REST samples, not an observed stream.
- Whether one JVM can absorb ~2,300 msg/sec and flush without backpressure into the socket.
