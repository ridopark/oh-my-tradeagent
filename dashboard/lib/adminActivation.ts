import "server-only";
import { auth } from "@/auth";

// Server-ONLY client for the Phase F one-click live activation / deactivation on the api-gateway:
//   POST /admin/tenants/{tenant}/strategies/{strategy}/activate-live
//   POST /admin/tenants/{tenant}/strategies/{strategy}/deactivate-live
// Operator-scoped (sends X-Operator-Id = the verified operator email, NOT X-Tenant-Id) behind the
// shared service token. The api-gateway route is itself dark-gated (operator.activation.enabled,
// default off → 404). Returns a coarse outcome only; the 422 body carries a machine reason
// (REJECTED_*) the UI surfaces verbatim.
const API_GATEWAY_BASE_URL =
  process.env.API_GATEWAY_BASE_URL ?? "http://localhost:8082";
const API_GATEWAY_TOKEN = process.env.API_GATEWAY_SHARED_TOKEN ?? "";
const API_GATEWAY_TIMEOUT_MS = 15_000;

export type ActivationAction = "activate" | "deactivate";

export interface ActivationResult {
  ok: boolean;
  status: number;
  // The activate-live status string on success (ACTIVATED/DEACTIVATED) or the REJECTED_* reason on a
  // 422; null on transport error / when the body had none.
  reason: string | null;
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
    return { ok: false, status: 0, reason: "not_operator" };
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
    let reason: string | null = null;
    try {
      const body = (await res.json()) as {
        status?: string;
        reason?: string;
      };
      reason = body.reason ?? body.status ?? null;
    } catch {
      // No/unparseable body (e.g. 404 when the route is dark) — leave reason null.
    }
    return { ok: res.ok, status: res.status, reason };
  } catch {
    return { ok: false, status: 0, reason: null };
  }
}
