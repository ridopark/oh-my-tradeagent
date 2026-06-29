import "server-only";
import { auth } from "@/auth";

// Server-ONLY client for the operator-scoped, cross-tenant admin read on the tenant-dashboard-bff
// (GET /api/admin/tenants, I-1a). Distinct from lib/bff.ts because the header semantics differ: the
// admin listing is cross-tenant, so it sends X-Operator-Id (the verified operator email from the
// session) and NO X-Tenant-Id. Same network-isolated BFF behind the same shared service token.
//
// The BFF route is itself dark-gated (operator.admin-read.enabled, default off → 404). When the flag
// is off this throws AdminReadDisabledError so the page can render an explanatory empty state rather
// than a hard error.
const BFF_URL = process.env.BFF_INTERNAL_URL ?? "http://localhost:8083";
const BFF_TOKEN = process.env.BFF_SHARED_TOKEN ?? "";
const BFF_TIMEOUT_MS = 12_000;

export class NotOperatorError extends Error {}
export class AdminReadDisabledError extends Error {}

// One (tenant, strategy) row of the admin listing. Mirrors AdminTenantsController.toItem. Contains NO
// secret material — account_masked is "••••" + last 4 (or "••••"); the secret columns are never read.
export interface AdminTenantItem {
  tenant_id: string;
  strategy_id: string;
  broker_target: string | null;
  account_masked: string;
  mode: "live" | "paper";
  // For live: the gate's classification — VALID | STALE | DEACTIVATED | CONFIG_CHANGED | ABSENT.
  // For paper: "n/a" (paper never hits the live-promotion gate).
  activation_state: string;
  // approved_at + 30d TTL, present only when activation_state === "VALID" (the "valid until" the UI
  // renders); null otherwise.
  expires_at: string | null;
  // True iff VALID and within 3 days of expiry — the UI warns the operator to re-approve.
  at_risk: boolean;
  // Forward-stable placeholders (I-1 follow-up): not yet wired.
  kill_switch_state: string | null;
  last_synced: string | null;
}

export interface AdminTenantsResponse {
  operator_id: string;
  count: number;
  items: AdminTenantItem[];
}

// Fetch the cross-tenant admin listing. Throws NotOperatorError when the session is not an operator
// (the page should never call this otherwise — the layout gates first) and AdminReadDisabledError
// when the BFF route is dark (404).
export async function getAdminTenants(): Promise<AdminTenantsResponse> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    throw new NotOperatorError("not an operator");
  }
  const res = await fetch(`${BFF_URL}/api/admin/tenants`, {
    headers: {
      Authorization: `Bearer ${BFF_TOKEN}`,
      "X-Operator-Id": session.operatorId,
    },
    cache: "no-store",
    signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
  });
  if (res.status === 404) {
    // Route absent ⇒ the operator.admin-read.enabled flag is off on the BFF.
    throw new AdminReadDisabledError("admin read disabled");
  }
  if (!res.ok) {
    throw new Error(`BFF /api/admin/tenants -> ${res.status}`);
  }
  return (await res.json()) as AdminTenantsResponse;
}
