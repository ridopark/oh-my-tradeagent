# H13.3 Buy-side option flow on SPX 0DTE — RESULT: NOT TESTED (data unobtainable)

Pre-registration: wl/PREREG13.md, section H13.3 (hypothesis, metric, split, pass rule unchanged).
Run date: 2026-10-23 (UTC 21:40–21:55). Outcome: **NO TEST RUN — the pre-registered dataset could not be obtained.**
Per the task rule ("if the data cannot be obtained, stop and report rather than substituting a different dataset"),
no substitute data was used and none of the 12 variants was evaluated. The 26-variant round-13 ledger budget is
unaffected by H13.3 (0 variants spent).

## What was obtained

Repo clone (shallow, depth 1, into its own empty directory; no code from it was executed or imported):

- URL: https://github.com/emlama/gex-backtesting
- Commit: `ec501fc6b61b700eb6c23c6423a9cb4a43ff84d8` (2026-03-03 10:49:28 -0500, "Add agent-friendly documentation…")
- Local path: wl/r13/h133/ext/repo/
- License: **none stated** — no LICENSE file, GitHub API `license: null`, no license field in pyproject.toml.
  (Even if the data had downloaded, redistribution/commercial-use terms are undefined; it is Polygon.io-derived.)
- Files: 26 tracked files, 304 KB total (code, notebooks, docs). Full sha256/size manifest: wl/r13/h133/ext/MANIFEST.txt.
- **Parquet files: none in the repo.** `.gitignore` excludes `data/*.parquet`; README says "not in git".
  No Git LFS pointers (`.gitattributes` absent). No GitHub releases (`/releases` → `[]`). Single branch `main`.
  Four forks (yuipan0611, JanAbraham, cedricpoma, laxmicc) are all 304 KB, i.e. code-only mirrors.

## Where the data is supposed to come from, and why it failed

`download_data.sh` (read, not run) fetches a single tarball from a bare-IP plain-HTTP server:
`http://45.55.51.49/data/gex-spx-0dte-trades.tar.gz` (stated 2.3 GB, 513 files `trades_YYYY-MM-DD.parquet`).
The alternative `setup_data.sh` symlinks from a private "Hermes" repo (`emlama/Hermes` → GitHub 404).

Probe log (all from this host, 2026-10-23 21:40–21:54 UTC):

| Probe | Result |
|---|---|
| `HEAD http://45.55.51.49/data/gex-spx-0dte-trades.tar.gz` | TCP connects, **curl 52 "Empty reply from server"** |
| `GET` same URL with `Range: 0-1023` | curl 52 Empty reply |
| `GET http://45.55.51.49/` and `/data/` | curl 52 Empty reply |
| `https://45.55.51.49/...` (port 443 open) | curl 35 SSL_ERROR_SYSCALL (not a TLS endpoint) |
| `http://45.55.51.49:8080/...` (port 8080 open) | curl 56 Connection reset by peer |
| `wget` retry ×2 | exit 4 (network failure) |
| 5 × retry over 5 min (wl/r13/h133/out/retry_log.txt) | all curl 52 Empty reply |
| Wayback Machine availability API for the URL | `archived_snapshots: {}` (never archived) |

Conclusion: the host is up but the HTTP service that served the archive is gone/misconfigured. The dataset exists
only on that server (no LFS, no release, no mirror, no fork copy). It cannot be obtained today.

## Amendment log

- A1 (before any outcome; procedural): the pre-reg said "clone into its own empty directory" assuming the parquet
  files ship with the repo. They do not; the only source is the HTTP tarball above. I attempted to fetch that tarball
  with my own curl/wget commands (never by running `download_data.sh`), which I judge to be within the pre-reg's
  intent ("fetch those the same way"). This amendment changed nothing about hypothesis/metric/split/pass rule.
- No other amendments. No outcomes were observed, so no outcome-dependent changes were possible.

## What the repo's own docs say about the data (useful if the server ever comes back)

Read-only notes from README.md / CLAUDE.md / src/data_loader.py / src/config.py (not executed):

- Source: Polygon.io options-trades flat files for SPXW 0DTE, "enriched with quote-matched trade side classification"
  by the author's private pipeline. **No SPX spot/quotes are included** — the author's loader *infers* spot from
  the option chain, which is exactly why PREREG13 specifies SPY×10 as the spot proxy with a parity check.
- `sip_timestamp` is **int64 nanoseconds since epoch (UTC)**; the author converts with `utc=True` then
  `tz_convert("America/New_York")`. Any future run must do the same and verify that the first trades land at
  ~09:30 ET, not 04:30/14:30 (the timezone-of-timestamps skepticism point in the task).
- `price` and `size` are **strings** in the parquet (must cast); `strike`/`bid`/`ask` are float64.
- `side` ∈ {at_bid, below_bid, at_ask, above_ask, mid_market}; the author's own code maps at_ask→BUY, at_bid→SELL.
  How "quote-matched" was done (which NBBO snapshot, latency tolerance) is **not documented** anywhere in the repo;
  the pre-reg's reliability check (recompute side from `price` vs `bid`/`ask` and measure agreement) remains necessary.
- The author uses r = 5% (config.py `risk_free_rate = 0.05`); PREREG13 fixes r = 4% — keep 4%.
- The author's TTE is to 16:00 ET from each trade's timestamp, matching the pre-reg.

## Pre-registered checks that were NOT performed (listed so nobody mistakes silence for a null)

All 12 variants (4 windows × 2 horizons × {all, P90-large}); |z|≥1 share trade net 1 bp; Spearman(z, fwd return);
day-clustered bootstrap CIs on FIT (2024-01..2025-03) and TEST (2025-04..2026-02); studentized max-t across 12 on TEST;
volume share by side label; sign of raw (unstandardized) relation; FOMC/CPI-excluded re-run; SPY×10 vs parity-implied
spot error; side-label reliability vs bid/ask; look-ahead audit of the 20-session z baseline; near-close delta blow-up.
None was run. Verdict: **INCONCLUSIVE / NOT TESTED** (not PASS, not FAIL).

## Options for the parent

1. Re-probe `http://45.55.51.49/data/gex-spx-0dte-trades.tar.gz` later; if it serves, re-run H13.3 exactly as
   pre-registered (code dir wl/r13/h133/code/ is empty and ready; this RESULT.md gets superseded). Verify archive
   listing with `tar tzf` for path traversal before extracting into wl/r13/h133/ext/data/.
2. Source the same mechanism from a licensed feed we already pay for: Alpaca has SPXW option **bars** from 2024-03
   (memory: reference_0dte_spx_sniping_research_2026_10) but **no option trades/quotes**, so quote-matched side
   labels are not reproducible there. Polygon options trades + quotes would be, but that is a new dataset and a new
   pre-registration (H13.3 as written is bound to this dataset).
3. Drop H13.3 from round 13 and leave the ledger budget to H13.1/H13.2.

## Artifacts

- wl/r13/h133/RESULT.md (this file)
- wl/r13/h133/ext/MANIFEST.txt — repo commit, license status, per-file sha256 + bytes
- wl/r13/h133/ext/repo/ — shallow clone (untrusted; nothing executed)
- wl/r13/h133/out/retry_log.txt — timed retry attempts
- wl/r13/h133/code/ — empty (no analysis was run)
