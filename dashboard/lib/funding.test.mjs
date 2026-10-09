// Run: node --experimental-strip-types --test dashboard/lib/funding.test.mjs   (Node 22)
// Not part of tsc (tsconfig includes *.ts/*.tsx only) or the Next build (nothing imports it).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  requiredOptionsLevel,
  levelShortfalls,
  isInsufficientFunds,
  latestFundsRejection,
  etDateOf,
} from "./funding.ts";

const copytrade = {
  strategy_id: "copytrade-v1",
  config: { broker_target: "alpaca-live" },
};
const condor = {
  strategy_id: "condor",
  config: { broker_target: "alpaca-paper", condor_entry_et: "13:30" },
};

test("required level: 3 for a condor-enabled strategy, 2 otherwise", () => {
  assert.equal(requiredOptionsLevel(copytrade.config), 2);
  assert.equal(requiredOptionsLevel(condor.config), 3);
  assert.equal(requiredOptionsLevel({ condor_entry_et: "  " }), 2);
  assert.equal(requiredOptionsLevel({ condor_entry_et: null }), 2);
});

test("flags a known level below what the strategy needs", () => {
  const out = levelShortfalls(
    [
      {
        broker_target: "alpaca-live",
        options_approved_level: 1,
        options_trading_level: 1,
      },
      {
        broker_target: "alpaca-paper",
        options_approved_level: 2,
        options_trading_level: 2,
      },
    ],
    [copytrade, condor],
  );
  assert.deepEqual(out, [
    {
      strategyId: "copytrade-v1",
      brokerTarget: "alpaca-live",
      required: 2,
      effective: 1,
    },
    {
      strategyId: "condor",
      brokerTarget: "alpaca-paper",
      required: 3,
      effective: 2,
    },
  ]);
});

test("the trading level wins over a higher approved level", () => {
  const out = levelShortfalls(
    [
      {
        broker_target: "alpaca-live",
        options_approved_level: 3,
        options_trading_level: 1,
      },
    ],
    [copytrade],
  );
  assert.equal(out.length, 1);
  assert.equal(out[0].effective, 1);
});

test("unknown level makes no claim (null, absent, garbled) and falls back to approved", () => {
  for (const acct of [
    { broker_target: "alpaca-live" },
    {
      broker_target: "alpaca-live",
      options_approved_level: null,
      options_trading_level: null,
    },
    { broker_target: "alpaca-live", options_trading_level: "N/A" },
  ]) {
    assert.deepEqual(levelShortfalls([acct], [copytrade]), []);
  }
  assert.equal(
    levelShortfalls(
      [{ broker_target: "alpaca-live", options_approved_level: 1 }],
      [copytrade],
    ).length,
    1,
  );
});

test("sufficient level, disabled strategy, and unmatched broker target are not flagged", () => {
  assert.deepEqual(
    levelShortfalls(
      [{ broker_target: "alpaca-live", options_trading_level: 2 }],
      [copytrade],
    ),
    [],
  );
  assert.deepEqual(
    levelShortfalls(
      [{ broker_target: "alpaca-live", options_trading_level: 1 }],
      [
        {
          strategy_id: "x",
          config: { broker_target: "alpaca-live", enabled: false },
        },
      ],
    ),
    [],
  );
  assert.deepEqual(
    levelShortfalls(
      [{ broker_target: "alpaca-paper", options_trading_level: 1 }],
      [copytrade],
    ),
    [],
  );
});

test("recognises the exec's insufficient-funds rejection text", () => {
  assert.equal(
    isInsufficientFunds(
      "message='Alpaca rejected order: insufficient options buying power', type='InsufficientFundsError', nonRetryable=true",
    ),
    true,
  );
  assert.equal(isInsufficientFunds("insufficient buying power"), true);
  assert.equal(
    isInsufficientFunds("code 40310000 insufficient_buying_power"),
    true,
  );
  assert.equal(isInsufficientFunds("invalid contract"), false);
  assert.equal(isInsufficientFunds(null), false);
});

test("latest funds rejection is today-only (US/Eastern) and newest-first", () => {
  const funds = "type='InsufficientFundsError'";
  const orders = [
    {
      broker_target: "a",
      option_symbol: "NEW",
      recorded_at: "2026-10-08T15:00:00Z",
      last_error: funds,
    },
    {
      broker_target: "a",
      option_symbol: "OLD",
      recorded_at: "2026-10-08T14:00:00Z",
      last_error: funds,
    },
  ];
  assert.equal(
    latestFundsRejection(orders, "2026-10-08")?.option_symbol,
    "NEW",
  );
  assert.equal(latestFundsRejection(orders, "2026-10-09"), null);
  // 02:00Z on the 9th is still the 8th in New York.
  assert.equal(etDateOf("2026-10-09T02:00:00Z"), "2026-10-08");
});
