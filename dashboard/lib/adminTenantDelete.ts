import "server-only";
import { auth } from "@/auth";
import {
  API_GATEWAY_BASE_URL,
  API_GATEWAY_TOKEN,
  API_GATEWAY_TIMEOUT_MS,
} from "@/lib/apiGateway";

// Server-ONLY client for the Phase 4 operator tenant-teardown on the api-gateway:
//   POST /admin/tenants/{tenant}/delete   body: { "confirm_tenant_id": "<tenant>" }
// Operator-scoped (sends X-Operator-Id = the verified operator email, NOT X-Tenant-Id) behind the
// shared service token — mirrors lib/adminActivation.ts's auth/base-url/token wiring. The api-gateway
// route is itself dark-gated (operator.tenant.delete.enabled, default off → 404) and re-enforces the
// live-tenant / all-dark preconditions server-side (P0/P2), so the UI gate is only belt-and-suspenders.
// Returns a coarse outcome only — plus blocked_by parsed from a 409 body so the page can name the
// blocker in its banner.

export interface TenantDeleteResult {
  ok: boolean;
  status: number;
  // Present only on a 409 (blocked) response that carries a { blocked_by } body — the reason the
  // teardown was refused (e.g. a live target or a non-dark strategy). Undefined otherwise.
  blockedBy?: string;
}

// Fire a delete for one tenant. tenant comes from the operator's selection in the admin list (a
// listed row, not free input); confirmTenantId is the value the operator typed into the confirm modal
// and is echoed to the gateway as the type-to-confirm guard. The operator id is bound to the verified
// session.
export async function postTenantDelete(
  tenant: string,
  confirmTenantId: string,
): Promise<TenantDeleteResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  const path = `/admin/tenants/${encodeURIComponent(tenant)}/delete`;
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
    return { ok: res.ok, status: res.status, blockedBy };
  } catch {
    // Transport/abort error — the call did not complete.
    return { ok: false, status: 0 };
  }
}
