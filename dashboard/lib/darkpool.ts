// Pure helpers + wire types behind the /dark-pool page (PLAN-2026-10-29-darkpool-prints, Phase 3).
// No imports on purpose: it runs unchanged under
// `node --experimental-strip-types --test lib/darkpool.test.mjs`, and the "use client" components
// import it directly (lib/bff.ts is server-only, so the shapes live here, not there).
//
// The data is OFF-EXCHANGE prints (FINRA TRF, exchange "D"): that is dark pools AND internalizers.
// Copy says "off-exchange"; only the page title says "Dark pool".

// --- Wire shapes (BFF DarkpoolController; snake_case, numbers are JSON numbers) ---------------

export type DarkpoolSide = "buy" | "sell" | "mid" | "unknown";

export interface DarkpoolPrint {
  /** int64 on the BFF — a STRING on the wire so JSON.parse cannot corrupt it. */
  trade_id: string | null;
  symbol: string;
  /** ISO instant, always `...Z`. */
  ts: string;
  price: number | null;
  size: number | null;
  notional: number | null;
  exchange: string | null;
  conditions: string | null;
  tape: string | null;
  /** NBBO just before the print; null when the collector found no quote within 5 s. */
  bid: number | null;
  ask: number | null;
  quote_ts: string | null;
  side: DarkpoolSide | string;
}

export interface DarkpoolPrintsPage {
  count: number;
  items: DarkpoolPrint[];
}

export interface DarkpoolLevel {
  price: number;
  notional: number;
  prints: number;
}

export interface DarkpoolSymbolSummary {
  symbol: string;
  dp_volume: number;
  total_volume: number;
  /** Null when the session has no total volume for the symbol. */
  dp_share: number | null;
  buy_volume: number;
  sell_volume: number;
  buy_ratio: number | null;
  late_buy_ratio: number | null;
  large_count: number;
  large_volume: number;
  /** Top 3 large-print price levels. From PRINTS (90-day retention), not bars (400-day). */
  levels: DarkpoolLevel[];
}

export interface DarkpoolSummary {
  date: string;
  count: number;
  symbols: DarkpoolSymbolSummary[];
}

/** What the /api/darkpool/* proxies return when the BFF has DARKPOOL_ENABLED off (BFF 404). */
export interface DarkpoolDisabled {
  disabled: true;
}

export function isDisabled(body: unknown): body is DarkpoolDisabled {
  return typeof body === "object" && body !== null && (body as DarkpoolDisabled).disabled === true;
}

// --- Constants --------------------------------------------------------------------------------

/** Server-side print retention (DarkpoolRetention). Bars live 400 days, so ratios outlive levels. */
export const PRINT_RETENTION_DAYS = 90;
/**
 * 0 = every stored print: the collector also keeps SIZE-qualified prints (≥ 10,000 shares) that
 * are under $200k, and those are only reachable with no notional floor.
 */
export const MIN_NOTIONAL_OPTIONS = [0, 200_000, 500_000, 1_000_000] as const;
export const DEFAULT_MIN_NOTIONAL = 200_000;
/** The BFF's own symbol pattern; checked client-side so a typo is not a round-trip 400. */
export const SYMBOL_RE = /^[A-Za-z0-9.-]{1,16}$/;

// --- ET session clock -------------------------------------------------------------------------

const ET = "America/New_York";

/** YYYY-MM-DD of `now` in US/Eastern — the date picker's default session. */
export function etToday(now: Date = new Date()): string {
  return now.toLocaleDateString("en-CA", { timeZone: ET });
}

function etParts(d: Date): { weekday: string; minutes: number } {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: ET,
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(d)
      .map((x) => [x.type, x.value]),
  );
  return { weekday: p.weekday, minutes: Number(p.hour) * 60 + Number(p.minute) };
}

const OPEN_MIN = 9 * 60 + 30;
// 16:03, not 16:00: the closing 5-minute bar and the last prints land one collector cycle (60 s)
// after the bell, so polling must outlive the session by that much.
const POLL_END_MIN = 16 * 60 + 3;

function isWeekday(weekday: string): boolean {
  return weekday !== "Sat" && weekday !== "Sun";
}

/**
 * Auto-refresh window: weekdays 09:30–16:03 ET. Holidays are NOT known here: on a holiday the page
 * simply re-reads an unchanging table — a cheap dashboard-DB read, never a broker request.
 */
export function isPollWindow(now: Date): boolean {
  const { weekday, minutes } = etParts(now);
  return isWeekday(weekday) && minutes >= OPEN_MIN && minutes < POLL_END_MIN;
}

/** Weekday before 09:30 ET: today's session has not started, so an empty summary is expected. */
export function isPreOpen(now: Date): boolean {
  const { weekday, minutes } = etParts(now);
  return isWeekday(weekday) && minutes < OPEN_MIN;
}

/** Whole calendar days from `date` to `today` (both YYYY-MM-DD). */
export function daysBetween(date: string, today: string): number {
  return Math.round((Date.parse(`${today}T00:00:00Z`) - Date.parse(`${date}T00:00:00Z`)) / 86_400_000);
}

// --- Formatting -------------------------------------------------------------------------------

function finite(v: number | null | undefined): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

/** Min-notional select label: "all sizes" for 0, else "≥ $200k". */
export function minNotionalLabel(n: number): string {
  return n > 0 ? `≥ ${fmtNotional(n)}` : "all sizes";
}

/** "$1.25M", "$512k", "$950" — notional columns. */
export function fmtNotional(v: number | null | undefined): string {
  if (!finite(v)) return "—";
  const a = Math.abs(v);
  if (a >= 1e9) return `$${(v / 1e9).toFixed(2)}B`;
  if (a >= 1e6) return `$${(v / 1e6).toFixed(2)}M`;
  if (a >= 1e3) return `$${Math.round(v / 1e3)}k`;
  return `$${Math.round(v)}`;
}

/** Share volume: "1.2M", "45.3k", "800". */
export function fmtVolume(v: number | null | undefined): string {
  if (!finite(v)) return "—";
  const a = Math.abs(v);
  if (a >= 1e6) return `${(v / 1e6).toFixed(1)}M`;
  if (a >= 1e3) return `${(v / 1e3).toFixed(1)}k`;
  return String(Math.round(v));
}

export function fmtShares(v: number | null | undefined): string {
  return finite(v) ? v.toLocaleString("en-US") : "—";
}

/** Two to four decimals: off-exchange prints are often sub-penny. */
export function fmtPrice(v: number | null | undefined): string {
  return finite(v)
    ? v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 4 })
    : "—";
}

/** A 0..1 ratio as "42.1%"; null (no denominator) as "—", never as 0%. */
export function fmtRatio(v: number | null | undefined): string {
  return finite(v) ? `${(v * 100).toFixed(1)}%` : "—";
}

/**
 * Print time in ET: "14:03:27" for a print on `today`, "10-28 14:03:27" otherwise — the newest 200
 * prints can span sessions, and a bare time would read as today.
 */
export function fmtEtTime(iso: string, today: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const time = d.toLocaleTimeString("en-US", { timeZone: ET, hourCycle: "h23" });
  const day = d.toLocaleDateString("en-CA", { timeZone: ET });
  return day === today ? time : `${day.slice(5)} ${time}`;
}

/** "3s ago", "4m ago", "2h ago". */
export function fmtAge(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}

/**
 * How the "updated N ago" note should look, most specific first:
 *   - "rejected": the BFF answered 400 to this query
 *   - "empty":    a 2xx with no usable body (counted as a failure; the last good frame stays)
 *   - "failed":   the last attempt failed (network, timeout, 502)
 *   - "stale":    auto-refresh is on but nothing succeeded for 3 intervals, so a frozen table
 *                 cannot pass for a live one
 */
export function freshness(
  ageMs: number | null,
  last: { failed: boolean; invalid: boolean; noData: boolean },
  pollMs: number | null,
): "fresh" | "stale" | "failed" | "rejected" | "empty" {
  if (last.invalid) return "rejected";
  if (last.noData) return "empty";
  if (last.failed) return "failed";
  if (pollMs !== null && ageMs !== null && ageMs > 3 * pollMs) return "stale";
  return "fresh";
}

// --- Summary sorting --------------------------------------------------------------------------

export type SummarySortKey =
  | "dp_share"
  | "buy_ratio"
  | "late_buy_ratio"
  | "large_count"
  | "dp_volume";

/** Descending or ascending by `key`; nulls ALWAYS last (an unknown ratio is not the smallest). */
export function sortSummary(
  rows: readonly DarkpoolSymbolSummary[],
  key: SummarySortKey,
  dir: "asc" | "desc",
): DarkpoolSymbolSummary[] {
  const sign = dir === "asc" ? 1 : -1;
  return [...rows].sort((a, b) => {
    const x = a[key];
    const y = b[key];
    const xn = !finite(x);
    const yn = !finite(y);
    if (xn || yn) return xn === yn ? a.symbol.localeCompare(b.symbol) : xn ? 1 : -1;
    return x === y ? a.symbol.localeCompare(b.symbol) : (x - y) * sign;
  });
}

// --- Levels -----------------------------------------------------------------------------------

/**
 * What the levels strip may claim for one symbol. Levels come from prints (90-day retention) while
 * `large_count` comes from bars (400 days): an old session can show large prints in the table with
 * no levels, which must read as "prints expired", never as "no large prints".
 *   - "levels":  render the clusters
 *   - "expired": the session is past print retention
 *   - "none":    no ≥$200k off-exchange prints that session
 *   - "missing": the bars counted large prints but no print rows exist (collector gap)
 */
export function levelsState(
  row: Pick<DarkpoolSymbolSummary, "levels" | "large_count">,
  date: string,
  today: string,
): "levels" | "expired" | "none" | "missing" {
  if (row.levels.length > 0) return "levels";
  if (daysBetween(date, today) >= PRINT_RETENTION_DAYS) return "expired";
  return row.large_count > 0 ? "missing" : "none";
}

/** One level chip: "$412.30 · $5.21M · 7 prints". */
export function levelLabel(l: DarkpoolLevel): string {
  return `$${fmtPrice(l.price)} · ${fmtNotional(l.notional)} · ${l.prints} print${l.prints === 1 ? "" : "s"}`;
}

// --- Side pill --------------------------------------------------------------------------------

/** Tailwind classes for the side pill; anything unrecognised renders as "unknown". */
export function sidePillClass(side: string): string {
  switch (side) {
    case "buy":
      return "bg-emerald-900/60 text-emerald-300";
    case "sell":
      return "bg-rose-900/60 text-rose-300";
    case "mid":
      return "bg-slate-700 text-slate-200";
    default:
      return "border border-slate-700 text-slate-500";
  }
}

// --- Query strings ----------------------------------------------------------------------------

/** The prints feed query: symbol is dropped unless it matches the BFF's charset. */
export function printsQuery(symbol: string, minNotional: number, limit = 200): string {
  const p = new URLSearchParams();
  const s = symbol.trim().toUpperCase();
  if (s && SYMBOL_RE.test(s)) p.set("symbol", s);
  if (minNotional > 0) p.set("min_notional", String(minNotional));
  p.set("limit", String(limit));
  return p.toString();
}
