import "server-only";
import { auth } from "@/auth";
import {
  API_GATEWAY_BASE_URL,
  API_GATEWAY_TOKEN,
  API_GATEWAY_TIMEOUT_MS,
} from "@/lib/apiGateway";

// Server-ONLY clients for the operator onboarding flow on the api-gateway. Both are operator-scoped
// (they send X-Operator-Id = the verified operator email and NO X-Tenant-Id — onboarding is
// inherently cross-tenant, the operator does not belong to the new tenant) behind the shared service
// token, and both target a dark-gated route (404 when its flag is off):
//   I-1b  POST /admin/tenants/{tenant}/strategies/{strategy}   (operator.tenant-create.enabled)
//   I-1c  POST /admin/tenants/{tenant}/broker-credentials       (operator.credential-write.enabled)
//
// Never import this from a client component — the broker secret rides the credential body ONCE, into
// the outbound fetch, and is NEVER read back, logged, returned, or interpolated into an error. The
// ONLY response data read back is the NON-secret {version, broker_account_id} (the authenticated
// account-number read-back that lets the operator confirm the keys reached the intended account).

export interface CreateTenantResult {
  ok: boolean;
  status: number;
  // Set only on a 200 CREATED — the persisted version (always 1 for a fresh row).
  createdVersion?: number;
}

// Create a tenant = INSERT the first strategy_config row for (tenant, strategy) at version 1. The
// (tenant, strategy) are the PATH; the operator id is bound to the verified session. `config` is the
// full StrategyConfig blob — tenant_id/strategy_id inside it MUST match the path (the caller injects
// them) or the writer returns REJECTED_INVALID (400).
export async function createTenant(
  tenant: string,
  strategy: string,
  config: Record<string, unknown>,
  // Optional account-level daily-loss cap (fraction of SOD equity). Supplied ONLY for a live create
  // (the backend arms the tenant_config cap row from it and rejects a live create with no cap). When
  // undefined (paper) the `account_daily_loss_pct` key is omitted from the body entirely.
  accountDailyLossPct?: number,
): Promise<CreateTenantResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  const path = `/admin/tenants/${encodeURIComponent(tenant)}/strategies/${encodeURIComponent(strategy)}`;
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Operator-Id": session.operatorId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        config,
        correlation_id: crypto.randomUUID(),
        // Backend key MUST be exactly account_daily_loss_pct (a number). Omitted for paper.
        ...(accountDailyLossPct !== undefined
          ? { account_daily_loss_pct: accountDailyLossPct }
          : {}),
      }),
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    let createdVersion: number | undefined;
    if (res.ok) {
      // CREATED body is {status, created_version} — non-secret. A parse failure is non-fatal.
      const body = (await res.json().catch(() => null)) as {
        created_version?: number;
      } | null;
      createdVersion = body?.created_version;
    }
    return { ok: res.ok, status: res.status, createdVersion };
  } catch {
    // Transport/abort error — the create did not complete.
    return { ok: false, status: 0 };
  }
}

export interface EnableStrategyResult {
  ok: boolean;
  status: number;
  // Set only on a 200 UPDATED — the persisted config version after the enabled=true flip.
  newVersion?: number;
}

// Arm a just-onboarded tenant's strategy = flip enabled=true via the A1 operator enable route
// (POST /admin/tenants/{tenant}/strategies/{strategy}/enable). Operator-scoped (X-Operator-Id, NO
// X-Tenant-Id — cross-tenant, like createTenant). The route's own VerifiedAccountGuard is the
// load-bearing enforcement: it rejects (422) unless a verified broker account exists, so this call
// carries NO secret and NO config — only the (tenant, strategy) ids in the path and a correlation_id.
export async function enableStrategy(
  tenant: string,
  strategy: string,
): Promise<EnableStrategyResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  const path = `/admin/tenants/${encodeURIComponent(tenant)}/strategies/${encodeURIComponent(strategy)}/enable`;
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Operator-Id": session.operatorId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ correlation_id: crypto.randomUUID() }),
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    let newVersion: number | undefined;
    if (res.ok) {
      // UPDATED body is {status, new_version} — non-secret. A parse failure is non-fatal.
      const body = (await res.json().catch(() => null)) as {
        new_version?: number;
      } | null;
      newVersion = body?.new_version;
    }
    return { ok: res.ok, status: res.status, newVersion };
  } catch {
    // Transport/abort error — the arm did not complete.
    return { ok: false, status: 0 };
  }
}

// Operator credential-write input. tenant_id is NOT here — it is the path tenant, set by the caller.
export interface OperatorBrokerCredentialInput {
  provider: string;
  api_key_id: string;
  api_secret_key: string;
  base_url: string;
  ws_url: string;
  declared_account_id: string;
  // 0 = first write (CREATE); a non-zero prior version = ROTATE. Onboarding is always 0.
  expected_version: number;
}

export interface OperatorBrokerCredentialResult {
  ok: boolean;
  status: number;
  // Set only on a 200 SAVED — the NON-secret authenticated account number exec read back from the
  // broker (/v2/account), for the operator to confirm the keys landed on the intended account.
  brokerAccountId?: string;
}

// Paste a tenant's broker api-key/secret. The secret flows through here ONCE into the outbound body
// and is never read back. On success the route returns {version, broker_account_id} (both non-secret)
// — we read exactly those two fields for the read-back, nothing else.
export async function postOperatorBrokerCredential(
  tenant: string,
  input: OperatorBrokerCredentialInput,
): Promise<OperatorBrokerCredentialResult> {
  const session = await auth();
  if (!session?.isOperator || !session.operatorId) {
    return { ok: false, status: 0 };
  }
  const path = `/admin/tenants/${encodeURIComponent(tenant)}/broker-credentials`;
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Operator-Id": session.operatorId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        // tenant_id is the PATH tenant (the route guards body == path); never caller-trusted.
        tenant_id: tenant,
        provider: input.provider,
        api_key_id: input.api_key_id,
        api_secret_key: input.api_secret_key,
        base_url: input.base_url,
        ws_url: input.ws_url,
        declared_account_id: input.declared_account_id,
        expected_version: input.expected_version,
        correlation_id: crypto.randomUUID(),
      }),
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    let brokerAccountId: string | undefined;
    if (res.ok) {
      // SAVED body is {version, broker_account_id} — both non-secret. NEVER contains key material.
      // We read back only broker_account_id (the operator's account-confirmation read-back).
      const body = (await res.json().catch(() => null)) as {
        broker_account_id?: string;
      } | null;
      brokerAccountId = body?.broker_account_id;
    }
    return { ok: res.ok, status: res.status, brokerAccountId };
  } catch {
    // Transport/abort error — return a coarse failure WITHOUT the thrown value (which could embed
    // the request body / secret in some runtimes).
    return { ok: false, status: 0 };
  }
}
