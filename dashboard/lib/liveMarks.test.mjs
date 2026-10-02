// Run: node --experimental-strip-types --test dashboard/lib/liveMarks.test.mjs   (Node 22)
// Not part of tsc (tsconfig includes *.ts/*.tsx only) or the Next build (nothing imports it).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  bidUsability,
  underlyingUsability,
  liveValue,
  livePnlToday,
  livePnlTotal,
  allIn,
  pctMove,
  flashDirection,
  serverLight,
  partLight,
  optionLight,
  fmtAge,
  occKey,
  num,
} from "./liveMarks.ts";

const NOW = Date.parse("2026-10-01T15:00:10Z");
const clock = (over = {}) => ({ payloadNowMs: NOW, elapsedMs: 0, failures: 0, ...over });
const mark = (over = {}) => ({
  bid: 2.5,
  quote_at: "2026-10-01T15:00:08Z",
  underlying: { price: 41.12, at: "2026-10-01T15:00:09Z" },
  warming: false,
  capped: false,
  ...over,
});

test("a fresh mark is usable with its age measured against the payload's now", () => {
  assert.deepEqual(bidUsability(mark(), clock()), { usable: true, value: 2.5, ageS: 2 });
  assert.deepEqual(underlyingUsability(mark(), clock()), { usable: true, value: 41.12, ageS: 1 });
});

test("missing / warming / capped / no bid / no frame are never usable", () => {
  assert.equal(bidUsability(undefined, clock()).reason, "missing");
  assert.equal(bidUsability(mark(), null).reason, "missing");
  assert.equal(bidUsability(mark({ warming: true }), clock()).reason, "warming");
  assert.equal(bidUsability(mark({ capped: true, bid: null }), clock()).reason, "capped");
  assert.equal(bidUsability(mark({ bid: null }), clock()).reason, "no-quote");
  assert.equal(bidUsability(mark({ quote_at: null }), clock()).reason, "no-quote");
  assert.equal(underlyingUsability(mark({ underlying: null }), clock()).reason, "no-quote");
});

test("older than 10s is stale; a held frame keeps ageing on the client clock", () => {
  assert.equal(bidUsability(mark({ quote_at: "2026-10-01T15:00:00Z" }), clock()).usable, true); // exactly 10s
  const old = bidUsability(mark({ quote_at: "2026-10-01T14:59:59Z" }), clock());
  assert.equal(old.usable, false);
  assert.equal(old.reason, "stale");
  assert.equal(old.ageS, 11);
  // 2s old at arrival + 9s since the frame arrived = 11s
  assert.equal(bidUsability(mark(), clock({ elapsedMs: 9000 })).reason, "stale");
});

test("a malformed timestamp is not usable (never NaN-aged live)", () => {
  const u = bidUsability(mark({ quote_at: "garbage" }), clock());
  assert.deepEqual(u, { usable: false, reason: "no-quote", ageS: null });
  const v = underlyingUsability(mark({ underlying: { price: 41.12, at: "garbage" } }), clock());
  assert.deepEqual(v, { usable: false, reason: "no-quote", ageS: null });
});

test("a null timestamp with a price is no-quote, not aged from the epoch", () => {
  assert.deepEqual(bidUsability(mark({ quote_at: null }), clock()), { usable: false, reason: "no-quote", ageS: null });
  const v = underlyingUsability(mark({ underlying: { price: 41.12, at: null } }), clock());
  assert.deepEqual(v, { usable: false, reason: "no-quote", ageS: null });
});

test("a future-dated quote (clock skew) clamps to age 0, never negative", () => {
  const u = bidUsability(mark({ quote_at: "2026-10-01T15:00:15Z" }), clock()); // 5s ahead of now
  assert.deepEqual(u, { usable: true, value: 2.5, ageS: 0 });
});

test("three consecutive failed polls make every mark unusable", () => {
  assert.equal(bidUsability(mark(), clock({ failures: 2 })).usable, true);
  assert.equal(bidUsability(mark(), clock({ failures: 3 })).reason, "server");
  assert.equal(underlyingUsability(mark(), clock({ failures: 3 })).reason, "server");
});

test("value and P&L math at the bid", () => {
  assert.equal(liveValue(2.5, 3), 750);
  assert.equal(liveValue(2.5, null), null);
  // (2.5 - 2.0) * 3 * 100
  assert.equal(livePnlToday(2.5, 2.0, 3), 150);
  assert.equal(livePnlToday(2.5, null, 3), null);
  // (2.5 - 2.805) * 21 * 100
  assert.ok(Math.abs(livePnlTotal(2.5, 2.805, 21) - -640.5) < 1e-9);
  assert.equal(livePnlTotal(2.5, null, 21), null);
  assert.equal(allIn(-640.5, 1406), 765.5);
  assert.equal(allIn(-640.5, null), null);
  assert.equal(allIn(null, 1406), null);
});

test("pctMove, flash direction, num, occKey", () => {
  assert.equal(pctMove(2, 2.5), 25);
  assert.equal(pctMove(0, 2.5), null);
  assert.equal(flashDirection(undefined, 2), null); // first mount
  assert.equal(flashDirection(2, 2), null);
  assert.equal(flashDirection(2, 2.05), "up");
  assert.equal(flashDirection(2, 1.95), "down");
  assert.equal(flashDirection(2, null), null);
  assert.equal(num(""), null);
  assert.equal(num(null), null);
  assert.equal(num("2.805"), 2.805);
  assert.equal(occKey("SPY   261009C00500000"), "SPY261009C00500000");
});

test("server light: green, amber when late or unknown, red after 3 failures", () => {
  assert.deepEqual(serverLight(NOW - 1000, NOW, 0), { tone: "green", text: "ok", ageS: 1 });
  assert.equal(serverLight(NOW - 4000, NOW, 1).tone, "amber");
  assert.equal(serverLight(null, NOW, 0).text, "unknown");
  assert.equal(serverLight(null, NOW, 0).tone, "amber");
  assert.equal(serverLight(NOW - 1000, NOW, 3).tone, "red");
});

test("part light: status mapping, market closed grey, stale snapshot unknown, never green unknown", () => {
  const ok = { status: "ok", age_s: 1, reason: null };
  assert.deepEqual(partLight(ok, 2, false, true), { tone: "green", text: "ok", ageS: 3 });
  assert.equal(partLight({ ...ok, status: "stale" }, 0, false, true).tone, "amber");
  assert.equal(partLight({ ...ok, status: "down" }, 0, false, true).tone, "red");
  assert.equal(partLight({ ...ok, status: "unknown" }, 0, false, true).text, "unknown");
  assert.equal(partLight({ ...ok, status: "weird" }, 0, false, true).tone, "amber");
  assert.equal(partLight(ok, 16, false, true).text, "unknown");
  assert.equal(partLight(ok, null, false, true).text, "unknown");
  assert.equal(partLight(undefined, 0, false, true).text, "unknown");
  assert.deepEqual(partLight(ok, 0, true, false), { tone: "grey", text: "market closed", ageS: null });
  // closed only greys MARKET feeds
  assert.equal(partLight({ ...ok, status: "down" }, 0, false, false).tone, "red");
});

test("option light: worst usable age across holdings, else the BFF part", () => {
  const u = (ageS) => ({ usable: true, value: 1, ageS });
  const stale = { usable: false, reason: "stale", ageS: 12 };
  assert.deepEqual(optionLight([u(1), u(3)], null, 0, true), { tone: "green", text: "ok", ageS: 3 });
  assert.equal(optionLight([u(1), u(7)], null, 0, true).tone, "amber");
  assert.deepEqual(optionLight([u(1), stale], null, 0, true), { tone: "amber", text: "1/2 live", ageS: 1 });
  assert.equal(optionLight([stale], null, 0, true).tone, "red");
  assert.equal(optionLight([stale], null, 0, false).text, "market closed");
  assert.equal(optionLight([], { status: "ok", age_s: 0 }, 0, true).tone, "green");
  assert.equal(optionLight([], null, 0, true).text, "unknown");
});

test("fmtAge", () => {
  assert.equal(fmtAge(null), "—");
  assert.equal(fmtAge(3.9), "3s");
  assert.equal(fmtAge(125), "2m");
  assert.equal(fmtAge(7300), "2h");
});
