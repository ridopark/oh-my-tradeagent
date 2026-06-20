// P0 SPIKE — exchange an IdP id_token for a first-party token, and stash it in the device keystore.
// Production refresh/rotation/logout is P1 (#451); this is deliberately one-shot.
import * as SecureStore from "expo-secure-store";
import { config } from "../config";

const TOKEN_KEY = "omta.access";

export type ExchangeResult =
  | { ok: true; tenant: string; tenantIds: string[] }
  | { ok: false; status: number; reason: string };

// POST { provider, id_token } -> /m/auth/exchange. 200 => first-party token + resolved tenants;
// 401 => unprovisioned identity (parity with the web dashboard's DENIED_LOGIN).
export async function exchange(
  provider: "google" | "facebook",
  idToken: string,
): Promise<ExchangeResult> {
  const res = await fetch(`${config.apiBaseUrl}/m/auth/exchange`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ provider, id_token: idToken }),
  });
  if (res.status === 401) {
    return { ok: false, status: 401, reason: "not provisioned for any tenant" };
  }
  if (!res.ok) {
    return { ok: false, status: res.status, reason: `exchange failed (${res.status})` };
  }
  const body = (await res.json()) as { access: string; tenant: string; tenantIds: string[] };
  await SecureStore.setItemAsync(TOKEN_KEY, body.access);
  return { ok: true, tenant: body.tenant, tenantIds: body.tenantIds };
}

export async function storedToken(): Promise<string | null> {
  return SecureStore.getItemAsync(TOKEN_KEY);
}

export async function signOut(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKEN_KEY);
}
