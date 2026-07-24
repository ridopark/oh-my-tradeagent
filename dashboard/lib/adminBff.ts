import "server-only";
import { auth } from "@/auth";
import { BFF_URL, BFF_TOKEN, BFF_TIMEOUT_MS } from "@/lib/bff";

// Server-ONLY client for the operator-scoped, cross-tenant admin read on the tenant-dashboard-bff
// (GET /api/admin/tenants, I-1a). Distinct from lib/bff.ts's bffGet because the header semantics
// differ: the admin listing is cross-tenant, so it sends X-Operator-Id (the verified operator email
// from the session) and NO X-Tenant-Id. Same network-isolated BFF behind the same shared service
// token, so it imports bff.ts's URL/token/timeout (and the misconfig guard that lives there).
//
// The BFF route is itself dark-gated (operator.admin-read.enabled, default off → 404). When the flag
// is off this throws AdminReadDisabledError so the page can render an explanatory empty state rather
// than a hard error.
export class AdminReadDisabledError extends Error {}

// One (tenant, strategy) row of the admin listing. Mirrors AdminTenantsController.toItem. Contains NO
// secret material — account_masked is "••••" + last 4 (or "••••"); the secret columns are never read.
export interface AdminTenantItem {
  tenant_id: string;
  strategy_id: string;
  broker_target: string | null;
  account_masked: string;
  mode: "live" | "paper";
  // The RUNTIME arm state (strategy_config.enabled). DISTINCT from activation_state: a strategy can
  // be activated (live-promotion VALID) yet enabled=false, so it does NOT trade. Surfaced so the UI
  // shows the real armed/disabled truth, not just the activation badge.
  enabled: boolean;
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

// Fetch the cross-tenant admin listing. Throws when the session is not an operator (a belt-and-
// suspenders guard — the /admin layout gates first, so this is unreachable in normal flow) and
// AdminReadDisabledError when the BFF route is dark (404), which the page degrades on.
export async function getAdminTenants(): Promise<AdminTenantsResponse> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    throw new Error("not an operator");
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

// Coarse result of the create-invite call. No secret material — an invite is just (email, tenant,
// operator); `expiresAt` is the non-secret expiry the operator can relay to the invited person.
export interface CreateInviteResult {
  ok: boolean;
  status: number;
  // Set only on a 2xx — the invite's expiry (ISO-8601 timestamptz), read back from the BFF response.
  expiresAt?: string;
}

// Create a pending tenant-user invite (P4). Operator-scoped, BFF-routed — this mirrors getAdminTenants
// (Bearer BFF service token + X-Operator-Id, NO X-Tenant-Id) because the create-invite endpoint lives
// on the tenant-dashboard-bff (POST /api/admin/tenant-invites), NOT the api-gateway. The BFF route is
// itself dark-gated (operator.tenant-invite.enabled + dashboard.writer.enabled → 404 when off).
//
// Body is {email, tenant_id}; the operator id is bound from the verified session, never caller-trusted.
// We read back ONLY the non-secret {expires_at} on success; the invite id/email are not surfaced.
export async function createTenantInvite(
  tenant: string,
  email: string,
): Promise<CreateInviteResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  try {
    const res = await fetch(`${BFF_URL}/api/admin/tenant-invites`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${BFF_TOKEN}`,
        "X-Operator-Id": session.operatorId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email, tenant_id: tenant }),
      cache: "no-store",
      signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
    });
    let expiresAt: string | undefined;
    if (res.ok) {
      // OK body is {invite_id, tenant_id, email, expires_at} — non-secret. Read only expires_at; a
      // parse failure is non-fatal (the create still succeeded).
      const body = (await res.json().catch(() => null)) as {
        expires_at?: string;
      } | null;
      expiresAt = body?.expires_at;
    }
    return { ok: res.ok, status: res.status, expiresAt };
  } catch {
    // Transport/abort error — the invite did not complete.
    return { ok: false, status: 0 };
  }
}
