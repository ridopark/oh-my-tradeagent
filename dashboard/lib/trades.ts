// Pure fill helpers: no server-only imports, so both server pages and client-rendered views can use
// them. Moved verbatim from lib/bff.ts, which re-exports them.

export interface Trade {
  /**
   * Weighted entry cost basis for this fill's position, attached by the BFF so an EXIT row can show
   * what it made. Null when the entry fills carried no price, or for a fill whose entry predates
   * retention.
   */
  entry_basis?: string | number | null;
  event_id: string;
  occurred_at: string;
  kind: string;
  strategy_id: string;
  workflow_id: string | null;
  correlation_id: string | null;
  subject: string | null;
}

// What a fill actually traded, lifted out of the audit event's `subject`.
export interface TradeFill {
  option_symbol: string | null;
  qty: number | null;
  avg_fill_price: number | null;
}

// Read the contract / qty / price out of a Trade's `subject`. Two things make this more than a
// property access:
//   * `subject` crosses the wire as a JSON STRING (TradesReader casts the JSONB column to text), so
//     it has to be parsed — and a malformed one must degrade to "—" cells, not break the table;
//   * the two fill kinds spell the quantity DIFFERENTLY (verified against live audit rows):
//     EntryFilled writes `filled_qty`, PartialExitFilled writes `qty_filled`.
// `option_symbol` is absent on pre-#276 events (it shipped behind a replay version gate), hence
// nullable rather than assumed.
export function tradeFill(t: Trade): TradeFill {
  const s = parseSubject(t.subject);
  return {
    option_symbol: typeof s.option_symbol === "string" ? s.option_symbol : null,
    qty: finite(s.filled_qty ?? s.qty_filled),
    avg_fill_price: finite(s.avg_fill_price),
  };
}

// An audit event's `subject`, parsed. It crosses the wire as a JSON STRING (the readers cast the
// JSONB column to text), and a malformed one must degrade to empty fields rather than throw through
// a page render.
export function parseSubject(raw: string | null): Record<string, unknown> {
  if (!raw) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed !== null && typeof parsed === "object"
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

// A numeric subject field, or null when it is absent or not a finite number. The null/undefined
// guard is load-bearing: Number(null) is 0, so an absent field would otherwise read as a real zero.
export function finite(v: unknown): number | null {
  if (v === null || v === undefined) {
    return null;
  }
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/**
 * What an EXIT fill made: (exit price − entry basis) × qty × 100. Null for an entry fill (an entry
 * makes nothing — it is the basis everything else is measured against), and null when either side
 * of the subtraction is missing.
 *
 * The ×100 is the equity-OPTION contract multiplier. This strip only ever shows option fills, so it
 * is hard-coded rather than carried per row; a non-option instrument here would be mis-valued by
 * 100×, which is the thing to notice if this feed ever widens.
 */
export function tradePnl(t: Trade): number | null {
  if (t.kind !== "PartialExitFilled") {
    return null;
  }
  const f = tradeFill(t);
  const basis = finite(t.entry_basis);
  if (f.avg_fill_price === null || f.qty === null || basis === null) {
    return null;
  }
  return (f.avg_fill_price - basis) * f.qty * 100;
}
