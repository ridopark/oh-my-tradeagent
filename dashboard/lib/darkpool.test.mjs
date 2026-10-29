// Run: node --experimental-strip-types --test dashboard/lib/darkpool.test.mjs   (Node 22)
//  or: cd dashboard && npm run test:lib
// Not part of tsc (tsconfig includes *.ts/*.tsx only) or the Next build (nothing imports it).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  etToday,
  isPollWindow,
  isPreOpen,
  daysBetween,
  fmtNotional,
  fmtRatio,
  fmtEtTime,
  freshness,
  sortSummary,
  levelsState,
  levelLabel,
  minNotionalLabel,
  printsQuery,
  isDisabled,
} from "./darkpool.ts";

// Pin the RUNNER's zone so the ET helpers are proven to use America/New_York explicitly rather
// than passing only because the machine happens to sit in an ET-adjacent zone. Node re-reads TZ on
// assignment; the helpers compute nothing at import time.
process.env.TZ = "UTC";

test("etToday is the ET calendar date, not UTC", () => {
  // 2026-10-30 02:00Z = 2026-10-29 22:00 EDT.
  assert.equal(etToday(new Date("2026-10-30T02:00:00Z")), "2026-10-29");
});

test("isPollWindow: 09:30 up to 16:03 ET on weekdays, across DST", () => {
  assert.equal(isPollWindow(new Date("2026-10-29T13:29:59Z")), false); // 09:29:59 EDT
  assert.equal(isPollWindow(new Date("2026-10-29T13:30:00Z")), true); // 09:30 EDT
  assert.equal(isPollWindow(new Date("2026-10-29T20:02:00Z")), true); // 16:02 EDT: closing bar lands
  assert.equal(isPollWindow(new Date("2026-10-29T20:03:00Z")), false); // 16:03 EDT
  assert.equal(isPollWindow(new Date("2026-12-01T14:30:00Z")), true); // 09:30 EST
  assert.equal(isPollWindow(new Date("2026-12-01T14:29:00Z")), false);
  assert.equal(isPollWindow(new Date("2026-10-31T15:00:00Z")), false); // Saturday
});

test("isPreOpen: weekday before 09:30 ET only", () => {
  assert.equal(isPreOpen(new Date("2026-10-29T12:00:00Z")), true); // 08:00 EDT Thu
  assert.equal(isPreOpen(new Date("2026-10-29T13:30:00Z")), false); // 09:30 EDT
  assert.equal(isPreOpen(new Date("2026-10-31T12:00:00Z")), false); // Saturday
});

test("formatting: null ratios are a dash, never 0%", () => {
  assert.equal(fmtRatio(null), "—");
  assert.equal(fmtRatio(0), "0.0%");
  assert.equal(fmtRatio(0.4213), "42.1%");
  assert.equal(fmtNotional(1_250_000), "$1.25M");
  assert.equal(fmtNotional(512_400), "$512k");
  assert.equal(fmtNotional(null), "—");
  assert.equal(minNotionalLabel(0), "all sizes");
  assert.equal(minNotionalLabel(200_000), "≥ $200k");
});

test("fmtEtTime prefixes the date only for a print outside today; EDT and EST", () => {
  assert.equal(fmtEtTime("2026-10-29T18:03:27Z", "2026-10-29"), "14:03:27"); // EDT, UTC-4
  assert.equal(fmtEtTime("2026-10-28T18:03:27Z", "2026-10-29"), "10-28 14:03:27");
  assert.equal(fmtEtTime("2026-12-01T19:03:27Z", "2026-12-01"), "14:03:27"); // EST, UTC-5
  // 03:00Z on Dec 2 is still Dec 1 in ET.
  assert.equal(fmtEtTime("2026-12-02T03:00:00Z", "2026-12-01"), "22:00:00");
});

test("freshness: rejected > empty > failed; stale only while auto-refresh is on", () => {
  const ok = { failed: false, invalid: false, noData: false };
  assert.equal(freshness(1_000, { ...ok, invalid: true, failed: true }, 30_000), "rejected");
  assert.equal(freshness(1_000, { ...ok, noData: true, failed: true }, 30_000), "empty");
  assert.equal(freshness(1_000, { ...ok, failed: true }, 30_000), "failed");
  assert.equal(freshness(91_000, ok, 30_000), "stale");
  assert.equal(freshness(89_000, ok, 30_000), "fresh");
  assert.equal(freshness(10 * 3_600_000, ok, null), "fresh");
});

const row = (symbol, over = {}) => ({
  symbol,
  dp_volume: 0,
  total_volume: 0,
  dp_share: null,
  buy_volume: 0,
  sell_volume: 0,
  buy_ratio: null,
  late_buy_ratio: null,
  large_count: 0,
  large_volume: 0,
  levels: [],
  ...over,
});

test("sortSummary keeps nulls last in both directions", () => {
  const rows = [row("A", { dp_share: 0.2 }), row("B"), row("C", { dp_share: 0.5 })];
  assert.deepEqual(sortSummary(rows, "dp_share", "desc").map((r) => r.symbol), ["C", "A", "B"]);
  assert.deepEqual(sortSummary(rows, "dp_share", "asc").map((r) => r.symbol), ["A", "C", "B"]);
});

test("levelsState: an old session with no levels is EXPIRED, not 'none'", () => {
  const today = "2026-10-29";
  assert.equal(levelsState(row("A", { large_count: 4 }), "2026-07-01", today), "expired");
  assert.equal(levelsState(row("A", { large_count: 0 }), "2026-07-01", today), "expired");
  assert.equal(levelsState(row("A", { large_count: 0 }), "2026-10-28", today), "none");
  assert.equal(levelsState(row("A", { large_count: 2 }), "2026-10-28", today), "missing");
  const lv = [{ price: 412.3, notional: 5_210_000, prints: 7 }];
  assert.equal(levelsState(row("A", { levels: lv }), "2026-07-01", today), "levels");
});

test("levelsState: the 90-day retention boundary", () => {
  const today = "2026-10-29";
  assert.equal(daysBetween("2026-07-31", today), 90);
  assert.equal(levelsState(row("A", { large_count: 2 }), "2026-07-31", today), "expired"); // day 90
  assert.equal(levelsState(row("A", { large_count: 2 }), "2026-08-01", today), "missing"); // day 89
  assert.equal(levelsState(row("A", { large_count: 0 }), "2026-08-01", today), "none");
});

test("levelLabel pluralises the print count", () => {
  assert.equal(levelLabel({ price: 412.3, notional: 5_210_000, prints: 7 }), "$412.30 · $5.21M · 7 prints");
  assert.equal(levelLabel({ price: 9.87, notional: 250_000, prints: 1 }), "$9.87 · $250k · 1 print");
});

test("printsQuery drops a symbol outside the BFF charset and a zero floor", () => {
  assert.equal(printsQuery(" qqq ", 500000), "symbol=QQQ&min_notional=500000&limit=200");
  assert.equal(printsQuery("Q Q;", 200000), "min_notional=200000&limit=200");
  assert.equal(printsQuery("", 0), "limit=200");
});

test("isDisabled recognises only the proxy's disabled body", () => {
  assert.equal(isDisabled({ disabled: true }), true);
  assert.equal(isDisabled({ count: 0, items: [] }), false);
  assert.equal(isDisabled(null), false);
});
