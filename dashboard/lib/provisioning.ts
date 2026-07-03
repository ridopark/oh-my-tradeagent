import "server-only";
import { BFF_URL, BFF_TOKEN, BFF_TIMEOUT_MS } from "@/lib/bff";

// Server-ONLY login-bind client for the off-ingress tenant-dashboard BFF. Never a route handler and
// never imported from a client component: the ONLY caller is the Auth.js signIn callback (Node
// runtime), which passes the person's TRUSTED server-side OAuth profile — a browser can never reach
// this. On an UNPROVISIONED first login it relays (provider, subject, verified email) to
// POST /internal/provisioning/bind behind the shared service token; the BFF binds the identity into
// dashboard_user for any matching open invite's tenant and returns the granted tenant ids (the
// tenant is taken ONLY from the invite — this client trusts no tenant value).
//
// Fail-SAFE: any non-2xx, network error, timeout, or malformed body collapses to [] (no grant, deny
// the login). A bind failure must never crash signIn nor admit access.
export async function bindInvite(
  provider: string,
  subject: string,
  email: string,
): Promise<string[]> {
  try {
    const res = await fetch(`${BFF_URL}/internal/provisioning/bind`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${BFF_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ provider, subject, email }),
      // The bind writes a row; never serve a cached response.
      cache: "no-store",
      // Bound so an unreachable/slow BFF can't hang the login round-trip.
      signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
    });
    if (!res.ok) {
      return [];
    }
    const body = (await res.json()) as { granted?: unknown };
    if (!Array.isArray(body.granted)) {
      return [];
    }
    return body.granted.filter((t): t is string => typeof t === "string");
  } catch {
    // Unreachable BFF, timeout, or bad JSON ⇒ deny (empty grant). Never throw into signIn.
    return [];
  }
}
