# Plan — Only mirror a watchlist that was actually posted today (ET)

## Context / incident

At **midnight ET 2026-06-05** the watchlist mirror posted a **stale prior-day watchlist**
(PLTR/AMZN/…) to the alert channel, mislabeled `📋 Watchlist — 2026-06-05`, and **recorded
the day as mirrored** — which then **blocked the real 8:24 AM watchlist** (SPY/QQQ/…) from
ever mirroring. Homelab state confirmed it:
```
{"last_mirrored_date":"2026-06-05","source_message_id":"...1507372053043482766",
 "mirrored_at_utc":"2026-06-05T04:00:26Z"}   # 04:00 UTC = 00:00 ET (midnight)
```
`TradingTheTrend` posts watchlists at ~8:20 AM ET, never at midnight — so the message mirrored
at 00:00 ET was a prior day's, sitting in scrollback.

**Why #355 didn't catch it:** #355 only skips a watchlist that was *already mirrored* (Temporal
`deduped=True`). This stale watchlist had **never been mirrored**, so it wasn't deduped and sailed
straight through — posted + recorded + blocked today.

## Root cause (verified)

`services/signal-source-discord/ohmytradeagent_sidecar/watchlist_watcher.py` `process()` mirrors
*"the newest watchlist-shaped message from the author, once per ET day"* but **never checks WHEN the
watchlist was posted**. At midnight the newest watchlist-shaped message is the prior day's, so the
watcher mislabels it with today's `et_today()` and burns the day's slot. The once-per-day file +
#355's dedup are both about *identity*, not *recency*.

## The fix

Add a **posted-date gate** to `process()`: only mirror a candidate message whose **posted date,
converted to America/New_York, equals today's ET date** (the `et_date` arg). `RawMessage` already
carries `timestamp_iso` (the Discord post time, UTC — the signal `Watcher` already uses it as
`posted_at`). For each candidate (after the author + `is_watchlist` + seen-set + once-per-day
checks), parse `timestamp_iso` → convert to ET → take the date; if it ≠ `et_date`, **skip** (it is a
prior-day watchlist in scrollback).

Effect:
- **Midnight:** the newest watchlist is the prior day's → posted-date ≠ today → skipped → day stays
  open, nothing posted.
- **~8:20 AM:** `TradingTheTrend` posts today's watchlist → posted-date == today → mirrored,
  recorded — correctly labeled, in the right slot.

### Decision to lock (interpret + Discord-note if ambiguous)
- **Missing/unparseable `timestamp_iso`:** treat as "not today" → **skip** (fail-closed). Re-posting a
  stale watchlist and blocking the real one (the incident) is strictly worse than skipping a rare
  timestamp-less message; the signal `Watcher` already depends on `timestamp_iso` being present, so
  this should be vanishingly rare. Log a warning when it happens.
- Keep the existing once-per-day `DailyMirrorState` and #355 dedup/seen-set — the posted-date gate is
  additive, not a replacement (belt-and-suspenders).
- The `et_date` used for the payload label stays `et_today()`; the gate guarantees it equals the
  message's posted ET date, so the label is always correct.

### Constraints
- Pure addition to `process()`; no change to the Playwright loop, the emitter, or the contract.
- Deterministic ET conversion via `zoneinfo.ZoneInfo("America/New_York")` (already used in
  `watchlist_state.et_today`); no new timezone source.
- The signal `Watcher` path is untouched.

## Tests (TDD)

In `services/signal-source-discord/tests/test_watchlist_watcher.py` (existing style:
`InMemoryWatchlistEmitter`, `_msg(...)` helper, `process()` driven directly). The `_msg` helper /
`RawMessage` must let a test set `timestamp_iso`:

1. **`test_stale_prior_day_watchlist_is_not_mirrored` (headline / incident regression):** a
   watchlist-shaped message from the author whose `timestamp_iso` is **yesterday** (e.g. the message
   posted `2026-06-04T13:00:00Z`), processed with `et_date="2026-06-05"`. Assert: **no emit**, and
   `DailyMirrorState` is **not** recorded (the day stays open) — i.e. a subsequent today-dated
   watchlist still mirrors.
2. **`test_todays_watchlist_after_skipping_stale_one`:** msgs = [stale prior-day watchlist, then
   today's watchlist (timestamp today)], `et_date` today. Assert: only **today's** is emitted
   (the stale one skipped), recorded once.
3. **`test_todays_watchlist_mirrors`:** a watchlist whose `timestamp_iso` is today → emitted +
   recorded (happy path unchanged).
4. **`test_missing_timestamp_is_skipped`:** a watchlist with `timestamp_iso=None` → skipped, not
   recorded (fail-closed), warning logged.
5. **Keep green:** all existing `test_watchlist_watcher.py` tests pass — update their `_msg(...)`
   construction to carry a **today** `timestamp_iso` so the previously-passing emit cases still emit
   under the new gate (the test helper should default `timestamp_iso` to "today" so existing
   author/shape/dedup tests are unaffected).

## Success criteria (must all hold)
1. `cd services/signal-source-discord && uv run pytest -q` → all pass (incl. the 4 new tests).
2. Test 1 (the regression) FAILS without the fix and PASSES with it (genuinely reproduces the
   stale-midnight-post → it asserts no emit + day-not-recorded).
3. A watchlist whose posted ET date ≠ today is never emitted and never records the day; a
   watchlist posted today still mirrors exactly once — verified by tests 1-3.
4. `uv run ruff check ohmytradeagent_sidecar/` clean.
5. The signal-watcher tests and emitter tests are unaffected (no behavior change outside the
   watchlist `process()` gate).

## Halt conditions
- If `RawMessage`/`extract_recent` does NOT reliably populate `timestamp_iso` for watchlist-channel
  messages (verify in `discord_dom.py`) → stop and surface (the gate would skip everything).
- If the test harness can't set a per-message `timestamp_iso` without a broad refactor → stop and
  reconsider the seam.

## Verification commands
```
cd services/signal-source-discord && uv run pytest -q
cd services/signal-source-discord && uv run ruff check ohmytradeagent_sidecar/
```

## Out of scope (flag as follow-ups, do NOT fix here)
- The embed parser falling back to **raw text** when a watchlist has trailing chatter (e.g. "Good
  luck @everyone") — and the related risk that raw-content mirroring of an `@everyone` line could
  **ping the channel** (the webhook posts without restricting `allowed_mentions`). Worth a separate
  fix (parser ignores trailing non-level lines like it ignores the author header; and/or set
  `allowed_mentions:{parse:[]}` on the webhook). Noted, not addressed by this plan.
- Reducing/clearing the orphaned days that already mislabelled state (operational; today already
  remediated by hand).
