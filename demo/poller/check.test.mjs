import { test } from "node:test";
import assert from "node:assert/strict";
import { check } from "./check.mjs";

const UUID = "11111111-2222-4333-8444-555555555555";
const OCC = "NVDA  261224C00200000";
const position = {
  strategy_id: "copytrade-v1",
  contract_symbol: OCC,
  remaining_qty: 11,
  entry_premium: 3.36,
  open_notional: 3696,
  current_price: 0.9,
  trailing_armed: false,
};
const equity = { broker_target: "alpaca-paper", equity: 96591.36, last_equity: 1, today_pl: 2 };
const portfolio = {
  trading_day: "2026-11-27",
  open_positions: [position],
  open_positions_degraded: false,
  open_positions_count: 1,
  sum_open_notional: 3696,
  realized_pnl_today: 12.5,
  realized_pnl_all_time: null,
  account_equity: [equity],
};
const subject = (extra = {}) =>
  JSON.stringify({ option_symbol: OCC, filled_qty: 17, avg_fill_price: 3.36, broker_order_id: UUID, ...extra });
const fill = { occurred_at: "2026-11-27T15:00:00.123+00:00", kind: "EntryFilled", strategy_id: "copytrade-v1", subject: subject(), entry_basis: 3.36 };
const fills = (s = subject()) => ({ count: 1, items: [{ ...fill, subject: s }] });
const order = {
  strategy_id: "copytrade-v1", broker_target: "alpaca-paper", option_symbol: OCC,
  side: "BUY", qty: 17, limit_price: 3.4, state: "FILLED", broker_order_id: UUID,
  recorded_at: "2026-11-27T15:00:00Z", submitted_at: null, filled_qty: 17, avg_fill_price: 3.36,
  filled_at: "2026-11-27T15:00:02.123456Z", failed: false,
};
const mark = { occ: OCC, bid: 0.9, mid: 0.95, ask: 1, quote_at: "2026-11-27T15:00:00.123456789Z", polled_at: null, warming: false, underlying: { ticker: "NVDA", price: 181.5, at: "2026-11-27T15:00:00Z" } };
const marks = { now: "2026-11-27T15:00:00Z", market_data_reachable: true, marks: [mark] };

const ok = (feed, body) => assert.equal(check(feed, JSON.stringify(body)), null, feed);
const refused = (feed, body, rule) => assert.equal(check(feed, JSON.stringify(body)), rule);
// One changed field per feed's row, so each test names only the value it is about.
const withPosition = (extra) => ({ ...portfolio, open_positions: [{ ...position, ...extra }] });
const withEquity = (extra) => ({ ...portfolio, account_equity: [{ ...equity, ...extra }] });
const withFill = (extra) => ({ count: 1, items: [{ ...fill, ...extra }] });
const withOrder = (extra) => ({ count: 1, items: [{ ...order, ...extra }] });
const withMark = (extra) => ({ ...marks, marks: [{ ...mark, ...extra }] });
const without = (obj, key) => Object.fromEntries(Object.entries(obj).filter(([k]) => k !== key));

test("the five real payload shapes are publishable", () => {
  ok("status", { tripped: false, trippedAt: null, resettableAt: null });
  ok("status", { tripped: true, trippedAt: "2026-11-27T15:00Z", resettableAt: "2026-11-27T15:15Z" });
  ok("status", { tripped: null });
  ok("portfolio", portfolio);
  ok("portfolio", withPosition({ underlying_spot_entry: null, underlying_price: 181.5, unrealized_pl: -2706, unrealized_intraday_pl: -12.5, lastday_price: 0.91, trail_giveback_pct: 35, trail_stop_price: 0.6 }));
  ok("portfolio", withPosition({ contract_symbol: "GOOGL 261204C00285000" }));
  ok("fills", fills());
  ok("fills", fills(subject({ qty_filled: 6, remaining_qty_after: 11 })));
  ok("fills", withFill({ kind: "PartialExitFilled", entry_basis: null }));
  ok("orders", { count: 1, items: [order] });
  ok("orders", withOrder({ limit_price: null, filled_qty: null, avg_fill_price: null, filled_at: null, state: "ERRORED", side: "SELL", failed: true }));
  ok("orders", { count: 1, items: [without(order, "broker_order_id")] });
  ok("marks", marks);
  ok("marks", withMark({ bid: null, mid: null, ask: null, quote_at: null, warming: true, underlying: { ticker: "NVDA", price: null, at: null } }));
  ok("marks", { now: "2026-11-27T15:00:00Z", market_data_reachable: false, marks: [] });
  ok("orders", { count: 0, items: [] });
});

test("the portfolio's warm-up payload (no account totals yet) is publishable", () => {
  ok("portfolio", { ...portfolio, account_equity: [], realized_pnl_today: null, realized_pnl_all_time: null });
});

test("any key outside the feed's allow-list refuses the whole payload", () => {
  for (const k of ["workflow_id", "signal_id", "correlation_id", "event_id", "intent_key", "tenant_id", "actor", "last_error", "some_future_field", "entry_qty", "realized_pl"]) {
    refused("portfolio", withPosition({ [k]: "x" }), "unknown-key");
  }
  refused("portfolio", { ...portfolio, tenant_id: "staging_paper" }, "unknown-key");
  refused("portfolio", { ...portfolio, unmanaged_positions: [] }, "unknown-key");
  refused("portfolio", withEquity({ account_number: "x" }), "unknown-key");
  refused("status", { tripped: true, reason: "auto:daily_loss" }, "unknown-key");
  refused("orders", withOrder({ last_error: "broker said no" }), "unknown-key");
  refused("orders", withOrder({ signal_id: "x" }), "unknown-key");
  refused("fills", withFill({ correlation_id: "x" }), "unknown-key");
  // A key that is fine in one feed is not thereby fine in another.
  refused("status", { tripped: false, equity: 1 }, "unknown-key");
});

test("a key that is allowed at one depth is refused at another", () => {
  refused("portfolio", { ...portfolio, equity: 1 }, "unknown-key");
  refused("portfolio", withPosition({ equity: 1 }), "unknown-key");
  refused("portfolio", withEquity({ strategy_id: "copytrade-v1" }), "unknown-key");
  refused("orders", { count: 1, items: [order], option_symbol: OCC }, "unknown-key");
  refused("marks", withMark({ ticker: "NVDA" }), "unknown-key");
  refused("marks", withMark({ underlying: { ticker: "NVDA", price: 1, at: null, bid: 1 } }), "unknown-key");
  refused("marks", { ...marks, occ: OCC }, "unknown-key");
});

test("a required key that is missing refuses", () => {
  refused("status", {}, "missing-key");
  refused("portfolio", without(portfolio, "account_equity"), "missing-key");
  refused("portfolio", { ...portfolio, open_positions: [without(position, "contract_symbol")] }, "missing-key");
  refused("fills", { count: 1, items: [without(fill, "subject")] }, "missing-key");
  refused("orders", { count: 1, items: [without(order, "failed")] }, "missing-key");
  refused("marks", { ...marks, marks: [without(mark, "occ")] }, "missing-key");
  refused("fills", fills(JSON.stringify({ filled_qty: 1 })), "missing-key");
});

test("a value of the wrong JSON type refuses", () => {
  // Ids arriving as numbers where a string belongs.
  refused("orders", withOrder({ broker_order_id: 1574829260651 }), "not-a-string");
  refused("orders", withOrder({ strategy_id: 7 }), "not-a-string");
  refused("orders", withOrder({ option_symbol: 261224 }), "not-a-string");
  refused("orders", withOrder({ recorded_at: 1764255600 }), "not-a-string");
  refused("orders", withOrder({ recorded_at: null }), "not-a-string");
  refused("portfolio", { ...portfolio, trading_day: 20261127 }, "not-a-string");
  refused("marks", withMark({ underlying: { ticker: 5, price: 1, at: null } }), "not-a-string");
  refused("fills", withFill({ subject: { option_symbol: OCC } }), "not-a-string");
  // Strings and booleans where a number belongs.
  refused("portfolio", withPosition({ entry_premium: "3.36" }), "not-a-number");
  refused("portfolio", withPosition({ remaining_qty: "11" }), "not-a-number");
  refused("portfolio", withEquity({ equity: true }), "not-a-number");
  refused("orders", { count: "1", items: [order] }, "not-a-number");
  refused("marks", withMark({ bid: "0.9" }), "not-a-number");
  // Anything but a boolean where a boolean belongs.
  refused("status", { tripped: "true" }, "not-a-boolean");
  refused("status", { tripped: 1 }, "not-a-boolean");
  refused("portfolio", withPosition({ trailing_armed: 0 }), "not-a-boolean");
  refused("portfolio", { ...portfolio, open_positions_degraded: null }, "not-a-boolean");
  refused("orders", withOrder({ failed: "no" }), "not-a-boolean");
  refused("marks", { ...marks, market_data_reachable: "yes" }, "not-a-boolean");
  // Containers.
  refused("portfolio", { ...portfolio, open_positions: {} }, "not-an-array");
  refused("orders", { count: 1, items: "x" }, "not-an-array");
  refused("orders", { count: 1, items: ["x"] }, "not-an-object");
  refused("marks", withMark({ underlying: [] }), "not-an-object");
  refused("portfolio", { ...portfolio, open_positions: [[position]] }, "not-an-object");
});

test("every numeric and boolean field is held to its own type", () => {
  const id = "7697-9717"; // an id sent as a string where a boolean belongs
  for (const k of ["entry_premium", "open_notional", "underlying_spot_entry", "underlying_price", "current_price", "unrealized_pl", "unrealized_intraday_pl", "lastday_price", "trail_giveback_pct", "trail_stop_price"]) {
    refused("portfolio", withPosition({ [k]: "x" }), "not-a-number");
    refused("portfolio", withPosition({ [k]: 1e12 }), "number-out-of-range");
  }
  for (const k of ["sum_open_notional", "realized_pnl_today", "realized_pnl_all_time", "open_positions_count"]) {
    refused("portfolio", { ...portfolio, [k]: "x" }, "not-a-number");
    refused("portfolio", { ...portfolio, [k]: 1e12 }, "number-out-of-range");
  }
  for (const k of ["equity", "last_equity", "today_pl"]) {
    refused("portfolio", withEquity({ [k]: "x" }), "not-a-number");
    refused("portfolio", withEquity({ [k]: 1e12 }), "number-out-of-range");
  }
  refused("fills", withFill({ entry_basis: "x" }), "not-a-number");
  refused("fills", withFill({ entry_basis: 1e12 }), "number-out-of-range");
  for (const k of ["qty", "limit_price", "filled_qty", "avg_fill_price"]) {
    refused("orders", withOrder({ [k]: "x" }), "not-a-number");
    refused("orders", withOrder({ [k]: 1e12 }), "number-out-of-range");
  }
  for (const k of ["bid", "mid", "ask"]) {
    refused("marks", withMark({ [k]: "x" }), "not-a-number");
    refused("marks", withMark({ [k]: 1e12 }), "number-out-of-range");
  }
  refused("marks", withMark({ underlying: { ticker: "NVDA", price: "x", at: null } }), "not-a-number");
  refused("marks", withMark({ warming: id }), "not-a-boolean");
  refused("marks", withMark({ warming: null }), "not-a-boolean");
});

test("counts and quantities are whole numbers", () => {
  refused("portfolio", withPosition({ remaining_qty: 1.5 }), "not-an-integer");
  refused("portfolio", { ...portfolio, open_positions_count: 0.5 }, "not-an-integer");
  refused("orders", withOrder({ qty: 17.1 }), "not-an-integer");
  refused("orders", withOrder({ filled_qty: 0.1 }), "not-an-integer");
  refused("fills", { count: 1.5, items: [fill] }, "not-an-integer");
  refused("fills", fills(subject({ filled_qty: 1.5 })), "not-an-integer");
});

test("a number too large to be a price, quantity or balance refuses", () => {
  // 17-19 digit ids, and 9-10 digit account numbers, do not fit any numeric field.
  refused("portfolio", withPosition({ remaining_qty: 157482926065136 }), "number-out-of-range");
  refused("portfolio", withPosition({ entry_premium: 1574829260651.5 }), "number-out-of-range");
  refused("portfolio", withEquity({ equity: 847309116 }), "number-out-of-range");
  refused("portfolio", withEquity({ today_pl: -847309116 }), "number-out-of-range");
  refused("portfolio", { ...portfolio, sum_open_notional: 3133923880 }, "number-out-of-range");
  refused("orders", withOrder({ qty: 847309116 }), "number-out-of-range");
  refused("orders", withOrder({ limit_price: -1 }), "number-out-of-range");
  refused("orders", withOrder({ qty: -1 }), "number-out-of-range");
  refused("marks", withMark({ bid: 847309116 }), "number-out-of-range");
  assert.equal(check("marks", JSON.stringify(withMark({ bid: 0.5 })).replace("0.5", "1e999")), "number-out-of-range");
});

test("a run of 17 or more digits anywhere in the body refuses, number or string", () => {
  refused("portfolio", withPosition({ remaining_qty: 1574829260651368620 }), "snowflake");
  assert.equal(check("marks", JSON.stringify(withMark({ bid: 0.5 })).replace("0.5", "0.15748292606513686")), "snowflake");
  refused("portfolio", withPosition({ contract_symbol: "1574829260651368620" }), "snowflake");
  // Written with escapes, the digits are not adjacent in the body; the per-string net still sees them.
  const escaped = JSON.stringify(withPosition({ contract_symbol: "ESCAPED" })).replace("ESCAPED", "\\u0031".repeat(18));
  assert.equal(check("portfolio", escaped), "snowflake");
});

test("closed sets refuse a value outside the set", () => {
  refused("fills", withFill({ kind: "PositionEntryIncreased" }), "unknown-kind");
  refused("fills", withFill({ kind: "entryfilled" }), "unknown-kind");
  refused("orders", withOrder({ side: "SHORT" }), "unknown-side");
  refused("orders", withOrder({ side: "buy" }), "unknown-side");
  refused("orders", withOrder({ state: "REJECTED" }), "unknown-state");
  refused("orders", withOrder({ state: "anything the broker said" }), "unknown-state");
  refused("orders", withOrder({ strategy_id: "some-new-strategy" }), "strategy-not-shown");
  refused("portfolio", withPosition({ strategy_id: "copytrade-v2" }), "strategy-not-shown");
  refused("fills", withFill({ strategy_id: "" }), "strategy-not-shown");
});

test("only a paper broker target is publishable", () => {
  ok("orders", withOrder({ broker_target: "tradier-paper" }));
  refused("orders", withOrder({ broker_target: "alpaca-live" }), "not-paper");
  refused("portfolio", withEquity({ broker_target: "alpaca-live" }), "not-paper");
  refused("orders", withOrder({ broker_target: "some free text that ends in -paper" }), "not-paper");
  refused("orders", withOrder({ broker_target: "-paper" }), "not-paper");
});

test("an option symbol must be an OCC symbol", () => {
  ok("orders", withOrder({ option_symbol: "NVDA261224C00200000" }));
  ok("orders", withOrder({ option_symbol: "F     261224P00012500" }));
  for (const bad of ["NVDA", "NVDA 261224C00200000", "nvda  261224C00200000", "NVDA  261324C00200000", "NVDA  261232C00200000", "NVDA  261224c00200000", "NVDA  261224C0020000", "NVDA  261224C00200000 and more", "TOOLONGX261224C00200000", ""]) {
    refused("orders", withOrder({ option_symbol: bad }), "bad-occ");
    refused("portfolio", withPosition({ contract_symbol: bad }), "bad-occ");
    refused("marks", withMark({ occ: bad }), "bad-occ");
    refused("fills", fills(subject({ option_symbol: bad })), "bad-occ");
  }
});

test("a ticker must look like a ticker", () => {
  ok("marks", withMark({ underlying: { ticker: "BRK.B", price: 1, at: null } }));
  for (const bad of ["nvda", "NVDA Corp", "TOOLONGX", "", "N/A"]) {
    refused("marks", withMark({ underlying: { ticker: bad, price: 1, at: null } }), "bad-ticker");
  }
});

test("a timestamp must be ISO-8601 with a zone, a date YYYY-MM-DD", () => {
  for (const bad of ["yesterday", "2026-11-27", "2026-11-27 15:00:00Z", "2026-11-27T15:00:00", "2026-13-27T15:00:00Z", "2026-11-27T25:00:00Z", "2026-11-27T15:00:00.1234567890Z", "11/27/2026 3pm", "2026-11-27T15:00:00Z by an operator", ""]) {
    refused("orders", withOrder({ recorded_at: bad }), "bad-timestamp");
    refused("orders", withOrder({ submitted_at: bad }), "bad-timestamp");
    refused("orders", withOrder({ filled_at: bad }), "bad-timestamp");
    refused("fills", withFill({ occurred_at: bad }), "bad-timestamp");
    refused("status", { tripped: true, trippedAt: bad }, "bad-timestamp");
    refused("status", { tripped: true, resettableAt: bad }, "bad-timestamp");
    refused("marks", { ...marks, now: bad }, "bad-timestamp");
    refused("marks", withMark({ quote_at: bad }), "bad-timestamp");
    refused("marks", withMark({ polled_at: bad }), "bad-timestamp");
    refused("marks", withMark({ underlying: { ticker: "NVDA", price: 1, at: bad } }), "bad-timestamp");
  }
  for (const bad of ["today", "2026-11-27T15:00:00Z", "2026-13-01", "2026-11-32", "26-11-27", ""]) {
    refused("portfolio", { ...portfolio, trading_day: bad }, "bad-date");
  }
});

test("lists longer than the backend can send refuse", () => {
  const many = (row, n) => Array.from({ length: n }, () => row);
  ok("orders", { count: 500, items: many(order, 500) });
  refused("orders", { count: 501, items: many(order, 501) }, "too-many-items");
  ok("fills", { count: 500, items: many(fill, 500) });
  refused("fills", { count: 501, items: many(fill, 501) }, "too-many-items");
  refused("portfolio", { ...portfolio, open_positions: many(position, 201) }, "too-many-items");
  refused("portfolio", { ...portfolio, account_equity: many(equity, 9) }, "too-many-items");
  refused("marks", { ...marks, marks: many(mark, 51) }, "too-many-items");
});

test("a fill subject is re-checked field by field", () => {
  refused("fills", fills(subject({ signal_id: "x" })), "unknown-subject-key");
  refused("fills", fills(subject({ intent_key: "x" })), "unknown-subject-key");
  refused("fills", fills(subject({ broker_order_id: { x: 1 } })), "not-a-string");
  refused("fills", fills(subject({ broker_order_id: "adopt:run-1" })), "order-id-not-uuid");
  refused("fills", fills(subject({ broker_order_id: 1574829260651 })), "not-a-string");
  refused("fills", fills(subject({ filled_qty: "17" })), "not-a-number");
  refused("fills", fills(subject({ qty_filled: 847309116 })), "number-out-of-range");
  refused("fills", fills(subject({ remaining_qty_after: -1 })), "number-out-of-range");
  refused("fills", fills(subject({ avg_fill_price: "3.36" })), "not-a-number");
  refused("fills", fills(subject({ avg_fill_price: [3.36] })), "not-a-number");
  refused("fills", fills("not json"), "subject-not-json");
  refused("fills", fills("[1]"), "subject-not-object");
  refused("fills", fills("null"), "subject-not-object");
  refused("fills", fills(subject({ option_symbol: "see chat-messages-123-456" })), "discord-id");
  refused("fills", fills(subject() + " ".repeat(400)), "long-string");
});

test("a key written twice refuses: only its last value would have been checked", () => {
  const twice = '{"tripped":"anything at all","tripped":false}';
  assert.equal(JSON.parse(twice).tripped, false);
  assert.equal(check("status", twice), "duplicate-key");
  const inner = `{"option_symbol":"free text","option_symbol":"${OCC}"}`;
  refused("fills", fills(inner), "duplicate-key");
});

test("forbidden shapes are refused wherever they appear", () => {
  const withSymbol = (v) => withPosition({ contract_symbol: v });
  refused("portfolio", withSymbol("chat-messages-769797179992571914-1574829260651368620:0"), "snowflake");
  refused("portfolio", withSymbol("chat-messages-7697-1574:0"), "discord-id");
  refused("portfolio", withSymbol("someone@example.com"), "email");
  refused("portfolio", withSymbol("https://example.com/x"), "url");
  refused("portfolio", withSymbol("<img src=x onerror=alert(1)>"), "markup");
  refused("portfolio", withSymbol("PA3FKGPFYPLH"), "paper-account-number");
  refused("portfolio", withSymbol("x".repeat(401)), "long-string");
  refused("portfolio", withSymbol("posted to a webhook"), "webhook");
  refused("portfolio", withSymbol("gated_condor"), "hidden-strategy");
  refused("orders", withOrder({ strategy_id: "gated_condor" }), "hidden-strategy");
  refused("marks", withMark({ occ: "discord.gg/abc" }), "discord");
  // The net runs over every string, whatever format its field has.
  refused("orders", withOrder({ state: "someone@example.com" }), "email");
  refused("orders", withOrder({ recorded_at: "https://example.com/x" }), "url");
  refused("marks", withMark({ underlying: { ticker: "<b>", price: 1, at: null } }), "markup");
});

test("an account number is refused anywhere inside a string", () => {
  const withSymbol = (v) => withPosition({ contract_symbol: v });
  refused("portfolio", withSymbol("847309116"), "account-number");
  refused("portfolio", withSymbol("acct 847309116 (live)"), "account-number");
  refused("portfolio", withSymbol("acct847309116"), "account-number");
  refused("portfolio", withSymbol("account=3133923880;"), "account-number");
  // The digit runs inside the real formats are not account numbers.
  ok("orders", withOrder({ broker_order_id: "12345678-1234-4234-8234-123456789012", filled_at: "2026-11-27T15:00:02.123456789Z" }));
});

test("every workflow-id shape is refused", () => {
  const withSymbol = (v) => withPosition({ contract_symbol: v });
  for (const id of [
    "t-acme/s-copytrade-v1/pos/NVDA261224C00200000/abc",
    "t-acme/s-copytrade-v1/sig/abc",
    "t-acme/s-copytrade-v1/killswitch",
    "t-acme/account/killswitch",
    "t-acme/wl/2026-11-27/digest",
    "t-acme/account/cfg-write/abc",
    "t-acme/_broker/cred-audit/abc",
    "x/account/cfg-write/abc",
    "audit-emit/abc/EntryFilled/def",
    "x/wl/2026-11-27/digest",
    "x/pos/NVDA",
    "x/sig/abc",
    "x/killswitch",
    "wl-armed:acme:watchlist-trigger-v1:2026-11-27",
  ]) {
    refused("portfolio", withSymbol(id), "workflow-id");
  }
});

test("a tenant id is refused", () => {
  const withSymbol = (v) => withPosition({ contract_symbol: v });
  for (const id of ["prod_real", "prod-kipark", "tenant prod-jinchul here", "staging_paper", "PROD_REAL", "paper_someone"]) {
    refused("portfolio", withSymbol(id), "tenant-id");
  }
  refused("orders", withOrder({ broker_target: "prod_real-paper" }), "tenant-id");
});

test("error bodies, junk and oversized responses are never published", () => {
  assert.equal(check("portfolio", '{"error":"unavailable"}'), "unknown-key");
  assert.equal(check("portfolio", "<html>502 Bad Gateway</html>"), "not-json");
  assert.equal(check("portfolio", ""), "empty");
  assert.equal(check("portfolio", "[]"), "not-an-object");
  assert.equal(check("portfolio", "null"), "not-an-object");
  assert.equal(check("portfolio", JSON.stringify({ trading_day: "x".repeat(2_000_001) })), "too-large");
  assert.equal(check("nope", "{}"), "unknown-feed");
  assert.equal(check("constructor", "{}"), "unknown-feed");
});

test("a rule name never contains the offending content", () => {
  const rule = check("status", JSON.stringify({ tripped: true, reason: "SECRET free text" }));
  assert.equal(rule, "unknown-key");
  assert.ok(!rule.includes("SECRET"));
  assert.equal(check("orders", JSON.stringify(withOrder({ state: "SECRET" }))), "unknown-state");
});
