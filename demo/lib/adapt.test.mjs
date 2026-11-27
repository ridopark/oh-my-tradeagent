import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ageSeconds,
  isStale,
  toKillSwitch,
  toOrders,
  toPortfolio,
  toTrades,
  withMark,
} from "./adapt.ts";

const pos = {
  strategy_id: "copytrade-v1",
  contract_symbol: "NVDA  261224C00200000",
  remaining_qty: 11,
  entry_premium: "3.36",
  open_notional: "3696.00",
  current_price: 0.9,
  unrealized_pl: -2706,
  lastday_price: 1.1,
};

test("a live bid replaces the price and recomputes both P&L figures", () => {
  const out = withMark(pos, [{ occ: "NVDA  261224C00200000", bid: 1.25 }]);
  assert.equal(out.current_price, 1.25);
  assert.equal(out.unrealized_pl, -2321); // (1.25 - 3.36) * 11 * 100
  assert.equal(out.unrealized_intraday_pl, 165); // (1.25 - 1.10) * 11 * 100
});

test("no mark, a null bid or another contract's mark leaves the row untouched", () => {
  assert.equal(withMark(pos, []), pos);
  assert.equal(withMark(pos, [{ occ: "NVDA  261224C00200000", bid: null }]), pos);
  assert.equal(withMark(pos, [{ occ: "GOOGL 261204C00285000", bid: 9 }]), pos);
});

test("a bid of zero is a real price, not a missing one", () => {
  const out = withMark(pos, [{ occ: "NVDA  261224C00200000", bid: 0 }]);
  assert.equal(out.current_price, 0);
  assert.equal(out.unrealized_pl, -3696);
});

test("portfolio keeps published numbers and fills only the absent text fields", () => {
  const p = toPortfolio(
    {
      trading_day: "2026-11-27",
      open_positions: [pos],
      open_positions_count: 1,
      sum_open_notional: "3696.00",
      realized_pnl_today: "12.50",
      realized_pnl_all_time: null,
      account_equity: [{ broker_target: "alpaca-paper", equity: 96591.36 }],
    },
    [],
  );
  assert.equal(p.open_positions_count, 1);
  assert.equal(p.sum_open_notional, "3696.00");
  assert.equal(p.realized_pnl_all_time, null);
  assert.equal(p.open_positions_degraded, false);
  assert.equal(p.tenant_id, "");
  assert.equal(p.account_equity[0].equity, 96591.36);
  assert.equal("account_number" in p.account_equity[0], false);
});

test("a failed order shows only that it failed", () => {
  const [ok, bad] = toOrders([
    { strategy_id: "s", broker_target: "alpaca-paper", option_symbol: "X", side: "BUY", qty: 1, state: "FILLED", recorded_at: "t", failed: false },
    { strategy_id: "s", broker_target: "alpaca-paper", option_symbol: "X", side: "BUY", qty: 1, state: "ERRORED", recorded_at: "t", failed: true },
  ]);
  assert.equal(ok.last_error, null);
  assert.equal(bad.last_error, "failed");
  assert.equal(ok.signal_id, null);
});

test("fills get distinct keys even when two share a timestamp", () => {
  const rows = toTrades([
    { occurred_at: "t", kind: "EntryFilled", strategy_id: "s", subject: "{}" },
    { occurred_at: "t", kind: "EntryFilled", strategy_id: "s", subject: "{}" },
  ]);
  assert.notEqual(rows[0].event_id, rows[1].event_id);
  assert.equal(rows[0].correlation_id, null);
});

test("kill switch: unreadable state hides the panel; a trip carries no reason", () => {
  assert.equal(toKillSwitch(null), null);
  assert.equal(toKillSwitch({ tripped: null }), null);
  const k = toKillSwitch({ tripped: true, trippedAt: "a", resettableAt: "b" });
  assert.equal(k.tripped, true);
  assert.equal(k.reason, "");
});

test("age and staleness", () => {
  const now = Date.parse("2026-11-27T15:00:30Z");
  assert.equal(ageSeconds("2026-11-27T15:00:00Z", now), 30);
  assert.equal(ageSeconds(null, now), null);
  assert.equal(ageSeconds("not a date", now), null);
  assert.equal(ageSeconds("2026-11-27T15:01:00Z", now), 0); // clock skew never reads negative
  assert.equal(isStale(null, 3), true);
  assert.equal(isStale(30, 3), false);
  assert.equal(isStale(31, 3), true);
  assert.equal(isStale(80, 30), false);
  assert.equal(isStale(91, 30), true);
});
