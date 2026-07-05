import "server-only";
import { auth } from "@/auth";
import {
  API_GATEWAY_BASE_URL,
  API_GATEWAY_TOKEN,
  API_GATEWAY_TIMEOUT_MS,
} from "@/lib/apiGateway";

// Server-ONLY client for the Phase 2 residual-cleanup route on the api-gateway:
//   POST /admin/tenants/{tenant}/cleanup-residual   body: { "confirm_tenant_id": "<tenant>" }
// Converges a PARTIALLY-deleted tenant (strategy_config already gone, but residual broker_credentials
// / dashboard rows survived a step fault on the original delete). Operator-scoped (sends X-Operator-Id
// = the verified operator email, NOT X-Tenant-Id) behind the shared service token — mirrors
// lib/adminTenantDelete.ts's auth/base-url/token wiring. The api-gateway route is itself dark-gated
// (operator.tenant-delete.enabled, default off → 404) and re-enforces the residual precondition
// server-side (rows==0 or 409 NOT_RESIDUAL), so the UI gate is only belt-and-suspenders.

export interface TenantResidualCleanupResult {
  // TRUE only on a fully-completed cleanup (HTTP 200 CLEANED). A 207 (a step failed after some stores
  // were cleaned) is NOT ok — the caller must surface "partially cleaned, retry", never "cleaned".
  ok: boolean;
  status: number;
  // Present only on a 409 (blocked) response that carries a { blocked_by } body — the reason the
  // cleanup was refused (e.g. NOT_RESIDUAL: config still present, or no prior delete attempt).
  // Undefined otherwise.
  blockedBy?: string;
}

// Fire a residual cleanup for one tenant. tenant comes from the operator's selection in the admin list
// (a listed `partial` row, not free input); confirmTenantId is the value the operator confirmed and is
// echoed to the gateway as the type-to-confirm guard. The operator id is bound to the verified session.
export async function postTenantResidualCleanup(
  tenant: string,
  confirmTenantId: string,
): Promise<TenantResidualCleanupResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  const path = `/admin/tenants/${encodeURIComponent(tenant)}/cleanup-residual`;
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Operator-Id": session.operatorId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ confirm_tenant_id: confirmTenantId }),
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    let blockedBy: string | undefined;
    if (res.status === 409) {
      // Only a blocked response carries a reason worth surfacing; any other status maps to a coarse
      // banner. Fail-safe: a missing/invalid body leaves blockedBy undefined (generic "Blocked.").
      try {
        const body = (await res.json()) as { blocked_by?: unknown };
        if (typeof body?.blocked_by === "string") {
          blockedBy = body.blocked_by;
        }
      } catch {
        // No/invalid JSON body — leave blockedBy undefined.
      }
    }
    // ok ONLY on a full 200 CLEANED — a 207 partial falls through to the "partially cleaned" banner.
    return { ok: res.status === 200, status: res.status, blockedBy };
  } catch {
    // Transport/abort error — the call did not complete.
    return { ok: false, status: 0 };
  }
}
