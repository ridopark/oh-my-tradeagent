# Options Flow Archive Format

Each RTH session (09:25-16:20 ET, weekdays) produces two files in the archive directory
(`FLOW_ARCHIVE_DIR`, mounted from PVC `flow-archive` at `/app/archive`) plus the durable
run log:

```
/app/archive/run.log                     — JSONL durable run log (see below)
```

per session:

```
<YYYY-MM-DD>.trades.ndjson.gz   — all OPRA trades received that session
<YYYY-MM-DD>.manifest.json      — session summary + sha256 of the trades file
```

## Run log (`/app/archive/run.log`)

One JSON object per line, appended by every INFO/WARNING/ERROR the collector emits.
The log survives pod replacement (it lives on the same PVC as the trades files) and is
size-capped by a rotating file handler: `maxBytes=50_000_000`, `backupCount=2` (at most
150 MB total across `run.log`, `run.log.1`, `run.log.2`).

### Line format

```json
{"ts": "2026-11-09T14:30:00.123Z", "level": "INFO", "logger": "flow_collector", "msg": "flow metrics: ...", "exc": "Traceback ..."}
```

| Field | Type | Description |
|-------|------|-------------|
| `ts` | string (RFC-3339 ms Z) | UTC timestamp |
| `level` | string | `INFO`, `WARNING`, or `ERROR` |
| `logger` | string | Logger name (e.g. `flow_collector`) |
| `msg` | string | Formatted log message |
| `exc` | string | Full traceback text; present only on `log.exception()` calls |

### Key message prefixes

| Prefix | Meaning |
|--------|---------|
| `flow collector starting` | Process start |
| `flow: archive dir …` | Startup self-check: writability and free bytes |
| `flow: connected` | WebSocket greeting received |
| `flow: authenticated` | Auth success |
| `flow: subscription ack …` | Subscription acknowledged |
| `flow: first trade …` | First trade received this session |
| `flow: first archive flush bytes=…` | First bytes written to the trades file |
| `flow metrics: …` | 60-second interval metrics (emitted during RTH) |
| `flow session summary: …` | End-of-session totals |
| `flow: manifest written … trades=…` | Manifest file written |
| `flow: slot held (406) …` | OPRA slot held by another connection |
| `flow collector crashed` | Uncaught exception (full traceback in `exc`) |

### Reading the log

```bash
kubectl -n copytrade exec deploy/flow-collector -- tail -n 20 /app/archive/run.log | python3 -c "
import sys, json
for line in sys.stdin:
    d = json.loads(line)
    print(d['ts'], d['level'], d['msg'][:120])
"
```

## Trades file (`<date>.trades.ndjson.gz`)

One JSON object per line, gzip-compressed. The file is built from **concatenated gzip
members** — each flush appends a new member, and the result is a valid gzip file that can
be decompressed as a whole by any standard `gunzip`/`zcat`. Partial sessions (from
restarts or gaps) are therefore self-consistent: any member that was fully flushed is
recoverable even if the session ended mid-day.

### Row fields

| Field | Type | Description |
|-------|------|-------------|
| `t` | string (RFC-3339) | Trade timestamp from the exchange |
| `S` | string | OCC compact contract symbol |
| `p` | number | Trade price |
| `s` | integer | Trade size (contracts) |
| `x` | string | Exchange code |
| `c` | array of string | Raw OPRA condition codes |
| `bp` | number\|null | Best-bid at trade time (from live quote) |
| `ap` | number\|null | Best-ask at trade time (from live quote) |
| `bs` | integer\|null | Bid size |
| `as` | integer\|null | Ask size |
| `qt` | string\|null | Quote timestamp (RFC-3339); null when no live quote was available |

Quote fields (`bp`, `ap`, `bs`, `as`, `qt`) are `null` when no live quote was in the
quote cache for that contract at the time of the trade, or when the quote timestamp
exceeded `FLOW_QUOTE_MAX_AGE` (3 s) relative to the trade timestamp.

### Verification

```bash
# Decompress without errors
gzip -t <date>.trades.ndjson.gz

# Count rows
zcat <date>.trades.ndjson.gz | wc -l

# Inspect the first row
zcat <date>.trades.ndjson.gz | head -1 | python3 -m json.tool
```

## Manifest file (`<date>.manifest.json`)

Written at session end (window close or pod restart). **Always written**, even when no
trades file was produced (e.g. 406 slot-held all day, disk guard, crash before first
trade). Zero-trade manifests have `file: null`, `bytes: 0`, `sha256: null`,
`first_t: null`, `last_t: null`, and `trades: 0`.

| Field | Type | Description |
|-------|------|-------------|
| `date` | string (YYYY-MM-DD) | Session date in ET |
| `file` | string\|**null** | Basename of the trades file; **null** when no trades were archived |
| `bytes` | integer | File size in bytes; `0` when `file` is null |
| `sha256` | string\|**null** | Hex SHA-256 of the trades file; **null** when `file` is null |
| `trades` | integer | Total rows written; `0` when no trades archived |
| `first_t` | string\|null | Timestamp of the first trade (RFC-3339); null when no trades |
| `last_t` | string\|null | Timestamp of the last trade (RFC-3339); null when no trades |
| `gaps` | array | List of gap records (see below) |
| `window` | string | `"09:25-16:20"` |
| `build` | string | Sidecar image tag or `"dev"` |

Each gap record in `gaps`:

| Field | Type | Description |
|-------|------|-------------|
| `from` | string | RFC-3339 timestamp when the gap opened (disconnect or cap stop) |
| `to` | string\|null | RFC-3339 timestamp when the gap closed (reconnect); null if still open at session end |
| `reason` | string | `"reconnect"`, `"slot_held"`, `"disk_cap"`, `"disk_free_floor"`, or `"disk_write_error"` |

Gap reason `slot_held`: the OPRA slot was held by another connection (Alpaca 406 all day);
the collector never connected, so there is no trades file and no archive bytes.

### Integrity check

```bash
sha256sum <date>.trades.ndjson.gz
# Must match the `sha256` field in the manifest.
```

## Disk guard

The archiver stops writing (and records a gap) when either:
- The archive directory exceeds `FLOW_ARCHIVE_CAP_BYTES` (default 20 GB), or
- The volume's free space falls below `FLOW_ARCHIVE_FREE_FLOOR_BYTES` (default 15 GB).

The collector continues classifying and aggregating trades in memory; only the on-disk
writes are paused. A Step 2 health alert fires to `ALERT_DISCORD_WEBHOOK_URL`. In Step 1
the cap-stop is logged only.

Current PVC size: `25 Gi` (`flow-archive`). The local-path provisioner does **not**
enforce storage limits, so the disk guard is the only cap in effect.

## Monthly offload procedure (operator, manual)

1. Wait until the previous month is at least 3 days old (all sessions fully closed).
2. `rsync -av --progress ridopark@192.168.10.123:/path/to/archive/<YYYY-MM>-*.* ~/flow-archive/`
   — copy the closed month's files to the workstation.
3. Verify each manifest's `sha256` against the transferred file:
   ```bash
   for m in ~/flow-archive/<YYYY-MM>-*.manifest.json; do
     f="${m%.manifest.json}.trades.ndjson.gz"
     expected=$(python3 -c "import json,sys; print(json.load(sys.stdin)['sha256'])" < "$m")
     got=$(sha256sum "$f" | awk '{print $1}')
     [ "$expected" = "$got" ] && echo "OK $f" || echo "FAIL $f"
   done
   ```
4. After all files check out, delete the transferred month from the PVC:
   ```bash
   ssh ridopark@192.168.10.123 "rm /path/to/archive/<YYYY-MM>-*.*"
   ```
   Check free space before and after: `df -h /path/to/archive`.

Estimated file sizes (subject to the Step 1 measurement):
- ~100–500 MB per session (depends on market activity and quote-set size).
- ~20–100 GB per year.
Adjust `FLOW_ARCHIVE_CAP_BYTES` and the offload cadence after seeing the Step 1 report.
