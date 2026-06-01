import "server-only";
import { auth } from "@/auth";

// Server-ONLY client for the off-ingress tenant-dashboard BFF. Never import this from a client
// component. It reads the verified tenant_id from the session and injects it as X-Tenant-Id behind
// the shared service token — the BFF trusts that header precisely because the only thing that can
// reach it (network isolation) is this server, which sets the header only after Auth.js verified
// the identity.
const BFF_URL = process.env.BFF_INTERNAL_URL ?? "http://localhost:8083";
const BFF_TOKEN = process.env.BFF_SHARED_TOKEN ?? "";

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
  account_equity: { broker_target: string; equity: string | number | null }[];
  account_equity_scope: string;
  unrealized_pnl: null;
  unrealized_pnl_note: string;
}

export const getPositions = () => bffGet<Envelope<Position>>("/api/positions");
export const getTrades = (limit = 100) =>
  bffGet<Envelope<Trade>>(`/api/trades?limit=${limit}`);
export const getOrders = (limit = 100) =>
  bffGet<Envelope<Order>>(`/api/orders?limit=${limit}`);
export const getPortfolio = () => bffGet<Portfolio>("/api/portfolio");
