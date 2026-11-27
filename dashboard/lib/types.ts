// Data shapes the view components render. Pure types with no imports, moved verbatim from
// lib/bff.ts (which re-exports them), so a static build that only renders views — the public demo —
// never pulls the server-only BFF client, or the auth code behind it, into its type-check.

// Account daily-loss kill switch state. resettableAt = trippedAt + 15min — the circuit-breaker gate
// the UI drives its live countdown off. Null timestamps mean "not tripped".
export interface AccountKillSwitch {
  tripped: boolean;
  trippedAt: string | null;
  reason: string;
  resettableAt: string | null;
  // Open exposure the operator would resume over on reset (last-heartbeat book read). Both nullable:
  // null for the per-strategy switch / a pre-first-heartbeat account switch; openMtm additionally null
  // when the book is unpriceable. openMtm is SIGNED unrealized P&L (a gain is positive), NOT a loss or
  // a position value. Surfaced at the reset control so a reset isn't done blind (#591).
  openPositions?: number | null;
  openMtm?: number | null;
}

// A broker lot nothing manages (GET /api/portfolio `unmanaged_positions`). `occ` is compact.
export interface UnmanagedPosition {
  occ: string;
  qty: number;
  current_price: string | number | null;
  unrealized_pl: string | number | null;
  unrealized_intraday_pl: string | number | null;
}

export interface Position {
  workflow_id: string;
  strategy_id: string;
  contract_symbol: string;
  remaining_qty: number;
  entry_premium: string | number | null;
  open_notional: string | number | null;
  // Live broker marks (account-level), present only when this position matched a broker mark by OCC.
  // current_price = per-unit mark; unrealized_pl = TOTAL since entry; unrealized_intraday_pl = TODAY.
  // Absent/null when the broker carries no mark for this contract (the row still renders).
  current_price?: string | number | null;
  unrealized_pl?: string | number | null;
  unrealized_intraday_pl?: string | number | null;
  // Armed trailing-stop state for this position, read off its workflow. `trailing_armed` is present
  // on every row from a current BFF; the two numerics only when a trail IS armed. Optional because
  // an older BFF omits all three — the row then renders as un-armed rather than breaking.
  /**
   * The UNDERLYING equity's price at entry (recorded once per position by the #783 trade-context
   * recorder) and its price now. Either may be null — a position entered before the recorder
   * existed has no recorded spot, and the live equity quote is a best-effort hop — so each half
   * renders independently.
   */
  /**
   * Contracts ORIGINALLY entered, derived from the audit trail (entry fill + any later entry
   * growth). Null when that trail could not supply it, in which case only the remaining qty shows.
   */
  entry_qty?: number | null;
  /**
   * Money already BANKED on the sold part of this position, on the same entry basis
   * `unrealized_pl` uses — so the two add up to what the position has made in total. Null when
   * nothing priced has been sold, which is NOT the same as zero.
   */
  realized_pl?: string | number | null;
  /**
   * Per-contract intraday base the BFF derives from the broker's intraday P&L (scope lock #7):
   * the prior close, or the entry premium for a position opened today. P&L today at the bid =
   * (bid − lastday_price) × qty × 100. Absent from an older BFF / when an input is missing.
   */
  lastday_price?: string | number | null;
  underlying_spot_entry?: string | number | null;
  underlying_price?: string | number | null;
  // trail_stop_price is PEAK-anchored (the price the stop fires at NOW). Render it as given; do not
  // recompute it from current_price, which understates the stop whenever the position is off its
  // high.
  trailing_armed?: boolean;
  trail_giveback_pct?: string | number | null;
  trail_stop_price?: string | number | null;
}

export interface Order {
  intent_key: string;
  strategy_id: string;
  broker_target: string;
  option_symbol: string;
  side: string;
  qty: number;
  state: string;
  limit_price: string | number | null;
  avg_fill_price: string | number | null;
  // Qty the broker actually filled (OrdersReader selects it). Null until a fill terminalizes the
  // row, and BELOW `qty` on a cancel-with-partial-fill — the one place the two differ.
  filled_qty: number | null;
  recorded_at: string;
  filled_at: string | null;
  last_error: string | null;
  // Sent by OrdersReader but not rendered yet; typed so the BFF row and this interface stay one set.
  signal_id: string | null;
  broker_order_id: string | null;
  submitted_at: string | null;
}

export interface Portfolio {
  tenant_id: string;
  trading_day: string;
  open_positions: Position[];
  /**
   * True when the BFF's positions read stalled or failed. `open_positions` is then [] and the
   * count/sum are 0 — NOT an empty book; render them as unknown. Absent on a BFF predating #934.
   */
  open_positions_degraded?: boolean;
  /**
   * Broker lots of this tenant's account that no workflow manages (bought directly at the broker,
   * typically). Null when the managed book is degraded (unknown — never "everything unmanaged");
   * absent on a BFF that predates the Adopt button.
   */
  unmanaged_positions?: UnmanagedPosition[] | null;
  open_positions_count: number;
  sum_open_notional: string | number;
  sum_open_notional_basis: string;
  realized_pnl_today: string | number;
  // Since-inception realized P&L (FIFO cost basis across ALL history). Lets the Status page
  // reconcile to starting capital (start + realized_all_time + unrealized ≈ equity). Null when the
  // (full-history) read degraded under the sub-read budget — the tile then renders "—", not $0.
  realized_pnl_all_time: string | number | null;
  // account_number is present only when the BFF's dev flag (bff.expose-broker-account-number) is on
  // — never in prod. Used purely to verify which brokerage account a broker_target maps to.
  account_equity: {
    broker_target: string;
    equity: string | number | null;
    // Live intraday "today" P&L for this broker_target: equity - last_equity (prior market close).
    // The GENUINE today figure the /live header shows, distinct from portfolio-history's last
    // completed daily bar. Null when last_equity is unavailable (then the header falls back to the
    // daily bar). last_equity is surfaced so the header can aggregate the percentage denominator
    // (sum today_pl / sum last_equity) across broker_targets.
    last_equity?: string | number | null;
    today_pl?: string | number | null;
    account_number?: string;
    // #942 funding detail (Alpaca /v2/account), informational. Each is null/absent when the exec
    // predates #942 or Alpaca sent it garbled — hide it then, never show 0. multiplier 1 = cash
    // account (T+1 settlement), 2/4 = margin. Levels: 1 covered, 2 long calls/puts, 3 spreads.
    options_buying_power?: string | number | null;
    options_approved_level?: number | null;
    options_trading_level?: number | null;
    multiplier?: string | number | null;
    pending_transfer_in?: string | number | null;
  }[];
  account_equity_scope: string;
  unrealized_pnl: null;
  unrealized_pnl_note: string;
}
