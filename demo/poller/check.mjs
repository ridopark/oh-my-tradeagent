// The poller's own gate on what may be published. It is deliberately independent of the BFF's
// projection code: a second allow-list, written separately, so one mistake upstream is not enough
// to put a hidden value on the public site. `check` returns null when a payload may be written, or
// the NAME of the rule it broke (never any of the content).
//
// Each feed has one exact shape: which keys at which level, and for every key a type and a format.
// Nothing is passed on trust; a value the schema does not describe refuses the whole payload.

export const SHOWN_STRATEGIES = ["copytrade-v1", "copytrade-lotto-v1", "watchlist-trigger-v1"];

const MAX_BYTES = 2_000_000;
const MAX_STRING = 400;

// Shapes that must never appear in any string value, whatever key it sits under. A second net
// under the per-field formats below: it runs first, on every string.
const FORBIDDEN = [
  ["discord-id", /chat-messages-/i],
  ["discord", /discord/i],
  ["webhook", /webhook|hooks\./i],
  ["email", /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}/],
  // Every WorkflowIds shape: all tenant-scoped ids start "t-<tenant>/"; the rest are their tails.
  ["workflow-id", /\bt-[A-Za-z0-9_-]+\/|\/pos\/|\/sig\/|\/wl\/|\/killswitch|\/cfg-write\/|\baudit-emit\/|\bwl-armed:/],
  ["paper-account-number", /\bPA[0-9A-Z]{8,12}\b/],
  // 8-10 digits anywhere in a string. The lookarounds skip the digit runs the real formats hold:
  // an OCC strike (after C/P), a timestamp's fraction (after "."), a UUID's groups (hex and "-").
  ["account-number", /(?<![\dA-Fa-f.CP-])\d{8,10}(?![\dA-Fa-f-])/],
  ["snowflake", /\d{17,}/],
  ["tenant-id", /\bprod[_-][a-z0-9]|staging_paper|\bpaper_[a-z]/i],
  ["url", /https?:\/\//i],
  ["markup", /[<>]/],
  ["hidden-strategy", /gated_condor/i],
];
const SNOWFLAKE = /\d{17,}/;

function checkString(s) {
  if (s.length > MAX_STRING) return "long-string";
  for (const [name, re] of FORBIDDEN) {
    if (re.test(s)) return name;
  }
  return null;
}

// ---- validators: each takes a value and returns null or a rule name --------------------------

/** A string that passes the forbidden-content net and then `format`. */
const str = (format) => (v) =>
  typeof v !== "string" ? "not-a-string" : (checkString(v) ?? format(v));
const matching = (re, rule) => str((s) => (re.test(s) ? null : rule));
const oneOf = (values, rule) => str((s) => (values.includes(s) ? null : rule));

/** A JSON number in [min, max]. 1e999 parses to Infinity, which is out of every range. */
const num = (min, max, integer = false) => (v) => {
  if (typeof v !== "number") return "not-a-number";
  if (integer && !Number.isInteger(v)) return "not-an-integer";
  return v >= min && v <= max ? null : "number-out-of-range";
};
const bool = (v) => (typeof v === "boolean" ? null : "not-a-boolean");
const orNull = (validator) => (v) => (v === null ? null : validator(v));
/** Marks a key that may be absent. Must be the outermost wrapper. */
const opt = (validator) => Object.assign((v) => validator(v), { optional: true });

const array = (item, max) => (v) => {
  if (!Array.isArray(v)) return "not-an-array";
  if (v.length > max) return "too-many-items";
  for (const x of v) {
    const r = item(x);
    if (r) return r;
  }
  return null;
};

/** Exactly these keys: none besides, and every one not marked `opt` present. */
const object = (fields, unknownRule = "unknown-key") => (v) => {
  if (v === null || typeof v !== "object" || Array.isArray(v)) return "not-an-object";
  for (const k of Object.keys(v)) {
    if (!Object.hasOwn(fields, k)) return unknownRule;
  }
  for (const [k, validator] of Object.entries(fields)) {
    if (!Object.hasOwn(v, k)) {
      if (validator.optional) continue;
      return "missing-key";
    }
    const r = validator(v[k]);
    if (r) return r;
  }
  return null;
};

// ---- formats -----------------------------------------------------------------------------------

const YMD = "20\\d\\d-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])";
const DATE = matching(new RegExp(`^${YMD}$`), "bad-date");
// ISO-8601 with a zone. Seconds are optional (OffsetDateTime.toString drops :00), the fraction is
// up to nanoseconds, and the zone is Z or a numeric offset (java.sql.Timestamp writes +00:00).
const TIMESTAMP = matching(
  new RegExp(`^${YMD}T([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d{1,9})?)?(Z|[+-]\\d\\d:\\d\\d)$`),
  "bad-timestamp",
);
// OCC option symbol: root, yymmdd, C|P, strike x1000 in 8 digits. Either compact, or the 21-char
// row form with the root space-padded to 6.
const OCC_RE = /^[A-Z][A-Z0-9]{0,5} *\d\d(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])[CP]\d{8}$/;
const OCC = str((s) => (OCC_RE.test(s) && (s.length === 21 || !s.includes(" ")) ? null : "bad-occ"));
const TICKER = matching(/^[A-Z][A-Z0-9.]{0,5}$/, "bad-ticker");
const UUID = matching(
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
  "order-id-not-uuid",
);
const STRATEGY = oneOf(SHOWN_STRATEGIES, "strategy-not-shown");
// The backend's set of broker targets is open-ended (one per broker adapter), so this is a tight
// pattern rather than a list: a short lower-case name that ends in "-paper".
const PAPER_TARGET = matching(/^[a-z][a-z0-9]{0,19}-paper$/, "not-paper");
// TradesReader.FILL_KINDS; the order_intent_journal CHECK constraints on side and state.
const KIND = oneOf(["EntryFilled", "PartialExitFilled"], "unknown-kind");
const SIDE = oneOf(["BUY", "SELL"], "unknown-side");
const STATE = oneOf(
  ["RECORDED", "SUBMITTED", "FILLED", "CANCELLED", "EXPIRED", "ERRORED"],
  "unknown-state",
);

// Bounds sit far above anything a paper account holds and far below any id: a 17-19 digit
// snowflake or a 9-10 digit account number fits none of them.
const INT = num(0, 1_000_000, true); // contracts, row counts
const PRICE = num(0, 1_000_000); // per-unit: premium, quote, underlying, percent
const MONEY = num(-100_000_000, 100_000_000); // balances, notional, P&L

// ---- the five feeds ----------------------------------------------------------------------------
// Bounds: fills and orders are read with PreviewService.READ_LIMIT (500); marks are at most
// market-data's DEMO_CAP (10) contracts, each under every row form it is held as. The backend
// puts no limit on open positions or broker targets; 200 and 8 are far above a real account.

const POSITION = object({
  strategy_id: STRATEGY,
  contract_symbol: OCC,
  remaining_qty: INT,
  entry_premium: orNull(PRICE),
  open_notional: orNull(MONEY),
  trailing_armed: opt(bool),
  underlying_spot_entry: opt(orNull(PRICE)),
  underlying_price: opt(orNull(PRICE)),
  current_price: opt(orNull(PRICE)),
  unrealized_pl: opt(orNull(MONEY)),
  unrealized_intraday_pl: opt(orNull(MONEY)),
  lastday_price: opt(orNull(MONEY)), // derived by subtraction, so not bounded at zero
  trail_giveback_pct: opt(orNull(PRICE)),
  trail_stop_price: opt(orNull(PRICE)),
});

const FILL_SUBJECT = object(
  {
    option_symbol: OCC,
    filled_qty: opt(orNull(INT)),
    qty_filled: opt(orNull(INT)),
    avg_fill_price: opt(orNull(PRICE)),
    remaining_qty_after: opt(orNull(INT)),
    broker_order_id: opt(UUID),
  },
  "unknown-subject-key",
);

/** `subject` is a JSON document inside a string: parsed and held to its own exact shape. */
function fillSubject(raw) {
  if (typeof raw !== "string") return "not-a-string";
  if (raw.length > MAX_STRING) return "long-string";
  const { rule, value } = parse(raw);
  if (rule) return rule === "not-json" ? "subject-not-json" : rule;
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return "subject-not-object";
  }
  return FILL_SUBJECT(value);
}

const FEEDS = {
  status: object({
    tripped: orNull(bool), // null: the kill-switch state could not be read
    trippedAt: opt(orNull(TIMESTAMP)),
    resettableAt: opt(orNull(TIMESTAMP)),
  }),
  portfolio: object({
    trading_day: DATE,
    open_positions: array(POSITION, 200),
    open_positions_degraded: bool,
    open_positions_count: INT,
    sum_open_notional: MONEY,
    realized_pnl_today: orNull(MONEY),
    realized_pnl_all_time: orNull(MONEY),
    account_equity: array(
      object({
        broker_target: PAPER_TARGET,
        equity: orNull(MONEY),
        last_equity: opt(orNull(MONEY)),
        today_pl: opt(orNull(MONEY)),
      }),
      8,
    ),
  }),
  fills: object({
    count: INT,
    items: array(
      object({
        occurred_at: TIMESTAMP,
        kind: KIND,
        strategy_id: STRATEGY,
        subject: fillSubject,
        entry_basis: orNull(PRICE),
      }),
      500,
    ),
  }),
  orders: object({
    count: INT,
    items: array(
      object({
        strategy_id: STRATEGY,
        broker_target: PAPER_TARGET,
        option_symbol: OCC,
        side: SIDE,
        qty: INT,
        limit_price: orNull(PRICE),
        state: STATE,
        broker_order_id: opt(UUID), // dropped by the projection when it is not a UUID
        recorded_at: TIMESTAMP,
        submitted_at: orNull(TIMESTAMP),
        filled_qty: orNull(INT),
        avg_fill_price: orNull(PRICE),
        filled_at: orNull(TIMESTAMP),
        failed: bool,
      }),
      500,
    ),
  }),
  marks: object({
    now: TIMESTAMP,
    market_data_reachable: bool,
    marks: array(
      object({
        occ: OCC,
        bid: opt(orNull(PRICE)),
        mid: opt(orNull(PRICE)),
        ask: opt(orNull(PRICE)),
        quote_at: opt(orNull(TIMESTAMP)),
        polled_at: opt(orNull(TIMESTAMP)),
        warming: opt(bool),
        underlying: opt(
          object({ ticker: TICKER, price: orNull(PRICE), at: orNull(TIMESTAMP) }),
        ),
      }),
      50,
    ),
  }),
};

// ---- parsing -----------------------------------------------------------------------------------

const STRING_TOKEN = /"(?:[^"\\]|\\.)*"/g;

/** How many string tokens (keys and string values) the JSON text of `v` holds. */
function countStrings(v) {
  if (typeof v === "string") return 1;
  if (Array.isArray(v)) return v.reduce((n, x) => n + countStrings(x), 0);
  if (v !== null && typeof v === "object") {
    return Object.values(v).reduce((n, x) => n + 1 + countStrings(x), 0);
  }
  return 0;
}

/**
 * JSON.parse, refusing a key written twice. The text is published as received, but JSON.parse
 * keeps only a repeated key's last value, so the earlier ones would go out unchecked.
 */
function parse(text) {
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    return { rule: "not-json" };
  }
  if ((text.match(STRING_TOKEN) ?? []).length !== countStrings(value)) {
    return { rule: "duplicate-key" };
  }
  return { value };
}

/**
 * @param {keyof typeof FEEDS} feed
 * @param {string} text the response body exactly as received
 * @returns {string | null} null = may be published; otherwise the broken rule's name
 */
export function check(feed, text) {
  if (!Object.hasOwn(FEEDS, feed)) return "unknown-feed";
  if (typeof text !== "string" || text.length === 0) return "empty";
  if (text.length > MAX_BYTES) return "too-large";
  // Over the raw text, so it also covers number literals, which are published digit for digit.
  if (SNOWFLAKE.test(text)) return "snowflake";
  const { rule, value } = parse(text);
  return rule ?? FEEDS[feed](value);
}
