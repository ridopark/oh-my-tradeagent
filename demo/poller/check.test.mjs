import { test } from "node:test";
import assert from "node:assert/strict";
import { check } from "./check.mjs";

const UUID = "11111111-2222-4333-8444-555555555555";
const position = {
  strategy_id: "copytrade-v1",
  contract_symbol: "NVDA  261224C00200000",
  remaining_qty: 11,
  entry_premium: 3.36,
  open_notional: 3696,
  current_price: 0.9,
  trailing_armed: false,
};
const portfolio = {
  trading_day: "2026-11-27",
  open_positions: [position],
  open_positions_degraded: false,
  open_positions_count: 1,
  sum_open_notional: 3696,
  realized_pnl_today: 12.5,
  realized_pnl_all_time: null,
  account_equity: [{ broker_target: "alpaca-paper", equity: 96591.36, last_equity: 1, today_pl: 2 }],
};
const subject = (extra = {}) =>
  JSON.stringify({ option_symbol: "NVDA  261224C00200000", filled_qty: 17, avg_fill_price: 3.36, broker_order_id: UUID, ...extra });
const fills = (s = subject()) => ({
  count: 1,
  items: [{ occurred_at: "2026-11-27T15:00:00Z", kind: "EntryFilled", strategy_id: "copytrade-v1", subject: s, entry_basis: 3.36 }],
});
const order = {
  strategy_id: "copytrade-v1", broker_target: "alpaca-paper", option_symbol: "NVDA  261224C00200000",
  side: "BUY", qty: 17, limit_price: 3.4, state: "FILLED", broker_order_id: UUID,
  recorded_at: "2026-11-27T15:00:00Z", submitted_at: null, filled_qty: 17, avg_fill_price: 3.36,
  filled_at: "2026-11-27T15:00:02Z", failed: false,
};
const marks = {
  now: "2026-11-27T15:00:00Z", market_data_reachable: true,
  marks: [{ occ: "NVDA  261224C00200000", bid: 0.9, mid: 0.95, ask: 1, quote_at: "2026-11-27T15:00:00Z", polled_at: null, warming: false, underlying: { ticker: "NVDA", price: 181.5, at: "2026-11-27T15:00:00Z" } }],
};
const ok = (feed, body) => assert.equal(check(feed, JSON.stringify(body)), null, feed);
const refused = (feed, body, rule) => assert.equal(check(feed, JSON.stringify(body)), rule);

test("the five real payload shapes are publishable", () => {
  ok("status", { tripped: false, trippedAt: null, resettableAt: null });
  ok("status", { tripped: null });
  ok("portfolio", portfolio);
  ok("fills", fills());
  ok("orders", { count: 1, items: [order] });
  ok("marks", marks);
  ok("orders", { count: 0, items: [] });
});

test("any key outside the feed's allow-list refuses the whole payload", () => {
  for (const k of ["workflow_id", "signal_id", "correlation_id", "event_id", "intent_key", "tenant_id", "actor", "last_error", "some_future_field", "entry_qty", "realized_pl"]) {
    refused("portfolio", { ...portfolio, open_positions: [{ ...position, [k]: "x" }] }, "unknown-key");
  }
  refused("portfolio", { ...portfolio, tenant_id: "staging_paper" }, "unknown-key");
  refused("portfolio", { ...portfolio, unmanaged_positions: [] }, "unknown-key");
  refused("portfolio", { ...portfolio, account_equity: [{ broker_target: "alpaca-paper", equity: 1, account_number: "x" }] }, "unknown-key");
  refused("status", { tripped: true, reason: "auto:daily_loss" }, "unknown-key");
  refused("orders", { count: 1, items: [{ ...order, last_error: "broker said no" }] }, "unknown-key");
  refused("orders", { count: 1, items: [{ ...order, signal_id: "x" }] }, "unknown-key");
  refused("fills", { count: 1, items: [{ ...fills().items[0], correlation_id: "x" }] }, "unknown-key");
  // A key that is fine in one feed is not thereby fine in another.
  refused("status", { tripped: false, equity: 1 }, "unknown-key");
});

test("a fill subject is re-checked field by field", () => {
  refused("fills", fills(subject({ signal_id: "x" })), "unknown-subject-key");
  refused("fills", fills(subject({ intent_key: "x" })), "unknown-subject-key");
  refused("fills", fills(subject({ broker_order_id: { x: 1 } })), "nested-subject-value");
  refused("fills", fills(subject({ broker_order_id: "adopt:run-1" })), "order-id-not-uuid");
  refused("fills", fills("not json"), "subject-not-json");
  refused("fills", fills("[1]"), "subject-not-object");
  refused("fills", fills(subject({ option_symbol: "see chat-messages-123-456" })), "discord-id");
});

test("forbidden shapes are refused wherever they appear", () => {
  const withSymbol = (v) => ({ ...portfolio, open_positions: [{ ...position, contract_symbol: v }] });
  refused("portfolio", withSymbol("chat-messages-769797179992571914-1574829260651368620:0"), "discord-id");
  refused("portfolio", withSymbol("t-staging_paper/s-copytrade-v1/pos/NVDA"), "workflow-id");
  refused("portfolio", withSymbol("someone@example.com"), "email");
  refused("portfolio", withSymbol("https://example.com/x"), "url");
  refused("portfolio", withSymbol("<img src=x onerror=alert(1)>"), "markup");
  refused("portfolio", withSymbol("PA3FKGPFYPLH"), "paper-account-number");
  refused("portfolio", withSymbol("847309116"), "account-number");
  refused("portfolio", withSymbol("x".repeat(401)), "long-string");
  refused("marks", { ...marks, marks: [{ ...marks.marks[0], occ: "discord.gg/abc" }] }, "discord");
});

test("a row from a strategy the demo does not show, or a non-paper account, refuses", () => {
  refused("orders", { count: 1, items: [{ ...order, strategy_id: "gated_condor" }] }, "strategy-not-shown");
  refused("orders", { count: 1, items: [{ ...order, strategy_id: "some-new-strategy" }] }, "strategy-not-shown");
  refused("orders", { count: 1, items: [{ ...order, broker_target: "alpaca-live" }] }, "not-paper");
  refused("portfolio", { ...portfolio, account_equity: [{ broker_target: "alpaca-live", equity: 1 }] }, "not-paper");
  refused("orders", { count: 1, items: [{ ...order, broker_order_id: "adopt:run-1" }] }, "order-id-not-uuid");
});

test("error bodies, junk and oversized responses are never published", () => {
  assert.equal(check("portfolio", '{"error":"unavailable"}'), "unknown-key");
  assert.equal(check("portfolio", "<html>502 Bad Gateway</html>"), "not-json");
  assert.equal(check("portfolio", ""), "empty");
  assert.equal(check("portfolio", "[]"), "not-an-object");
  assert.equal(check("portfolio", "null"), "not-an-object");
  assert.equal(check("portfolio", JSON.stringify({ trading_day: "x".repeat(2_000_001) })), "too-large");
  assert.equal(check("nope", "{}"), "unknown-feed");
});

test("a rule name never contains the offending content", () => {
  const rule = check("status", JSON.stringify({ tripped: true, reason: "SECRET free text" }));
  assert.equal(rule, "unknown-key");
  assert.ok(!rule.includes("SECRET"));
});
