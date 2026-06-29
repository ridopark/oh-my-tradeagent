import "server-only";
import { auth } from "@/auth";
import {
  API_GATEWAY_BASE_URL,
  API_GATEWAY_TOKEN,
  API_GATEWAY_TIMEOUT_MS,
} from "@/lib/apiGateway";

// Server-ONLY client for the Phase F one-click live activation / deactivation on the api-gateway:
//   POST /admin/tenants/{tenant}/strategies/{strategy}/activate-live
//   POST /admin/tenants/{tenant}/strategies/{strategy}/deactivate-live
// Operator-scoped (sends X-Operator-Id = the verified operator email, NOT X-Tenant-Id) behind the
// shared service token. The api-gateway route is itself dark-gated (operator.activation.enabled,
// default off → 404). Returns a coarse outcome only (the same {ok, status} shape the existing
// api-gateway clients return), which the page maps to a coarse banner.

export type ActivationAction = "activate" | "deactivate";

export interface ActivationResult {
  ok: boolean;
  status: number;
}

// Fire an activate/deactivate for one (tenant, strategy). tenant/strategy come from the operator's
// selection in the admin list (NOT free input — they're the listed rows). The operator id is bound
// to the verified session.
export async function postActivation(
  action: ActivationAction,
  tenant: string,
  strategy: string,
): Promise<ActivationResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  const suffix = action === "activate" ? "activate-live" : "deactivate-live";
  const path = `/admin/tenants/${encodeURIComponent(tenant)}/strategies/${encodeURIComponent(strategy)}/${suffix}`;
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Operator-Id": session.operatorId,
      },
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    return { ok: res.ok, status: res.status };
  } catch {
    // Transport/abort error — the call did not complete.
    return { ok: false, status: 0 };
  }
}
