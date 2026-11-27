// Pure adapters from the published JSON (the BFF's /api/preview/* projections) to the props the
// dashboard's view components take. No imports on purpose: it runs unchanged under
// `node --experimental-strip-types --test lib/adapt.test.mjs`.
//
// The projections omit everything the demo must not show, so several fields the views read are
// simply absent; these functions supply neutral values for them and nothing else.

/** What the poller writes: when it fetched, and the BFF's body verbatim. */
export interface Published<T> {
  generated_at: string;
  data: T;
}

type Val = string | number | null;
type Num = Val | undefined;

function num(v: Num): number | null {
  if (v === null || v === undefined || v === "") {
    return null;
  }
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

export interface DemoMark {
  occ: string;
  bid?: number | null;
}

export interface DemoPosition {
  strategy_id: string;
  contract_symbol: string;
  remaining_qty: number;
  entry_premium: Val;
  open_notional: Val;
  current_price?: Val;
  unrealized_pl?: Val;
  unrealized_intraday_pl?: Val;
  lastday_price?: Val;
  [k: string]: unknown;
}

export interface DemoPortfolio {
  trading_day: string;
  open_positions: DemoPosition[];
  open_positions_degraded?: boolean;
  open_positions_count: number;
  sum_open_notional: Val;
  realized_pnl_today: Val;
  realized_pnl_all_time: Val;
  account_equity: { broker_target: string; equity: Val; last_equity?: Val; today_pl?: Val }[];
}

/**
 * A position row with a live quote laid over it. The portfolio file refreshes every 30s; the marks
 * file every 3s. Price is the BID (what a sale would fetch) and P&L uses the same ×100 contract
 * multiplier and the same formulas as the dashboard's live cells.
 */
export function withMark(p: DemoPosition, marks: DemoMark[]): DemoPosition {
  const bid = num(marks.find((m) => m.occ === p.contract_symbol)?.bid);
  if (bid === null) {
    return p;
  }
  const out: DemoPosition = { ...p, current_price: bid };
  const entry = num(p.entry_premium);
  if (entry !== null) {
    out.unrealized_pl = round2((bid - entry) * p.remaining_qty * 100);
  }
  const lastday = num(p.lastday_price);
  if (lastday !== null) {
    out.unrealized_intraday_pl = round2((bid - lastday) * p.remaining_qty * 100);
  }
  return out;
}

/** The portfolio the views render: published fields, live marks overlaid, absent text left empty. */
export function toPortfolio(d: DemoPortfolio, marks: DemoMark[]) {
  return {
    tenant_id: "",
    trading_day: d.trading_day,
    open_positions: d.open_positions.map((p) => ({ workflow_id: "", ...withMark(p, marks) })),
    open_positions_degraded: d.open_positions_degraded === true,
    open_positions_count: d.open_positions_count,
    sum_open_notional: d.sum_open_notional ?? 0,
    sum_open_notional_basis: "cost_basis_at_entry",
    realized_pnl_today: d.realized_pnl_today ?? 0,
    realized_pnl_all_time: d.realized_pnl_all_time ?? null,
    account_equity: d.account_equity,
    account_equity_scope: "Net-liquidation equity of the paper brokerage account.",
    unrealized_pnl: null,
    unrealized_pnl_note: "",
  };
}

export interface DemoOrder {
  strategy_id: string;
  broker_target: string;
  option_symbol: string;
  side: string;
  qty: number;
  state: string;
  limit_price?: Val;
  avg_fill_price?: Val;
  filled_qty?: number | null;
  recorded_at: string;
  submitted_at?: string | null;
  filled_at?: string | null;
  broker_order_id?: string | null;
  failed: boolean;
}

/** Order rows. The broker's error text is never published; a failed order just says so. */
export function toOrders(items: DemoOrder[]) {
  return items.map((o) => ({
    intent_key: "",
    signal_id: null,
    strategy_id: o.strategy_id,
    broker_target: o.broker_target,
    option_symbol: o.option_symbol,
    side: o.side,
    qty: o.qty,
    state: o.state,
    limit_price: o.limit_price ?? null,
    avg_fill_price: o.avg_fill_price ?? null,
    filled_qty: o.filled_qty ?? null,
    recorded_at: o.recorded_at,
    submitted_at: o.submitted_at ?? null,
    filled_at: o.filled_at ?? null,
    broker_order_id: o.broker_order_id ?? null,
    last_error: o.failed ? "failed" : null,
  }));
}

export interface DemoFill {
  occurred_at: string;
  kind: string;
  strategy_id: string;
  subject: string | null;
  entry_basis?: Val;
}

/** Fill rows. No internal id is published, so each gets a positional key for React. */
export function toTrades(items: DemoFill[]) {
  return items.map((f, i) => ({
    event_id: `${f.occurred_at}#${i}`,
    occurred_at: f.occurred_at,
    kind: f.kind,
    strategy_id: f.strategy_id,
    workflow_id: null,
    correlation_id: null,
    subject: f.subject,
    entry_basis: f.entry_basis ?? null,
  }));
}

export interface DemoStatus {
  tripped: boolean | null;
  trippedAt?: string | null;
  resettableAt?: string | null;
}

/** Kill-switch state for the panel; null (panel hidden) when the state could not be read. */
export function toKillSwitch(s: DemoStatus | null) {
  if (!s || s.tripped === null || s.tripped === undefined) {
    return null;
  }
  return {
    tripped: s.tripped,
    trippedAt: s.trippedAt ?? null,
    reason: "",
    resettableAt: s.resettableAt ?? null,
    openPositions: null,
    openMtm: null,
  };
}

/** Seconds since `generatedAt` as of `nowMs`; null when there is nothing published yet. */
export function ageSeconds(generatedAt: string | null, nowMs: number): number | null {
  if (!generatedAt) {
    return null;
  }
  const t = Date.parse(generatedAt);
  return Number.isNaN(t) ? null : Math.max(0, Math.round((nowMs - t) / 1000));
}

/** A file is stale after three missed refreshes, and never sooner than 30s. */
export function isStale(age: number | null, cadenceSeconds: number): boolean {
  return age === null || age > Math.max(30, cadenceSeconds * 3);
}
