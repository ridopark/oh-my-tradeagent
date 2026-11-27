// The poller's own gate on what may be published. It is deliberately independent of the BFF's
// projection code: a second allow-list, written separately, so one mistake upstream is not enough
// to put a hidden value on the public site. `check` returns null when a payload may be written, or
// the NAME of the rule it broke (never any of the content).

const POSITION = [
  "strategy_id", "contract_symbol", "remaining_qty", "entry_premium", "open_notional",
  "underlying_spot_entry", "underlying_price", "trailing_armed", "current_price", "unrealized_pl",
  "unrealized_intraday_pl", "lastday_price", "trail_giveback_pct", "trail_stop_price",
];
const ORDER = [
  "strategy_id", "broker_target", "option_symbol", "side", "qty", "limit_price", "state",
  "broker_order_id", "recorded_at", "submitted_at", "filled_qty", "avg_fill_price", "filled_at",
  "failed",
];
const FILL_SUBJECT = [
  "option_symbol", "filled_qty", "qty_filled", "avg_fill_price", "remaining_qty_after",
  "broker_order_id",
];

/** Every key that may appear anywhere in a feed's JSON. Anything else refuses the whole payload. */
export const ALLOWED_KEYS = {
  status: ["tripped", "trippedAt", "resettableAt"],
  portfolio: [
    "trading_day", "open_positions", "open_positions_degraded", "open_positions_count",
    "sum_open_notional", "realized_pnl_today", "realized_pnl_all_time", "account_equity",
    "broker_target", "equity", "last_equity", "today_pl", ...POSITION,
  ],
  fills: ["count", "items", "occurred_at", "kind", "strategy_id", "subject", "entry_basis"],
  orders: ["count", "items", ...ORDER],
  marks: [
    "now", "market_data_reachable", "marks", "occ", "bid", "mid", "ask", "quote_at", "polled_at",
    "warming", "underlying", "ticker", "price", "at",
  ],
};

export const SHOWN_STRATEGIES = ["copytrade-v1", "copytrade-lotto-v1", "watchlist-trigger-v1"];

const MAX_BYTES = 2_000_000;
const MAX_STRING = 400;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Shapes that must never appear in any string value, whatever key it sits under.
const FORBIDDEN = [
  ["discord-id", /chat-messages-/i],
  ["discord", /discord/i],
  ["webhook", /webhook|hooks\./i],
  ["email", /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}/],
  ["workflow-id", /\bt-[A-Za-z0-9_-]+\/s-|\/pos\/|\/sig\/|\/killswitch/],
  ["paper-account-number", /\bPA[0-9A-Z]{8,12}\b/],
  ["account-number", /^\d{8,10}$/],
  ["url", /https?:\/\//i],
  ["markup", /[<>]/],
  ["hidden-strategy", /gated_condor/i],
];

function walk(value, allowed, path, visit) {
  if (Array.isArray(value)) {
    for (const v of value) {
      const r = walk(v, allowed, path, visit);
      if (r) return r;
    }
    return null;
  }
  if (value !== null && typeof value === "object") {
    for (const [k, v] of Object.entries(value)) {
      if (!allowed.includes(k)) return "unknown-key";
      const r = walk(v, allowed, k, visit);
      if (r) return r;
    }
    return null;
  }
  return visit(value, path);
}

function checkString(s) {
  if (s.length > MAX_STRING) return "long-string";
  for (const [name, re] of FORBIDDEN) {
    if (re.test(s)) return name;
  }
  return null;
}

function checkFillSubject(raw) {
  let subject;
  try {
    subject = JSON.parse(raw);
  } catch {
    return "subject-not-json";
  }
  if (subject === null || typeof subject !== "object" || Array.isArray(subject)) {
    return "subject-not-object";
  }
  for (const [k, v] of Object.entries(subject)) {
    if (!FILL_SUBJECT.includes(k)) return "unknown-subject-key";
    if (v !== null && typeof v === "object") return "nested-subject-value";
    if (typeof v === "string") {
      const r = checkString(v);
      if (r) return r;
    }
    if (k === "broker_order_id" && v !== null && !(typeof v === "string" && UUID.test(v))) {
      return "order-id-not-uuid";
    }
  }
  return null;
}

/**
 * @param {keyof typeof ALLOWED_KEYS} feed
 * @param {string} text the response body exactly as received
 * @returns {string | null} null = may be published; otherwise the broken rule's name
 */
export function check(feed, text) {
  const allowed = ALLOWED_KEYS[feed];
  if (!allowed) return "unknown-feed";
  if (typeof text !== "string" || text.length === 0) return "empty";
  if (text.length > MAX_BYTES) return "too-large";
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return "not-json";
  }
  if (body === null || typeof body !== "object" || Array.isArray(body)) return "not-an-object";
  return walk(body, allowed, "", (value, key) => {
    if (typeof value !== "string") return null;
    if (key === "subject") return checkFillSubject(value);
    if (key === "strategy_id" && !SHOWN_STRATEGIES.includes(value)) return "strategy-not-shown";
    if (key === "broker_order_id" && !UUID.test(value)) return "order-id-not-uuid";
    if (key === "broker_target" && !value.endsWith("-paper")) return "not-paper";
    return checkString(value);
  });
}
