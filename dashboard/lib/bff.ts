import "server-only";
import { auth } from "@/auth";

// Server-ONLY client for the off-ingress tenant-dashboard BFF. Never import this from a client
// component. It reads the verified tenant_id from the session and injects it as X-Tenant-Id behind
// the shared service token — the BFF trusts that header precisely because the only thing that can
// reach it (network isolation) is this server, which sets the header only after Auth.js verified
// the identity.
// Exported so the operator admin client (lib/adminBff.ts) shares the SAME base URL, token, and the
// timeout-ordering invariant below rather than re-declaring them (and risking drift).
export const BFF_URL = process.env.BFF_INTERNAL_URL ?? "http://localhost:8083";
export const BFF_TOKEN = process.env.BFF_SHARED_TOKEN ?? "";
// Upper bound on a single BFF call so an unreachable/slow BFF can't hang a page render. Top rung of
// an ordering invariant that keeps /status degrading (not 500-ing) under an orchestrator stall:
//   AccountEquityClient.RESULT_TIMEOUT_SECONDS (8s inner worker wait)
//     <= bff.portfolio.subread-timeout-seconds (9s BFF per-section budget)
//     <= this (12s). Lowering this below the BFF budget re-introduces the 500 it was meant to kill.
export const BFF_TIMEOUT_MS = 12_000;

if (!BFF_TOKEN) {
  // Misconfiguration: without the shared token every BFF call gets a 401. Surface it loudly rather
  // than letting it look like an auth bug at request time.
  console.error(
    "BFF_SHARED_TOKEN is empty — all tenant-dashboard-bff calls will be rejected (401).",
  );
}

export class NotAuthenticatedError extends Error {}

async function bffGet<T>(path: string): Promise<T> {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    throw new NotAuthenticatedError("no tenant in session");
  }
  const res = await fetch(`${BFF_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${BFF_TOKEN}`,
      "X-Tenant-Id": tenantId,
    },
    // Dashboard reads are always live — never serve a cached tenant's data.
    cache: "no-store",
    // Abort a hung/unreachable BFF rather than block the server-rendered page indefinitely.
    signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
  });
  if (!res.ok) {
    throw new Error(`BFF ${path} -> ${res.status}`);
  }
  return (await res.json()) as T;
}

// Non-throwing sibling of bffGet: returns the raw status + parsed body so a caller can branch on
// an EXPECTED non-2xx instead of catching. bffGet's throw-on-error is right for a page render (a
// failed read is a data outage); it is wrong for the manual-entry reads, where 400/404/503 are
// each a distinct thing to tell the operator.
async function bffGetRaw(path: string): Promise<{ status: number; body: unknown }> {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    throw new NotAuthenticatedError("no tenant in session");
  }
  const res = await fetch(`${BFF_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${BFF_TOKEN}`,
      "X-Tenant-Id": tenantId,
    },
    cache: "no-store",
    signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
  });
  let parsed: unknown = null;
  try {
    parsed = await res.json();
  } catch {
    parsed = null;
  }
  return { status: res.status, body: parsed };
}

export interface Envelope<T> {
  tenant_id: string;
  count: number;
  items: T[];
}

// Server-ONLY POST to the BFF. Mirrors bffGet (same X-Tenant-Id injection behind the shared token)
// but sends a JSON body and, crucially, DOES NOT throw on a non-2xx — it returns the raw status +
// parsed body so the caller can branch on the BFF's typed 409 envelopes (circuit_breaker_active /
// not_tripped) rather than treating an expected "please wait" as a 500.
async function bffPost(
  path: string,
  body: unknown,
  // Optional attribution-only headers threaded onto the request (e.g. X-Operator-Id carrying the
  // verified session email for per-human audit). NEVER an auth boundary — the BFF authorizes on the
  // injected X-Tenant-Id + its own dark flags; these are recorded, not trusted for access.
  extraHeaders?: Record<string, string>,
): Promise<{ status: number; body: unknown }> {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    throw new NotAuthenticatedError("no tenant in session");
  }
  const res = await fetch(`${BFF_URL}${path}`, {
    method: "POST",
    // extraHeaders is spread FIRST so the injected Authorization / X-Tenant-Id / Content-Type always
    // win — a caller's attribution headers can never override the fail-closed tenant boundary.
    headers: {
      ...extraHeaders,
      Authorization: `Bearer ${BFF_TOKEN}`,
      "X-Tenant-Id": tenantId,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
    cache: "no-store",
    signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
  });
  let parsed: unknown = null;
  try {
    parsed = await res.json();
  } catch {
    parsed = null; // empty/non-JSON body — the status alone drives the branch.
  }
  return { status: res.status, body: parsed };
}

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
export const getAccountKillSwitch = () =>
  bffGet<AccountKillSwitch>("/api/account-killswitch");

// Typed result of a reset attempt. Never throws on the expected 409s — the UI needs to SHOW the wait
// (circuit_breaker_active, with the resettableAt to resync its countdown) or the no-op (not_tripped)
// rather than crash. `ok` is the 200 RESET path.
export type ResetAccountKillSwitchResult =
  | { ok: true }
  | { ok: false; error: "circuit_breaker_active"; resettableAt: string | null }
  | { ok: false; error: "not_tripped" }
  | { ok: false; error: "unauthorized" }
  | { ok: false; error: "unknown"; status: number };

export async function resetAccountKillSwitch(): Promise<ResetAccountKillSwitchResult> {
  const { status, body } = await bffPost("/api/account-killswitch/reset", {});
  if (status === 200) {
    return { ok: true };
  }
  if (status === 409) {
    const err = (body as { error?: string } | null)?.error;
    if (err === "circuit_breaker_active") {
      const b = body as { resettableAt?: string | null };
      return {
        ok: false,
        error: "circuit_breaker_active",
        resettableAt: b.resettableAt ?? null,
      };
    }
    // The only other documented 409 is not_tripped (already reset / never tripped).
    return { ok: false, error: "not_tripped" };
  }
  if (status === 401) {
    return { ok: false, error: "unauthorized" };
  }
  return { ok: false, error: "unknown", status };
}

// Typed result of a per-position force-exit (POST /api/positions/force-close). Like the kill-switch
// reset it never throws on the expected non-2xx — the /live button needs to SHOW the outcome:
//   ok            — 202 ACCEPTED (exit placed) or 200 (benign no-op / phantom cleared).
//   disabled      — 404 {"error":"force_close_disabled"}: the BFF dark flag is off (should be
//                   unreachable when the paired UI flag gates the button, but handled defensively).
//   alreadyClosed — 409 {"error":"position_already_closed"}: the workflow terminated between render
//                   and click (self-heal / EOD flatten / already-cleared phantom).
// Any other status (403 cross-tenant, 401, 400) falls through as ok:false with no branch flag.
export type ForcePositionExitResult = {
  ok: boolean;
  disabled?: boolean;
  alreadyClosed?: boolean;
};

// Drive the EXISTING PositionWorkflow.force_close Update via the dark-gated BFF endpoint. `operatorId`
// (the verified session email) is threaded as X-Operator-Id for per-human audit attribution — the BFF
// records operator_id = "tenant:<tenant>:<email>"; it is attribution-only, never an authz principal
// (the BFF authorizes on the injected X-Tenant-Id + the tenant-prefix guard + its own dark flag).
export async function forcePositionExit(
  workflowId: string,
  reason: string,
  operatorId?: string,
): Promise<ForcePositionExitResult> {
  const { status, body } = await bffPost(
    "/api/positions/force-close",
    { workflow_id: workflowId, reason },
    operatorId ? { "X-Operator-Id": operatorId } : undefined,
  );
  const err = (body as { error?: string } | null)?.error;
  if (status === 404 && err === "force_close_disabled") {
    return { ok: false, disabled: true };
  }
  if (status === 409 && err === "position_already_closed") {
    return { ok: false, alreadyClosed: true };
  }
  if (status === 200 || status === 202) {
    return { ok: true };
  }
  return { ok: false };
}

// Typed result of a per-position partial close / "Trim" (POST /api/positions/partial-close). Same
// shape and non-throwing contract as forcePositionExit — the /live button needs to SHOW the outcome:
//   ok            — 202 ACCEPTED (trim placed) or 200 (benign no-op on an already-drained position).
//   disabled      — 404 {"error":"partial_close_disabled"}: the BFF dark flag is off (unreachable
//                   when the paired UI flag gates the button, but handled defensively).
//   alreadyClosed — 409 {"error":"position_already_closed"}: the workflow terminated between render
//                   and click.
export type TrimPositionResult = {
  ok: boolean;
  disabled?: boolean;
  alreadyClosed?: boolean;
};

// Drive PositionWorkflow.partial_close via the dark-gated BFF endpoint: sell `fraction` of the
// position's REMAINING qty at market and leave the rest running. `fraction` must be in (0,1)
// exclusive — the BFF and the workflow validator both reject 1.0, since a full close is
// forcePositionExit's job. `operatorId` is threaded as X-Operator-Id for audit attribution only.
export async function trimPosition(
  workflowId: string,
  fraction: number,
  reason: string,
  operatorId?: string,
): Promise<TrimPositionResult> {
  const { status, body } = await bffPost(
    "/api/positions/partial-close",
    { workflow_id: workflowId, reason, fraction },
    operatorId ? { "X-Operator-Id": operatorId } : undefined,
  );
  const err = (body as { error?: string } | null)?.error;
  if (status === 404 && err === "partial_close_disabled") {
    return { ok: false, disabled: true };
  }
  if (status === 409 && err === "position_already_closed") {
    return { ok: false, alreadyClosed: true };
  }
  // 202 ⟺ ACCEPTED (a sell is queued); 200 ⟺ NOOP_ALREADY_CLOSED (the position drained before the
  // Update landed — nothing was enqueued). Branch on the STATUS, not "any 2xx": collapsing them
  // would paint a green "Trim placed" over a trim that sold nothing, and the operator would
  // reasonably click again.
  if (status === 202) {
    return { ok: true };
  }
  if (status === 200) {
    return { ok: false, alreadyClosed: true };
  }
  return { ok: false };
}

// PLAN-2026-08-10-live-manual-bto ------------------------------------------------------------
// The /live manual-entry panel's three calls. All follow trimPosition's contract: never throw on
// an expected non-2xx, return a typed result the UI can branch on, because every one of these
// statuses is something the operator needs to SEE (a silent failure on a real-money entry is the
// failure mode this whole feature has to avoid).

// Parsed contract + live NBBO for the confirm step. `ask` is what anchors the marketable limit,
// so it is non-null by construction — the BFF 503s rather than returning a quote without one.
export interface OptionQuote {
  occ: string;
  underlying: string;
  expiry: string;
  strike: number;
  right: "C" | "P";
  bid: number | null;
  mid: number | null;
  ask: number;
  quoted_at: string;
}

export type OptionQuoteResult =
  | { ok: true; quote: OptionQuote }
  | { ok: false; kind: "invalid-occ"; detail: string }
  | { ok: false; kind: "unavailable" | "disabled" | "error" };

export async function getOptionQuote(occ: string): Promise<OptionQuoteResult> {
  const { status, body } = await bffGetRaw(
    `/api/entries/quote?occ=${encodeURIComponent(occ)}`,
  );
  const err = (body as { error?: string; detail?: string } | null) ?? {};
  if (status === 200) {
    return { ok: true, quote: body as OptionQuote };
  }
  if (status === 400 && err.error === "invalid_occ") {
    return { ok: false, kind: "invalid-occ", detail: err.detail ?? "not a valid contract" };
  }
  if (status === 503) {
    return { ok: false, kind: "unavailable" };
  }
  if (status === 404) {
    return { ok: false, kind: "disabled" };
  }
  return { ok: false, kind: "error" };
}

// Outcome of a submit. `quote-moved` carries both prices so the operator can see WHY it refused
// rather than being told to try again with no explanation.
export type ManualEntryResult =
  | { ok: true; signalId: string; anchorAsk: number | null }
  | { ok: false; kind: "quote-moved"; confirmedAsk: number; currentAsk: number }
  | {
      ok: false;
      kind:
        | "quote-stale"
        | "quote-unavailable"
        | "duplicate"
        | "unknown-strategy"
        | "invalid-occ"
        | "disabled"
        | "error";
    };

export async function submitManualEntry(
  occ: string,
  strategyId: string,
  qty: number,
  quotedAsk: number,
  quotedAt: string,
  idempotencyKey: string,
  operatorId?: string,
): Promise<ManualEntryResult> {
  const { status, body } = await bffPost(
    "/api/entries/manual",
    {
      occ,
      strategy_id: strategyId,
      qty,
      quoted_ask: quotedAsk,
      quoted_at: quotedAt,
      idempotency_key: idempotencyKey,
    },
    operatorId ? { "X-Operator-Id": operatorId } : undefined,
  );
  const b = (body as Record<string, unknown> | null) ?? {};
  const err = b.error as string | undefined;
  if (status === 202) {
    return {
      ok: true,
      signalId: String(b.signal_id ?? ""),
      anchorAsk: b.anchor_ask == null ? null : Number(b.anchor_ask),
    };
  }
  if (status === 409 && err === "quote_moved") {
    return {
      ok: false,
      kind: "quote-moved",
      confirmedAsk: Number(b.confirmed_ask),
      currentAsk: Number(b.current_ask),
    };
  }
  if (status === 409 && err === "quote_stale") return { ok: false, kind: "quote-stale" };
  if (status === 409 && err === "duplicate_submission") return { ok: false, kind: "duplicate" };
  if (status === 403 && err === "unknown_strategy") {
    return { ok: false, kind: "unknown-strategy" };
  }
  if (status === 400 && err === "invalid_occ") return { ok: false, kind: "invalid-occ" };
  if (status === 503) return { ok: false, kind: "quote-unavailable" };
  if (status === 404) return { ok: false, kind: "disabled" };
  return { ok: false, kind: "error" };
}

// Mirrors the CopytradeEntryStatus contract DTO. PENDING is transient (the gates are still
// running); everything else is terminal for the entry.
export interface EntryStatus {
  state: "PENDING" | "REJECTED" | "SUBMITTED" | "FILLED" | "EXPIRED" | "ABORTED" | "FAILED";
  reason_code: string | null;
  reason_detail: string | null;
  option_symbol: string | null;
  contracts: number | null;
  broker_order_id: string | null;
  filled_qty: number | null;
  avg_fill_price: string | number | null;
}

export async function getEntryStatus(
  signalId: string,
  strategyId: string,
): Promise<EntryStatus | null> {
  const { status, body } = await bffGetRaw(
    `/api/entries/${encodeURIComponent(signalId)}/status?strategy_id=${encodeURIComponent(strategyId)}`,
  );
  return status === 200 ? (body as EntryStatus) : null;
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
}

export interface Trade {
  event_id: string;
  occurred_at: string;
  kind: string;
  strategy_id: string;
  workflow_id: string | null;
  correlation_id: string | null;
  subject: string | null;
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
  recorded_at: string;
  filled_at: string | null;
  last_error: string | null;
}

export interface Portfolio {
  tenant_id: string;
  trading_day: string;
  open_positions: Position[];
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
  }[];
  account_equity_scope: string;
  unrealized_pnl: null;
  unrealized_pnl_note: string;
}

// UI-P2-b: non-secret broker-credential status surfaced by the BFF. Contains NO secret material —
// broker_account_id is a non-secret brokerage account identifier; the api_key_id / api_secret_key
// are NEVER returned by the status endpoint.
export interface BrokerCredentialStatus {
  provider: string;
  configured: boolean;
  version: number;
  broker_account_id: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

// UI-P3-a: a tenant's editable strategy config(s) + version (for the future write path's optimistic
// CAS) + field-class metadata. No secret material (broker keys live in a separate table). The
// `config` is the raw strategy-config object; `field_classes` lists the non-SAFE fields so the UI
// can render IDENTITY/DANGEROUS read-only and EXPOSURE tighten-only — any field not listed is SAFE.
export interface StrategyConfigItem {
  strategy_id: string;
  version: number;
  config: Record<string, unknown>;
}
export interface StrategyConfigResponse {
  tenant_id: string;
  count: number;
  field_classes: { IDENTITY: string[]; DANGEROUS: string[]; EXPOSURE: string[] };
  items: StrategyConfigItem[];
}
export const getStrategyConfig = () =>
  bffGet<StrategyConfigResponse>("/api/strategy-config");

// UI-P2: the tenant's ACCOUNT-level daily-loss cap (tenant-wide, realized + open P&L) — distinct
// from the per-strategy `daily_loss_threshold`. Two mutually-independent knobs, either or both may
// be null (unset): `account_daily_loss_threshold` is absolute USD; `account_daily_loss_pct` is a
// FRACTION of start-of-day equity (0.40 == 40%). `version` backs the future write path's CAS.
// `field_classes` marks both fields EXPOSURE (tighten-only) for the read-only badge.
export interface TenantConfig {
  account_daily_loss_threshold: number | null;
  account_daily_loss_pct: number | null;
  version: number | null;
  field_classes: { EXPOSURE: string[] };
}
export const getTenantConfig = () => bffGet<TenantConfig>("/api/tenant-config");

// Live proximity (/live view). last_tick_age_ms is -1 before the first tick; status is "ok" when
// the market-data actuator answered, "unknown" when it was unreachable (the tables still render).
export interface FeedState {
  connected: boolean;
  lastTickAgeMs: number;
}
export interface FeedLiveness {
  status: string;
  equity?: FeedState;
  option?: FeedState;
}
export interface WatchlistProximity {
  workflow_id: string;
  strategy_id: string;
  ticker: string;
  direction: string;
  trigger_level: string | number | null;
  band_low: string | number | null;
  band_high: string | number | null;
  last_price: string | number | null;
  state: string;
  distance_to_trigger_pct: number | null;
  option_symbol: string | null;
  option_premium: string | number | null;
}
export interface PositionProximity {
  workflow_id: string;
  strategy_id: string;
  contract_symbol: string;
  underlying: string | null;
  underlying_price: string | number | null;
  entry_premium: string | number | null;
  stop_level: string | number | null;
  target_level: string | number | null;
  last_bid: string | number | null;
  peak_premium: string | number | null;
  trailing_armed: boolean;
  distance_to_stop_pct: number | null;
  distance_to_target_pct: number | null;
}
export interface ProximityResponse {
  tenant_id: string;
  liveness: FeedLiveness;
  watchlist: WatchlistProximity[];
  positions: PositionProximity[];
}
export const getProximity = () => bffGet<ProximityResponse>("/api/proximity");

// Account portfolio history (/live equity chart). Parallel arrays indexed by `timestamps` (epoch
// seconds): `equity` is the chart line, `profit_loss`/`profit_loss_pct` the range-aware headline,
// `base_value` the dashed baseline / range start. Account-level / shared scope (same caveat as
// account_equity_scope) — `account_scope` carries the label. Empty arrays = unavailable; the chart
// degrades, never crashes.
export interface PortfolioHistory {
  timestamps: number[];
  equity: number[];
  profit_loss: number[];
  profit_loss_pct: number[];
  base_value: number;
  base_value_asof: number | null;
  timeframe: string;
  account_scope: string;
  range_pl: number | null;
  range_pl_pct: number | null;
  cash_flows_available: boolean;
}
export const getPortfolioHistory = (range: string) =>
  bffGet<PortfolioHistory>(`/api/portfolio-history?range=${range}`);

export const getTrades = (limit = 100) =>
  bffGet<Envelope<Trade>>(`/api/trades?limit=${limit}`);
export const getOrders = (limit = 100) =>
  bffGet<Envelope<Order>>(`/api/orders?limit=${limit}`);
export const getPortfolio = () => bffGet<Portfolio>("/api/portfolio");
export const getBrokerCredentialStatus = () =>
  bffGet<Envelope<BrokerCredentialStatus>>("/api/broker-credentials/status");

// --- /options-chat Discord mirror (PLAN-2026-08-12) -------------------------------------------
//
// DELIBERATELY TENANT-INDEPENDENT: the BFF returns identical bytes for every tenant, because the
// mirror is one shared room rather than per-tenant trading data. bffGet still injects X-Tenant-Id
// and still throws without a session — the header is the authentication assertion, not a scope.
//
// Snowflake ids are STRINGS on the wire. They exceed 2^53, so typing them as `number` here would
// silently corrupt every id the moment JSON.parse ran.

export interface OptionsChatAttachment {
  id: string;
  kind: string;
  filename: string | null;
  content_type: string | null;
  width: number | null;
  height: number | null;
  /** pending | ok | failed | skipped_too_large — bytes exist only once this is "ok" (Phase 4). */
  fetch_state: string;
}

export interface OptionsChatEmbed {
  title: string | null;
  description: string | null;
  url: string | null;
  author: string | null;
  footer: string | null;
  thumbnail_url: string | null;
}

export interface OptionsChatMessage {
  message_id: string;
  author_name: string;
  /**
   * Discord role colour as `#rrggbb`, or null when the author has none. Validated at ingest.
   *
   * OPTIONAL, not merely nullable: during a rolling deploy — or against a BFF that predates the
   * column — the key is ABSENT rather than null, which a non-optional type claims is impossible.
   */
  author_color?: string | null;
  author_avatar_url: string | null;
  posted_at: string | null;
  /** PLAIN TEXT — never HTML. Render it through DiscordMarkdown, never as markup. */
  content: string;
  reply_to_id: string | null;
  edited: boolean;
  deleted: boolean;
  attachments: OptionsChatAttachment[];
  embeds: OptionsChatEmbed[];
}

/** One mirrored channel, as the page's tab. Labels come from BFF config, not the page. */
export interface OptionsChatChannel {
  id: string;
  label: string;
}

export interface OptionsChatPage {
  channel_id: string;
  /** Optional: absent when talking to a BFF that predates multi-channel. */
  channels?: OptionsChatChannel[];
  count: number;
  items: OptionsChatMessage[];
}

/**
 * Fetch a page, distinguishing "the feature is switched off" from "the read failed".
 *
 * Uses the non-throwing bffGetRaw because a 404 here is an EXPECTED state, not an outage: the BFF
 * gates both options-chat routes on a two-name @ConditionalOnProperty, so until an operator sets
 * OPTIONS_CHAT_ENABLED the controller bean does not exist and there is no route at all. Collapsing
 * that into a generic failure is what made the page report "Unable to reach the mirror" when in
 * fact the mirror was fine and the feature simply was not enabled yet.
 */
export const getOptionsChatMessages = async (
  opts: { before?: string; limit?: number; channel?: string } = {},
): Promise<{ page: OptionsChatPage | null; disabled: boolean }> => {
  const params = new URLSearchParams();
  if (opts.channel) params.set("channel", opts.channel);
  if (opts.before) params.set("before", opts.before);
  if (opts.limit) params.set("limit", String(opts.limit));
  const qs = params.toString();
  const { status, body } = await bffGetRaw(
    `/api/options-chat/messages${qs ? `?${qs}` : ""}`,
  );
  if (status === 404) {
    return { page: null, disabled: true };
  }
  if (status < 200 || status >= 300) {
    throw new Error(`BFF options-chat -> ${status}`);
  }
  return { page: body as OptionsChatPage, disabled: false };
};

/**
 * Raw upstream response for one mirrored attachment's bytes.
 *
 * Returns the {@link Response} rather than parsed data because the caller streams the body straight
 * through — buffering an image in the route handler would double its memory for no reason.
 *
 * Deliberately built on the shared {@link BFF_URL}/{@link BFF_TOKEN} rather than re-reading the env
 * in the route handler: a second copy drifts, and the copy this replaced had a WEAKER fallback
 * (defaulting the shared token to a well-known dev value instead of failing loudly like the
 * module-level check above).
 */
export const fetchOptionsChatMedia = async (attachmentId: string): Promise<Response> => {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    throw new NotAuthenticatedError("no tenant in session");
  }
  return fetch(`${BFF_URL}/api/options-chat/media/${attachmentId}`, {
    headers: { Authorization: `Bearer ${BFF_TOKEN}`, "X-Tenant-Id": tenantId },
    cache: "no-store",
    signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
  });
};
