import "server-only";
import { auth } from "@/auth";

// Server-ONLY client for the off-ingress tenant-dashboard BFF. Never import this from a client
// component. It reads the verified tenant_id from the session and injects it as X-Tenant-Id behind
// the shared service token — the BFF trusts that header precisely because the only thing that can
// reach it (network isolation) is this server, which sets the header only after Auth.js verified
// the identity.
const BFF_URL = process.env.BFF_INTERNAL_URL ?? "http://localhost:8083";
const BFF_TOKEN = process.env.BFF_SHARED_TOKEN ?? "";
// Upper bound on a single BFF call so an unreachable/slow BFF can't hang a page render. Top rung of
// an ordering invariant that keeps /status degrading (not 500-ing) under an orchestrator stall:
//   AccountEquityClient.RESULT_TIMEOUT_SECONDS (8s inner worker wait)
//     <= bff.portfolio.subread-timeout-seconds (9s BFF per-section budget)
//     <= this (12s). Lowering this below the BFF budget re-introduces the 500 it was meant to kill.
const BFF_TIMEOUT_MS = 12_000;

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

export interface Envelope<T> {
  tenant_id: string;
  count: number;
  items: T[];
}

export interface Position {
  workflow_id: string;
  strategy_id: string;
  contract_symbol: string;
  remaining_qty: number;
  entry_premium: string | number | null;
  open_notional: string | number | null;
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
  // account_number is present only when the BFF's dev flag (bff.expose-broker-account-number) is on
  // — never in prod. Used purely to verify which brokerage account a broker_target maps to.
  account_equity: {
    broker_target: string;
    equity: string | number | null;
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

export const getPositions = () => bffGet<Envelope<Position>>("/api/positions");
export const getTrades = (limit = 100) =>
  bffGet<Envelope<Trade>>(`/api/trades?limit=${limit}`);
export const getOrders = (limit = 100) =>
  bffGet<Envelope<Order>>(`/api/orders?limit=${limit}`);
export const getPortfolio = () => bffGet<Portfolio>("/api/portfolio");
export const getBrokerCredentialStatus = () =>
  bffGet<Envelope<BrokerCredentialStatus>>("/api/broker-credentials/status");
