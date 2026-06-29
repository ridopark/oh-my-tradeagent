import "server-only";
import { auth } from "@/auth";

// Server-ONLY client for the api-gateway broker-credential WRITE endpoint (UI-P2-a). Never import
// this from a client component. It reads the verified tenant_id from the session and sets it as
// X-Tenant-Id AND as the request body's tenant_id behind the shared service token. The secret
// (api_secret_key) flows through this module ONCE, into the outbound fetch body — it is NEVER read
// back, logged, returned to the caller, or interpolated into an error. The only thing returned is a
// coarse {ok, status}.
// Exported so the operator activation client (lib/adminActivation.ts) shares the SAME base URL,
// token, and timeout rather than re-declaring them (and risking drift).
export const API_GATEWAY_BASE_URL =
  process.env.API_GATEWAY_BASE_URL ?? "http://localhost:8082";
export const API_GATEWAY_TOKEN = process.env.API_GATEWAY_SHARED_TOKEN ?? "";
// Upper bound on a single api-gateway call so an unreachable/slow gateway can't hang the action.
export const API_GATEWAY_TIMEOUT_MS = 15_000;

if (!API_GATEWAY_TOKEN) {
  // Misconfiguration: without the shared token every api-gateway call gets a 401/403. Surface it
  // loudly rather than letting it look like an auth bug at request time.
  console.error(
    "API_GATEWAY_SHARED_TOKEN is empty — all api-gateway broker-credential writes will be rejected.",
  );
}

// Snake_case body matching the api-gateway BrokerCredentialForwardRequest contract, minus tenant_id
// (which is set FROM THE SESSION, never from caller input).
export interface BrokerCredentialInput {
  provider: string;
  api_key_id: string;
  api_secret_key: string;
  base_url: string;
  ws_url: string;
  declared_account_id: string;
  expected_version: number;
  correlation_id: string;
}

// Coarse result ONLY. No response body, no secret, no detail — just whether the write succeeded and
// the raw HTTP status (0 on a transport/abort error) so the caller can map it to a coarse banner.
export interface PostBrokerCredentialResult {
  ok: boolean;
  status: number;
}

// UI-P4: snake_case body matching the api-gateway strategy-config WRITE contract, minus tenant_id
// (which is set FROM THE SESSION, never from caller input). `config` is the FULL edited strategy
// config object (the stored config with edited scalar fields overlaid). No secret material —
// strategy config holds no broker keys.
export interface StrategyConfigInput {
  strategy_id: string;
  config: Record<string, unknown>;
  expected_version: number;
  correlation_id: string;
}

export async function postStrategyConfig(
  input: StrategyConfigInput,
): Promise<PostBrokerCredentialResult> {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    throw new Error("no tenant in session");
  }
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}/strategy-config`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Tenant-Id": tenantId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        // tenant_id is bound to the verified session — NEVER trusted from input.
        tenant_id: tenantId,
        strategy_id: input.strategy_id,
        config: input.config,
        expected_version: input.expected_version,
        correlation_id: input.correlation_id,
      }),
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    // Coarse result only — match the credential client (no body read).
    return { ok: res.ok, status: res.status };
  } catch {
    // Transport/abort error — the write did not happen. Coarse failure, no thrown detail.
    return { ok: false, status: 0 };
  }
}

export async function postBrokerCredential(
  input: BrokerCredentialInput,
): Promise<PostBrokerCredentialResult> {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    throw new Error("no tenant in session");
  }
  try {
    const res = await fetch(`${API_GATEWAY_BASE_URL}/broker-credentials`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_GATEWAY_TOKEN}`,
        "X-Tenant-Id": tenantId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        // tenant_id is bound to the verified session — NEVER trusted from input.
        tenant_id: tenantId,
        provider: input.provider,
        api_key_id: input.api_key_id,
        api_secret_key: input.api_secret_key,
        base_url: input.base_url,
        ws_url: input.ws_url,
        declared_account_id: input.declared_account_id,
        expected_version: input.expected_version,
        correlation_id: input.correlation_id,
      }),
      cache: "no-store",
      signal: AbortSignal.timeout(API_GATEWAY_TIMEOUT_MS),
    });
    // Coarse result only — the body may echo the secret, so it is NEVER read.
    return { ok: res.ok, status: res.status };
  } catch {
    // Transport/abort error. Return a coarse failure WITHOUT the thrown value (which could embed the
    // request body / secret in some runtimes).
    return { ok: false, status: 0 };
  }
}
