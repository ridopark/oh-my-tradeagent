// Pure math behind the /live real-time holdings cells and the connection strip
// (PLAN-2026-10-01-live-realtime-holdings P4). No imports on purpose: it runs unchanged under
// `node --experimental-strip-types --test lib/liveMarks.test.mjs`, and every number the operator
// reads off a live cell is decided here, where it can be checked in isolation.

/** Equity-option contract multiplier — the same ×100 the BFF's unrealized_pl uses. */
export const OPTIONS_MULTIPLIER = 100;

/**
 * A mark (or underlying print) older than this is not live. For the bid, "age" is since market-data
 * last polled it (`polled_at`) when the field is present, else since the quote last changed.
 */
export const MARK_MAX_AGE_S = 10;

/** Consecutive failed marks polls after which the Server light is red and every mark is unusable. */
export const SERVER_MAX_FAILURES = 3;

/** The Server light turns amber when the last successful marks poll is older than this (1s cadence). */
export const SERVER_LATE_S = 3;

/** A connection snapshot older than this (3 missed 5s polls) says nothing about now: every part is unknown. */
export const CONNECTION_MAX_AGE_S = 15;

/** Option-prices light: amber once the worst usable mark is older than this. */
export const OPTION_LATE_S = 5;

/** The fields of one BFF /api/live/marks entry this module reads. */
export interface MarkInput {
  bid: number | null;
  quote_at: string | null;
  /** When market-data last polled this contract. Absent = market-data predates the field. */
  polled_at?: string | null;
  underlying?: { price: number | null; at: string | null } | null;
  warming: boolean;
  capped: boolean;
}

/** Where the marks frame came from: its server clock, and how long ago (client clock) it arrived. */
export interface FrameClock {
  /** The marks payload's own `now` (epoch ms). Mark ages are measured against it, not the browser clock. */
  payloadNowMs: number;
  /** Client-measured time since the frame arrived, so a held frame keeps ageing while polls fail. */
  elapsedMs: number;
  /** Consecutive failed marks polls. */
  failures: number;
}

export type MarkReason = "missing" | "server" | "capped" | "warming" | "no-quote" | "stale";

export type Usability =
  | { usable: true; value: number; ageS: number }
  | { usable: false; reason: MarkReason; ageS: number | null };

/** Compact OCC: the BFF echoes the row's contract_symbol, but padding must never break the join. */
export function occKey(occ: string): string {
  return occ.replace(/\s+/g, "");
}

function parseMs(iso: string | null | undefined): number | null {
  if (iso == null) return null;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : null;
}

function usableAt(
  value: number | null | undefined,
  at: string | null | undefined,
  clock: FrameClock,
): Usability {
  const atMs = parseMs(at);
  if (value == null || !Number.isFinite(value) || atMs === null) {
    return { usable: false, reason: "no-quote", ageS: null };
  }
  const ageS = Math.max(0, (clock.payloadNowMs - atMs + clock.elapsedMs) / 1000);
  if (ageS > MARK_MAX_AGE_S) {
    return { usable: false, reason: "stale", ageS };
  }
  return { usable: true, value, ageS };
}

function gate(mark: MarkInput, clock: FrameClock): MarkReason | null {
  if (clock.failures >= SERVER_MAX_FAILURES) return "server";
  if (mark.capped) return "capped";
  if (mark.warming) return "warming";
  return null;
}

/**
 * Whether a mark's option BID may be shown as live. Never true for missing/warming/capped/old marks.
 * Aged by `polled_at` when present (a quiet contract's unchanged quote is still live); a present
 * null is no-quote, never a fallback to `quote_at`.
 */
export function bidUsability(mark: MarkInput | null | undefined, clock: FrameClock | null): Usability {
  if (mark == null || clock === null) return { usable: false, reason: "missing", ageS: null };
  const reason = gate(mark, clock);
  if (reason !== null) return { usable: false, reason, ageS: null };
  return usableAt(mark.bid, mark.polled_at !== undefined ? mark.polled_at : mark.quote_at, clock);
}

/** Same rule for the mark's underlying price, which carries its own timestamp. */
export function underlyingUsability(
  mark: MarkInput | null | undefined,
  clock: FrameClock | null,
): Usability {
  if (mark == null || clock === null) return { usable: false, reason: "missing", ageS: null };
  const reason = gate(mark, clock);
  if (reason !== null) return { usable: false, reason, ageS: null };
  return usableAt(mark.underlying?.price, mark.underlying?.at, clock);
}

/** A nullable numeric wire value as a number. Number("") and Number(null) are 0, not "absent". */
export function num(v: unknown): number | null {
  if (v === null || v === undefined || (typeof v === "string" && v.trim() === "")) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/** Value = bid × qty × 100. */
export function liveValue(bid: number, qty: number | null): number | null {
  return qty === null ? null : bid * qty * OPTIONS_MULTIPLIER;
}

/**
 * P&L today = (bid − lastday_price) × qty × 100. lastday_price is the BFF-derived per-contract
 * intraday base (scope lock #7: entry premium for a position opened today, else the prior close).
 * Null when it is absent — the caller then shows the server value as stale, never a guess.
 */
export function livePnlToday(bid: number, lastday: number | null, qty: number | null): number | null {
  return lastday === null || qty === null ? null : (bid - lastday) * qty * OPTIONS_MULTIPLIER;
}

/** P&L total = (bid − entry_premium) × remaining_qty × 100 — the BFF's unrealized_pl, at the bid. */
export function livePnlTotal(bid: number, entry: number | null, qty: number | null): number | null {
  return entry === null || qty === null ? null : (bid - entry) * qty * OPTIONS_MULTIPLIER;
}

/** All-in = unrealized + realized, null until something has been sold (mirrors page.tsx allInPl). */
export function allIn(unrealized: number | null, realized: number | null): number | null {
  return unrealized !== null && realized !== null ? unrealized + realized : null;
}

/** The percentage move between two prices, or null (a zero entry is excluded rather than Infinity). */
export function pctMove(entry: number | null, now: number | null): number | null {
  return entry !== null && now !== null && entry !== 0 ? ((now - entry) / entry) * 100 : null;
}

/** Flash direction for a live value change. Null on first sight or when either side is not a number. */
export function flashDirection(prev: number | null | undefined, next: number | null): "up" | "down" | null {
  if (prev == null || next === null || prev === next) return null;
  return next > prev ? "up" : "down";
}

export type Tone = "green" | "amber" | "red" | "grey";

export interface Light {
  tone: Tone;
  /** Short status text: "ok", "late", "down", "unknown", "market closed", ... */
  text: string;
  /** Age in seconds, or null when there is none to show. */
  ageS: number | null;
}

/** "3s", "4m", "2h" — compact age for the strip and stale cells. */
export function fmtAge(ageS: number | null): string {
  if (ageS === null || !Number.isFinite(ageS)) return "—";
  const s = Math.max(0, Math.floor(ageS));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  return `${Math.floor(s / 3600)}h`;
}

/** Server light: dashboard↔BFF, judged by the 1s marks poll. Red after 3 consecutive failures. */
export function serverLight(lastOkMs: number | null, nowMs: number, failures: number): Light {
  const ageS = lastOkMs === null ? null : Math.max(0, (nowMs - lastOkMs) / 1000);
  if (failures >= SERVER_MAX_FAILURES) return { tone: "red", text: "down", ageS };
  if (ageS === null) return { tone: "amber", text: "unknown", ageS };
  if (ageS > SERVER_LATE_S) return { tone: "amber", text: "late", ageS };
  return { tone: "green", text: "ok", ageS };
}

/** One part of /api/live/connection. */
export interface PartInput {
  status: string;
  age_s: number | null;
  reason?: string | null;
}

/**
 * A connection part's light. `connAgeS` is how old the whole snapshot is (client clock); a snapshot
 * older than CONNECTION_MAX_AGE_S, or absent, is unknown — it must not keep a light green. Market
 * feeds read grey "market closed" outside regular hours rather than red.
 */
export function partLight(
  part: PartInput | null | undefined,
  connAgeS: number | null,
  marketFeed: boolean,
  marketOpen: boolean | null,
): Light {
  if (part == null || connAgeS === null || connAgeS > CONNECTION_MAX_AGE_S) {
    return { tone: "amber", text: "unknown", ageS: null };
  }
  if (marketFeed && marketOpen === false) {
    return { tone: "grey", text: "market closed", ageS: null };
  }
  const ageS = part.age_s == null ? null : part.age_s + connAgeS;
  switch (part.status) {
    case "ok":
      return { tone: "green", text: "ok", ageS };
    case "stale":
      return { tone: "amber", text: "stale", ageS };
    case "down":
      return { tone: "red", text: "down", ageS };
    default:
      return { tone: "amber", text: "unknown", ageS };
  }
}

/**
 * Option-prices light. With holdings: the worst age among their marks — red when none is usable,
 * amber when some are not or the worst is late. Without holdings: the BFF's market_data.option part.
 */
export function optionLight(
  held: Usability[],
  fallback: PartInput | null | undefined,
  connAgeS: number | null,
  marketOpen: boolean | null,
): Light {
  if (held.length === 0) return partLight(fallback, connAgeS, true, marketOpen);
  if (marketOpen === false) return { tone: "grey", text: "market closed", ageS: null };
  const ages = held.flatMap((u) => (u.usable ? [u.ageS] : []));
  if (ages.length === 0) return { tone: "red", text: "no live marks", ageS: null };
  const worst = Math.max(...ages);
  if (ages.length < held.length) {
    return { tone: "amber", text: `${ages.length}/${held.length} live`, ageS: worst };
  }
  return worst > OPTION_LATE_S
    ? { tone: "amber", text: "late", ageS: worst }
    : { tone: "green", text: "ok", ageS: worst };
}
